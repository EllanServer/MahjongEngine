package top.ellan.mahjong.compat;

import static net.momirealms.sparrow.reflection.constructor.matcher.ConstructorMatchers.cTakeArguments;
import static net.momirealms.sparrow.reflection.field.matcher.FieldMatchers.fAllOf;
import static net.momirealms.sparrow.reflection.field.matcher.FieldMatchers.fNamed;
import static net.momirealms.sparrow.reflection.field.matcher.FieldMatchers.fNamedNoRemap;
import static net.momirealms.sparrow.reflection.field.matcher.FieldMatchers.fStatic;
import static net.momirealms.sparrow.reflection.field.matcher.FieldMatchers.fType;
import static net.momirealms.sparrow.reflection.method.matcher.MethodMatchers.mAllOf;
import static net.momirealms.sparrow.reflection.method.matcher.MethodMatchers.mNamed;
import static net.momirealms.sparrow.reflection.method.matcher.MethodMatchers.mNamedNoRemap;
import static net.momirealms.sparrow.reflection.method.matcher.MethodMatchers.mStatic;
import static net.momirealms.sparrow.reflection.method.matcher.MethodMatchers.mTakeArguments;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.WrongMethodTypeException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.momirealms.sparrow.reflection.SReflection;
import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.constructor.SparrowConstructor;
import net.momirealms.sparrow.reflection.field.SparrowField;
import net.momirealms.sparrow.reflection.method.SparrowMethod;
import net.momirealms.sparrow.reflection.remapper.Remapper;
import org.bukkit.entity.Player;

/**
 * Configures client-only display position/rotation interpolation without adding a server entity.
 *
 * <p>Sparrow Heart 0.72 deliberately exposes only the item and text payload of its fake display
 * API. Where the vanilla display interpolation metadata exists (1.20.2 onward), this bridge
 * resolves the mapped accessor and packet members once and keeps only method handles on the
 * animation hot path. Failure is optional: 1.20.1 and unsupported mappings retain their per-tick
 * teleport fallback.</p>
 */
public final class ClientDisplayInterpolationBridge {
    private static final String DISPLAY_CLASS = "net.minecraft.world.entity.Display";
    private static final String ENTITY_DATA_ACCESSOR_CLASS =
        "net.minecraft.network.syncher.EntityDataAccessor";
    private static final String DATA_VALUE_CLASS =
        "net.minecraft.network.syncher.SynchedEntityData$DataValue";
    private static final String ENTITY_DATA_PACKET_CLASS =
        "net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket";
    private static final String PACKET_CLASS = "net.minecraft.network.protocol.Packet";
    private static final String CONNECTION_CLASS =
        "net.minecraft.server.network.ServerGamePacketListenerImpl";
    private static final String ACCESSOR_FIELD = "DATA_POS_ROT_INTERPOLATION_DURATION_ID";

    private final Logger logger;
    private volatile Access access;
    private volatile boolean unavailable;

