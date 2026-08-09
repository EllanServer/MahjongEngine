package top.ellan.mahjong.craftengine.privateview;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import net.momirealms.sparrow.heart.feature.entity.display.FakeItemDisplay;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import top.ellan.mahjong.application.interaction.OverheadViewPort;
import top.ellan.mahjong.platform.paper.anchor.TableAnchorLookup;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.presentation.node.CameraNode;
import top.ellan.mahjong.spi.PlayerId;

/** Owns pending/active overhead camera sessions independently from private tile rendering. */
final class OverheadCameraController implements OverheadViewPort, AutoCloseable {
    private final Plugin plugin;
    private final TableAnchorLookup anchors;
    private final PlayerRegionTaskScheduler tasks;
    private final SparrowDisplayGateway displays;
    private final CameraProjectionCallbacks callbacks;
    private final ClientCameraPacketSender cameraPackets;
    private final int transitionTicks;
    private final ConcurrentHashMap<PlayerId, PendingCamera> pending =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PlayerId, ActiveCamera> active =
            new ConcurrentHashMap<>();

    OverheadCameraController(
            Plugin plugin,
            TableAnchorLookup anchors,
            PlayerRegionTaskScheduler tasks,
            SparrowDisplayGateway displays,
            CameraProjectionCallbacks callbacks,
            int transitionTicks) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.anchors = Objects.requireNonNull(anchors, "anchors");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.displays = Objects.requireNonNull(displays, "displays");
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
        if (transitionTicks < 1 || transitionTicks > 40) {
            throw new IllegalArgumentException("transitionTicks must be between 1 and 40");
        }
        this.transitionTicks = transitionTicks;
        cameraPackets = new ClientCameraPacketSender(plugin.getLogger());
        cameraPackets.prewarm();
    }

    @Override
    public CompletionStage<ToggleResult> toggle(
            TableId tableId,
            PlayerId playerId,
            long revision) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(playerId, "playerId");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        PendingCamera pendingCamera = pending.remove(playerId);
        if (pendingCamera != null) {
            pendingCamera.result().complete(ToggleResult.EXITED);
            return CompletableFuture.completedFuture(ToggleResult.EXITED);
        }
        ActiveCamera current = active.get(playerId);
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
        PendingCamera raced = pending.putIfAbsent(playerId, next);
        if (raced != null) {
            return raced.result();
        }
        try {
            player.getScheduler()
                    .run(
                            plugin,
                            ignored -> enterPending(tableId, playerId, player, next),
                            () -> failPending(playerId, next));
        } catch (RuntimeException schedulingFailure) {
            if (pending.remove(playerId, next)) {
                result.completeExceptionally(schedulingFailure);
            }
        }
        return result;
    }

    @Override
    public boolean active(PlayerId playerId) {
        return playerId != null
                && (pending.containsKey(playerId) || active.containsKey(playerId));
    }

    boolean viewing(PlayerId playerId) {
        return active.containsKey(playerId);
    }

    @Override
    public boolean exit(PlayerId playerId) {
        if (playerId == null) {
            return false;
        }
        PendingCamera pendingCamera = pending.remove(playerId);
        if (pendingCamera != null) {
            pendingCamera.result().complete(ToggleResult.EXITED);
            return true;
        }
        ActiveCamera camera = active.get(playerId);
        return camera != null && exitCamera(playerId, camera.tableId(), true);
    }

    void removeCamera(TableId tableId, PlayerId playerId) {
        cancelPending(playerId, tableId, ToggleResult.UNAVAILABLE);
        exitCamera(playerId, tableId, true);
    }

    void onQuit(PlayerId playerId) {
        PendingCamera pendingCamera = pending.remove(playerId);
        if (pendingCamera != null) {
            pendingCamera.result().complete(ToggleResult.UNAVAILABLE);
        }
        active.remove(playerId);
    }

    private void enterPending(
            TableId tableId,
            PlayerId playerId,
            Player player,
            PendingCamera expected) {
        if (!pending.remove(playerId, expected)) {
            return;
        }
        try {
            expected.result().complete(enterCamera(tableId, playerId, player));
        } catch (RuntimeException | LinkageError failure) {
            expected.result().completeExceptionally(failure);
        }
    }

    private void failPending(PlayerId playerId, PendingCamera expected) {
        if (pending.remove(playerId, expected)) {
            expected.result().complete(ToggleResult.UNAVAILABLE);
        }
    }

    private ToggleResult enterCamera(
            TableId tableId,
            PlayerId playerId,
            Player player) {
        ActiveCamera current = active.get(playerId);
        if (current != null) {
            exitCamera(playerId, current.tableId(), true);
            return ToggleResult.EXITED;
        }
        CameraNode camera = callbacks.cameraNode(tableId, playerId).orElse(null);
        if (!player.isOnline() || !player.isInsideVehicle() || camera == null) {
            return ToggleResult.UNAVAILABLE;
        }
        Location anchor = anchors.location(tableId).orElse(null);
        if (anchor == null) {
            return ToggleResult.UNAVAILABLE;
        }
        Location start = player.getEyeLocation().clone();
        Location target = PrivateSceneGeometry.localToWorld(anchor, camera.transform());
        FakeItemDisplay display = null;
        ActiveCamera next = null;
        boolean cameraTargeted = false;
        try {
            display = displays.createItem(start);
            display.item(new ItemStack(Material.AIR));
            display.spawn(player);
            next = new ActiveCamera(tableId, display, start, target, transitionTicks);
            ActiveCamera raced = active.putIfAbsent(playerId, next);
            if (raced != null) {
                displays.destroyItem(player, display);
                return ToggleResult.UNAVAILABLE;
            }
            if (!cameraPackets.pointAt(player, display.entityID())) {
                active.remove(playerId, next);
                displays.destroyItem(player, display);
                return ToggleResult.UNAVAILABLE;
            }
            cameraTargeted = true;
            callbacks.hideForCamera(player, playerId);
            ActiveCamera entered = next;
            tasks.executeLater(player, () -> animate(player, playerId, entered, 1), 1L);
            return ToggleResult.ENTERED;
        } catch (RuntimeException | LinkageError failure) {
            if (next != null && active.remove(playerId, next) && cameraTargeted) {
                restore(player, playerId, next, 3);
            } else if (display != null) {
                displays.destroyItem(player, display);
            }
            throw failure;
        }
    }

    private void animate(
            Player player,
            PlayerId playerId,
            ActiveCamera camera,
            int frame) {
        if (!player.isOnline() || active.get(playerId) != camera) {
            return;
        }
        try {
            displays.teleport(
                    player,
                    OverheadCameraPath.frame(
                            camera.start(), camera.target(), frame, camera.frames()),
                    camera.display().entityID());
            if (frame < camera.frames()) {
                tasks.executeLater(
                        player,
                        () -> animate(player, playerId, camera, frame + 1),
                        1L);
            }
        } catch (RuntimeException failure) {
            if (active.remove(playerId, camera)) {
                restore(player, playerId, camera, 3);
            }
            plugin.getLogger()
                    .warning("Overhead camera animation failed for " + player.getName());
        }
    }

    private boolean exitCamera(
            PlayerId playerId,
            TableId expectedTable,
            boolean restoreView) {
        ActiveCamera camera = active.get(playerId);
        if (camera == null
                || !camera.tableId().equals(expectedTable)
                || !active.remove(playerId, camera)) {
            return false;
        }
        Player player = Bukkit.getPlayer(playerId.value());
        if (player == null || !player.isOnline()) {
            return true;
        }
        if (restoreView) {
            tasks.execute(player, () -> restore(player, playerId, camera, 3));
        } else {
            tasks.execute(player, () -> displays.destroyItem(player, camera.display()));
        }
        return true;
    }

    private boolean cancelPending(
            PlayerId playerId,
            TableId expectedTable,
            ToggleResult result) {
        PendingCamera pendingCamera = pending.get(playerId);
        if (pendingCamera == null
                || !pendingCamera.tableId().equals(expectedTable)
                || !pending.remove(playerId, pendingCamera)) {
            return false;
        }
        pendingCamera.result().complete(result);
        return true;
    }

    private void restore(
            Player player,
            PlayerId playerId,
            ActiveCamera camera,
            int attemptsRemaining) {
        ActiveCamera current = active.get(playerId);
        int targetEntityId =
                current == null ? player.getEntityId() : current.display().entityID();
        if (cameraPackets.pointAt(player, targetEntityId)) {
            displays.destroyItem(player, camera.display());
            if (current == null) {
                callbacks.restoreAfterCamera(player, playerId);
            }
            return;
        }
        if (attemptsRemaining > 0 && player.isOnline() && plugin.isEnabled()) {
            tasks.executeLater(
                    player,
                    () -> restore(player, playerId, camera, attemptsRemaining - 1),
                    1L);
            return;
        }
        displays.destroyItem(player, camera.display());
        if (current == null) {
            callbacks.restoreAfterCamera(player, playerId);
        }
    }

    @Override
    public void close() {
        for (Map.Entry<PlayerId, ActiveCamera> entry : active.entrySet()) {
            if (!active.remove(entry.getKey(), entry.getValue())) {
                continue;
            }
            Player player = Bukkit.getPlayer(entry.getKey().value());
            if (player != null && player.isOnline()) {
                restore(player, entry.getKey(), entry.getValue(), 0);
            }
        }
        active.clear();
        pending.forEach(
                (ignored, value) -> value.result().complete(ToggleResult.UNAVAILABLE));
        pending.clear();
    }

    private record PendingCamera(
            TableId tableId,
            CompletableFuture<ToggleResult> result) {}

    private record ActiveCamera(
            TableId tableId,
            FakeItemDisplay display,
            Location start,
            Location target,
            int frames) {}
}
