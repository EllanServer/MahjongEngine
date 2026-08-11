package top.ellan.mahjong.craftengine.privateview;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Executes client projection work only on the target player's Folia entity scheduler. */
final class PlayerRegionTaskScheduler {
    private static final int MAX_LATEST_TASKS_PER_TICK = 16;
    private final Plugin plugin;
    private final ConcurrentHashMap<UUID, PlayerBatch> latest = new ConcurrentHashMap<>();

    PlayerRegionTaskScheduler(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    void execute(Player player, Runnable task) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(task, "task");
        if (!plugin.isEnabled() || !player.isOnline()) {
            return;
        }
        player.getScheduler().run(plugin, ignored -> task.run(), null);
    }

    void executeLater(Player player, Runnable task, long delayTicks) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(task, "task");
        if (!plugin.isEnabled() || !player.isOnline()) {
            return;
        }
        player.getScheduler()
                .runDelayed(
                        plugin,
                        ignored -> task.run(),
                        null,
                        Math.max(1L, delayTicks));
    }

    void executeLatest(Player player, Object key, Runnable task) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(task, "task");
        if (!plugin.isEnabled() || !player.isOnline()) {
            return;
        }
        PlayerBatch batch = latest.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerBatch());
        batch.tasks.offer(key, task);
        scheduleLatest(player, batch, false);
    }

    void forget(UUID playerId) {
        PlayerBatch removed = latest.remove(Objects.requireNonNull(playerId, "playerId"));
        if (removed != null) {
            removed.tasks.clear();
        }
    }

    void clear() {
        latest.values().forEach(batch -> batch.tasks.clear());
        latest.clear();
    }

    private void scheduleLatest(Player player, PlayerBatch batch, boolean delayed) {
        if (!batch.scheduled.compareAndSet(false, true)) {
            return;
        }
        if (!plugin.isEnabled() || !player.isOnline()) {
            batch.tasks.clear();
            batch.scheduled.set(false);
            return;
        }
        try {
            if (delayed) {
                player.getScheduler().runDelayed(
                        plugin,
                        ignored -> drainLatest(player, batch),
                        () -> retire(batch),
                        1L);
            } else {
                player.getScheduler().run(
                        plugin,
                        ignored -> drainLatest(player, batch),
                        () -> retire(batch));
            }
        } catch (RuntimeException failure) {
            batch.scheduled.set(false);
            throw failure;
        }
    }

    private void drainLatest(Player player, PlayerBatch batch) {
        try {
            batch.tasks.drain(MAX_LATEST_TASKS_PER_TICK);
        } finally {
            batch.scheduled.set(false);
            if (!batch.tasks.isEmpty()) {
                scheduleLatest(player, batch, true);
            }
        }
    }

    private static void retire(PlayerBatch batch) {
        batch.tasks.clear();
        batch.scheduled.set(false);
    }

    private static final class PlayerBatch {
        private final LatestTaskBuffer<Object> tasks = new LatestTaskBuffer<>();
        private final AtomicBoolean scheduled = new AtomicBoolean();
    }
}
