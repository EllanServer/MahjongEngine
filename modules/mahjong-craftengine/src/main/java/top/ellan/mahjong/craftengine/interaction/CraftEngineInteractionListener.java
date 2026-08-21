package top.ellan.mahjong.craftengine.interaction;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import top.ellan.mahjong.application.automation.PlayerPresencePort;
import top.ellan.mahjong.application.interaction.InteractionHandle;
import top.ellan.mahjong.application.interaction.InteractionRouter;
import top.ellan.mahjong.application.lobby.port.SeatInteractionAdmission;
import top.ellan.mahjong.application.lobby.port.SeatInteractionPort;
import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.craftengine.port.TableDialogPort;
import top.ellan.mahjong.craftengine.scene.CraftEngineManagedFurnitureRegistry;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.SeatId;

/** CE wake-up bridge plus exact viewer-ray and plugin-owned lifecycle ingress. */
public final class CraftEngineInteractionListener implements Listener {
    private static final double MAX_RAY_DISTANCE = 6.0D;
    private static final double BLOCK_OCCLUSION_EPSILON = 0.05D;
    private static final long DUPLICATE_WINDOW_NANOS = 40_000_000L;

    private final Plugin plugin;
    private final InteractionRouter router;
    private final InteractionRayRegistry interactionRays;
    private final InteractionFeedback feedback;
    private final SeatInteractionPort seats;
    private final PlayerPresencePort playerPresence;
    private final TableDialogPort tableDialogs;
    private final CraftEngineSeatResolver seatResolver = new CraftEngineSeatResolver();
    private final ConcurrentHashMap<UUID, RecentRay> recentRays = new ConcurrentHashMap<>();

