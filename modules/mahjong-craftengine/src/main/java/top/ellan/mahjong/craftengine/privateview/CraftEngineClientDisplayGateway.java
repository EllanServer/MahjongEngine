package top.ellan.mahjong.craftengine.privateview;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.entity.data.DisplayData;
import net.momirealms.craftengine.bukkit.util.ComponentUtils;
import net.momirealms.craftengine.bukkit.util.EntityUtils;
import net.momirealms.craftengine.core.plugin.network.NetWorkUser;
import net.momirealms.craftengine.proxy.bukkit.craftbukkit.inventory.CraftItemStackProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundAddEntityPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetEntityDataPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundTeleportEntityPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.EntityTypesProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.PositionMoveRotationProxy;
import net.momirealms.craftengine.proxy.minecraft.world.phys.Vec3Proxy;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Narrow CraftEngine adapter for the client-only displays that CE furniture cannot model.
 *
 * <p>Entity ids, metadata accessors, version proxies and connection delivery all come from CE.
 * The only state retained here is the immutable spawn description required by a private packet
 * entity; no Bukkit entity or second world lifecycle is created.</p>
 */
final class CraftEngineClientDisplayGateway {
    private final Logger logger;

    CraftEngineClientDisplayGateway(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    ClientItemDisplay createItem(Location location) {
        return new ClientItemDisplay(this, location, nextEntityId());
    }

    ClientTextDisplay createText(Location location) {
        return new ClientTextDisplay(this, location, nextEntityId());
    }

    void teleport(Player player, Location location, int entityId) {
        Objects.requireNonNull(location, "location");
        Object position = Vec3Proxy.INSTANCE.newInstance(
                location.getX(), location.getY(), location.getZ());
        Object change = PositionMoveRotationProxy.INSTANCE.newInstance(
                position,
                Vec3Proxy.ZERO,
                location.getYaw(),
                location.getPitch());
        Object packet = ClientboundTeleportEntityPacketProxy.INSTANCE.newInstance(
                entityId, change, Set.of(), false);
        sendPacket(player, packet);
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

    void destroy(Player player, ClientDisplay display) {
        try {
            sendPacket(player, removePacket(display.entityId()));
        } catch (RuntimeException | LinkageError failure) {
            logger.log(Level.FINE, "Could not remove a CraftEngine client display", failure);
        }
    }

    Object addPacket(ClientDisplay display, Object entityType) {
        Location location = display.location();
        return ClientboundAddEntityPacketProxy.INSTANCE.newInstance(
                display.entityId(),
                display.uuid(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getPitch(),
                location.getYaw(),
                entityType,
                0,
                Vec3Proxy.ZERO,
                0);
    }

    Object itemMetadataPacket(int entityId, ItemStack item) {
        ArrayList<Object> values = new ArrayList<>(1);
        DisplayData.ItemDisplayData.ItemStack.addEntityData(
                CraftItemStackProxy.INSTANCE.asNMSCopy(item), values);
        return ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(entityId, values);
    }

    Object textMetadataPacket(int entityId, String json, int rgba) {
        ArrayList<Object> values = new ArrayList<>(5);
        DisplayData.BillboardConstraints.addEntityData((byte) 0, values);
        DisplayData.TextDisplayData.Text.addEntityData(
                ComponentUtils.jsonToMinecraft(json), values);
        DisplayData.TextDisplayData.BackgroundColor.addEntityData(rgba, values);
        DisplayData.TextDisplayData.TextOpacity.addEntityData((byte) -1, values);
        DisplayData.TextDisplayData.Flags.addEntityData((byte) 0, values);
        return ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(entityId, values);
    }

    Object itemDisplayType() {
        return EntityTypesProxy.ITEM_DISPLAY;
    }

    Object textDisplayType() {
        return EntityTypesProxy.TEXT_DISPLAY;
    }

    private static int nextEntityId() {
        return EntityUtils.ENTITY_COUNTER.incrementAndGet();
    }

    private static Object removePacket(int entityId) {
        IntArrayList ids = new IntArrayList(1);
        ids.add(entityId);
        return ClientboundRemoveEntitiesPacketProxy.INSTANCE.newInstance(ids);
    }
}
