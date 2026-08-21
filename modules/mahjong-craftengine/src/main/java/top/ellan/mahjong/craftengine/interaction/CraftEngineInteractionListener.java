package top.ellan.mahjong.craftengine.interaction;

import java.util.Objects;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.Plugin;
import top.ellan.mahjong.application.automation.PlayerPresencePort;
import top.ellan.mahjong.application.interaction.InteractionRouter;
import top.ellan.mahjong.application.lobby.port.SeatInteractionAdmission;
import top.ellan.mahjong.application.lobby.port.SeatInteractionPort;
import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.craftengine.port.TableDialogPort;
import top.ellan.mahjong.craftengine.scene.CraftEngineManagedFurnitureRegistry;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.SeatId;

/** CE behavior interaction bridge plus plugin-owned player lifecycle ingress. */
public final class CraftEngineInteractionListener implements Listener {
    private final Plugin plugin;
    private final InteractionRouter router;
    private final InteractionFeedback feedback;
    private final SeatInteractionPort seats;
    private final PlayerPresencePort playerPresence;
    private final TableDialogPort tableDialogs;
    private final CraftEngineSeatResolver seatResolver = new CraftEngineSeatResolver();

    public CraftEngineInteractionListener(
            Plugin plugin,
            InteractionRouter router,
            InteractionFeedback feedback,
            SeatInteractionPort seats,
            PlayerPresencePort playerPresence,
            TableDialogPort tableDialogs) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.router = Objects.requireNonNull(router, "router");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.seats = Objects.requireNonNull(seats, "seats");
        this.playerPresence = Objects.requireNonNull(playerPresence, "playerPresence");
        this.tableDialogs = Objects.requireNonNull(tableDialogs, "tableDialogs");
    }

    public InteractionResult onFurnitureUse(CraftEngineManagedFurnitureRegistry.Use use) {
        if (!(use.context().getPlayer().platformPlayer() instanceof Player player)) {
            return InteractionResult.SUCCESS_AND_CANCEL;
        }
        if (use.identity().interaction().isPresent()) {
            router.interact(
                            use.identity().interaction().orElseThrow(),
                            new PlayerId(player.getUniqueId()),
                            use.context().isSecondaryUseActive())
                    .whenComplete((result, failure) -> feedback.accept(player, result, failure));
            return InteractionResult.SUCCESS_AND_CANCEL;
        }
        String node = use.identity().nodeId().value();
        if ("furniture/table".equals(node)) {
            tableDialogs.open(player, use.identity().tableId());
            return InteractionResult.SUCCESS_AND_CANCEL;
        }
        SeatId seatId = seatResolver.resolve(node).orElse(null);
        if (seatId == null
                || use.context().isSecondaryUseActive()
                || player.isInsideVehicle()) {
            return InteractionResult.PASS;
        }
        SeatInteractionAdmission admission = seats.interact(
                use.identity().tableId(), seatId, new PlayerId(player.getUniqueId()));
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        PlayerId playerId = new PlayerId(event.getPlayer().getUniqueId());
        router.clearPlayer(playerId);
        seats.disconnected(playerId);
        playerPresence.disconnected(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
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
}
