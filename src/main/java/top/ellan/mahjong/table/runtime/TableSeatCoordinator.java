package top.ellan.mahjong.table.runtime;

import top.ellan.mahjong.compat.CraftEngineService;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.display.DisplayClickAction;
import top.ellan.mahjong.render.display.DisplayClickAction.ActionType;
import top.ellan.mahjong.render.display.TableDisplayRegistry;
import top.ellan.mahjong.runtime.ServerScheduler;
import top.ellan.mahjong.table.core.MahjongTableManager;
import top.ellan.mahjong.table.core.MahjongTableSession;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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
    private final Map<UUID, SeatWatchdogBinding> seatWatchdogs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> seatRestoreCooldownUntilMillis = new ConcurrentHashMap<>();
    private final Set<UUID> seatDismountBypass = ConcurrentHashMap.newKeySet();
    private final AtomicLong seatWatchdogClock = new AtomicLong();
    private PluginTask seatWatchdogTask;

    public TableSeatCoordinator(Supplier<CraftEngineService> craftEngine, ServerScheduler scheduler, MahjongTableManager tableManager) {
        this.craftEngine = Objects.requireNonNull(craftEngine, "craftEngine");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.tableManager = Objects.requireNonNull(tableManager, "tableManager");
    }

    public void shutdown() {
        if (this.seatWatchdogTask != null) {
            this.seatWatchdogTask.cancel();
            this.seatWatchdogTask = null;
        }
        this.seatWatchdogs.clear();
        this.seatWatchdogClock.set(0L);
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
        long expiresAtTick = this.seatWatchdogClock.get() + durationTicks;
        SeatWatchdogBinding existing = this.seatWatchdogs.get(playerId);
        if (existing == null || existing.expiresAtTick() < expiresAtTick) {
            this.seatWatchdogs.put(playerId, new SeatWatchdogBinding(session.id(), wind, expiresAtTick));
        }
        this.ensureSeatWatchdogTask();
    }

    private void ensureSeatWatchdogTask() {
        if (this.seatWatchdogTask != null && !this.seatWatchdogTask.isCancelled()) {
            return;
        }
        this.seatWatchdogTask = this.scheduler.runGlobalTimer(this::runSeatWatchdogs, 1L, SEAT_WATCHDOG_PERIOD_TICKS);
    }

    private void runSeatWatchdogs() {
        if (this.seatWatchdogs.isEmpty()) {
            this.stopSeatWatchdogTask();
            return;
        }
        long nowTick = this.seatWatchdogClock.updateAndGet(current -> current + SEAT_WATCHDOG_PERIOD_TICKS);
        for (Map.Entry<UUID, SeatWatchdogBinding> entry : this.seatWatchdogs.entrySet()) {
            UUID playerId = entry.getKey();
            SeatWatchdogBinding binding = entry.getValue();
            // Pure-local-state short-circuit only: binding expiry is owned by this
            // global timer thread, so it is safe to read here. All session-state
            // checks (isStarted, seatOf) and player online checks are deferred to
            // inspectSeatWatchdogOnPlayerThread, which runs on the player's entity
            // thread. Reading session.isStarted() / session.seatOf() here would
            // touch non-region threads to session state (roundController etc.)
            // even though T1 made those fields volatile; we still prefer the
            // region-thread read because it eliminates the torn-read window where
            // the binding is valid at check time but the session starts/stops a
            // round between this check and the runEntity callback.
            if (binding.expiresAtTick() < nowTick) {
                this.seatWatchdogs.remove(playerId, binding);
                continue;
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                this.seatWatchdogs.remove(playerId, binding);
                continue;
            }
            this.scheduler.runEntity(player, () -> this.inspectSeatWatchdogOnPlayerThread(player, playerId, binding));
        }
        if (this.seatWatchdogs.isEmpty()) {
            this.stopSeatWatchdogTask();
        }
    }

    private void stopSeatWatchdogTask() {
        if (this.seatWatchdogTask != null) {
            this.seatWatchdogTask.cancel();
            this.seatWatchdogTask = null;
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

    private void inspectSeatWatchdogOnPlayerThread(Player player, UUID playerId, SeatWatchdogBinding binding) {
        if (player == null || playerId == null || binding == null) {
            return;
        }
        SeatWatchdogBinding currentBinding = this.seatWatchdogs.get(playerId);
        if (!binding.equals(currentBinding)) {
            return;
        }
        MahjongTableSession session = this.tableManager.resolveTableById(binding.tableId());
        long nowTick = this.seatWatchdogClock.get();
        if (session == null || !session.isStarted() || session.seatOf(playerId) != binding.wind() || binding.expiresAtTick() < nowTick) {
            this.seatWatchdogs.remove(playerId, binding);
            return;
        }
        if (!player.isOnline()) {
            this.seatWatchdogs.remove(playerId, binding);
            return;
        }
        if (!this.isPlayerSeatedAt(player, session, binding.wind()) && this.tryEnterSeatRestoreCooldown(playerId)) {
            this.restoreSeatOnPlayerThread(player, playerId, session, binding.wind());
        }
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

    private record SeatWatchdogBinding(String tableId, SeatWind wind, long expiresAtTick) {
    }

    private CraftEngineService craftEngine() {
        return this.craftEngine.get();
    }
}
