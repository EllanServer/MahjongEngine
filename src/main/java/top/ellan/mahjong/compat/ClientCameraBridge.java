package top.ellan.mahjong.compat;

import static net.momirealms.sparrow.reflection.field.matcher.FieldMatchers.fAllOf;
import static net.momirealms.sparrow.reflection.field.matcher.FieldMatchers.fInstance;
import static net.momirealms.sparrow.reflection.field.matcher.FieldMatchers.fType;
import static net.momirealms.sparrow.reflection.method.matcher.MethodMatchers.mAllOf;
import static net.momirealms.sparrow.reflection.method.matcher.MethodMatchers.mNamedNoRemap;
import static net.momirealms.sparrow.reflection.method.matcher.MethodMatchers.mTakeArguments;

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
import net.momirealms.sparrow.reflection.method.SparrowMethod;
import net.momirealms.sparrow.reflection.remapper.Remapper;
import org.bukkit.entity.Player;

/**
 * Sends the client-only camera packet without binding the plugin to one NMS
 * revision. Sparrow Reflection resolves the members and privileged method
 * handles once; the animation path itself performs no reflective lookup.
 */
public final class ClientCameraBridge {
    private static final String CAMERA_PACKET_CLASS =
        "net.minecraft.network.protocol.game.ClientboundSetCameraPacket";
    private static final String PACKET_CLASS = "net.minecraft.network.protocol.Packet";
    private static final String CONNECTION_CLASS =
        "net.minecraft.server.network.ServerGamePacketListenerImpl";

    private final Logger logger;
    private volatile Access access;
    private volatile boolean unavailable;

    public ClientCameraBridge(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public boolean setCamera(Player player, int cameraEntityId) {
        if (player == null || !player.isOnline() || this.unavailable) {
            return false;
        }
        Access resolved = this.access;
        if (resolved == null) {
            resolved = this.resolve(player);
        }
        if (resolved == null) {
            return false;
        }
        try {
            Object serverPlayer = resolved.getHandle().invoke(player);
            Object connection = resolved.connection().invoke(serverPlayer);
            Object packet = SReflection.allocateInstance(resolved.packetClass());
            if (connection == null || packet == null) {
                throw new IllegalStateException("Camera packet access returned null");
            }
            // The packet stores its entity id in a private final field. Sparrow
            // creates a privileged setter handle once during resolution, so the
            // constructor-free packet can be initialized without a deprecated
            // Unsafe field offset or a per-send reflective lookup.
            resolved.cameraIdSetter().invoke(packet, cameraEntityId);
            resolved.sendPacket().invoke(connection, packet);
            return true;
        } catch (WrongMethodTypeException | ClassCastException | LinkageError throwable) {
            this.disable("The resolved overhead camera accessors are incompatible with this server revision", throwable);
            return false;
        } catch (Throwable throwable) {
            // A closed or changing player connection is not evidence that the
            // resolved access is invalid for every player on this server.
            this.logger.log(Level.WARNING, "Failed to send the client camera packet to " + player.getName(), throwable);
            return false;
        }
    }

    public boolean available() {
        return !this.unavailable;
    }

    private Access resolve(Player player) {
        synchronized (this) {
            if (this.access != null || this.unavailable) {
                return this.access;
            }
            try {
                SReflection.setRemapper(Remapper.createFromPaperJar());

                Class<?> packetClass = requireClass(CAMERA_PACKET_CLASS);
                Class<?> packetInterface = requireClass(PACKET_CLASS);
                SparrowField cameraIdField = SparrowClass.of(packetClass).getDeclaredSparrowField(
                    fAllOf(fType(int.class), fInstance()),
                    0
                );
                SparrowMethod getHandleMethod = SparrowClass.of(player.getClass()).getSparrowMethod(
                    mAllOf(mNamedNoRemap("getHandle"), mTakeArguments(new Class<?>[0])),
                    0
                );
                if (cameraIdField == null || getHandleMethod == null) {
                    throw new IllegalStateException("Camera packet or CraftPlayer accessor was not found");
                }

                MethodHandle getHandle = requireHandle(getHandleMethod.unreflect(), "CraftPlayer#getHandle");
                Object liveServerPlayer = getHandle.invoke(player);
                if (liveServerPlayer == null) {
                    throw new IllegalStateException("CraftPlayer#getHandle returned null");
                }
                // CraftPlayer exposes several covariant bridge getHandle()
                // methods (Entity, LivingEntity, Player and ServerPlayer).
                // Sparrow may legally select any bridge, so its declared
                // return type is not authoritative; the live object is.
                Class<?> serverPlayerClass = liveServerPlayer.getClass();
                Class<?> expectedConnectionClass = requireClass(CONNECTION_CLASS);
                Field connectionField = findConnectionField(serverPlayerClass, expectedConnectionClass);
                if (connectionField == null) {
                    throw new IllegalStateException("ServerPlayer connection accessor was not found");
                }

                Class<?> connectionClass = connectionField.getType();
                Method sendPacketMethod = findPacketSendMethod(connectionClass, packetInterface);
                if (sendPacketMethod == null) {
                    throw new IllegalStateException("Server packet send method was not found");
                }

                Access resolved = new Access(
                    packetClass,
                    unreflectFinalFieldSetter(cameraIdField.field()),
                    getHandle,
                    requireHandle(unreflectGetter(connectionField), "ServerPlayer#connection"),
                    requireHandle(unreflect(sendPacketMethod), "connection#send")
                );
                this.access = resolved;
                return resolved;
            } catch (Throwable throwable) {
                this.disable("Overhead camera is unavailable on this server revision", throwable);
                return null;
            }
        }
    }

    /**
     * Resolves the live connection by JVM type instead of a mapped field name.
     * Paper 26.x exposes this field publicly, while older Paper/Folia builds may
     * keep it private or obfuscated, so every superclass is inspected.
     */
    static Field findConnectionField(Class<?> serverPlayerClass, Class<?> expectedConnectionClass) {
        Field namedFallback = null;
        for (Class<?> type = serverPlayerClass; type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (expectedConnectionClass.isAssignableFrom(field.getType())) {
                    makeAccessible(field);
                    return field;
                }
                if (namedFallback == null && "connection".equals(field.getName())) {
                    namedFallback = field;
                }
            }
        }
        makeAccessible(namedFallback);
        return namedFallback;
    }

