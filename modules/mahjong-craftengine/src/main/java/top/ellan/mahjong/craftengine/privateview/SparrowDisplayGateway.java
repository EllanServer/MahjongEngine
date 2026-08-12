package top.ellan.mahjong.craftengine.privateview;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.entity.data.DisplayData;
import net.momirealms.craftengine.core.plugin.network.NetWorkUser;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetEntityDataPacketProxy;
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

    Object interpolationPacket(int entityId, int durationTicks) {
        if (durationTicks <= 0 || durationTicks > 59) {
            return null;
        }
        try {
            Object value = DisplayData.PosRotInterpolationDuration.create(
                    DisplayData.PosRotInterpolationDuration.entityDataAccessor(),
                    durationTicks);
            return ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(
                    entityId, List.of(value));
        } catch (RuntimeException | LinkageError failure) {
            logger.log(Level.FINE, "CraftEngine display interpolation is unavailable", failure);
            return null;
        }
    }

    boolean sendPacket(Player player, Object packet) {
        if (player == null || !player.isOnline() || packet == null) {
            return false;
        }
        NetWorkUser user = BukkitAdaptor.adapt(player);
        if (user == null || !user.isOnline()) {
            return false;
        }
        user.sendPacket(packet, false);
        return true;
    }

    boolean sendPackets(Player player, List<Object> packets) {
        if (player == null || !player.isOnline() || packets.isEmpty()) {
            return false;
        }
        NetWorkUser user = BukkitAdaptor.adapt(player);
        if (user == null || !user.isOnline()) {
            return false;
        }
        user.sendPackets(packets, false);
        return true;
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
