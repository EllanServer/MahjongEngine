package top.ellan.mahjong.table.runtime;

import top.ellan.mahjong.compat.CraftEngineService;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.display.DisplayClickAction;
import top.ellan.mahjong.render.display.DisplayClickAction.ActionType;
import top.ellan.mahjong.render.display.TableDisplayRegistry;
import top.ellan.mahjong.runtime.ServerScheduler;
import top.ellan.mahjong.table.core.MahjongTableManager;
import top.ellan.mahjong.table.core.MahjongTableSession;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import top.ellan.mahjong.runtime.PluginTask;

public final class TableSeatCoordinator {
    private static final long SEAT_WATCHDOG_PERIOD_TICKS = 2L;
    private static final long SEAT_WATCHDOG_DURATION_TICKS = 40L;
    private static final long SEAT_RESTORE_COOLDOWN_MILLIS = 150L;
    private static final double SEAT_RESTORE_MAX_DISTANCE_SQUARED = 16.0D;
    private static final double SEAT_EXIT_OUTWARD_DISTANCE = 0.8D;
    private static final double SEAT_EXIT_FOOT_CLEARANCE = 0.02D;
    private static final int SEAT_EXIT_GROUND_SCAN_BLOCKS = 4;
    private static final int SEAT_EXIT_DISMOUNT_RETRIES = 3;

    private final Supplier<CraftEngineService> craftEngine;
    private final ServerScheduler scheduler;
    private final MahjongTableManager tableManager;
    private final Map<UUID, SeatWatchdogLoop> seatWatchdogs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> seatRestoreCooldownUntilMillis = new ConcurrentHashMap<>();
    private final Set<UUID> seatDismountBypass = ConcurrentHashMap.newKeySet();

    public TableSeatCoordinator(Supplier<CraftEngineService> craftEngine, ServerScheduler scheduler, MahjongTableManager tableManager) {
        this.craftEngine = Objects.requireNonNull(craftEngine, "craftEngine");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.tableManager = Objects.requireNonNull(tableManager, "tableManager");
    }

    public void shutdown() {
        this.seatWatchdogs.values().forEach(SeatWatchdogLoop::close);
        this.seatWatchdogs.clear();
        this.seatRestoreCooldownUntilMillis.clear();
        this.seatDismountBypass.clear();
    }

    public DisplayClickAction seatAction(Entity entity) {
        if (entity == null) {
            return null;
        }
        DisplayClickAction direct = TableDisplayRegistry.get(entity.getEntityId());
        if (direct != null && (direct.actionType() == ActionType.JOIN_SEAT || direct.actionType() == ActionType.TOGGLE_READY)) {
            return direct;
        }
        CraftEngineService craftEngine = this.craftEngine();
        if (craftEngine == null) {
            return null;
        }
        Entity furniture = null;
        if (craftEngine.isFurnitureEntity(entity)) {
            furniture = entity;
        } else if (craftEngine.isSeatEntity(entity)) {
            furniture = craftEngine.furnitureEntityForSeat(entity);
        }
        if (furniture == null) {
            return null;
        }
        DisplayClickAction action = TableDisplayRegistry.get(furniture.getEntityId());
        if (action == null) {
            return null;
        }
        return switch (action.actionType()) {
            case JOIN_SEAT, TOGGLE_READY -> action;
            default -> null;
        };
    }

    public boolean consumeDismountBypass(UUID playerId) {
        return playerId != null && this.seatDismountBypass.remove(playerId);
    }

    public void ejectSeatOccupant(UUID playerId) {
        if (playerId == null) {
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }
        this.scheduler.runEntity(player, () -> {
            if (!player.isOnline() || !player.isInsideVehicle()) {
                return;
            }
            Entity vehicle = player.getVehicle();
            if (this.seatAction(vehicle) == null) {
                return;
            }
            this.seatDismountBypass.add(playerId);
            player.leaveVehicle();
        });
    }