    public CraftEngineInteractionListener(
            Plugin plugin,
            InteractionRouter router,
            InteractionRayRegistry interactionRays,
            InteractionFeedback feedback,
            SeatInteractionPort seats,
            PlayerPresencePort playerPresence,
            TableDialogPort tableDialogs) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.router = Objects.requireNonNull(router, "router");
        this.interactionRays = Objects.requireNonNull(interactionRays, "interactionRays");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.seats = Objects.requireNonNull(seats, "seats");
        this.playerPresence = Objects.requireNonNull(playerPresence, "playerPresence");
        this.tableDialogs = Objects.requireNonNull(tableDialogs, "tableDialogs");
    }

    public InteractionResult onFurnitureUse(CraftEngineManagedFurnitureRegistry.Use use) {
        if (!(use.context().getPlayer().platformPlayer() instanceof Player player)) {
            return InteractionResult.SUCCESS_AND_CANCEL;
        }
        UUID playerId = player.getUniqueId();
        TableId tableId = use.identity().tableId();
        // CE interaction furniture only wakes a client packet. Resolve on the player's owning
        // region; a furniture callback may run on a different Folia region.
        if (use.identity().interaction().isPresent()) {
            if (!handledRecently(playerId)
                    && interactionRays.hasTargets(playerId, tableId)) {
                boolean secondaryUse = use.context().isSecondaryUseActive();
                player.getScheduler().run(
                        plugin,
                        ignored -> dispatchRay(player, secondaryUse, tableId),
                        null);
            }
            return InteractionResult.SUCCESS_AND_CANCEL;
        }
        if (handledRecently(playerId)) {
            return InteractionResult.SUCCESS_AND_CANCEL;
        }
        String node = use.identity().nodeId().value();
        if ("furniture/table".equals(node)) {
            tableDialogs.open(player, tableId);
            return InteractionResult.SUCCESS_AND_CANCEL;
        }
        SeatId seatId = seatResolver.resolve(node).orElse(null);
        if (seatId == null
                || use.context().isSecondaryUseActive()
                || player.isInsideVehicle()) {
            return InteractionResult.PASS;
        }
        SeatInteractionAdmission admission =
                seats.interact(tableId, seatId, new PlayerId(playerId));
        admission.completion().whenComplete((result, failure) -> {
            if (failure != null
                    || result == null
                    || result.code() != TableActionCode.ACCEPTED_MEMORY) {
                player.getScheduler().run(plugin, ignored -> player.leaveVehicle(), null);
            }
            feedback.accept(player, result, failure);
        });
        return admission.admitted()
                ? InteractionResult.PASS
                : InteractionResult.SUCCESS_AND_CANCEL;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() == Action.PHYSICAL) {
            return;
        }
        if (dispatchRay(
                event.getPlayer(),
                event.getPlayer().isSneaking(),
                null,
                event.getInteractionPoint())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() == PlayerAnimationType.ARM_SWING
                && dispatchRay(event.getPlayer(), event.getPlayer().isSneaking(), null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        PlayerId playerId = new PlayerId(event.getPlayer().getUniqueId());
        recentRays.remove(event.getPlayer().getUniqueId());
        router.clearPlayer(playerId);
        seats.disconnected(playerId);
        playerPresence.disconnected(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        recentRays.remove(event.getPlayer().getUniqueId());
        seats.connected(new PlayerId(event.getPlayer().getUniqueId()));
        playerPresence.connected(new PlayerId(event.getPlayer().getUniqueId()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking()
                && router.exitOverhead(new PlayerId(event.getPlayer().getUniqueId()))) {
            event.setCancelled(true);
            feedback.accept(
                    event.getPlayer(),
                    new TableActionResult(
                            TableActionCode.OVERHEAD_VIEW_EXITED,
                            0,
                            "overhead-view-exited"),
                    null);
        }
    }

    private boolean handledRecently(UUID playerId) {
        RecentRay recent = recentRays.get(playerId);
        return recent != null
                && System.nanoTime() - recent.timestampNanos() <= DUPLICATE_WINDOW_NANOS;
    }

    private boolean dispatchRay(Player player, boolean secondaryUse, TableId requiredTable) {
        return dispatchRay(player, secondaryUse, requiredTable, null);
    }

    private boolean dispatchRay(
            Player player,
            boolean secondaryUse,
            TableId requiredTable,
            Location interactionPoint) {
        UUID playerUuid = player.getUniqueId();
        if (!interactionRays.hasTargets(playerUuid, requiredTable)) {
            return false;
        }
        Optional<InteractionHandle> selected = interactionRays.resolve(
                player, rayDistance(player, interactionPoint), requiredTable);
        if (selected.isEmpty()) {
            return false;
        }
        InteractionHandle handle = selected.orElseThrow();
        PlayerId playerId = new PlayerId(playerUuid);
        long now = System.nanoTime();
        RecentRay previous = recentRays.get(playerUuid);
        if (previous != null
                && previous.handle().equals(handle)
                && now - previous.timestampNanos() <= DUPLICATE_WINDOW_NANOS) {
            return true;
        }
        recentRays.put(playerUuid, new RecentRay(handle, now));
        router.interact(handle, playerId, secondaryUse)
                .whenComplete((result, failure) -> feedback.accept(player, result, failure));
        return true;
    }

    private static double rayDistance(Player player, Location interactionPoint) {
        Location eye = player.getEyeLocation();
        World world = eye.getWorld();
        if (world == null) {
            return MAX_RAY_DISTANCE;
        }
        double maxDistance = MAX_RAY_DISTANCE;
        Vector direction = eye.getDirection();
        RayTraceResult blocked = world.rayTraceBlocks(
                eye,
                direction,
                MAX_RAY_DISTANCE,
                FluidCollisionMode.NEVER,
                true);
        if (blocked != null && blocked.getHitPosition() != null) {
            maxDistance = Math.min(
                    maxDistance,
                    blocked.getHitPosition().distance(eye.toVector())
                            + BLOCK_OCCLUSION_EPSILON);
        }
        if (interactionPoint != null && world.equals(interactionPoint.getWorld())) {
            maxDistance = Math.min(
                    maxDistance,
                    interactionPoint.toVector().distance(eye.toVector())
                            + BLOCK_OCCLUSION_EPSILON);
        }
        return maxDistance;
    }

    private record RecentRay(InteractionHandle handle, long timestampNanos) {
        private RecentRay {
            Objects.requireNonNull(handle, "handle");
        }
    }
}
