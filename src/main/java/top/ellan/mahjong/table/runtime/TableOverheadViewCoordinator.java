package top.ellan.mahjong.table.runtime;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.momirealms.sparrow.heart.SparrowHeart;
import net.momirealms.sparrow.heart.feature.entity.FakeEntity;
import net.momirealms.sparrow.heart.feature.entity.display.FakeItemDisplay;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import top.ellan.mahjong.compat.ClientCameraBridge;
import top.ellan.mahjong.compat.ClientDisplayInterpolationBridge;
import top.ellan.mahjong.compat.SparrowFakeEntityFactory;
import top.ellan.mahjong.config.PluginSettings;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.table.core.MahjongTableSession;
import top.ellan.mahjong.table.core.TableActionDeadlines;
import top.ellan.mahjong.table.core.TableOverheadViews;
import top.ellan.mahjong.table.core.TableRuntimeServices;

/** Maintains one client-only overhead camera per seated player. */
public final class TableOverheadViewCoordinator implements TableOverheadViews {
    private final TableRuntimeServices plugin;
    private final ClientCameraBridge cameraBridge;
    private final ClientDisplayInterpolationBridge interpolationBridge;
    private final Map<UUID, ActiveView> activeViews = new ConcurrentHashMap<>();
    private volatile SparrowHeart heart;
    private volatile boolean heartUnavailable;

    public TableOverheadViewCoordinator(TableRuntimeServices plugin) {
        this.plugin = plugin;
        this.cameraBridge = new ClientCameraBridge(plugin.getLogger());
        this.interpolationBridge = new ClientDisplayInterpolationBridge(plugin.getLogger());
    }

    public ToggleResult toggle(Player player, MahjongTableSession session, SeatWind wind) {
        if (player == null) {
            return ToggleResult.REJECTED;
        }
        if (this.activeViews.containsKey(player.getUniqueId())) {
            return this.exit(player, true) ? ToggleResult.EXITED : ToggleResult.REJECTED;
        }
        return this.enter(player, session, wind) ? ToggleResult.ENTERED : ToggleResult.REJECTED;
    }

    @Override
    public boolean isActive(UUID playerId) {
        return playerId != null && this.activeViews.containsKey(playerId);
    }

    @Override
    public boolean isAvailable() {
        return !this.heartUnavailable && this.cameraBridge.available();
    }

    public boolean exit(Player player, boolean notify) {
        if (player == null) {
            return false;
        }
        ActiveView view = this.activeViews.remove(player.getUniqueId());
        if (view == null) {
            return false;
        }
        this.finishExit(player, view, notify, true);
        return true;
    }

    public void discard(UUID playerId) {
        if (playerId == null) {
            return;
        }
        ActiveView view = this.activeViews.remove(playerId);
        if (view != null) {
            // A disconnected player may retain their active seat. Restore the
            // action clock before unattended handling takes over on the next
            // table tick instead of leaving an orphaned camera suspension.
            TableActionDeadlines.resume(view.session(), playerId);
        }
    }

