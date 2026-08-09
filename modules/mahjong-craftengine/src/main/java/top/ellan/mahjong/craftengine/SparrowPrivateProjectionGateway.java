package top.ellan.mahjong.craftengine;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.sparrow.heart.SparrowHeart;
import net.momirealms.sparrow.heart.feature.entity.display.FakeItemDisplay;
import net.momirealms.sparrow.heart.feature.entity.display.FakeTextDisplay;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
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
import top.ellan.mahjong.application.HandTileSelectionPort;
import top.ellan.mahjong.application.OverheadViewPort;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.presentation.ActionLabelNode;
import top.ellan.mahjong.presentation.CameraNode;
import top.ellan.mahjong.presentation.HudNode;
import top.ellan.mahjong.presentation.PrivateItemNode;
import top.ellan.mahjong.presentation.SceneNode;
import top.ellan.mahjong.presentation.SceneNodeId;
import top.ellan.mahjong.presentation.SceneTransform;
import top.ellan.mahjong.presentation.TileAssetName;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.TileInstanceId;

/** Client-only secret tiles, action labels, HUD and cameras. */
public final class SparrowPrivateProjectionGateway
        implements PrivateProjectionGateway,
                HandTileSelectionPort,
                OverheadViewPort,
                Listener,
                AutoCloseable {
    private static final Object SPARROW_CREATION_LOCK = new Object();

    private final Plugin plugin;
    private final TableAnchorLookup anchors;
    private final SparrowHeart heart;
    private final ConcurrentHashMap<PlayerId, ConcurrentHashMap<NodeKey, DesiredNode>> desired =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TableNodeKey, PlayerId> viewersByNode =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PlayerId, ConcurrentHashMap<NodeKey, ActiveItem>> activeItems =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PlayerId, ConcurrentHashMap<NodeKey, ActiveLabel>> activeLabels =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ViewerTileKey, NodeKey> handTiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PlayerId, SelectedTile> selectedTiles =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CameraKey, NodeKey> cameraNodes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PlayerId, PendingCamera> pendingCameras =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PlayerId, ActiveCamera> activeCameras =
            new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private final double selectionRaise;
    private final int cameraTransitionTicks;
    private final ClientCameraPacketSender cameraPackets;

    public SparrowPrivateProjectionGateway(
            Plugin plugin, TableAnchorLookup anchors, double selectionRaise) {
        this(plugin, anchors, selectionRaise, 16);
    }

    public SparrowPrivateProjectionGateway(
            Plugin plugin,
            TableAnchorLookup anchors,
            double selectionRaise,
            int cameraTransitionTicks) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.anchors = Objects.requireNonNull(anchors, "anchors");
        if (!Double.isFinite(selectionRaise) || selectionRaise < 0.0D) {
            throw new IllegalArgumentException("selectionRaise must be finite and non-negative");
        }
        this.selectionRaise = selectionRaise;
        if (cameraTransitionTicks < 1 || cameraTransitionTicks > 40) {
            throw new IllegalArgumentException("cameraTransitionTicks must be between 1 and 40");
        }
        this.cameraTransitionTicks = cameraTransitionTicks;
        heart = SparrowHeart.getInstance();
        cameraPackets = new ClientCameraPacketSender(plugin.getLogger());
        cameraPackets.prewarm();
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
            schedule(player, () -> apply(player, key, revision));
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
            cancelPendingCamera(viewer, tableId, ToggleResult.UNAVAILABLE);
            exitCamera(viewer, tableId, true);
        }
        Player player = Bukkit.getPlayer(viewer.value());
        if (player != null) {
            boolean hudChanged = removed.node() instanceof HudNode;
            schedule(
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
        schedule(
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
        selectedTiles.remove(playerId);
        PendingCamera pending = pendingCameras.remove(playerId);
        if (pending != null) {
            pending.result().complete(ToggleResult.UNAVAILABLE);
        }
        activeCameras.remove(playerId);
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
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(selectedTile, "selectedTile");
        SelectedTile next = selectedTile.map(tile -> new SelectedTile(tableId, tile)).orElse(null);
        SelectedTile previous;
        while (true) {
            previous = selectedTiles.get(playerId);
            if (next == null) {
                if (previous == null || !previous.tableId().equals(tableId)) {
                    return;
                }
                if (selectedTiles.remove(playerId, previous)) {
                    break;
                }
            } else if (previous == null) {
                if (selectedTiles.putIfAbsent(playerId, next) == null) {
                    break;
                }
            } else if (previous.equals(next)) {
                return;
            } else if (selectedTiles.replace(playerId, previous, next)) {
                break;
            }
        }
        Player player = Bukkit.getPlayer(playerId.value());
        if (player == null) {
            return;
        }
        SelectedTile previousSelection = previous;
        schedule(
                player,
                () -> {
                    if (previousSelection != null) {
                        moveHandTile(
                                player,
                                previousSelection.tableId(),
                                playerId,
                                previousSelection.tileInstanceId());
                    }
                    if (next != null) {
                        moveHandTile(player, tableId, playerId, next.tileInstanceId());
                    }
                });
    }

    @Override
    public CompletionStage<ToggleResult> toggle(
            TableId tableId, PlayerId playerId, long revision) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(playerId, "playerId");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        PendingCamera pending = pendingCameras.remove(playerId);
        if (pending != null) {
            pending.result().complete(ToggleResult.EXITED);
            return CompletableFuture.completedFuture(ToggleResult.EXITED);
        }
        ActiveCamera current = activeCameras.get(playerId);
        if (current != null) {
            exitCamera(playerId, current.tableId(), true);
            return CompletableFuture.completedFuture(ToggleResult.EXITED);
        }
        Player player = Bukkit.getPlayer(playerId.value());
        if (player == null || !player.isOnline() || !plugin.isEnabled()) {
            return CompletableFuture.completedFuture(ToggleResult.UNAVAILABLE);
        }
        CompletableFuture<ToggleResult> result = new CompletableFuture<>();
        PendingCamera next = new PendingCamera(tableId, result);
        PendingCamera raced = pendingCameras.putIfAbsent(playerId, next);
        if (raced != null) {
            return raced.result();
        }
        try {
            player.getScheduler()
                    .run(
                            plugin,
                            ignored -> {
                                if (pendingCameras.remove(playerId, next)) {
                                    try {
                                        result.complete(enterCamera(tableId, playerId, player));
                                    } catch (RuntimeException | LinkageError failure) {
                                        result.completeExceptionally(failure);
                                    }
                                }
                            },
                            () -> {
                                if (pendingCameras.remove(playerId, next)) {
                                    result.complete(ToggleResult.UNAVAILABLE);
                                }
                            });
        } catch (RuntimeException schedulingFailure) {
            if (pendingCameras.remove(playerId, next)) {
                result.completeExceptionally(schedulingFailure);
            }
        }
        return result;
    }

    private ToggleResult enterCamera(TableId tableId, PlayerId playerId, Player player) {
        ActiveCamera current = activeCameras.get(playerId);
        if (current != null) {
            exitCamera(playerId, current.tableId(), true);
            return ToggleResult.EXITED;
        }
        NodeKey nodeKey = cameraNodes.get(new CameraKey(tableId, playerId));
        DesiredNode desiredCamera = nodeKey == null ? null : desiredNode(playerId, nodeKey);
        if (!player.isOnline()
                || !player.isInsideVehicle()
                || desiredCamera == null
                || !(desiredCamera.node() instanceof CameraNode camera)) {
            return ToggleResult.UNAVAILABLE;
        }
        Location anchor = anchors.location(tableId).orElse(null);
        if (anchor == null) {
            return ToggleResult.UNAVAILABLE;
        }
        Location start = player.getEyeLocation().clone();
        Location target = localToWorld(anchor, camera.transform());
        FakeItemDisplay display = null;
        ActiveCamera next = null;
        boolean cameraTargeted = false;
        try {
            synchronized (SPARROW_CREATION_LOCK) {
                display = heart.createFakeItemDisplay(start);
            }
            display.item(new ItemStack(Material.AIR));
            display.spawn(player);
            next = new ActiveCamera(
                    tableId,
                    display,
                    start,
                    target,
                    cameraTransitionTicks);
            ActiveCamera raced = activeCameras.putIfAbsent(playerId, next);
            if (raced != null) {
                destroyFakeItem(player, display);
                return ToggleResult.UNAVAILABLE;
            }
            if (!cameraPackets.pointAt(player, display.entityID())) {
                activeCameras.remove(playerId, next);
                destroyFakeItem(player, display);
                return ToggleResult.UNAVAILABLE;
            }
            cameraTargeted = true;
            hideActionLabels(player, playerId);
            refreshHud(player, playerId);
            ActiveCamera active = next;
            scheduleLater(player, () -> animateCamera(player, playerId, active, 1), 1L);
            return ToggleResult.ENTERED;
        } catch (RuntimeException | LinkageError failure) {
            if (next != null && activeCameras.remove(playerId, next) && cameraTargeted) {
                restoreCamera(player, playerId, next, 3);
            } else if (display != null) {
                destroyFakeItem(player, display);
            }
            throw failure;
        }
    }

    @Override
    public boolean active(PlayerId playerId) {
        return playerId != null
                && (pendingCameras.containsKey(playerId)
                        || activeCameras.containsKey(playerId));
    }

    @Override
    public boolean exit(PlayerId playerId) {
        if (playerId == null) {
            return false;
        }
        PendingCamera pending = pendingCameras.remove(playerId);
        if (pending != null) {
            pending.result().complete(ToggleResult.EXITED);
            return true;
        }
        ActiveCamera camera = activeCameras.get(playerId);
        return camera != null && exitCamera(playerId, camera.tableId(), true);
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
            if (activeCameras.containsKey(key.viewer())) {
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
        Location location = localToWorld(anchor, presentedTransform(key, item));
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
        ActiveItem replaced = putActiveItem(key, active);
        if (replaced != null) {
            destroyFakeItem(player, replaced.display());
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
        FakeTextDisplay display;
        synchronized (SPARROW_CREATION_LOCK) {
            display = heart.createFakeTextDisplay(localToWorld(anchor, label.transform()));
        }
        display.name(labelJson(label));
        if (label.emphasized()) {
            display.rgba(92, 63, 0, 190);
        } else {
            display.rgba(0, 62, 70, 190);
        }
        display.spawn(player);
        ActiveLabel active = new ActiveLabel(expectedGeneration, display);
        ActiveLabel replaced = putActiveLabel(key, active);
        if (replaced != null) {
            destroyLabel(player, replaced.display());
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
            destroyFakeItem(player, active.display());
        }
        ActiveLabel label = removeActiveLabel(key);
        if (label != null) {
            destroyLabel(player, label.display());
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
        heart.sendClientSideTeleportEntity(
                player,
                localToWorld(anchor, presentedTransform(key, item)),
                false,
                active.display().entityID());
    }

    private void animateCamera(
            Player player,
            PlayerId playerId,
            ActiveCamera camera,
            int frame) {
        if (!player.isOnline() || activeCameras.get(playerId) != camera) {
            return;
        }
        try {
            heart.sendClientSideTeleportEntity(
                    player,
                    OverheadCameraPath.frame(
                            camera.start(), camera.target(), frame, camera.frames()),
                    false,
                    camera.display().entityID());
            if (frame < camera.frames()) {
                scheduleLater(
                        player,
                        () -> animateCamera(player, playerId, camera, frame + 1),
                        1L);
            }
        } catch (RuntimeException failure) {
            if (activeCameras.remove(playerId, camera)) {
                restoreCamera(player, playerId, camera, 3);
            }
            plugin.getLogger().warning(
                    "Overhead camera animation failed for " + player.getName());
        }
    }

    private boolean exitCamera(PlayerId playerId, TableId expectedTable, boolean restore) {
        ActiveCamera camera = activeCameras.get(playerId);
        if (camera == null
                || !camera.tableId().equals(expectedTable)
                || !activeCameras.remove(playerId, camera)) {
            return false;
        }
        Player player = Bukkit.getPlayer(playerId.value());
        if (player == null || !player.isOnline()) {
            return true;
        }
        if (restore) {
            schedule(player, () -> restoreCamera(player, playerId, camera, 3));
        } else {
            schedule(player, () -> destroyFakeItem(player, camera.display()));
        }
        return true;
    }

    private boolean cancelPendingCamera(
            PlayerId playerId, TableId expectedTable, ToggleResult result) {
        PendingCamera pending = pendingCameras.get(playerId);
        if (pending == null
                || !pending.tableId().equals(expectedTable)
                || !pendingCameras.remove(playerId, pending)) {
            return false;
        }
        pending.result().complete(result);
        return true;
    }

    private void restoreCamera(
            Player player, PlayerId playerId, ActiveCamera camera, int attemptsRemaining) {
        ActiveCamera current = activeCameras.get(playerId);
        int targetEntityId = current == null
                ? player.getEntityId()
                : current.display().entityID();
        if (cameraPackets.pointAt(player, targetEntityId)) {
            destroyFakeItem(player, camera.display());
            if (current == null) {
                restoreActionLabels(player, playerId);
                refreshHud(player, playerId);
            }
            return;
        }
        if (attemptsRemaining > 0 && player.isOnline() && plugin.isEnabled()) {
            scheduleLater(
                    player,
                    () -> restoreCamera(player, playerId, camera, attemptsRemaining - 1),
                    1L);
            return;
        }
        destroyFakeItem(player, camera.display());
        if (current == null) {
            restoreActionLabels(player, playerId);
            refreshHud(player, playerId);
        }
    }

    private void hideActionLabels(Player player, PlayerId playerId) {
        ConcurrentHashMap<NodeKey, ActiveLabel> labels = activeLabels.get(playerId);
        if (labels == null) {
            return;
        }
        for (Map.Entry<NodeKey, ActiveLabel> entry : labels.entrySet()) {
            if (labels.remove(entry.getKey(), entry.getValue())) {
                destroyLabel(player, entry.getValue().display());
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

    private SceneTransform presentedTransform(NodeKey key, PrivateItemNode item) {
        SelectedTile selected = selectedTiles.get(key.viewer());
        if (selected == null
                || !selected.tableId().equals(key.tableId())
                || !item.tileInstanceId().equals(selected.tileInstanceId())
                || selectionRaise == 0.0D) {
            return item.transform();
        }
        SceneTransform base = item.transform();
        return new SceneTransform(
                base.x(),
                base.y() + selectionRaise,
                base.z(),
                base.yawDegrees(),
                base.pitchDegrees(),
                base.rollDegrees(),
                base.scale());
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

    private void schedule(Player player, Runnable task) {
        if (!plugin.isEnabled() || !player.isOnline()) {
            return;
        }
        player.getScheduler().run(plugin, ignored -> task.run(), null);
    }

    private void scheduleLater(Player player, Runnable task, long delayTicks) {
        if (!plugin.isEnabled() || !player.isOnline()) {
            return;
        }
        player.getScheduler().runDelayed(
                plugin, ignored -> task.run(), null, Math.max(1L, delayTicks));
    }

    private DesiredNode desiredNode(PlayerId viewer, NodeKey key) {
        Map<NodeKey, DesiredNode> nodes = desired.get(viewer);
        return nodes == null ? null : nodes.get(key);
    }

    private static String itemAsset(PrivateItemNode item) {
        return "mahjongpaper:" + TileAssetName.from(item.visualId());
    }

    private static String labelJson(ActionLabelNode label) {
        String color = label.emphasized() ? "gold" : "aqua";
        LabelText text = labelText(label.labelKey());
        return "{\"text\":\"[\",\"color\":\""
                + color
                + "\",\"extra\":[{\"translate\":\""
                + text.translationKey()
                + "\",\"fallback\":\""
                + text.fallback()
                + "\"}"
                + text.suffixJson()
                + ",{\"text\":\"]\"}]}";
    }

    private static LabelText labelText(String labelKey) {
        int separator = labelKey.indexOf(':');
        if (separator < 0) {
            return new LabelText("mahjongpaper." + labelKey, labelKey, "");
        }
        String base = labelKey.substring(0, separator);
        String suffix = labelKey.substring(separator + 1);
        if (base.equals("action.respond")) {
            int nextSeparator = suffix.indexOf(':');
            String reaction = nextSeparator < 0 ? suffix : suffix.substring(0, nextSeparator);
            String choices = nextSeparator < 0 ? "" : suffix.substring(nextSeparator + 1);
            return new LabelText(
                    "mahjongpaper.action." + reaction,
                    reaction,
                    literalSuffix(choices));
        }
        return new LabelText(
                "mahjongpaper." + base,
                base,
                literalSuffix(suffix));
    }

    private static String literalSuffix(String suffix) {
        return suffix.isEmpty()
                ? ""
                : ",{\"text\":\" " + suffix + "\",\"color\":\"gray\"}";
    }

    private void destroyLabel(Player player, FakeTextDisplay display) {
        try {
            heart.removeClientSideEntity(player, display.entityID());
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.FINE, "Could not remove a client action label", failure);
        }
    }

    private void destroyFakeItem(Player player, FakeItemDisplay display) {
        try {
            display.destroy(player);
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.FINE, "Could not remove a client item display", failure);
        }
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
        for (Map.Entry<PlayerId, ActiveCamera> entry : activeCameras.entrySet()) {
            if (!activeCameras.remove(entry.getKey(), entry.getValue())) {
                continue;
            }
            Player player = Bukkit.getPlayer(entry.getKey().value());
            if (player != null && player.isOnline()) {
                restoreCamera(player, entry.getKey(), entry.getValue(), 0);
            }
        }
        activeCameras.clear();
        pendingCameras.forEach(
                (ignored, pending) -> pending.result().complete(ToggleResult.UNAVAILABLE));
        pendingCameras.clear();
        for (Map.Entry<PlayerId, ConcurrentHashMap<NodeKey, ActiveItem>> viewerEntry
                : activeItems.entrySet()) {
            Player player = Bukkit.getPlayer(viewerEntry.getKey().value());
            if (player != null) {
                for (ActiveItem item : viewerEntry.getValue().values()) {
                    schedule(player, () -> destroyFakeItem(player, item.display()));
                }
            }
        }
        activeItems.clear();
        for (Map.Entry<PlayerId, ConcurrentHashMap<NodeKey, ActiveLabel>> viewerEntry
                : activeLabels.entrySet()) {
            Player player = Bukkit.getPlayer(viewerEntry.getKey().value());
            if (player != null) {
                for (ActiveLabel label : viewerEntry.getValue().values()) {
                    schedule(player, () -> destroyLabel(player, label.display()));
                }
            }
        }
        activeLabels.clear();
        handTiles.clear();
        selectedTiles.clear();
        cameraNodes.clear();
        viewersByNode.clear();
        desired.clear();
    }

    private record TableNodeKey(TableId tableId, SceneNodeId nodeId) {}

    private record NodeKey(TableId tableId, SceneNodeId nodeId, PlayerId viewer) {}

    private record ViewerTileKey(
            TableId tableId, PlayerId viewer, TileInstanceId tileInstanceId) {}

    private record SelectedTile(TableId tableId, TileInstanceId tileInstanceId) {}

    private record CameraKey(TableId tableId, PlayerId viewer) {}

    private record PendingCamera(
            TableId tableId, CompletableFuture<ToggleResult> result) {}

    private record DesiredNode(long generation, SceneNode node) {}

    private record ActiveItem(long generation, FakeItemDisplay display) {}

    private record ActiveLabel(long generation, FakeTextDisplay display) {}

    private record LabelText(String translationKey, String fallback, String suffixJson) {}

    private record ActiveCamera(
            TableId tableId,
            FakeItemDisplay display,
            Location start,
            Location target,
            int frames) {}
}
