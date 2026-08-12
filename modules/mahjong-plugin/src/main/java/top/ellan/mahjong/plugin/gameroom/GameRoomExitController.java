package top.ellan.mahjong.plugin.gameroom;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import top.ellan.mahjong.application.concurrent.Cancellable;
import top.ellan.mahjong.application.concurrent.TaskScheduler;
import top.ellan.mahjong.domain.match.MatchBinding;
import top.ellan.mahjong.domain.table.ParticipantRole;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.plugin.MahjongPaperPlugin;
import top.ellan.mahjong.plugin.config.PluginConfiguration;
import top.ellan.mahjong.plugin.i18n.LocalizedMessageCatalog;
import top.ellan.mahjong.plugin.match.StartedRulePackMatch;
import top.ellan.mahjong.plugin.platform.CraftEnginePlatformRuntime;
import top.ellan.mahjong.plugin.table.LiveTableDirectory;
import top.ellan.mahjong.plugin.table.TableLifecycleCoordinator;
import top.ellan.mahjong.spi.PlayerId;

/**
 * Event-driven live-match boundary enforcement with at most one scheduled task per table.
 */
final class GameRoomExitController implements Listener, AutoCloseable {
    private final MahjongPaperPlugin plugin;
    private final PluginConfiguration.GameRoomSettings settings;
    private final GameRoomRegistry rooms;
    private final LiveTableDirectory liveTables;
    private final CraftEnginePlatformRuntime platform;
    private final TableLifecycleCoordinator lifecycle;
    private final TaskScheduler scheduler;
    private final LocalizedMessageCatalog messages;
    private final long timeoutNanos;
    private final Map<TableId, TableExitState> exits = new HashMap<>();
    private boolean closed;

    GameRoomExitController(
            MahjongPaperPlugin plugin,
            PluginConfiguration.GameRoomSettings settings,
            GameRoomRegistry rooms,
            LiveTableDirectory liveTables,
            CraftEnginePlatformRuntime platform,
            TableLifecycleCoordinator lifecycle,
            TaskScheduler scheduler,
            LocalizedMessageCatalog messages) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.rooms = Objects.requireNonNull(rooms, "rooms");
        this.liveTables = Objects.requireNonNull(liveTables, "liveTables");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.messages = Objects.requireNonNull(messages, "messages");
        timeoutNanos = Duration.ofSeconds(settings.leaveCountdownSeconds()).toNanos();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location destination = event.getTo();
        if (destination == null || sameBlock(event.getFrom(), destination)) {
            return;
        }
        updatePosition(event.getPlayer(), event.getFrom(), destination);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        enforceCurrentPosition(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        enforceDeparture(event.getPlayer());
    }

    private void updatePosition(Player player, Location previous, Location current) {
        MatchBoundary boundary = boundaryFor(player);
        if (boundary == null) {
            return;
        }
        boolean wasInside = boundary.room().contains(previous);
        boolean isInside = boundary.room().contains(current);
        if (isInside) {
            if (cancel(boundary.match(), boundary.playerId())) {
                sendReturned(player);
            }
        } else if (wasInside && depart(boundary.match(), boundary.playerId())) {
            sendWarning(player, boundary.room());
        }
    }

    private void enforceCurrentPosition(Player player) {
        MatchBoundary boundary = boundaryFor(player);
        if (boundary == null) {
            return;
        }
        if (boundary.room().contains(player.getLocation())) {
            if (cancel(boundary.match(), boundary.playerId())) {
                sendReturned(player);
            }
        } else if (depart(boundary.match(), boundary.playerId())) {
            sendWarning(player, boundary.room());
        }
    }

    private void enforceDeparture(Player player) {
        MatchBoundary boundary = boundaryFor(player);
        if (boundary != null) {
            depart(boundary.match(), boundary.playerId());
        }
    }

    /** Recovery inspects only this match's fixed human roster, never all online players. */
    void reconcileRecoveredMatch(StartedRulePackMatch match) {
        Objects.requireNonNull(match, "match");
        try {
            Bukkit.getGlobalRegionScheduler()
                    .execute(
                            plugin,
                            () ->
                                    match.participants().stream()
                                            .filter(
                                                    participant ->
                                                            participant.role()
                                                                    == ParticipantRole.PLAYER)
                                            .map(
                                                    participant ->
                                                            plugin.getServer()
                                                                    .getPlayer(
                                                                            participant
                                                                                    .playerId()
                                                                                    .value()))
                                            .filter(Objects::nonNull)
                                            .forEach(
                                                    player ->
                                                            player.getScheduler()
                                                                    .run(
                                                                            plugin,
                                                                            ignored ->
                                                                                    enforceRecoveredPosition(
                                                                                            player,
                                                                                            match),
                                                                            null)));
        } catch (RuntimeException shuttingDown) {
            plugin.getLogger().fine("Recovered game-room boundary check was skipped");
        }
    }