    public ClientDisplayInterpolationBridge(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Sends one metadata packet and returns whether client interpolation is active. */
    public boolean configure(Player player, int entityId, int durationTicks) {
        if (player == null || !player.isOnline() || durationTicks <= 0 || this.unavailable) {
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
            Object value = resolved.createDataValue().invoke(
                resolved.positionRotationDurationAccessor(),
                Integer.valueOf(durationTicks)
            );
            Object packet = resolved.createPacket().invoke(entityId, List.of(value));
            resolved.sendPacket().invoke(connection, packet);
            return true;
        } catch (WrongMethodTypeException | ClassCastException | LinkageError throwable) {
            this.disable("Resolved client display interpolation access is incompatible", throwable);
            return false;
        } catch (Throwable throwable) {
            // A player can disconnect between the online check and packet send. Do not globally
            // disable a revision-compatible bridge for that transient connection failure.
            this.logger.log(
                Level.FINE,
                "Failed to configure client display interpolation for " + player.getName(),
                throwable
            );
            return false;
        }
    }

    private Access resolve(Player player) {
        synchronized (this) {
            if (this.access != null || this.unavailable) {
                return this.access;
            }
            try {
                SReflection.setRemapper(Remapper.createFromPaperJar());

                Class<?> displayClass = requireClass(DISPLAY_CLASS);
                Class<?> accessorClass = requireClass(ENTITY_DATA_ACCESSOR_CLASS);
                Class<?> dataValueClass = requireClass(DATA_VALUE_CLASS);
                Class<?> packetClass = requireClass(ENTITY_DATA_PACKET_CLASS);
                Class<?> packetInterface = requireClass(PACKET_CLASS);
                Class<?> expectedConnectionClass = requireClass(CONNECTION_CLASS);

                SparrowField durationField = findPositionRotationAccessor(displayClass, accessorClass);
                SparrowMethod createDataValue = findCreateDataValue(dataValueClass, accessorClass);
                SparrowConstructor<?> packetConstructor = SparrowClass.of(packetClass)
                    .getDeclaredSparrowConstructor(cTakeArguments(int.class, List.class), 0);
                SparrowMethod getHandleMethod = SparrowClass.of(player.getClass()).getSparrowMethod(
                    mAllOf(mNamedNoRemap("getHandle"), mTakeArguments(new Class<?>[0])),
                    0
                );
                if (durationField == null
                    || createDataValue == null
                    || packetConstructor == null
                    || getHandleMethod == null) {
                    throw new IllegalStateException("Display interpolation packet members were not found");
                }

                MethodHandle getHandle = requireHandle(getHandleMethod.unreflect(), "CraftPlayer#getHandle");
                Object liveServerPlayer = getHandle.invoke(player);
                if (liveServerPlayer == null) {
                    throw new IllegalStateException("CraftPlayer#getHandle returned null");
                }
                Field connectionField = ClientCameraBridge.findConnectionField(
                    liveServerPlayer.getClass(),
                    expectedConnectionClass
                );
                if (connectionField == null) {
                    throw new IllegalStateException("ServerPlayer connection accessor was not found");
                }
                java.lang.reflect.Method sendMethod = ClientCameraBridge.findPacketSendMethod(
                    connectionField.getType(),
                    packetInterface
                );
                if (sendMethod == null) {
                    throw new IllegalStateException("Server packet send method was not found");
                }

                Access resolved = new Access(
                    requireHandle(durationField.unreflectGetter(), "Display position/rotation duration accessor")
                        .invoke(),
                    getHandle,
                    requireHandle(SparrowField.of(connectionField).unreflectGetter(), "ServerPlayer#connection"),
                    requireHandle(createDataValue.unreflect(), "SynchedEntityData.DataValue#create"),
                    requireHandle(packetConstructor.unreflect(), "ClientboundSetEntityDataPacket constructor"),
                    requireHandle(
                        SparrowMethod.of(sendMethod).unreflect(),
                        "ServerGamePacketListenerImpl#send"
                    )
                );
                this.access = resolved;
                return resolved;
            } catch (Throwable throwable) {
                this.disable("Client display interpolation is unavailable on this server revision", throwable);
                return null;
            }
        }
    }

    static SparrowField findPositionRotationAccessor(Class<?> displayClass, Class<?> accessorClass) {
        SparrowClass<?> display = SparrowClass.of(displayClass);
        SparrowField mapped = display.getDeclaredSparrowField(
            fAllOf(fNamed(ACCESSOR_FIELD), fStatic(), fType(accessorClass)),
            0
        );
        if (mapped != null) {
            mapped.field().trySetAccessible();
            return mapped;
        }
        SparrowField direct = display.getDeclaredSparrowField(
            fAllOf(fNamedNoRemap(ACCESSOR_FIELD), fStatic(), fType(accessorClass)),
            0
        );
        if (direct != null) {
            direct.field().trySetAccessible();
            return direct;
        }

        // Never guess by declaration order. Minecraft 1.20.1 has no position/rotation duration
        // accessor and its third Display accessor stores a Vector3f translation; treating it as
        // an integer can fail only when the packet is encoded. Missing named access therefore
        // means this optional optimization is unsupported and the caller keeps per-tick movement.
        return null;
    }

    private static SparrowMethod findCreateDataValue(Class<?> dataValueClass, Class<?> accessorClass) {
        SparrowClass<?> dataValue = SparrowClass.of(dataValueClass);
        SparrowMethod mapped = dataValue.getDeclaredSparrowMethod(
            mAllOf(mNamed("create"), mStatic(), mTakeArguments(accessorClass, Object.class)),
            0
        );
        if (mapped != null) {
            return mapped;
        }
        return dataValue.getDeclaredSparrowMethod(
            mAllOf(mNamedNoRemap("create"), mStatic(), mTakeArguments(accessorClass, Object.class)),
            0
        );
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
        // This optimization is optional. Keep the existing per-tick camera fallback available and
        // log only at FINE instead of telling players the overhead feature itself is unsupported.
        this.logger.log(Level.FINE, message + "; retaining per-tick camera movement.", throwable);
    }

    private record Access(
        Object positionRotationDurationAccessor,
        MethodHandle getHandle,
        MethodHandle connection,
        MethodHandle createDataValue,
        MethodHandle createPacket,
        MethodHandle sendPacket
    ) {
    }
}
