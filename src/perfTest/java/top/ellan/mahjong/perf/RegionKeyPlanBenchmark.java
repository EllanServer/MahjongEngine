package top.ellan.mahjong.perf;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.table.render.TableRegionDisplayCoordinator;

/** Measures every dynamic region key requested while planning one complete table refresh. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class RegionKeyPlanBenchmark {
    private static final int WALL_TILE_REGIONS = 136;
    private static final int HAND_TILE_REGIONS = 14;
    private static final int DISCARD_TILE_REGIONS = 24;
    private static final int MELD_TILE_REGIONS = 20;
    private static final int EXPECTED_KEY_COUNT = 452;
    private static final long CHECKSUM_SEED = 0x243F6A8885A308D3L;
    private static final SeatWind[] WINDS = SeatWind.values();

    private TableRegionDisplayCoordinator coordinator;
    private MethodHandle seatRegionKey;
    private MethodHandle handPrivateRegionKey;
    private MethodHandle handPublicRegionKey;
    private MethodHandle discardRegionKey;
    private MethodHandle meldRegionKey;
    private MethodHandle wallRegionKey;
    private List<String> expectedKeys;
    private long expectedChecksum;

    @Setup(Level.Trial)
    public void setUp() throws Throwable {
        this.coordinator = new TableRegionDisplayCoordinator(null, null);
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
            TableRegionDisplayCoordinator.class,
            MethodHandles.lookup()
        );
        this.seatRegionKey = lookup.findVirtual(
            TableRegionDisplayCoordinator.class,
            "seatRegionKey",
            MethodType.methodType(String.class, String.class, SeatWind.class)
        );
        this.handPrivateRegionKey = indexedKeyHandle(lookup, "handPrivateRegionKey");
        this.handPublicRegionKey = indexedKeyHandle(lookup, "handPublicRegionKey");
        this.discardRegionKey = indexedKeyHandle(lookup, "discardRegionKey");
        this.meldRegionKey = indexedKeyHandle(lookup, "meldRegionKey");
        this.wallRegionKey = lookup.findVirtual(
            TableRegionDisplayCoordinator.class,
            "wallRegionKey",
            MethodType.methodType(String.class, int.class)
        );
        this.expectedKeys = referenceKeys();
        this.expectedChecksum = checksum(this.expectedKeys);
        this.verifyRegionKeyContract();
    }

    @Setup(Level.Iteration)
    public void verifyRegionKeyContract() throws Throwable {
        List<String> actual = this.actualKeys();
        if (!this.expectedKeys.equals(actual)) {
            throw new IllegalStateException("Region key plan changed");
        }
        Set<String> unique = new HashSet<>(actual);
        if (actual.size() != EXPECTED_KEY_COUNT || unique.size() != EXPECTED_KEY_COUNT) {
            throw new IllegalStateException(
                "Expected " + EXPECTED_KEY_COUNT + " distinct region keys, got " + actual.size()
                    + " entries and " + unique.size() + " distinct keys"
            );
        }
        long actualChecksum = this.buildRepresentativeRegionKeyPlan();
        if (actualChecksum != this.expectedChecksum) {
            throw new IllegalStateException(
                "Region key checksum changed: expected=" + this.expectedChecksum + ", actual=" + actualChecksum
            );
        }
    }

    @Benchmark
    public long buildRepresentativeRegionKeyPlan() throws Throwable {
        long checksum = CHECKSUM_SEED;
        for (int index = 0; index < WALL_TILE_REGIONS; index++) {
            checksum = mix(checksum, (String) this.wallRegionKey.invokeExact(this.coordinator, index));
        }
        for (SeatWind wind : WINDS) {
            checksum = mix(checksum, this.invokeSeatRegionKey("visual", wind));
            checksum = mix(checksum, this.invokeSeatRegionKey("labels", wind));
            checksum = mix(checksum, this.invokeSeatRegionKey("sticks", wind));
            checksum = mix(checksum, this.invokeSeatRegionKey("hand-public", wind));
            for (int index = 0; index < HAND_TILE_REGIONS; index++) {
                checksum = mix(checksum, this.invokeIndexedKey(this.handPublicRegionKey, wind, index));
            }
            checksum = mix(checksum, this.invokeSeatRegionKey("hand-private", wind));
            for (int index = 0; index < HAND_TILE_REGIONS; index++) {
                checksum = mix(checksum, this.invokeIndexedKey(this.handPrivateRegionKey, wind, index));
            }
            checksum = mix(checksum, this.invokeSeatRegionKey("discards", wind));
            for (int index = 0; index < DISCARD_TILE_REGIONS; index++) {
                checksum = mix(checksum, this.invokeIndexedKey(this.discardRegionKey, wind, index));
            }
            checksum = mix(checksum, this.invokeSeatRegionKey("melds", wind));
            for (int index = 0; index < MELD_TILE_REGIONS; index++) {
                checksum = mix(checksum, this.invokeIndexedKey(this.meldRegionKey, wind, index));
            }
        }
        return checksum;
    }

    private List<String> actualKeys() throws Throwable {
        List<String> keys = new ArrayList<>(EXPECTED_KEY_COUNT);
        for (int index = 0; index < WALL_TILE_REGIONS; index++) {
            keys.add((String) this.wallRegionKey.invokeExact(this.coordinator, index));
        }
        for (SeatWind wind : WINDS) {
            keys.add(this.invokeSeatRegionKey("visual", wind));
            keys.add(this.invokeSeatRegionKey("labels", wind));
            keys.add(this.invokeSeatRegionKey("sticks", wind));
            keys.add(this.invokeSeatRegionKey("hand-public", wind));
            appendIndexedKeys(keys, this.handPublicRegionKey, wind, HAND_TILE_REGIONS);
            keys.add(this.invokeSeatRegionKey("hand-private", wind));
            appendIndexedKeys(keys, this.handPrivateRegionKey, wind, HAND_TILE_REGIONS);
            keys.add(this.invokeSeatRegionKey("discards", wind));
            appendIndexedKeys(keys, this.discardRegionKey, wind, DISCARD_TILE_REGIONS);
            keys.add(this.invokeSeatRegionKey("melds", wind));
            appendIndexedKeys(keys, this.meldRegionKey, wind, MELD_TILE_REGIONS);
        }
        return keys;
    }

    private String invokeSeatRegionKey(String region, SeatWind wind) throws Throwable {
        return (String) this.seatRegionKey.invokeExact(this.coordinator, region, wind);
    }

    private String invokeIndexedKey(MethodHandle handle, SeatWind wind, int index) throws Throwable {
        return (String) handle.invokeExact(this.coordinator, wind, index);
    }

    private void appendIndexedKeys(List<String> keys, MethodHandle handle, SeatWind wind, int count)
        throws Throwable {
        for (int index = 0; index < count; index++) {
            keys.add(this.invokeIndexedKey(handle, wind, index));
        }
    }

    private static MethodHandle indexedKeyHandle(MethodHandles.Lookup lookup, String methodName)
        throws NoSuchMethodException, IllegalAccessException {
        return lookup.findVirtual(
            TableRegionDisplayCoordinator.class,
            methodName,
            MethodType.methodType(String.class, SeatWind.class, int.class)
        );
    }

    private static List<String> referenceKeys() {
        List<String> keys = new ArrayList<>(EXPECTED_KEY_COUNT);
        for (int index = 0; index < WALL_TILE_REGIONS; index++) {
            keys.add("wall-" + index);
        }
        for (SeatWind wind : WINDS) {
            String suffix = ":" + wind.name();
            keys.add("visual" + suffix);
            keys.add("labels" + suffix);
            keys.add("sticks" + suffix);
            keys.add("hand-public" + suffix);
            appendReferenceIndexedKeys(keys, "hand-public-", suffix, HAND_TILE_REGIONS);
            keys.add("hand-private" + suffix);
            appendReferenceIndexedKeys(keys, "hand-private-", suffix, HAND_TILE_REGIONS);
            keys.add("discards" + suffix);
            appendReferenceIndexedKeys(keys, "discards-", suffix, DISCARD_TILE_REGIONS);
            keys.add("melds" + suffix);
            appendReferenceIndexedKeys(keys, "melds-", suffix, MELD_TILE_REGIONS);
        }
        return List.copyOf(keys);
    }

    private static void appendReferenceIndexedKeys(List<String> keys, String prefix, String suffix, int count) {
        for (int index = 0; index < count; index++) {
            keys.add(prefix + index + suffix);
        }
    }

    private static long checksum(List<String> keys) {
        long checksum = CHECKSUM_SEED;
        for (String key : keys) {
            checksum = mix(checksum, key);
        }
        return checksum;
    }

    private static long mix(long checksum, String key) {
        return Long.rotateLeft(checksum, 7) ^ key.hashCode();
    }
}