    private void enforceRecoveredPosition(Player player, StartedRulePackMatch expected) {
        StartedRulePackMatch current =
                liveTables
                        .findByPlayer(new PlayerId(player.getUniqueId()))
                        .filter(match -> match.binding().equals(expected.binding()))
                        .orElse(null);
        if (current != null) {
            enforceCurrentPosition(player);
        }
    }

    private MatchBoundary boundaryFor(Player player) {
        PlayerId playerId = new PlayerId(player.getUniqueId());
        StartedRulePackMatch match = liveTables.findByPlayer(playerId).orElse(null);
        if (match == null
                || liveTables.isDeparting(match.tableId(), playerId)
                || match.participants().stream()
                        .noneMatch(
                                participant ->
                                        participant.playerId().equals(playerId)
                                                && participant.role()
                                                        == ParticipantRole.PLAYER)) {
            return null;
        }
        GameRoom room = platform.tableAnchor(match.tableId()).flatMap(rooms::roomAt).orElse(null);
        return room == null ? null : new MatchBoundary(match, playerId, room);
    }

    /** Returns true only when a new deadline was installed for this player. */
    private boolean depart(StartedRulePackMatch match, PlayerId playerId) {
        TableId tableId = match.tableId();
        boolean scheduleFailed = false;
        synchronized (this) {
            if (closed) {
                return false;
            }
            TableExitState state = exits.get(tableId);
            if (state == null || !state.binding.equals(match.binding())) {
                if (state != null) {
                    state.cancel();
                }
                state = new TableExitState(match.binding());
                exits.put(tableId, state);
            }
            if (state.deadlines.containsKey(playerId)) {
                return false;
            }
            state.deadlines.put(playerId, System.nanoTime() + timeoutNanos);
            try {
                reschedule(tableId, state);
            } catch (RuntimeException failure) {
                exits.remove(tableId);
                state.cancel();
                plugin.getLogger()
                        .log(
                                Level.SEVERE,
                                "Could not schedule game-room exit for " + tableId,
                                failure);
                scheduleFailed = true;
            }
        }
        if (scheduleFailed) {
            forceEnd(match, Set.of(playerId));
        }
        return !scheduleFailed;
    }

    private boolean cancel(StartedRulePackMatch match, PlayerId playerId) {
        TableId tableId = match.tableId();
        Set<PlayerId> scheduleFailureDepartures = Set.of();
        synchronized (this) {
            TableExitState state = exits.get(tableId);
            if (state == null
                    || !state.binding.equals(match.binding())
                    || state.deadlines.remove(playerId) == null) {
                return false;
            }
            if (state.deadlines.isEmpty()) {
                state.cancel();
                exits.remove(tableId);
            } else {
                try {
                    reschedule(tableId, state);
                } catch (RuntimeException failure) {
                    scheduleFailureDepartures = Set.copyOf(state.deadlines.keySet());
                    exits.remove(tableId);
                    state.cancel();
                    plugin.getLogger()
                            .log(
                                    Level.SEVERE,
                                    "Could not reschedule game-room exit for " + tableId,
                                    failure);
                }
            }
        }
        if (!scheduleFailureDepartures.isEmpty()) {
            forceEnd(match, scheduleFailureDepartures);
        }
        return true;
    }

    private void reschedule(TableId tableId, TableExitState state) {
        state.cancel();
        long generation = ++state.generation;
        long earliest = state.deadlines.values().stream().mapToLong(Long::longValue).min().orElseThrow();
        long remaining = Math.max(0L, earliest - System.nanoTime());
        state.task =
                scheduler.schedule(
                        () -> expire(tableId, state.binding, generation),
                        Duration.ofNanos(remaining));
    }

