package top.ellan.mahjong.craftengine.privateview;

import static net.momirealms.sparrow.reflection.field.matcher.FieldMatchers.fAllOf;
import static net.momirealms.sparrow.reflection.field.matcher.FieldMatchers.fInstance;
import static net.momirealms.sparrow.reflection.field.matcher.FieldMatchers.fType;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.WrongMethodTypeException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.momirealms.sparrow.reflection.SReflection;
import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.field.SparrowField;
import net.momirealms.sparrow.reflection.remapper.Remapper;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Resolves the version-specific client camera packet once, then sends through cached handles. */
final class ClientCameraPacketSender {
    private static final String CAMERA_PACKET =
            "net.minecraft.network.protocol.game.ClientboundSetCameraPacket";
    private static final String PACKET = "net.minecraft.network.protocol.Packet";
    private static final String CONNECTION =
            "net.minecraft.server.network.ServerGamePacketListenerImpl";

    private final Logger logger;
    private volatile Access access;
    private volatile boolean unavailable;

    ClientCameraPacketSender(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    void prewarm() {
        resolve();
    }

    boolean pointAt(Player player, int entityId) {
        if (player == null || !player.isOnline() || unavailable) {
            return false;
        }
        Access resolved = access;
        if (resolved == null) {
            resolved = resolve();
        }
        if (resolved == null) {
            return false;
        }
        try {
            Object serverPlayer = resolved.playerHandle().invoke(player);
            Object connection = resolved.connection().invoke(serverPlayer);
            Object packet = SReflection.allocateInstance(resolved.packetClass());
            if (connection == null || packet == null) {
                throw new IllegalStateException("Camera packet access returned null");
            }
            resolved.entityIdSetter().invoke(packet, entityId);
            resolved.send().invoke(connection, packet);
            return true;
        } catch (WrongMethodTypeException | ClassCastException | LinkageError incompatible) {
            disable("Camera packet linkage changed", incompatible);
            return false;
        } catch (Throwable failure) {
            logger.log(Level.WARNING, "Could not update camera for " + player.getName(), failure);
            return false;
        }
    }

    private Access resolve() {
        synchronized (this) {
            if (access != null || unavailable) {
                return access;
            }
            try {
                SReflection.setRemapper(Remapper.createFromPaperJar());
                Class<?> packetClass = requireClass(CAMERA_PACKET);
                Class<?> packetInterface = requireClass(PACKET);
                SparrowField entityId = SparrowClass.of(packetClass).getDeclaredSparrowField(
                        fAllOf(fType(int.class), fInstance()), 0);
                Class<?> craftServer = Bukkit.getServer().getClass();
                Class<?> craftPlayer = Class.forName(
                        craftServer.getPackageName() + ".entity.CraftPlayer",
                        false,
                        craftServer.getClassLoader());
                Method bukkitHandle = craftPlayer.getMethod("getHandle");
                if (entityId == null) {
                    throw new IllegalStateException("Camera packet members are unavailable");
                }
                MethodHandle playerHandle = require(
                        MethodHandles.lookup().unreflect(bukkitHandle),
                        "CraftPlayer#getHandle");
                Field connectionField = findConnection(
                        bukkitHandle.getReturnType(), requireClass(CONNECTION));
                Method send = findSender(connectionField.getType(), packetInterface);
                Access resolved = new Access(
                        packetClass,
                        require(
                                SparrowField.of(entityId.field()).unreflectSetter(),
                                "camera entity id"),
                        playerHandle,
                        require(
                                MethodHandles.lookup().unreflectGetter(connectionField),
                                "player connection"),
                        require(MethodHandles.lookup().unreflect(send), "packet sender"));
                access = resolved;
                return resolved;
            } catch (Throwable failure) {
                disable("Overhead camera is unavailable on this server revision", failure);
                return null;
            }
        }
    }

    private static Field findConnection(Class<?> playerClass, Class<?> connectionClass) {
        Field named = null;
        for (Class<?> type = playerClass; type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (connectionClass.isAssignableFrom(field.getType())) {
                    field.trySetAccessible();
                    return field;
                }
                if (named == null && field.getName().equals("connection")) {
                    named = field;
                }
            }
        }
        if (named == null) {
            throw new IllegalStateException("Player connection field was not found");
        }
        named.trySetAccessible();
        return named;
    }

    private static Method findSender(Class<?> connectionClass, Class<?> packetClass) {
        Method fallback = null;
        for (Class<?> type = connectionClass; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != void.class
                        || method.getParameterCount() != 1
                        || method.getParameterTypes()[0] != packetClass) {
                    continue;
                }
                method.trySetAccessible();
                if (method.getName().equals("send")) {
                    return method;
                }
                fallback = method;
            }
        }
        if (fallback == null) {
            throw new IllegalStateException("Connection packet sender was not found");
        }
        return fallback;
    }

    private static Class<?> requireClass(String name) {
        Class<?> type = SparrowClass.find(name);
        if (type == null) {
            throw new IllegalStateException("Server class was not found: " + name);
        }
        return type;
    }

    private static MethodHandle require(MethodHandle handle, String description) {
        if (handle == null) {
            throw new IllegalStateException("Method handle was not created for " + description);
        }
        return handle;
    }

    private void disable(String message, Throwable failure) {
        if (!unavailable) {
            unavailable = true;
            logger.log(Level.WARNING, message + "; disabled until restart", failure);
        }
    }

    private record Access(
            Class<?> packetClass,
            MethodHandle entityIdSetter,
            MethodHandle playerHandle,
            MethodHandle connection,
            MethodHandle send) {}
}