    @Override
    public void closeTable(String tableId) {
        if (tableId == null) {
            return;
        }
        for (Map.Entry<UUID, ActiveView> entry : List.copyOf(this.activeViews.entrySet())) {
            ActiveView view = entry.getValue();
            if (!tableId.equalsIgnoreCase(view.tableId()) || !this.activeViews.remove(entry.getKey(), view)) {
                continue;
            }
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                this.plugin.scheduler().runEntity(player, () -> this.finishExit(player, view, false, true));
            } else {
                TableActionDeadlines.discardSuspension(view.session(), entry.getKey());
            }
        }
    }

    @Override
    public void closeAll() {
        for (Map.Entry<UUID, ActiveView> entry : List.copyOf(this.activeViews.entrySet())) {
            ActiveView view = entry.getValue();
            if (!this.activeViews.remove(entry.getKey(), view)) {
                continue;
            }
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                this.plugin.scheduler().runEntity(player, () -> this.finishExit(player, view, false, true));
            } else {
                TableActionDeadlines.discardSuspension(view.session(), entry.getKey());
            }
        }
    }

    public void shutdown() {
        for (Map.Entry<UUID, ActiveView> entry : List.copyOf(this.activeViews.entrySet())) {
            ActiveView view = entry.getValue();
            if (!this.activeViews.remove(entry.getKey(), view)) {
                continue;
            }
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                // Plugin disable cannot rely on a newly scheduled entity task.
                // Heart's fake entity and the camera packet are client-only, so
                // perform one direct best-effort restore without touching a
                // Bukkit world entity.
                this.finishExit(player, view, false, false);
            } else {
                TableActionDeadlines.discardSuspension(view.session(), entry.getKey());
            }
        }
        this.activeViews.clear();
    }

    private boolean enter(Player player, MahjongTableSession session, SeatWind wind) {
        PluginSettings.OverheadViewSettings settings = this.plugin.settings().tables().overheadView();
        if (!settings.enabled()
            || session == null
            || !session.isStarted()
            || wind == null
            || !player.isOnline()
            || !player.isInsideVehicle()) {
            return false;
        }

        SparrowHeart resolvedHeart = this.heart();
        if (resolvedHeart == null || !this.cameraBridge.available()) {
            this.plugin.messages().send(player, "table.overhead.unavailable");
            return false;
        }

        Location start = player.getEyeLocation().clone();
        Location target = session.center().clone().add(0.0D, settings.height(), 0.0D);
        target.setYaw(session.seatFacingYaw(wind));
        target.setPitch(90.0F);
        FakeItemDisplay camera = null;
        ActiveView view = null;
        try {
            // A display entity has zero eye-height, so the configured height is
            // the actual client camera height rather than an armor-stand base.
            camera = SparrowFakeEntityFactory.createItemDisplay(resolvedHeart, start);
            camera.item(new ItemStack(Material.AIR));
            camera.spawn(player);
            int interpolationTicks = OverheadCameraPath.clientInterpolationTicks(settings.transitionTicks());
            boolean clientInterpolationConfigured = this.interpolationBridge.configure(
                player,
                camera.entityID(),
                interpolationTicks
            );
            int serverKeyframes = OverheadCameraPath.serverKeyframeCount(
                settings.transitionTicks(),
                clientInterpolationConfigured
            );
            view = new ActiveView(session.id(), session, camera, start, target, serverKeyframes);
            ActiveView previous = this.activeViews.putIfAbsent(player.getUniqueId(), view);
            if (previous != null) {
                this.destroyCamera(player, camera);
                return false;
            }
            TableActionDeadlines.suspend(session, player.getUniqueId());
            if (!this.cameraBridge.setCamera(player, camera.entityID())) {
                this.activeViews.remove(player.getUniqueId(), view);
                TableActionDeadlines.resume(session, player.getUniqueId());
                this.restoreCameraAndDestroy(player, camera, true, 3, null);
                this.plugin.messages().send(player, "table.overhead.unavailable");
                return false;
            }
            this.startAnimation(player, view);
            session.flushViewerActionsNow(player.getUniqueId());
            this.plugin.messages().send(player, "table.overhead.active");
            return true;
        } catch (Throwable throwable) {
            if (view != null) {
                if (this.activeViews.remove(player.getUniqueId(), view)) {
                    TableActionDeadlines.resume(view.session(), player.getUniqueId());
                }
            }
            ActiveView current = this.activeViews.get(player.getUniqueId());
            if (camera != null && (current == null || current.camera() != camera)) {
                this.restoreCameraAndDestroy(player, camera, true, 3, null);
            }
            this.plugin.getLogger().log(Level.WARNING, "Failed to enter overhead view for " + player.getName(), throwable);
            this.plugin.messages().send(player, "table.overhead.unavailable");
            return false;
        }
    }

    private void startAnimation(Player player, ActiveView view) {
        // The spawn location is frame zero. Start frame one on the next tick so a
        // configured T-tick transition reaches its target at tick T, including the
        // final client-side interpolation window. Sending frame one immediately
        // would make both the interpolated and fallback paths finish one tick early.
        this.plugin.scheduler().runEntityDelayed(player, () -> this.animate(player, view, 1), 1L);
    }

    private void animate(Player player, ActiveView view, int frame) {
        if (this.activeViews.get(player.getUniqueId()) != view || !player.isOnline()) {
            return;
        }
        try {
            this.sendAnimationFrame(player, view, frame);
            if (frame < view.serverKeyframes()) {
                this.plugin.scheduler().runEntityDelayed(player, () -> this.animate(player, view, frame + 1), 1L);
            }
        } catch (RuntimeException | LinkageError exception) {
            if (this.activeViews.remove(player.getUniqueId(), view)) {
                this.finishExit(player, view, false, true);
                this.refreshAfterAnimationFailure(player, view);
            }
            this.plugin.getLogger().log(Level.WARNING, "Overhead camera animation failed for " + player.getName(), exception);
        }
    }

    private void sendAnimationFrame(Player player, ActiveView view, int frame) {
        int serverKeyframes = Math.max(1, view.serverKeyframes());
        Location location = OverheadCameraPath.interpolate(view.start(), view.target(), frame, serverKeyframes);
        this.heart.sendClientSideTeleportEntity(player, location, false, view.camera().entityID());
    }

    private void refreshAfterAnimationFailure(Player player, ActiveView view) {
        try {
            view.session().flushViewerActionsNow(player.getUniqueId());
            this.plugin.messages().send(player, "table.overhead.unavailable");
        } catch (RuntimeException exception) {
            this.plugin.getLogger().log(Level.FINE, "Failed to refresh the overhead camera controls after rollback", exception);
        }
    }

    private void finishExit(Player player, ActiveView view, boolean notify, boolean retry) {
        Runnable restored = () -> {
            ActiveView current = this.activeViews.get(player.getUniqueId());
            if (current == null) {
                TableActionDeadlines.resume(view.session(), player.getUniqueId());
                if (notify) {
                    view.session().flushViewerActionsNow(player.getUniqueId());
                    this.plugin.messages().send(player, "table.overhead.restored");
                }
            } else if (current.session() != view.session()) {
                // A new table may have acquired the player's camera while the
                // old restore retried. Do not resume the old table's action.
                TableActionDeadlines.discardSuspension(view.session(), player.getUniqueId());
            }
        };
        this.restoreCameraAndDestroy(player, view.camera(), retry, retry ? 3 : 0, restored);
    }

    private void restoreCameraAndDestroy(
        Player player,
        FakeEntity camera,
        boolean retry,
        int attemptsRemaining,
        Runnable restored
    ) {
        ActiveView current = this.activeViews.get(player.getUniqueId());
        int restoredCameraId = current == null ? player.getEntityId() : current.camera().entityID();
        if (this.cameraBridge.setCamera(player, restoredCameraId)) {
            if (current == null || current.camera() != camera) {
                this.destroyCamera(player, camera);
            }
            if (restored != null) {
                restored.run();
            }
            return;
        }
        if (retry && attemptsRemaining > 0 && player.isOnline()) {
            try {
                this.plugin.scheduler().runEntityDelayed(
                    player,
                    () -> this.restoreCameraAndDestroy(player, camera, true, attemptsRemaining - 1, restored),
                    1L
                );
                return;
            } catch (RuntimeException exception) {
                this.plugin.getLogger().log(Level.FINE, "Could not schedule an overhead camera restore retry", exception);
            }
        }
        // Removing the target after retries are exhausted lets the vanilla
        // client fall back instead of retaining an unbounded fake entity.
        this.destroyCamera(player, camera);
        if (restored != null) {
            restored.run();
        }
    }

    private void destroyCamera(Player player, FakeEntity camera) {
        try {
            camera.destroy(player);
        } catch (RuntimeException exception) {
            this.plugin.getLogger().log(Level.FINE, "Failed to remove an overhead camera entity", exception);
        }
    }

    private SparrowHeart heart() {
        SparrowHeart resolved = this.heart;
        if (resolved != null || this.heartUnavailable) {
            return resolved;
        }
        synchronized (this) {
            if (this.heart != null || this.heartUnavailable) {
                return this.heart;
            }
            try {
                this.heart = SparrowHeart.getInstance();
                return this.heart;
            } catch (Throwable throwable) {
                this.heartUnavailable = true;
                this.plugin.getLogger().log(Level.WARNING, "Sparrow Heart does not support this server; overhead view is disabled.", throwable);
                return null;
            }
        }
    }

    public enum ToggleResult {
        ENTERED,
        EXITED,
        REJECTED
    }

    private record ActiveView(
        String tableId,
        MahjongTableSession session,
        FakeEntity camera,
        Location start,
        Location target,
        int serverKeyframes
    ) {
    }
}
