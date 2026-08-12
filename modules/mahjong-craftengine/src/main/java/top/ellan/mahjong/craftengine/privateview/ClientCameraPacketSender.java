package top.ellan.mahjong.craftengine.privateview;

import static net.momirealms.sparrow.reflection.field.matcher.FieldMatchers.fAllOf;
import static net.momirealms.sparrow.reflection.field.matcher.FieldMatchers.fInstance;
import static net.momirealms.sparrow.reflection.field.matcher.FieldMatchers.fType;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.WrongMethodTypeException;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.momirealms.sparrow.reflection.SReflection;
import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.field.SparrowField;
import net.momirealms.sparrow.reflection.remapper.Remapper;
import org.bukkit.entity.Player;

/** Creates the one camera packet CE does not proxy; CE owns connection lookup and delivery. */
final class ClientCameraPacketSender {
    private static final String CAMERA_PACKET =
            "net.minecraft.network.protocol.game.ClientboundSetCameraPacket";

    private final SparrowDisplayGateway packets;
    private final Logger logger;
    private volatile Access access;
    private volatile boolean unavailable;

    ClientCameraPacketSender(SparrowDisplayGateway packets, Logger logger) {
        this.packets = Objects.requireNonNull(packets, "packets");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    void prewarm() {
        resolve();
    }

    boolean pointAt(Player player, int entityId) {
        if (player == null || !player.isOnline() || unavailable) {
            return false;
        }
        try {
            Object packet = createPacket(entityId);
            return packet != null && packets.sendPacket(player, packet);
        } catch (RuntimeException | LinkageError failure) {
            logger.log(Level.WARNING, "Could not update camera for " + player.getName(), failure);
            return false;
        }
    }

    Object createPacket(int entityId) {
        if (unavailable) {
            return null;
        }
        Access resolved = access == null ? resolve() : access;
        if (resolved == null) {
            return null;
        }
        try {
            Object packet = SReflection.allocateInstance(resolved.packetClass());
            if (packet == null) {
                throw new IllegalStateException("Camera packet allocation returned null");
            }
            resolved.entityIdSetter().invokeExact(packet, entityId);
            return packet;
        } catch (WrongMethodTypeException | ClassCastException | LinkageError incompatible) {
            disable("Camera packet linkage changed", incompatible);
            return null;
        } catch (Throwable failure) {
            logger.log(Level.WARNING, "Could not create an overhead camera packet", failure);
            return null;
        }
    }

    private Access resolve() {
        synchronized (this) {
            if (access != null || unavailable) {
                return access;
            }
            try {
                SReflection.setRemapper(Remapper.createFromPaperJar());
                Class<?> packetClass = SparrowClass.find(CAMERA_PACKET);
                if (packetClass == null) {
                    throw new IllegalStateException("Camera packet class is unavailable");
                }
                SparrowField entityId = SparrowClass.of(packetClass).getDeclaredSparrowField(
                        fAllOf(fType(int.class), fInstance()), 0);
                if (entityId == null) {
                    throw new IllegalStateException("Camera entity id field is unavailable");
                }
                // Sparrow's ASM accessor is defined as an NMS nestmate. Paper's isolated plugin
                // loader therefore cannot make its plugin-owned SIntField superclass visible.
                MethodHandle setter = entityId.unreflectSetter();
                if (setter == null) {
                    throw new IllegalStateException("Camera entity id setter is unavailable");
                }
                setter = setter.asType(MethodType.methodType(void.class, Object.class, int.class));
                Access resolved = new Access(packetClass, setter);
                access = resolved;
                return resolved;
            } catch (Throwable failure) {
                disable("Overhead camera is unavailable on this server revision", failure);
                return null;
            }
        }
    }

    private void disable(String message, Throwable failure) {
        if (!unavailable) {
            unavailable = true;
            logger.log(Level.WARNING, message + "; disabled until restart", failure);
        }
    }

    private record Access(Class<?> packetClass, MethodHandle entityIdSetter) {}
}
