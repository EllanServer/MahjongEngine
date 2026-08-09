package top.ellan.mahjong.craftengine.interaction;

import java.util.Objects;
import java.util.UUID;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.FurnitureHitEvent;
import net.momirealms.craftengine.bukkit.api.event.FurnitureInteractEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import top.ellan.mahjong.application.interaction.InteractionHandle;
import top.ellan.mahjong.application.interaction.InteractionRouter;
import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.application.lobby.port.SeatInteractionAdmission;
import top.ellan.mahjong.application.lobby.port.SeatInteractionPort;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.SeatId;

/** CE event ingress: PDC UUID lookup, permission-neutral O(1) routing, and immediate return. */
public final class CraftEngineInteractionListener implements Listener {
    private final Plugin plugin;
    private final InteractionRouter router;
    private final InteractionFeedback feedback;
    private final SeatInteractionPort seats;
    private final CraftEngineSeatResolver seatResolver;
    private final NamespacedKey managedKey;
    private final NamespacedKey tableKey;
    private final NamespacedKey nodeKey;
    private final NamespacedKey interactionKey;

    public CraftEngineInteractionListener(
            Plugin plugin,
            InteractionRouter router,
            InteractionFeedback feedback,
            SeatInteractionPort seats,
            NamespacedKey managedKey,
            NamespacedKey tableKey,
            NamespacedKey nodeKey,
            NamespacedKey interactionKey) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.router = Objects.requireNonNull(router, "router");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.seats = Objects.requireNonNull(seats, "seats");
        seatResolver = new CraftEngineSeatResolver();
        this.managedKey = Objects.requireNonNull(managedKey, "managedKey");
        this.tableKey = Objects.requireNonNull(tableKey, "tableKey");
        this.nodeKey = Objects.requireNonNull(nodeKey, "nodeKey");
        this.interactionKey = Objects.requireNonNull(interactionKey, "interactionKey");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(FurnitureInteractEvent event) {
        if (event.furniture() == null || event.player() == null) {
            return;
        }
        Entity entity = event.furniture().bukkitEntity();
        if (!managed(entity)) {
            return;
        }
        String encoded =
                entity.getPersistentDataContainer().get(interactionKey, PersistentDataType.STRING);
        if (encoded == null) {
            routeSeat(event, entity);
            return;
        }
        InteractionHandle handle;
        try {
            handle = new InteractionHandle(UUID.fromString(encoded));
        } catch (IllegalArgumentException invalidHandle) {
            event.setCancelled(true);
            feedback.accept(event.player(), null, invalidHandle);
            return;
        }
        event.setCancelled(true);
        Player player = event.player();
        router.interact(handle, new PlayerId(player.getUniqueId()), player.isSneaking())
                .whenComplete((result, failure) -> feedback.accept(player, result, failure));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        PlayerId playerId = new PlayerId(event.getPlayer().getUniqueId());
        router.clearPlayer(playerId);
        seats.disconnected(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        seats.connected(new PlayerId(event.getPlayer().getUniqueId()));
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(FurnitureBreakEvent event) {
        if (event.furniture() != null && managed(event.furniture().bukkitEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHit(FurnitureHitEvent event) {
        if (event.furniture() != null && managed(event.furniture().bukkitEntity())) {
            event.setCancelled(true);
        }
    }

    private boolean managed(Entity entity) {
        return entity != null
                && entity.getPersistentDataContainer().has(managedKey, PersistentDataType.BYTE);
    }

    private void routeSeat(FurnitureInteractEvent event, Entity entity) {
        String node = entity.getPersistentDataContainer().get(nodeKey, PersistentDataType.STRING);
        if (!"furniture/table".equals(node)) {
            return;
        }
        String encodedTable =
                entity.getPersistentDataContainer().get(tableKey, PersistentDataType.STRING);
        SeatId seatId = seatResolver.resolve(event.hitBox()).orElse(null);
        if (encodedTable == null || seatId == null) {
            event.setCancelled(true);
            return;
        }
        TableId tableId;
        try {
            tableId = TableId.parse(encodedTable);
        } catch (IllegalArgumentException invalidTable) {
            event.setCancelled(true);
            feedback.accept(event.player(), null, invalidTable);
            return;
        }
        Player player = event.player();
        SeatInteractionAdmission admission =
                seats.interact(tableId, seatId, new PlayerId(player.getUniqueId()));
        if (!admission.admitted()) {
            event.setCancelled(true);
        }
        admission.completion()
                .whenComplete(
                        (result, failure) -> {
                            if (failure != null
                                    || result == null
                                    || result.code() != TableActionCode.ACCEPTED_MEMORY) {
                                player.getScheduler()
                                        .run(
                                                plugin,
                                                ignored -> player.leaveVehicle(),
                                                null);
                            }
                            feedback.accept(player, result, failure);
                        });
    }
}