    public void requestSeatRestore(Player player, MahjongTableSession session, SeatWind wind) {
        if (player == null || session == null || wind == null || this.craftEngine() == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        if (!this.tryEnterSeatRestoreCooldown(playerId)) {
            return;
        }
        this.scheduler.runEntity(player, () -> this.restoreSeatOnPlayerThread(player, playerId, session, wind));
    }

    public void movePlayerToSeatExit(UUID playerId, MahjongTableSession session, SeatWind wind) {
        if (playerId == null || session == null || wind == null) {
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        Location seatAnchor = session.seatAnchorLocation(wind);
        Location tableCenter = session.center();
        if (seatAnchor.getWorld() == null || tableCenter.getWorld() == null || !seatAnchor.getWorld().equals(tableCenter.getWorld())) {
            return;
        }
        double deltaX = seatAnchor.getX() - tableCenter.getX();
        double deltaZ = seatAnchor.getZ() - tableCenter.getZ();
        double horizontalLength = Math.hypot(deltaX, deltaZ);
        double offsetX = horizontalLength > 0.0001D ? deltaX / horizontalLength * SEAT_EXIT_OUTWARD_DISTANCE : 0.0D;
        double offsetZ = horizontalLength > 0.0001D ? deltaZ / horizontalLength * SEAT_EXIT_OUTWARD_DISTANCE : 0.0D;
        Location exitProbe = seatAnchor.clone().add(offsetX, 0.0D, offsetZ);
        exitProbe.setY(tableCenter.getBlockY());
        exitProbe.setYaw(session.seatFacingYaw(wind));
        exitProbe.setPitch(0.0F);

        // Resolve block collision on the destination region, then dismount and teleport as one
        // entity-thread operation. The CraftEngine chair's hidden seat is deliberately below the
        // floor; splitting these into unrelated tasks occasionally left the player at that hidden
        // carrier when the old teleport task observed that dismount had not completed yet.
        this.scheduler.runRegion(exitProbe, () -> {
            Location exit = groundedSeatExit(exitProbe, tableCenter.getBlockY());
            this.scheduler.runEntity(
                player,
                () -> this.dismountAndMoveToSeatExit(player, playerId, exit, SEAT_EXIT_DISMOUNT_RETRIES)
            );
        });
    }

    static Location groundedSeatExit(Location probe, int preferredFootY) {
        if (probe == null || probe.getWorld() == null) {
            return probe;
        }
        World world = probe.getWorld();
        int blockX = probe.getBlockX();
        int blockZ = probe.getBlockZ();
        int firstSupportY = Math.min(world.getMaxHeight() - 1, preferredFootY - 1);
        int lastSupportY = Math.max(world.getMinHeight(), firstSupportY - SEAT_EXIT_GROUND_SCAN_BLOCKS + 1);
        double footY = preferredFootY;
        for (int y = firstSupportY; y >= lastSupportY; y--) {
            Block support = world.getBlockAt(blockX, y, blockZ);
            if (support == null || support.isPassable() || support.isLiquid()) {
                continue;
            }
            try {
                double collisionTop = support.getBoundingBox().getMaxY();
                footY = collisionTop > y && collisionTop <= y + 1.0D
                    ? collisionTop
                    : y + 1.0D;
            } catch (RuntimeException exception) {
                footY = y + 1.0D;
            }
            break;
        }
        Location grounded = probe.clone();
        grounded.setY(footY + SEAT_EXIT_FOOT_CLEARANCE);
        return grounded;
    }

    private void dismountAndMoveToSeatExit(Player player, UUID playerId, Location exit, int retriesRemaining) {
        if (player == null || playerId == null || exit == null || !player.isOnline()) {
            if (playerId != null) {
                this.seatDismountBypass.remove(playerId);
            }
            return;
        }
        if (player.isInsideVehicle()) {
            this.seatDismountBypass.add(playerId);
            player.leaveVehicle();
            if (player.isInsideVehicle() && retriesRemaining > 0) {
                this.scheduler.runEntityDelayed(
                    player,
                    () -> this.dismountAndMoveToSeatExit(player, playerId, exit, retriesRemaining - 1),
                    1L
                );
                return;
            }
        }
        this.scheduler.teleport(player, exit).whenComplete((success, throwable) -> this.seatDismountBypass.remove(playerId));
    }

    public void startSeatWatchdog(MahjongTableSession session) {
        this.startSeatWatchdog(session, SEAT_WATCHDOG_DURATION_TICKS);
    }

    public void startSeatWatchdog(MahjongTableSession session, long durationTicks) {
        if (session == null || durationTicks <= 0L) {
            return;
        }
        for (SeatWind wind : SeatWind.values()) {
            UUID playerId = session.playerAt(wind);
            if (playerId == null || session.isBot(playerId)) {
                continue;
            }
            this.startSeatWatchdog(session, playerId, wind, durationTicks);
        }
    }

    public void startSeatWatchdog(MahjongTableSession session, UUID playerId, SeatWind wind) {
        this.startSeatWatchdog(session, playerId, wind, SEAT_WATCHDOG_DURATION_TICKS);
    }

    public void startSeatWatchdog(MahjongTableSession session, UUID playerId, SeatWind wind, long durationTicks) {
        if (session == null || playerId == null || wind == null || durationTicks <= 0L) {
            return;
        }
        long durationNanos = Math.multiplyExact(durationTicks, 50_000_000L);
        long now = System.nanoTime();
        long expiresAtNanos = now > Long.MAX_VALUE - durationNanos
            ? Long.MAX_VALUE
            : now + durationNanos;
        SeatWatchdogLoop replacement = new SeatWatchdogLoop(
            playerId,
            new SeatWatchdogBinding(session.id(), wind, expiresAtNanos)
        );
        SeatWatchdogLoop selected = seatWatchdogs.compute(playerId, (ignored, existing) -> {
            if (existing != null
                && existing.binding.matches(session.id(), wind)
                && existing.binding.expiresAtNanos() >= expiresAtNanos) {
                return existing;
            }
            if (existing != null) {
                existing.close();
            }
            return replacement;
        });
        if (selected == replacement) {
            replacement.schedule(1L);
        }
    }

    private boolean tryEnterSeatRestoreCooldown(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long cooldownUntil = this.seatRestoreCooldownUntilMillis.get(playerId);
        if (cooldownUntil != null && cooldownUntil > now) {
            return false;
        }
        this.seatRestoreCooldownUntilMillis.put(playerId, now + SEAT_RESTORE_COOLDOWN_MILLIS);
        return true;
    }

    private boolean inspectSeatWatchdogOnPlayerThread(Player player, UUID playerId, SeatWatchdogBinding binding) {
        if (player == null || playerId == null || binding == null) {
            return false;
        }
        MahjongTableSession session = this.tableManager.resolveTableById(binding.tableId());
        if (session == null
            || !session.isStarted()
            || session.seatOf(playerId) != binding.wind()
            || binding.expiresAtNanos() < System.nanoTime()) {
            return false;
        }
        if (!player.isOnline()) {
            return false;
        }
        if (!this.isPlayerSeatedAt(player, session, binding.wind()) && this.tryEnterSeatRestoreCooldown(playerId)) {
            this.restoreSeatOnPlayerThread(player, playerId, session, binding.wind());
        }
        return true;
    }

    private boolean isPlayerSeatedAt(Player player, MahjongTableSession session, SeatWind wind) {
        if (player == null || session == null || wind == null || !player.isInsideVehicle()) {
            return false;
        }
        DisplayClickAction action = this.seatAction(player.getVehicle());
        return action != null
            && action.seatWind() == wind
            && session.id().equals(action.tableId());
    }

    private void restoreSeatOnPlayerThread(Player player, UUID playerId, MahjongTableSession session, SeatWind wind) {
        if (player == null || playerId == null || session == null || wind == null || this.craftEngine() == null) {
            return;
        }
        if (!player.isOnline()) {
            return;
        }
        if (this.tableManager.tableFor(playerId) != session || player.isInsideVehicle()) {
            return;
        }
        Location seatAnchor = session.seatAnchorLocation(wind);
        if (!this.isSeatRestoreInRange(player, seatAnchor)) {
            return;
        }
        this.restoreSeatViaAnchorRegion(player, playerId, session, wind, seatAnchor);
    }

    private void restoreSeatViaAnchorRegion(Player player, UUID playerId, MahjongTableSession session, SeatWind wind, Location seatAnchor) {
        if (player == null || playerId == null || session == null || wind == null || seatAnchor == null) {
            return;
        }
        Location anchor = seatAnchor.clone();
        this.scheduler.runRegion(anchor, () -> {
            Entity furniture = this.findSeatFurnitureAtAnchor(session, wind, anchor);
            if (furniture == null) {
                return;
            }
            this.scheduler.runEntity(player, () -> this.mountPlayerIfStillEligible(player, playerId, session, wind, anchor, furniture));
        });
    }

    private void mountPlayerIfStillEligible(
        Player player,
        UUID playerId,
        MahjongTableSession session,
        SeatWind wind,
        Location anchor,
        Entity furniture
    ) {
        CraftEngineService craftEngine = this.craftEngine();
        if (player == null || playerId == null || session == null || wind == null || anchor == null || furniture == null || craftEngine == null) {
            return;
        }
        if (!player.isOnline() || player.isInsideVehicle()) {
            return;
        }
        if (this.tableManager.tableFor(playerId) != session || session.seatOf(playerId) != wind) {
            return;
        }
        if (!this.isSeatRestoreInRange(player, anchor)) {
            return;
        }
        craftEngine.seatPlayerOnFurniture(furniture, player);
    }

    private boolean isSeatRestoreInRange(Player player, Location seatAnchor) {
        if (player == null || seatAnchor == null || seatAnchor.getWorld() == null) {
            return false;
        }
        Location playerLocation = player.getLocation();
        World playerWorld = playerLocation.getWorld();
        if (playerWorld == null || !playerWorld.equals(seatAnchor.getWorld())) {
            return false;
        }
        return playerLocation.distanceSquared(seatAnchor) <= SEAT_RESTORE_MAX_DISTANCE_SQUARED;
    }

    private Entity findSeatFurnitureAtAnchor(MahjongTableSession session, SeatWind wind, Location anchor) {
        CraftEngineService craftEngine = this.craftEngine();
        if (session == null || wind == null || anchor == null || craftEngine == null) {
            return null;
        }
        World world = anchor.getWorld();
        if (world == null) {
            return null;
        }
        for (Entity entity : world.getNearbyEntities(anchor, 1.6D, 1.6D, 1.6D)) {
            if (!craftEngine.isFurnitureEntity(entity)) {
                continue;
            }
            DisplayClickAction action = TableDisplayRegistry.get(entity.getEntityId());
            if (action == null || !session.id().equals(action.tableId()) || action.seatWind() != wind) {
                continue;
            }
            if (action.actionType() == ActionType.JOIN_SEAT || action.actionType() == ActionType.TOGGLE_READY) {
                return entity;
            }
        }
        return null;
    }

    private record SeatWatchdogBinding(String tableId, SeatWind wind, long expiresAtNanos) {
        private boolean matches(String candidateTableId, SeatWind candidateWind) {
            return this.tableId.equals(candidateTableId) && this.wind == candidateWind;
        }
    }

    private final class SeatWatchdogLoop {
        private final UUID playerId;
        private final SeatWatchdogBinding binding;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<PluginTask> task = new AtomicReference<>();

        private SeatWatchdogLoop(UUID playerId, SeatWatchdogBinding binding) {
            this.playerId = playerId;
            this.binding = binding;
        }

        private void schedule(long delayTicks) {
            if (this.closed.get()) {
                return;
            }
            Player player = Bukkit.getPlayer(this.playerId);
            if (player == null || !player.isOnline()) {
                this.finish();
                return;
            }
            PluginTask next = scheduler.runEntityDelayed(player, () -> this.run(player), delayTicks);
            PluginTask previous = this.task.getAndSet(next);
            if (previous != null && previous != next) {
                previous.cancel();
            }
            if (this.closed.get() && this.task.compareAndSet(next, null)) {
                next.cancel();
            }
        }

        private void run(Player player) {
            this.task.set(null);
            if (this.closed.get() || seatWatchdogs.get(this.playerId) != this) {
                return;
            }
            if (!inspectSeatWatchdogOnPlayerThread(player, this.playerId, this.binding)) {
                this.finish();
                return;
            }
            this.schedule(SEAT_WATCHDOG_PERIOD_TICKS);
        }

        private void finish() {
            seatWatchdogs.remove(this.playerId, this);
            this.close();
        }

        private void close() {
            this.closed.set(true);
            PluginTask current = this.task.getAndSet(null);
            if (current != null) {
                current.cancel();
            }
        }
    }

    private CraftEngineService craftEngine() {
        return this.craftEngine.get();
    }
}
