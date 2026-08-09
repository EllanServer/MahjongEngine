package top.ellan.mahjong.craftengine;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.sparrow.heart.SparrowHeart;
import net.momirealms.sparrow.heart.feature.entity.display.FakeItemDisplay;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.presentation.CameraNode;
import top.ellan.mahjong.presentation.HudNode;
import top.ellan.mahjong.presentation.PrivateItemNode;
import top.ellan.mahjong.presentation.SceneNode;
import top.ellan.mahjong.presentation.SceneNodeId;
import top.ellan.mahjong.presentation.SceneTransform;
import top.ellan.mahjong.presentation.TileAssetName;
import top.ellan.mahjong.spi.PlayerId;

/** Client-only secret tile and HUD projection. No private face is backed by a Bukkit entity. */
public final class SparrowPrivateProjectionGateway
        implements PrivateProjectionGateway, Listener, AutoCloseable {
    private static final Object SPARROW_CREATION_LOCK = new Object();

    private final Plugin plugin;
    private final TableAnchorLookup anchors;
    private final SparrowHeart heart;
    private final ConcurrentHashMap<PlayerId, ConcurrentHashMap<NodeKey, DesiredNode>> desired =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<NodeKey, ActiveItem> activeItems = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();

    public SparrowPrivateProjectionGateway(Plugin plugin, TableAnchorLookup anchors) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.anchors = Objects.requireNonNull(anchors, "anchors");
        heart = SparrowHeart.getInstance();
    }

    @Override
    public void upsert(TableId tableId, SceneNode node) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(node, "node");
        PlayerId viewer =
                node.visibility()
                        .privateViewer()
                        .orElseThrow(() -> new IllegalArgumentException("Private node has no viewer"));
        if (node instanceof CameraNode) {
            throw new IllegalArgumentException("Camera projection is not enabled in the 2.0 core");
        }
        if (!(node instanceof PrivateItemNode) && !(node instanceof HudNode)) {
            throw new IllegalArgumentException("Unsupported private node: " + node.getClass());
        }
        NodeKey key = new NodeKey(tableId, node.id(), viewer);
        long revision = generation.incrementAndGet();
        desired.computeIfAbsent(viewer, ignored -> new ConcurrentHashMap<>())
                .put(key, new DesiredNode(revision, node));
        Player player = Bukkit.getPlayer(viewer.value());
        if (player != null) {
            schedule(player, () -> apply(player, key, revision));
        }
    }

    @Override
    public void remove(TableId tableId, SceneNodeId nodeId) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(nodeId, "nodeId");
        desired.forEach(
                (viewer, nodes) -> {
                    NodeKey key = new NodeKey(tableId, nodeId, viewer);
                    if (nodes.remove(key) == null) {
                        return;
                    }
                    Player player = Bukkit.getPlayer(viewer.value());
                    if (player != null) {
                        schedule(player, () -> removeActive(player, key));
                    } else {
                        activeItems.remove(key);
                    }
                });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerId viewer = new PlayerId(player.getUniqueId());
        schedule(
                player,
                () -> {
                    Map<NodeKey, DesiredNode> nodes = desired.getOrDefault(viewer, new ConcurrentHashMap<>());
                    nodes.forEach((key, value) -> apply(player, key, value.generation()));
                    refreshHud(player, viewer);
                });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        activeItems.keySet().removeIf(key -> key.viewer().value().equals(playerId));
    }

    private void apply(Player player, NodeKey key, long expectedGeneration) {
        DesiredNode target = desired.getOrDefault(key.viewer(), new ConcurrentHashMap<>()).get(key);
        if (target == null || target.generation() != expectedGeneration || !player.isOnline()) {
            return;
        }
        if (target.node() instanceof HudNode) {
            refreshHud(player, key.viewer());
            return;
        }
        PrivateItemNode item = (PrivateItemNode) target.node();
        removeActive(player, key);
        Location anchor =
                anchors.location(key.tableId())
                        .orElseThrow(
                                () -> new IllegalStateException(
                                        "No anchor for table " + key.tableId()));
        Location location = localToWorld(anchor, item.transform());
        BukkitItemDefinition definition = CraftEngineItems.byId(itemAsset(item));
        if (definition == null) {
            throw new IllegalStateException("Missing CraftEngine item " + itemAsset(item));
        }
        ItemStack stack = definition.buildBukkitItem(player);
        FakeItemDisplay display;
        synchronized (SPARROW_CREATION_LOCK) {
            display = heart.createFakeItemDisplay(location);
        }
        display.item(stack);
        display.spawn(player);
        ActiveItem active = new ActiveItem(expectedGeneration, display);
        ActiveItem replaced = activeItems.put(key, active);
        if (replaced != null) {
            replaced.display().destroy(player);
        }
        DesiredNode current = desired.getOrDefault(key.viewer(), new ConcurrentHashMap<>()).get(key);
        if (current == null || current.generation() != expectedGeneration) {
            removeActive(player, key);
        }
    }

    private void refreshHud(Player player, PlayerId viewer) {
        String text = desired.getOrDefault(viewer, new ConcurrentHashMap<>()).values().stream()
                .map(DesiredNode::node)
                .filter(HudNode.class::isInstance)
                .map(HudNode.class::cast)
                .sorted(Comparator.comparing(HudNode::contentKey))
                .map(node -> node.contentKey() + '=' + node.contentValue())
                .collect(java.util.stream.Collectors.joining("  "));
        player.sendActionBar(Component.text(text));
    }

    private void removeActive(Player player, NodeKey key) {
        ActiveItem active = activeItems.remove(key);
        if (active != null) {
            active.display().destroy(player);
        }
        if (desired.getOrDefault(key.viewer(), new ConcurrentHashMap<>()).values().stream()
                .anyMatch(value -> value.node() instanceof HudNode)) {
            refreshHud(player, key.viewer());
        }
    }

    private void schedule(Player player, Runnable task) {
        if (!plugin.isEnabled() || !player.isOnline()) {
            return;
        }
        player.getScheduler().run(plugin, ignored -> task.run(), null);
    }

    private static String itemAsset(PrivateItemNode item) {
        return "mahjongpaper:" + TileAssetName.from(item.visualId());
    }

    private static Location localToWorld(Location anchor, SceneTransform transform) {
        double yaw = Math.toRadians(anchor.getYaw());
        double x = transform.x() * Math.cos(yaw) - transform.z() * Math.sin(yaw);
        double z = transform.x() * Math.sin(yaw) + transform.z() * Math.cos(yaw);
        Location result = anchor.clone().add(x, transform.y(), z);
        result.setYaw((float) (anchor.getYaw() + transform.yawDegrees()));
        result.setPitch((float) transform.pitchDegrees());
        return result;
    }

    @Override
    public void close() {
        for (Map.Entry<NodeKey, ActiveItem> entry : activeItems.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey().viewer().value());
            if (player != null) {
                schedule(player, () -> entry.getValue().display().destroy(player));
            }
        }
        activeItems.clear();
        desired.clear();
    }

    private record NodeKey(TableId tableId, SceneNodeId nodeId, PlayerId viewer) {}

    private record DesiredNode(long generation, SceneNode node) {}

    private record ActiveItem(long generation, FakeItemDisplay display) {}
}
