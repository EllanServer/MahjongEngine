package top.ellan.mahjong.craftengine;

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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.persistence.PersistentDataType;
import top.ellan.mahjong.application.InteractionHandle;
import top.ellan.mahjong.application.InteractionRouter;
import top.ellan.mahjong.application.TableActionCode;
import top.ellan.mahjong.application.TableActionResult;
import top.ellan.mahjong.spi.PlayerId;

/** CE event ingress: PDC UUID lookup, permission-neutral O(1) routing, and immediate return. */
public final class CraftEngineInteractionListener implements Listener {
    private final InteractionRouter router;
    private final InteractionFeedback feedback;
    private final NamespacedKey managedKey;
    private final NamespacedKey interactionKey;

    public CraftEngineInteractionListener(
            InteractionRouter router,
            InteractionFeedback feedback,
            NamespacedKey managedKey,
            NamespacedKey interactionKey) {
        this.router = Objects.requireNonNull(router, "router");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.managedKey = Objects.requireNonNull(managedKey, "managedKey");
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
        router.clearPlayer(new PlayerId(event.getPlayer().getUniqueId()));
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
}
