package top.ellan.mahjong.table.core;

import com.destroystokyo.paper.event.player.PlayerUseUnknownEntityEvent;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import io.papermc.paper.event.player.PlayerUntrackEntityEvent;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.display.DisplayClickAction;
import top.ellan.mahjong.render.display.DisplayClickAction.ActionType;
import top.ellan.mahjong.render.display.ClientInteractionProxyRegistry;
import top.ellan.mahjong.render.display.DisplayVisibilityRegistry;
import top.ellan.mahjong.render.display.DisplayEntities;
import top.ellan.mahjong.render.display.DisplayInteractionRayRegistry;
import top.ellan.mahjong.render.display.TableDisplayRegistry;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.RayTraceResult;

final class TableEventCoordinator implements Listener {
    private static final long DUPLICATE_DISPLAY_ACTION_WINDOW_NANOS = 150_000_000L;
    private static final long OVERHEAD_EXIT_GUARD_SECONDS = 2L;
    private static final double FLAT_INTERACTION_MAX_DISTANCE = 6.0D;
    private static final double FLAT_INTERACTION_BLOCK_EPSILON = 0.05D;
    /**
     * Bounded TTL on the per-player recent-action cache. The dedup window is
     * 150ms (DUPLICATE_DISPLAY_ACTION_WINDOW_NANOS), so anything older than a
     * few seconds is by definition not a duplicate of a fresh click — but the
     * previous bare ConcurrentHashMap only evicted on PlayerQuitEvent, which
     * on Folia can occasionally be missed when the player's entity region
     * differs from the global region at logout time. That left entries
     * forever. The TTL below caps the worst-case leak at 2 minutes per entry
     * so a server with many unique transient viewers cannot grow this map
     * without bound.
     */
    private static final long RECENT_ACTION_TTL_SECONDS = 120L;
    private final MahjongTableManager manager;
    private final SeatAttendanceCoordinator seatAttendance;
    private final Cache<UUID, RecentDisplayAction> recentDisplayActions = Caffeine.newBuilder()
        .expireAfterAccess(RECENT_ACTION_TTL_SECONDS, TimeUnit.SECONDS)
        .build();
    /**
     * Pressing Shift while mounted may emit both a sneak event and a dismount event, in either
     * order. Keep the paired event from being interpreted as a second, real request to leave the
     * table after the overhead camera has already been removed.
     */
    private final Cache<UUID, Boolean> recentOverheadExits = Caffeine.newBuilder()
        .expireAfterWrite(OVERHEAD_EXIT_GUARD_SECONDS, TimeUnit.SECONDS)
        .build();

    TableEventCoordinator(MahjongTableManager manager) {
        this.manager = manager;
        this.seatAttendance = new SeatAttendanceCoordinator(manager);
    }