    private void expire(TableId tableId, MatchBinding binding, long generation) {
        List<PlayerId> expired = new ArrayList<>(4);
        synchronized (this) {
            TableExitState state = exits.get(tableId);
            if (closed
                    || state == null
                    || !state.binding.equals(binding)
                    || state.generation != generation) {
                return;
            }
            long now = System.nanoTime();
            state.deadlines.forEach(
                    (playerId, deadline) -> {
                        if (deadline <= now) {
                            expired.add(playerId);
                        }
                    });
            if (expired.isEmpty()) {
                reschedule(tableId, state);
                return;
            }
            exits.remove(tableId);
            state.task = null;
        }
        StartedRulePackMatch match = liveTables.find(tableId).orElse(null);
        if (match == null || !match.binding().equals(binding)) {
            return;
        }
        Set<PlayerId> departed =
                expired.stream()
                        .filter(
                                playerId ->
                                        match.participants().stream()
                                                .anyMatch(
                                                        participant ->
                                                                participant.playerId()
                                                                                .equals(playerId)
                                                                        && participant.role()
                                                                                == ParticipantRole
                                                                                        .PLAYER))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (departed.isEmpty()) {
            return;
        }
        forceEnd(match, departed);
    }

    private void forceEnd(StartedRulePackMatch match, Set<PlayerId> departed) {
        try {
            lifecycle
                    .forceEnd(match.tableId(), match.binding(), departed)
                    .whenComplete(
                            (ignored, failure) -> {
                                if (failure == null) {
                                    notifyForcedEnd(match);
                                    return;
                                }
                                plugin.getLogger()
                                        .log(
                                                Level.WARNING,
                                                "Could not force-end game-room table "
                                                        + match.tableId(),
                                                failure);
                            });
        } catch (RuntimeException failure) {
            plugin.getLogger()
                    .log(
                            Level.FINE,
                            "Game-room table already ended " + match.tableId(),
                            failure);
        }
    }

    private void sendWarning(Player player, GameRoom room) {
        if (!settings.enterExitMessages()) {
            return;
        }
        player.sendMessage(
                Component.text(
                        String.format(
                                player.locale(),
                                messages.resolve(
                                        player.locale(),
                                        "mahjongpaper.gameroom.exit_warning",
                                        "Return to %s within %s seconds or this match will end."),
                                room.name(),
                                settings.leaveCountdownSeconds()),
                        NamedTextColor.YELLOW));
    }

    private void sendReturned(Player player) {
        if (settings.enterExitMessages()) {
            player.sendMessage(
                    Component.text(
                            messages.resolve(
                                    player.locale(),
                                    "mahjongpaper.gameroom.returned",
                                    "You returned to the game room; the countdown was cancelled."),
                            NamedTextColor.GREEN));
        }
    }

    private void notifyForcedEnd(StartedRulePackMatch match) {
        if (!settings.enterExitMessages()) {
            return;
        }
        try {
            Bukkit.getGlobalRegionScheduler()
                    .execute(
                        plugin,
                        () ->
                                match.participants().stream()
                                        .filter(
                                                participant ->
                                                        participant.role()
                                                                != ParticipantRole.BOT)
                                        .map(
                                                participant ->
                                                        plugin.getServer()
                                                                .getPlayer(
                                                                        participant.playerId()
                                                                                .value()))
                                        .filter(Objects::nonNull)
                                        .forEach(
                                                player ->
                                                        player.getScheduler()
                                                                .run(
                                                                        plugin,
                                                                        ignored ->
                                                                                player.sendMessage(
                                                                                        Component.text(
                                                                                                messages.resolve(
                                                                                                        player.locale(),
                                                                                                        "mahjongpaper.gameroom.force_ended",
                                                                                                        "The match ended because a player stayed outside its game room."),
                                                                                                NamedTextColor.RED)),
                                                                        null)));
        } catch (RuntimeException shuttingDown) {
            plugin.getLogger().fine("Game-room force-end notification skipped during shutdown");
        }
    }

    private static boolean sameBlock(Location first, Location second) {
        return first.getWorld() != null
                && second.getWorld() != null
                && first.getWorld().getUID().equals(second.getWorld().getUID())
                && first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }

    @Override
    public synchronized void close() {
        closed = true;
        exits.values().forEach(TableExitState::cancel);
        exits.clear();
    }

    private record MatchBoundary(
            StartedRulePackMatch match, PlayerId playerId, GameRoom room) {}

    private static final class TableExitState {
        private final MatchBinding binding;
        private final Map<PlayerId, Long> deadlines = new HashMap<>(4);
        private Cancellable task;
        private long generation;

        private TableExitState(MatchBinding binding) {
            this.binding = Objects.requireNonNull(binding, "binding");
        }

        private void cancel() {
            if (task != null) {
                task.cancel();
                task = null;
            }
        }
    }
}
