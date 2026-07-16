package top.ellan.mahjong.gameroom;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import top.ellan.mahjong.compat.PaperCompatibility;
import top.ellan.mahjong.runtime.PluginTask;
import top.ellan.mahjong.runtime.ServerScheduler;

public final class GameRoomSelectionPreviewService {
    private static final int PREVIEW_PULSES = 8;
    private static final long PREVIEW_PERIOD_TICKS = 10L;
    private static final Particle.DustOptions OUTLINE_DUST = new Particle.DustOptions(Color.fromRGB(60, 220, 255), 1.15F);

    private final GameRoomSelectionService selectionService;
    private final ServerScheduler scheduler;
    private final Map<UUID, PreviewSession> sessions = new ConcurrentHashMap<>();

    public GameRoomSelectionPreviewService(GameRoomSelectionService selectionService, ServerScheduler scheduler) {
        this.selectionService = selectionService;
        this.scheduler = scheduler;
    }

    public void showSelection(Player player) {
        if (player == null || this.selectionService == null || this.scheduler == null) {
            return;
        }
        this.showSelection(player, this.selectionService.selection(player.getUniqueId()));
    }

    public void showSelection(Player player, GameRoomSelectionService.Selection selection) {
        if (player == null || selection == null || this.scheduler == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        this.cancel(playerId);

        List<Location> points = this.selectionService.previewPoints(selection, player.getLocation());
        if (points.isEmpty()) {
            return;
        }
        PreviewSession session = new PreviewSession(points, PREVIEW_PULSES);
        this.sessions.put(playerId, session);
        this.schedule(player, session, 1L);
    }

    public void cancel(UUID playerId) {
        if (playerId == null) {
            return;
        }
        PreviewSession session = this.sessions.remove(playerId);
        if (session != null) {
            session.cancel();
        }
    }

    public void cancelAll() {
        for (UUID playerId : List.copyOf(this.sessions.keySet())) {
            this.cancel(playerId);
        }
    }

    private void schedule(Player player, PreviewSession session, long delayTicks) {
        session.task = this.scheduler.runEntityDelayed(player, () -> this.pulse(player, session), delayTicks);
    }

    private void pulse(Player player, PreviewSession session) {
        if (player == null || this.sessions.get(player.getUniqueId()) != session) {
            return;
        }
        if (!player.isOnline()) {
            this.cancel(player.getUniqueId());
            return;
        }
        for (Location point : session.points()) {
            player.spawnParticle(
                PaperCompatibility.dustParticle(),
                point,
                1,
                0.0D,
                0.0D,
                0.0D,
                0.0D,
                OUTLINE_DUST
            );
        }
        session.remainingPulses--;
        if (session.remainingPulses <= 0) {
            this.sessions.remove(player.getUniqueId(), session);
            return;
        }
        this.schedule(player, session, PREVIEW_PERIOD_TICKS);
    }

    private static final class PreviewSession {
        private final List<Location> points;
        private int remainingPulses;
        private PluginTask task;

        private PreviewSession(List<Location> points, int remainingPulses) {
            this.points = List.copyOf(points);
            this.remainingPulses = remainingPulses;
        }

        private List<Location> points() {
            return this.points;
        }

        private void cancel() {
            if (this.task != null && !this.task.isCancelled()) {
                this.task.cancel();
            }
        }
    }
}