    /** Resolve the one-argument packet sender even when its runtime name is obfuscated. */
    static Method findPacketSendMethod(Class<?> connectionClass, Class<?> packetInterface) {
        Method typedFallback = null;
        for (Class<?> type = connectionClass; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers())
                    || method.getParameterCount() != 1
                    || method.getReturnType() != void.class
                    || method.getParameterTypes()[0] != packetInterface) {
                    continue;
                }
                if ("send".equals(method.getName())) {
                    makeAccessible(method);
                    return method;
                }
                if (typedFallback == null) {
                    typedFallback = method;
                }
            }
        }
        makeAccessible(typedFallback);
        return typedFallback;
    }

    private static void makeAccessible(java.lang.reflect.AccessibleObject member) {
        if (member != null) {
            member.trySetAccessible();
        }
    }

    private static MethodHandle unreflectGetter(Field field) throws IllegalAccessException {
        return MethodHandles.lookup().unreflectGetter(field);
    }

    private static MethodHandle unreflect(Method method) throws IllegalAccessException {
        return MethodHandles.lookup().unreflect(method);
    }

    static MethodHandle unreflectFinalFieldSetter(Field field) {
        return requireHandle(SparrowField.of(field).unreflectSetter(), "final field " + field);
    }

    private static Class<?> requireClass(String className) {
        Class<?> type = SparrowClass.find(className);
        if (type == null) {
            throw new IllegalStateException("Required server class was not found: " + className);
        }
        return type;
    }

    private static MethodHandle requireHandle(MethodHandle handle, String description) {
        if (handle == null) {
            throw new IllegalStateException("Could not create method handle for " + description);
        }
        return handle;
    }

    private void disable(String message, Throwable throwable) {
        if (this.unavailable) {
            return;
        }
        this.unavailable = true;
        this.logger.log(Level.WARNING, message + "; disabling overhead view until restart.", throwable);
    }

    private record Access(
        Class<?> packetClass,
        MethodHandle cameraIdSetter,
        MethodHandle getHandle,
        MethodHandle connection,
        MethodHandle sendPacket
    ) {
    }
}
