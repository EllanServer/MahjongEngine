package top.ellan.mahjong.craftengine.privateview;

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
import java.lang.reflect.Method;
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

/** Enables vanilla client interpolation for a Sparrow client-only display entity. */
final class ClientDisplayInterpolationPacketSender {
    private static final String DISPLAY = "net.minecraft.world.entity.Display";
    private static final String ENTITY_DATA_ACCESSOR =
            "net.minecraft.network.syncher.EntityDataAccessor";
    private static final String DATA_VALUE =
            "net.minecraft.network.syncher.SynchedEntityData$DataValue";
    private static final String ENTITY_DATA_PACKET =
            "net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket";
    private static final String PACKET = "net.minecraft.network.protocol.Packet";
    private static final String CONNECTION =
            "net.minecraft.server.network.ServerGamePacketListenerImpl";
    private static final String DURATION_ACCESSOR =
            "DATA_POS_ROT_INTERPOLATION_DURATION_ID";

    private final Logger logger;
    private volatile Access access;
    private volatile boolean unavailable;

    ClientDisplayInterpolationPacketSender(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    boolean configure(Player player, int entityId, int durationTicks) {
        if (player == null
                || !player.isOnline()
                || durationTicks <= 0
                || durationTicks > 59
                || unavailable) {
            return false;
        }
        Access resolved = access;
        if (resolved == null) {
            resolved = resolve(player);
        }
        if (resolved == null) {
            return false;
        }
        try {
            Object serverPlayer = resolved.playerHandle().invoke(player);
            Object connection = resolved.connection().invoke(serverPlayer);
            Object value = resolved.createDataValue().invoke(
                    resolved.positionRotationDurationAccessor(),
                    Integer.valueOf(durationTicks));
            Object packet = resolved.createPacket().invoke(entityId, List.of(value));
            resolved.send().invoke(connection, packet);
            return true;
        } catch (WrongMethodTypeException | ClassCastException | LinkageError incompatible) {
            disable("Client display interpolation linkage changed", incompatible);
            return false;
        } catch (Throwable failure) {
            // A disconnect during a send does not make the cached revision access invalid.
            logger.log(
                    Level.FINE,
                    "Could not configure client display interpolation for " + player.getName(),
                    failure);
            return false;
        }
    }

    private Access resolve(Player player) {
        synchronized (this) {
            if (access != null || unavailable) {
                return access;
            }
            try {
                SReflection.setRemapper(Remapper.createFromPaperJar());
                Class<?> displayClass = requireClass(DISPLAY);
                Class<?> accessorClass = requireClass(ENTITY_DATA_ACCESSOR);
                Class<?> dataValueClass = requireClass(DATA_VALUE);
                Class<?> packetClass = requireClass(ENTITY_DATA_PACKET);
                Class<?> packetInterface = requireClass(PACKET);
                Class<?> connectionClass = requireClass(CONNECTION);

                SparrowField duration = findPositionRotationAccessor(displayClass, accessorClass);
                SparrowMethod createDataValue = findCreateDataValue(dataValueClass, accessorClass);
                SparrowConstructor<?> createPacket = SparrowClass.of(packetClass)
                        .getDeclaredSparrowConstructor(
                                cTakeArguments(int.class, List.class), 0);
                SparrowMethod playerHandle = SparrowClass.of(player.getClass())
                        .getSparrowMethod(
                                mAllOf(
                                        mNamedNoRemap("getHandle"),
                                        mTakeArguments(new Class<?>[0])),
                                0);
                if (duration == null
                        || createDataValue == null
                        || createPacket == null
                        || playerHandle == null) {
                    throw new IllegalStateException(
                            "Display interpolation packet members were not found");
                }

                MethodHandle getHandle = require(playerHandle.unreflect(), "CraftPlayer#getHandle");
                Object liveServerPlayer = getHandle.invoke(player);
                if (liveServerPlayer == null) {
                    throw new IllegalStateException("CraftPlayer#getHandle returned null");
                }
                Field connection = ClientCameraPacketSender.findConnection(
                        liveServerPlayer.getClass(), connectionClass);
                Method send = ClientCameraPacketSender.findSender(
                        connection.getType(), packetInterface);
                Access resolved = new Access(
                        require(duration.unreflectGetter(), "display interpolation accessor")
                                .invoke(),
                        getHandle,
                        require(
                                SparrowField.of(connection).unreflectGetter(),
                                "player connection"),
                        require(createDataValue.unreflect(), "data value factory"),
                        require(createPacket.unreflect(), "entity data packet constructor"),
                        require(SparrowMethod.of(send).unreflect(), "packet sender"));
                access = resolved;
                return resolved;
            } catch (Throwable failure) {
                disable("Client display interpolation is unavailable on this revision", failure);
                return null;
            }
        }
    }

    static SparrowField findPositionRotationAccessor(
            Class<?> displayClass, Class<?> accessorClass) {
        SparrowClass<?> display = SparrowClass.of(displayClass);
        SparrowField mapped = display.getDeclaredSparrowField(
                fAllOf(fNamed(DURATION_ACCESSOR), fStatic(), fType(accessorClass)), 0);
        if (mapped != null) {
            mapped.field().trySetAccessible();
            return mapped;
        }
        SparrowField direct = display.getDeclaredSparrowField(
                fAllOf(fNamedNoRemap(DURATION_ACCESSOR), fStatic(), fType(accessorClass)), 0);
        if (direct != null) {
            direct.field().trySetAccessible();
        }
        // Never guess by declaration order: older revisions use that slot for another type.
        return direct;
    }

    private static SparrowMethod findCreateDataValue(
            Class<?> dataValueClass, Class<?> accessorClass) {
        SparrowClass<?> dataValue = SparrowClass.of(dataValueClass);
        SparrowMethod mapped = dataValue.getDeclaredSparrowMethod(
                mAllOf(
                        mNamed("create"),
                        mStatic(),
                        mTakeArguments(accessorClass, Object.class)),
                0);
        return mapped != null
                ? mapped
                : dataValue.getDeclaredSparrowMethod(
                        mAllOf(
                                mNamedNoRemap("create"),
                                mStatic(),
                                mTakeArguments(accessorClass, Object.class)),
                        0);
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
            logger.log(Level.FINE, message + "; retaining server keyframes", failure);
        }
    }

    private record Access(
            Object positionRotationDurationAccessor,
            MethodHandle playerHandle,
            MethodHandle connection,
            MethodHandle createDataValue,
            MethodHandle createPacket,
            MethodHandle send) {}
}
