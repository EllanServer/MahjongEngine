package top.ellan.mahjong.craftengine.privateview;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.sparrow.heart.feature.entity.display.FakeItemDisplay;
import net.momirealms.sparrow.heart.feature.entity.display.FakeTextDisplay;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.spigotmc.event.entity.EntityDismountEvent;
import top.ellan.mahjong.application.interaction.HandTileSelectionPort;
import top.ellan.mahjong.application.interaction.OverheadViewPort;
import top.ellan.mahjong.craftengine.port.PrivateProjectionGateway;
import top.ellan.mahjong.craftengine.port.TableAnchorLookup;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.presentation.ActionLabelNode;
import top.ellan.mahjong.presentation.CameraNode;
import top.ellan.mahjong.presentation.HudNode;
import top.ellan.mahjong.presentation.PrivateItemNode;
import top.ellan.mahjong.presentation.SceneNode;
import top.ellan.mahjong.presentation.SceneNodeId;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.TileInstanceId;

/** Client-only secret tiles, action labels, HUD and cameras. */
public final class SparrowPrivateProjectionGateway
        implements PrivateProjectionGateway,
                HandTileSelectionPort,
                OverheadViewPort,
                Listener,
                AutoCloseable {
    private final TableAnchorLookup anchors;
    private final ConcurrentHashMap<PlayerId, ConcurrentHashMap<NodeKey, DesiredNode>> desired =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TableNodeKey, PlayerId> viewersByNode =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PlayerId, ConcurrentHashMap<NodeKey, ActiveItem>> activeItems =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PlayerId, ConcurrentHashMap<NodeKey, ActiveLabel>> activeLabels =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ViewerTileKey, NodeKey> handTiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CameraKey, NodeKey> cameraNodes = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private final PlayerRegionTaskScheduler tasks;
    private final SparrowDisplayGateway displays;
    private final HandTileSelectionController selections;
    private final OverheadCameraController cameras;

    public SparrowPrivateProjectionGateway(
            Plugin plugin, TableAnchorLookup anchors, double selectionRaise) {
        this(plugin, anchors, selectionRaise, 16);
    }

    public SparrowPrivateProjectionGateway(
            Plugin plugin,
            TableAnchorLookup anchors,
            double selectionRaise,
            int cameraTransitionTicks) {
        Objects.requireNonNull(plugin, "plugin");
        this.anchors = Objects.requireNonNull(anchors, "anchors");
        tasks = new PlayerRegionTaskScheduler(plugin);
        displays = new SparrowDisplayGateway(plugin.getLogger());
        selections =
                new HandTileSelectionController(tasks, this::moveHandTile, selectionRaise);
        cameras =
                new OverheadCameraController(
                        plugin,
                        anchors,
                        tasks,
                        displays,
                        new CameraProjectionCallbacks() {
                            @Override
                            public Optional<CameraNode> cameraNode(
                                    TableId tableId,
                                    PlayerId viewer) {
                                return findCameraNode(tableId, viewer);
                            }

                            @Override
                            public void hideForCamera(Player player, PlayerId viewer) {
                                hideActionLabels(player, viewer);
                                refreshHud(player, viewer);
                            }

                            @Override
                            public void restoreAfterCamera(Player player, PlayerId viewer) {
                                restoreActionLabels(player, viewer);
                                refreshHud(player, viewer);
                            }
                        },
                        cameraTransitionTicks);
    }

    @Override
    public void upsert(TableId tableId, SceneNode node) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(node, "node");
        PlayerId viewer =
                node.visibility()
                        .privateViewer()
                        .orElseThrow(() -> new IllegalArgumentException("Private node has no viewer"));
        if (!(node instanceof PrivateItemNode)
                && !(node instanceof HudNode)
                && !(node instanceof CameraNode)
                && !(node instanceof ActionLabelNode)) {
            throw new IllegalArgumentException("Unsupported private node: " + node.getClass());
        }
        NodeKey key = new NodeKey(tableId, node.id(), viewer);
        viewersByNode.compute(
                new TableNodeKey(tableId, node.id()),
                (ignored, previousViewer) -> {
                    if (previousViewer != null && !previousViewer.equals(viewer)) {
                        throw new IllegalArgumentException(
                                "Private scene node is assigned to more than one viewer");
                    }
                    return viewer;
                });
        long revision = generation.incrementAndGet();
        DesiredNode previous = desired.computeIfAbsent(viewer, ignored -> new ConcurrentHashMap<>())
                .put(key, new DesiredNode(revision, node));
        if (previous != null && previous.node() instanceof PrivateItemNode previousItem) {
            handTiles.remove(
                    new ViewerTileKey(tableId, viewer, previousItem.tileInstanceId()), key);
        }
        if (previous != null && previous.node() instanceof CameraNode) {
            cameraNodes.remove(new CameraKey(tableId, viewer), key);
        }
        if (node instanceof PrivateItemNode item) {
            handTiles.put(new ViewerTileKey(tableId, viewer, item.tileInstanceId()), key);
        } else if (node instanceof CameraNode) {
            cameraNodes.put(new CameraKey(tableId, viewer), key);
        }
        Player player = Bukkit.getPlayer(viewer.value());
        if (player != null) {
            tasks.execute(player, () -> apply(player, key, revision));
        }
    }

    @Override
    public void remove(TableId tableId, SceneNodeId nodeId) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(nodeId, "nodeId");
        PlayerId viewer = viewersByNode.remove(new TableNodeKey(tableId, nodeId));
        if (viewer == null) {
            return;
        }
        ConcurrentHashMap<NodeKey, DesiredNode> nodes = desired.get(viewer);
        if (nodes == null) {
            return;
        }
        NodeKey key = new NodeKey(tableId, nodeId, viewer);
        DesiredNode removed = nodes.remove(key);
        if (removed == null) {
            return;
        }
        if (nodes.isEmpty()) {
            desired.remove(viewer, nodes);
        }
        if (removed.node() instanceof PrivateItemNode item) {
            handTiles.remove(new ViewerTileKey(tableId, viewer, item.tileInstanceId()), key);
        }
        if (removed.node() instanceof CameraNode) {
            cameraNodes.remove(new CameraKey(tableId, viewer), key);
            cameras.removeCamera(tableId, viewer);
        }
        Player player = Bukkit.getPlayer(viewer.value());
        if (player != null) {
            boolean hudChanged = removed.node() instanceof HudNode;
            tasks.execute(
                    player,
                    () -> {
                        removeActive(player, key);
                        if (hudChanged) {
                            refreshHud(player, viewer);
                        }
                    });
        } else {
            removeActiveItem(key);
            removeActiveLabel(key);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerId viewer = new PlayerId(player.getUniqueId());
        tasks.execute(
                player,
                () -> {
                    Map<NodeKey, DesiredNode> nodes = desired.get(viewer);
                    if (nodes != null) {
                        nodes.forEach(
                                (key, value) -> {
                                    if (!(value.node() instanceof HudNode)) {
                                        apply(player, key, value.generation());
                                    }
                                });
                    }
                    refreshHud(player, viewer);
                });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        PlayerId playerId = new PlayerId(event.getPlayer().getUniqueId());
        activeItems.remove(playerId);
        activeLabels.remove(playerId);
        selections.onQuit(playerId);
        cameras.onQuit(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        exit(new PlayerId(event.getPlayer().getUniqueId()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        exit(new PlayerId(event.getEntity().getUniqueId()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player) {
            exit(new PlayerId(player.getUniqueId()));
        }
    }

    @Override
    public void showSelection(
            TableId tableId, PlayerId playerId, Optional<TileInstanceId> selectedTile) {
        selections.showSelection(tableId, playerId, selectedTile);
    }

    @Override
    public CompletionStage<ToggleResult> toggle(
            TableId tableId, PlayerId playerId, long revision) {
        return cameras.toggle(tableId, playerId, revision);
    }

    @Override
    public boolean active(PlayerId playerId) {
        return cameras.active(playerId);
    }

    @Override
    public boolean exit(PlayerId playerId) {
        return cameras.exit(playerId);
    }

    private void apply(Player player, NodeKey key, long expectedGeneration) {
        DesiredNode target = desiredNode(key.viewer(), key);
        if (target == null || target.generation() != expectedGeneration || !player.isOnline()) {
            return;
        }
        if (target.node() instanceof CameraNode) {
            return;
        }
        if (target.node() instanceof HudNode) {
            refreshHud(player, key.viewer());
            return;
        }
        if (target.node() instanceof ActionLabelNode label) {
            if (cameras.viewing(key.viewer())) {
                removeActive(player, key);
                return;
            }
            applyLabel(player, key, expectedGeneration, label);
            return;
        }
        PrivateItemNode item = (PrivateItemNode) target.node();
        removeActive(player, key);
        Location anchor =
                anchors.location(key.tableId())
                        .orElseThrow(
                                () -> new IllegalStateException(
                                        "No anchor for table " + key.tableId()));
        Location location =
                PrivateSceneGeometry.localToWorld(
                        anchor,
                        selections.presentedTransform(
                                key.tableId(),
                                key.viewer(),
                                item));
        String asset = PrivatePresentationAssets.itemAsset(item);
        BukkitItemDefinition definition = CraftEngineItems.byId(asset);
        if (definition == null) {
            throw new IllegalStateException("Missing CraftEngine item " + asset);
        }
        ItemStack stack = definition.buildBukkitItem(player);
        FakeItemDisplay display = displays.createItem(location);
        display.item(stack);
        display.spawn(player);
        ActiveItem active = new ActiveItem(expectedGeneration, display);
        ActiveItem replaced = putActiveItem(key, active);
        if (replaced != null) {
            displays.destroyItem(player, replaced.display());
        }
        DesiredNode current = desiredNode(key.viewer(), key);
        if (current == null || current.generation() != expectedGeneration) {
            removeActive(player, key);
        }
    }

    private void applyLabel(
            Player player,
            NodeKey key,
            long expectedGeneration,
            ActionLabelNode label) {
        removeActive(player, key);
        Location anchor = anchors.location(key.tableId())
                .orElseThrow(() -> new IllegalStateException("No anchor for table " + key.tableId()));
        FakeTextDisplay display =
                displays.createText(
                        PrivateSceneGeometry.localToWorld(anchor, label.transform()));
        display.name(PrivatePresentationAssets.labelJson(label));
        if (label.emphasized()) {
            display.rgba(92, 63, 0, 190);
        } else {
            display.rgba(0, 62, 70, 190);
        }
        display.spawn(player);
        ActiveLabel active = new ActiveLabel(expectedGeneration, display);
        ActiveLabel replaced = putActiveLabel(key, active);
        if (replaced != null) {
            displays.destroyText(player, replaced.display());
        }
        DesiredNode current = desiredNode(key.viewer(), key);
        if (current == null || current.generation() != expectedGeneration) {
            removeActive(player, key);
        }
    }

    private void refreshHud(Player player, PlayerId viewer) {
        Map<NodeKey, DesiredNode> nodes = desired.get(viewer);
        String text = (nodes == null ? java.util.stream.Stream.<DesiredNode>empty() : nodes.values().stream())
                .map(DesiredNode::node)
                .filter(HudNode.class::isInstance)
                .map(HudNode.class::cast)
                .sorted(Comparator.comparing(HudNode::contentKey))
                .map(node -> node.contentKey() + '=' + node.contentValue())
                .collect(java.util.stream.Collectors.joining("  "));
        player.sendActionBar(Component.text(text));
    }

    private void removeActive(Player player, NodeKey key) {
        ActiveItem active = removeActiveItem(key);
        if (active != null) {
            displays.destroyItem(player, active.display());
        }
        ActiveLabel label = removeActiveLabel(key);
        if (label != null) {
            displays.destroyText(player, label.display());
        }
    }

    private void moveHandTile(
            Player player,
            TableId tableId,
            PlayerId viewer,
            TileInstanceId tileInstanceId) {
        if (tileInstanceId == null || !player.isOnline()) {
            return;
        }
        NodeKey key = handTiles.get(new ViewerTileKey(tableId, viewer, tileInstanceId));
        if (key == null) {
            return;
        }
        DesiredNode desiredNode = desiredNode(viewer, key);
        if (desiredNode == null || !(desiredNode.node() instanceof PrivateItemNode item)) {
            return;
        }
        ActiveItem active = activeItem(key);
        if (active == null || active.generation() != desiredNode.generation()) {
            apply(player, key, desiredNode.generation());
            return;
        }
        Location anchor = anchors.location(tableId).orElse(null);
        if (anchor == null) {
            return;
        }
        displays.teleport(
                player,
                PrivateSceneGeometry.localToWorld(
                        anchor,
                        selections.presentedTransform(
                                key.tableId(),
                                key.viewer(),
                                item)),
                active.display().entityID());
    }

    private void hideActionLabels(Player player, PlayerId playerId) {
        ConcurrentHashMap<NodeKey, ActiveLabel> labels = activeLabels.get(playerId);
        if (labels == null) {
            return;
        }
        for (Map.Entry<NodeKey, ActiveLabel> entry : labels.entrySet()) {
            if (labels.remove(entry.getKey(), entry.getValue())) {
                displays.destroyText(player, entry.getValue().display());
            }
        }
        if (labels.isEmpty()) {
            activeLabels.remove(playerId, labels);
        }
    }

    private void restoreActionLabels(Player player, PlayerId playerId) {
        Map<NodeKey, DesiredNode> nodes = desired.get(playerId);
        if (nodes == null || !player.isOnline()) {
            return;
        }
        nodes.forEach(
                (key, value) -> {
                    if (value.node() instanceof ActionLabelNode) {
                        apply(player, key, value.generation());
                    }
                });
    }

    private ActiveItem activeItem(NodeKey key) {
        Map<NodeKey, ActiveItem> items = activeItems.get(key.viewer());
        return items == null ? null : items.get(key);
    }

    private ActiveItem putActiveItem(NodeKey key, ActiveItem item) {
        return activeItems
                .computeIfAbsent(key.viewer(), ignored -> new ConcurrentHashMap<>())
                .put(key, item);
    }

    private ActiveItem removeActiveItem(NodeKey key) {
        ConcurrentHashMap<NodeKey, ActiveItem> items = activeItems.get(key.viewer());
        if (items == null) {
            return null;
        }
        ActiveItem removed = items.remove(key);
        if (items.isEmpty()) {
            activeItems.remove(key.viewer(), items);
        }
        return removed;
    }

    private ActiveLabel putActiveLabel(NodeKey key, ActiveLabel label) {
        return activeLabels
                .computeIfAbsent(key.viewer(), ignored -> new ConcurrentHashMap<>())
                .put(key, label);
    }

    private ActiveLabel removeActiveLabel(NodeKey key) {
        ConcurrentHashMap<NodeKey, ActiveLabel> labels = activeLabels.get(key.viewer());
        if (labels == null) {
            return null;
        }
        ActiveLabel removed = labels.remove(key);
        if (labels.isEmpty()) {
            activeLabels.remove(key.viewer(), labels);
        }
        return removed;
    }

    private DesiredNode desiredNode(PlayerId viewer, NodeKey key) {
        Map<NodeKey, DesiredNode> nodes = desired.get(viewer);
        return nodes == null ? null : nodes.get(key);
    }

    private Optional<CameraNode> findCameraNode(TableId tableId, PlayerId viewer) {
        NodeKey key = cameraNodes.get(new CameraKey(tableId, viewer));
        DesiredNode value = key == null ? null : desiredNode(viewer, key);
        return value != null && value.node() instanceof CameraNode camera
                ? Optional.of(camera)
                : Optional.empty();
    }

    @Override
    public void close() {
        cameras.close();
        for (Map.Entry<PlayerId, ConcurrentHashMap<NodeKey, ActiveItem>> viewerEntry
                : activeItems.entrySet()) {
            Player player = Bukkit.getPlayer(viewerEntry.getKey().value());
            if (player != null) {
                for (ActiveItem item : viewerEntry.getValue().values()) {
                    tasks.execute(
                            player,
                            () -> displays.destroyItem(player, item.display()));
                }
            }
        }
        activeItems.clear();
        for (Map.Entry<PlayerId, ConcurrentHashMap<NodeKey, ActiveLabel>> viewerEntry
                : activeLabels.entrySet()) {
            Player player = Bukkit.getPlayer(viewerEntry.getKey().value());
            if (player != null) {
                for (ActiveLabel label : viewerEntry.getValue().values()) {
                    tasks.execute(
                            player,
                            () -> displays.destroyText(player, label.display()));
                }
            }
        }
        activeLabels.clear();
        handTiles.clear();
        selections.clear();
        cameraNodes.clear();
        viewersByNode.clear();
        desired.clear();
    }

    private record TableNodeKey(TableId tableId, SceneNodeId nodeId) {}

    private record NodeKey(TableId tableId, SceneNodeId nodeId, PlayerId viewer) {}

    private record ViewerTileKey(
            TableId tableId, PlayerId viewer, TileInstanceId tileInstanceId) {}

    private record CameraKey(TableId tableId, PlayerId viewer) {}

    private record DesiredNode(long generation, SceneNode node) {}

    private record ActiveItem(long generation, FakeItemDisplay display) {}

    private record ActiveLabel(long generation, FakeTextDisplay display) {}

}
