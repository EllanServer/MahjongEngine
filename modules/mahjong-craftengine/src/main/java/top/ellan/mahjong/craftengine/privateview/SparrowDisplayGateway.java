package top.ellan.mahjong.craftengine.privateview;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.momirealms.sparrow.heart.SparrowHeart;
import net.momirealms.sparrow.heart.feature.entity.display.FakeItemDisplay;
import net.momirealms.sparrow.heart.feature.entity.display.FakeTextDisplay;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Narrow Sparrow adapter for client-only display creation, movement and cleanup. */
final class SparrowDisplayGateway {
    private static final Object CREATION_LOCK = new Object();

    private final SparrowHeart heart;
    private final Logger logger;

    SparrowDisplayGateway(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        heart = SparrowHeart.getInstance();
    }

    FakeItemDisplay createItem(Location location) {
        synchronized (CREATION_LOCK) {
            return heart.createFakeItemDisplay(location);
        }
    }

    FakeTextDisplay createText(Location location) {
        synchronized (CREATION_LOCK) {
            return heart.createFakeTextDisplay(location);
        }
    }

    void teleport(Player player, Location location, int entityId) {
        heart.sendClientSideTeleportEntity(player, location, false, entityId);
    }

    void destroyItem(Player player, FakeItemDisplay display) {
        try {
            display.destroy(player);
        } catch (RuntimeException failure) {
            logger.log(Level.FINE, "Could not remove a client item display", failure);
        }
    }

    void destroyText(Player player, FakeTextDisplay display) {
        try {
            heart.removeClientSideEntity(player, display.entityID());
        } catch (RuntimeException failure) {
            logger.log(Level.FINE, "Could not remove a client action label", failure);
        }
    }
}