    @EventHandler
    void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        for (MahjongTableSession session : this.manager.tables()) {
            session.regionDisplaysInternal().discardViewerClientOverlay(playerId);
        }
        ClientInteractionProxyRegistry.clearViewer(playerId);
        this.manager.overheadViewCoordinatorRef().discard(playerId);
        this.recentDisplayActions.invalidate(playerId);
        this.recentOverheadExits.invalidate(playerId);
        this.seatAttendance.cancel(playerId);
        this.manager.clearRecentHandInput(playerId);
        if (!this.retainActiveSeatForDisconnect(playerId)) {
            this.manager.leave(playerId);
        }
    }

    @EventHandler
    void onJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        MahjongTableSession session = this.restoreConnectedSeat(playerId);
        if (session != null) {
            this.manager.pluginRef().scheduler().runRegion(session.center(), () -> {
                if (this.manager.tableFor(playerId) == session) {
                    session.flushViewerActionsNow(playerId);
                }
            });
        }
        this.manager.pluginRef().scheduler().runEntity(event.getPlayer(), () -> this.manager.pluginRef().craftEngine().syncTrackedEntitiesFor(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onTrackPublicJoinSource(PlayerTrackEntityEvent event) {
        Player player = event.getPlayer();
        Entity trackedEntity = event.getEntity();
        UUID trackedUuid = trackedEntity.getUniqueId();
        int trackedEntityId = trackedEntity.getEntityId();
        this.manager.pluginRef().scheduler().runEntityDelayed(trackedEntity, () -> {
            if (!trackedEntity.isValid()
                || !trackedUuid.equals(trackedEntity.getUniqueId())
                || !trackedEntity.getTrackedPlayers().contains(player)) {
                return;
            }
            DisplayInteractionRayRegistry.PublicJoinSource source =
                DisplayInteractionRayRegistry.publicJoinSource(trackedEntityId);
            if (source == null || !trackedUuid.equals(source.entityUuid())) {
                return;
            }
            MahjongTableSession session = this.manager.resolveTableById(source.tableId());
            if (session != null) {
                session.regionDisplaysInternal().trackPublicJoinSource(
                    player,
                    source.regionKey(),
                    trackedEntityId,
                    trackedUuid
                );
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onUntrackPublicJoinSource(PlayerUntrackEntityEvent event) {
        Player player = event.getPlayer();
        Entity trackedEntity = event.getEntity();
        int trackedEntityId = trackedEntity.getEntityId();
        UUID trackedUuid = trackedEntity.getUniqueId();
        DisplayInteractionRayRegistry.PublicJoinSource source =
            DisplayInteractionRayRegistry.publicJoinSource(trackedEntityId);
        if (source == null || !trackedUuid.equals(source.entityUuid())) {
            return;
        }
        MahjongTableSession session = this.manager.resolveTableById(source.tableId());
        if (session != null) {
            session.regionDisplaysInternal().untrackPublicJoinSource(
                player,
                source.regionKey(),
                trackedEntityId,
                trackedUuid
            );
        }
    }

    boolean retainActiveSeatForDisconnect(UUID playerId) {
        MahjongTableSession session = this.manager.tableFor(playerId);
        if (session == null || (!session.isStarted() && !session.isRoundStartInProgress())) {
            return false;
        }
        session.setPlayerUnattended(playerId, true);
        return true;
    }

    MahjongTableSession restoreConnectedSeat(UUID playerId) {
        MahjongTableSession session = this.manager.tableFor(playerId);
        if (session != null) {
            this.seatAttendance.markPresent(session, playerId);
        } else {
            this.seatAttendance.cancel(playerId);
        }
        return session;
    }

    void onSeatMount(Event event) {
        if (!(eventEntity(event, "getEntity") instanceof Player player)) {
            return;
        }
        DisplayClickAction action = this.manager.seatCoordinatorRef().seatAction(eventEntity(event, "getMount"));
        if (action == null || action.actionType() != ActionType.JOIN_SEAT) {
            return;
        }
        MahjongTableSession currentSeat = this.manager.tableFor(player.getUniqueId());
        if (MahjongTableManager.isSameSeatJoin(currentSeat, player.getUniqueId(), action)) {
            this.seatAttendance.markPresent(currentSeat, player.getUniqueId());
            return;
        }
        MahjongTableSession session = this.manager.join(player, action.tableId(), action.seatWind());
        if (session != null) {
            this.manager.pluginRef().messages().send(player, "command.joined_table", this.manager.pluginRef().messages().tag("table_id", session.id()));
            this.manager.seatCoordinatorRef().requestSeatRestore(player, session, action.seatWind());
            return;
        }
        cancel(event);
        this.manager.pluginRef().messages().actionBar(player, "command.join_failed");
    }

    void onSeatDismount(Event event) {
        if (!(eventEntity(event, "getEntity") instanceof Player player)) {
            return;
        }
        UUID playerId = player.getUniqueId();
        if (this.manager.seatCoordinatorRef().consumeDismountBypass(playerId)) {
            this.seatAttendance.cancel(playerId);
            this.manager.overheadViewCoordinatorRef().exit(player, false);
            this.recentOverheadExits.invalidate(playerId);
            return;
        }
        if (this.exitOverheadAndRestoreSeat(player)) {
            cancel(event);
            return;
        }
        MahjongTableSession playerSession = this.manager.tableFor(playerId);
        SeatWind playerSeatWind = playerSession == null ? null : playerSession.seatOf(playerId);
        if (playerSession != null && (playerSession.isStarted() || playerSession.isRoundStartInProgress()) && playerSeatWind != null) {
            // A quit can emit a dismount event as the player entity is removed. Keep that seat bound
            // to its UUID; onQuit/onJoin toggle unattended play without converting it into a manual
            // leave-after-round request.
            if (!player.isOnline()) {
                this.seatAttendance.cancel(playerId);
                playerSession.setPlayerUnattended(playerId, true);
                return;
            }
            this.seatAttendance.monitor(player, playerSession, playerSeatWind);
            if (this.manager.pluginRef().settings().tableFreeMoveDuringRound()) {
                return;
            }
            cancel(event);
            this.manager.seatCoordinatorRef().startSeatWatchdog(playerSession, playerId, playerSeatWind);
            this.manager.seatCoordinatorRef().requestSeatRestore(player, playerSession, playerSeatWind);
            return;
        }
        DisplayClickAction action = this.manager.seatCoordinatorRef().seatAction(eventEntity(event, "getDismounted"));
        if (action == null) {
            return;
        }
        MahjongTableSession session = this.manager.resolveTableById(action.tableId());
        SeatWind seatWind = action.seatWind() != null ? action.seatWind() : playerSeatWind;
        if (session != null && (session.isStarted() || session.isRoundStartInProgress()) && seatWind != null) {
            this.seatAttendance.monitor(player, session, seatWind);
            if (this.manager.pluginRef().settings().tableFreeMoveDuringRound()) {
                return;
            }
            cancel(event);
            this.manager.seatCoordinatorRef().startSeatWatchdog(session, playerId, seatWind);
            this.manager.seatCoordinatorRef().requestSeatRestore(player, session, seatWind);
            return;
        }
        this.manager.pluginRef().scheduler().runEntity(player, () -> this.manager.leave(playerId));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void onSeatSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            return;
        }
        Player player = event.getPlayer();
        if (this.exitOverheadAndRestoreSeat(player)) {
            event.setCancelled(true);
        }
    }

    boolean exitOverheadAndRestoreSeat(Player player) {
        if (player == null) {
            return false;
        }
        UUID playerId = player.getUniqueId();
        boolean exited = this.manager.overheadViewCoordinatorRef().exit(player, true);
        if (exited) {
            this.recentOverheadExits.put(playerId, Boolean.TRUE);
        } else if (this.recentOverheadExits.getIfPresent(playerId) == null) {
            return false;
        }

        MahjongTableSession session = this.manager.tableFor(playerId);
        SeatWind wind = session == null ? null : session.seatOf(playerId);
        if (session != null && wind != null && (session.isStarted() || session.isRoundStartInProgress())) {
            // A camera transition is not an absence. Clear any stale disconnect delegation and
            // make the seat watchdog repair a client/server dismount race before input resumes.
            this.seatAttendance.markPresent(session, playerId);
            this.manager.seatCoordinatorRef().startSeatWatchdog(session, playerId, wind);
            this.manager.seatCoordinatorRef().requestSeatRestore(player, session, wind);
        }
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getTo() != null) {
            this.manager.overheadViewCoordinatorRef().exit(event.getPlayer(), false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onPlayerDeath(PlayerDeathEvent event) {
        this.manager.overheadViewCoordinatorRef().exit(event.getEntity(), false);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    void onDisplayInteract(PlayerInteractEntityEvent event) {
        this.handleDisplayInteract(event.getPlayer(), event.getRightClicked(), event);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    void onDisplayInteractAt(PlayerInteractAtEntityEvent event) {
        this.handleDisplayInteract(event.getPlayer(), event.getRightClicked(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void onFlatDisplayInteract(PlayerInteractEvent event) {
        if (event == null || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action eventAction = event.getAction();
        if (eventAction != Action.RIGHT_CLICK_AIR
            && eventAction != Action.RIGHT_CLICK_BLOCK
            && eventAction != Action.LEFT_CLICK_AIR
            && eventAction != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        DisplayClickAction action = this.resolveFlatDisplayAction(player, event.getInteractionPoint());
        if (action == null) {
            return;
        }
        event.setCancelled(true);
        this.handleResolvedDisplayAction(player, action);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void onFlatDisplayAnimation(PlayerAnimationEvent event) {
        if (event == null || event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        Player player = event.getPlayer();
        DisplayClickAction action = this.resolveFlatDisplayAction(player, null);
        if (action == null) {
            return;
        }
        event.setCancelled(true);
        this.handleResolvedDisplayAction(player, action);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void onClientInteractionProxy(PlayerUseUnknownEntityEvent event) {
        if (event == null || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        String proxyTableId = ClientInteractionProxyRegistry.tableIdFor(
            event.getEntityId(),
            player.getUniqueId()
        );
        if (proxyTableId == null) {
            return;
        }
        DisplayClickAction action = this.resolveFlatDisplayAction(player, null);
        if (action == null || !proxyTableId.equals(action.tableId())) {
            return;
        }
        this.handleResolvedDisplayAction(player, action);
        if (!event.isAttack()) {
            player.swingMainHand();
        }
    }

    private DisplayClickAction resolveFlatDisplayAction(Player player, Location eventInteractionPoint) {
        if (player == null) {
            return null;
        }
        TableOverheadViews overheadViews = this.manager.overheadViews();
        if (overheadViews != null && overheadViews.isActive(player.getUniqueId())) {
            return null;
        }
        Location eye = player.getEyeLocation();
        World world = eye.getWorld();
        if (world == null) {
            return null;
        }
        double maxDistance = FLAT_INTERACTION_MAX_DISTANCE;
        RayTraceResult blockHit = world.rayTraceBlocks(
            eye,
            eye.getDirection(),
            FLAT_INTERACTION_MAX_DISTANCE,
            FluidCollisionMode.NEVER,
            true
        );
        if (blockHit != null && blockHit.getHitPosition() != null) {
            maxDistance = Math.min(
                maxDistance,
                blockHit.getHitPosition().distance(eye.toVector())
                    + FLAT_INTERACTION_BLOCK_EPSILON
            );
        }
        if (eventInteractionPoint != null && world.equals(eventInteractionPoint.getWorld())) {
            maxDistance = Math.min(
                maxDistance,
                eventInteractionPoint.toVector().distance(eye.toVector())
                    + FLAT_INTERACTION_BLOCK_EPSILON
            );
        }
        return DisplayInteractionRayRegistry.resolve(player, maxDistance);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void onProtectedDisplayDamage(EntityDamageEvent event) {
        if (this.isProtectedTableEntity(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    private void handleDisplayInteract(Player player, Entity clickedEntity, org.bukkit.event.Cancellable event) {
        if (player == null || clickedEntity == null || event == null) {
            return;
        }
        DisplayClickAction action = TableDisplayRegistry.get(clickedEntity.getEntityId());
        if (action == null) {
            return;
        }
        if (!this.isClickAllowedForEntity(player, clickedEntity)) {
            return;
        }
        if (this.manager.pluginRef().craftEngine() != null && this.manager.pluginRef().craftEngine().isFurnitureEntity(clickedEntity)) {
            return;
        }

        event.setCancelled(true);
        this.handleResolvedDisplayAction(player, action);
    }

    private boolean isProtectedTableEntity(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (DisplayEntities.isManagedEntity(this.manager.pluginRef().bukkitPlugin(), entity)) {
            return true;
        }
        if (TableDisplayRegistry.get(entity.getEntityId()) != null || this.manager.seatCoordinatorRef().seatAction(entity) != null) {
            return true;
        }
        if (this.manager.pluginRef().craftEngine() == null) {
            return false;
        }
        if (this.manager.pluginRef().craftEngine().isSeatEntity(entity)) {
            Entity furnitureEntity = this.manager.pluginRef().craftEngine().furnitureEntityForSeat(entity);
            if (furnitureEntity != null && this.manager.pluginRef().craftEngine().isManagedFurnitureEntity(furnitureEntity)) {
                return true;
            }
        }
        if (this.manager.pluginRef().craftEngine().isFurnitureEntity(entity)) {
            return this.manager.pluginRef().craftEngine().isManagedFurnitureEntity(entity);
        }
        return false;
    }

    private boolean isDuplicateDisplayAction(UUID playerId, DisplayClickAction action) {
        RecentDisplayAction recent = this.recentDisplayActions.getIfPresent(playerId);
        if (recent == null || !recent.matches(action)) {
            return false;
        }
        return System.nanoTime() - recent.timestampNanos() <= DUPLICATE_DISPLAY_ACTION_WINDOW_NANOS;
    }

    private void handleResolvedDisplayAction(Player player, DisplayClickAction action) {
        if (player == null || action == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        if (this.isDuplicateDisplayAction(playerId, action)) {
            return;
        }
        this.rememberDisplayAction(playerId, action);
        boolean accepted = this.manager.handleDisplayAction(player, action);
        if (!accepted) {
            if (action.actionType() == ActionType.HAND_TILE) {
                this.manager.pluginRef().messages().actionBar(player, "packet.cannot_click_tile");
            } else {
                this.manager.pluginRef().messages().actionBar(player, "command.join_failed");
            }
        }
    }

    private void rememberDisplayAction(UUID playerId, DisplayClickAction action) {
        if (playerId == null || action == null) {
            return;
        }
        this.recentDisplayActions.put(playerId, new RecentDisplayAction(action, System.nanoTime()));
    }

    private boolean isManagedDisplayActionEntity(Entity entity, UUID viewerId) {
        if (entity == null) {
            return false;
        }
        if (!(entity instanceof org.bukkit.entity.Display)) {
            return false;
        }
        if (!DisplayEntities.isManagedEntity(this.manager.pluginRef().bukkitPlugin(), entity)) {
            return false;
        }
        int entityId = entity.getEntityId();
        if (TableDisplayRegistry.get(entityId) == null) {
            return false;
        }
        return DisplayVisibilityRegistry.canView(entityId, viewerId);
    }

    private boolean isClickAllowedForEntity(Player player, Entity entity) {
        if (player == null || entity == null) {
            return false;
        }
        if (!player.isOnline()) {
            return false;
        }
        return DisplayVisibilityRegistry.canView(entity.getEntityId(), player.getUniqueId());
    }

    private static Entity eventEntity(Event event, String methodName) {
        if (event == null) {
            return null;
        }
        try {
            Object value = event.getClass().getMethod(methodName).invoke(event);
            return value instanceof Entity entity ? entity : null;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return null;
        }
    }

    private static void cancel(Event event) {
        if (event instanceof Cancellable cancellable) {
            cancellable.setCancelled(true);
        }
    }

    private record RecentDisplayAction(DisplayClickAction action, long timestampNanos) {
        private boolean matches(DisplayClickAction candidate) {
            return MahjongTableManager.sameDisplayAction(this.action, candidate);
        }
    }
}
