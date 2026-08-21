package top.ellan.mahjong.craftengine.privateview;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLocaleChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import top.ellan.mahjong.application.interaction.OverheadViewPort;
import top.ellan.mahjong.craftengine.port.PrivateProjectionGateway;
import top.ellan.mahjong.craftengine.port.PlayerTextResolver;
import top.ellan.mahjong.platform.paper.anchor.TableAnchorLookup;
import top.ellan.mahjong.craftengine.privateview.PrivateProjectionState.DesiredNode;
import top.ellan.mahjong.craftengine.privateview.PrivateProjectionState.RemovedNode;
import top.ellan.mahjong.craftengine.privateview.PrivateProjectionState.UpsertedNode;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.presentation.node.CameraNode;
import top.ellan.mahjong.presentation.node.HudNode;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.spi.PlayerId;

/**
 * CE packet-proxy facade for only the client-private channels CE furniture cannot model.
 *
 * <p>Concurrent indexes, dynamic display rendering and camera state are owned by separate
 * components; this class only translates lifecycle and port calls.</p>
 */
public final class CraftEnginePrivateProjectionGateway
        implements PrivateProjectionGateway,
                OverheadViewPort,
                Listener,
                AutoCloseable {
    private final PrivateProjectionState state = new PrivateProjectionState();
    private final ConcurrentHashMap<PlayerId, Locale> locales = new ConcurrentHashMap<>();
    private final PlayerRegionTaskScheduler tasks;
    private final PrivateNodeRenderer renderer;
    private final OverheadCameraController cameras;

    public CraftEnginePrivateProjectionGateway(
            Plugin plugin,
            TableAnchorLookup anchors,
            int cameraTransitionTicks,
            PlayerTextResolver messages) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(anchors, "anchors");
        Objects.requireNonNull(messages, "messages");
        tasks = new PlayerRegionTaskScheduler(plugin);
        CraftEngineClientDisplayGateway displays =
                new CraftEngineClientDisplayGateway(plugin.getLogger());
        renderer = new PrivateNodeRenderer(
                anchors,
                state,
                tasks,
                displays,
                this::cameraViewing,
                messages);
        cameras = new OverheadCameraController(
                plugin,
                anchors,
                tasks,
                displays,
                new CameraProjectionCallbacks() {
                    @Override
                    public Optional<CameraNode> cameraNode(TableId tableId, PlayerId viewer) {
                        return state.cameraNode(tableId, viewer);
                    }

                    @Override
                    public void hideForCamera(Player player, PlayerId viewer) {
                        renderer.hideActionLabels(player, viewer);
                        renderer.refreshHud(player, viewer);
                    }

                    @Override
                    public void restoreAfterCamera(Player player, PlayerId viewer) {
                        renderer.restoreActionLabels(player, viewer);
                        renderer.refreshHud(player, viewer);
                    }
                },
                cameraTransitionTicks);
    }

    @Override
    public void upsert(TableId tableId, SceneNode node) {
        // A shared node resolves to one key per viewer; each is dispatched on its own player thread.
        for (UpsertedNode upserted : state.upsert(tableId, node)) {
            Player player = Bukkit.getPlayer(upserted.key().viewer().value());
            if (player != null) {
                locales.putIfAbsent(upserted.key().viewer(), player.locale());
                Object taskKey = node instanceof HudNode
                        ? new HudRenderKey(upserted.key().viewer())
                        : upserted.key();
                tasks.executeLatest(
                        player,
                        taskKey,
                        () -> renderer.apply(player, upserted.key(), upserted.generation()));
            }
        }
    }

    @Override
    public void remove(TableId tableId, SceneNodeId nodeId) {
        for (RemovedNode target : state.remove(tableId, nodeId)) {
            if (target.node() instanceof CameraNode) {
                cameras.removeCamera(tableId, target.key().viewer());
            }
            Player player = Bukkit.getPlayer(target.key().viewer().value());
            if (player == null) {
                renderer.forget(target.key());
                continue;
            }
            boolean hudChanged = target.node() instanceof HudNode;
            Object taskKey = hudChanged
                    ? new HudRenderKey(target.key().viewer())
                    : target.key();
            tasks.executeLatest(
                    player,
                    taskKey,
                    () -> {
                        renderer.remove(player, target.key());
                        if (hudChanged) {
                            renderer.refreshHud(player, target.key().viewer());
                        }
                    });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerId viewer = new PlayerId(player.getUniqueId());
        locales.put(viewer, player.locale());
        tasks.execute(
                player,
                () -> {
                    for (Map.Entry<PrivateProjectionState.NodeKey, DesiredNode> entry
                            : state.desiredNodes(viewer).entrySet()) {
                        if (!(entry.getValue().node() instanceof HudNode)) {
                            renderer.apply(
                                    player,
                                    entry.getKey(),
                                    entry.getValue().generation());
                        }
                    }
                    renderer.refreshHud(player, viewer);
                });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        PlayerId playerId = new PlayerId(event.getPlayer().getUniqueId());
        locales.remove(playerId);
        tasks.forget(playerId.value());
        renderer.onQuit(playerId);
        cameras.onQuit(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLocaleChange(PlayerLocaleChangeEvent event) {
        Player player = event.getPlayer();
        PlayerId viewer = new PlayerId(player.getUniqueId());
        locales.put(viewer, event.locale());
        tasks.execute(
                player,
                () -> {
                    renderer.restoreActionLabels(player, viewer);
                    renderer.refreshHud(player, viewer);
                });
    }

    /** Locale snapshot used by the render worker; never calls Bukkit off-thread. */
    public Locale locale(PlayerId playerId) {
        return locales.getOrDefault(playerId, Locale.ENGLISH);
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

    @Override
    public void close() {
        cameras.close();
        renderer.close();
        tasks.clear();
        locales.clear();
        state.clear();
    }

    private boolean cameraViewing(PlayerId viewer) {
        return cameras != null && cameras.viewing(viewer);
    }

    private record HudRenderKey(PlayerId viewer) {}
}
