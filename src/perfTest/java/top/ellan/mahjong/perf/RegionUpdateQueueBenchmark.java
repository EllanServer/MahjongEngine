package top.ellan.mahjong.perf;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongFunction;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import top.ellan.mahjong.table.render.TableRegionDisplayCoordinator;

/** Measures the private bucket queue used to apply a representative table render. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class RegionUpdateQueueBenchmark {
    private static final int BUCKET_REACTION_PROMPT = 0;
    private static final int BUCKET_HAND = 1;
    private static final int BUCKET_TURN_STATE = 2;
    private static final int BUCKET_BOARD = 3;
    private static final int BUCKET_BACKGROUND = 4;
    private static final int WALL_TILE_REGIONS = 136;
    private static final int SEATS = 4;
    private static final int HAND_TILES_PER_SEAT = 11;
    private static final int DISCARDS_PER_SEAT = 18;
    private static final int MELD_TILES_PER_SEAT = 4;
    private static final int EXPECTED_REGION_UPDATES = 327;

    private TableRegionDisplayCoordinator coordinator;
    private Class<?> actionType;
    private Constructor<?> queueConstructor;
    private MethodHandle addToQueue;
    private MethodHandle applyQueue;
    private MethodHandle deferredAccessor;
    private MethodHandle processedUpdatesAccessor;
    private List<Integer> bucketPlan;
    private Object queue;

    @Setup(Level.Trial)
    public void setUp() throws Throwable {
        this.coordinator = new TableRegionDisplayCoordinator(null, null);
        Class<?> coordinatorType = TableRegionDisplayCoordinator.class;
        Class<?> queueType = Class.forName(coordinatorType.getName() + "$RegionUpdateQueue");
        Class<?> executionType = Class.forName(coordinatorType.getName() + "$QueueExecution");
        this.actionType = Class.forName(coordinatorType.getName() + "$RegionUpdateAction");

        this.queueConstructor = queueType.getDeclaredConstructor();
        this.queueConstructor.setAccessible(true);

        MethodHandles.Lookup coordinatorLookup = MethodHandles.privateLookupIn(coordinatorType, MethodHandles.lookup());
        this.applyQueue = coordinatorLookup.findVirtual(
            coordinatorType,
            "applyQueue",
            MethodType.methodType(executionType, queueType)
        );
        MethodHandles.Lookup queueLookup = MethodHandles.privateLookupIn(queueType, MethodHandles.lookup());
        this.addToQueue = queueLookup.findVirtual(
            queueType,
            "add",
            MethodType.methodType(void.class, int.class, this.actionType)
        );
        MethodHandles.Lookup executionLookup = MethodHandles.privateLookupIn(executionType, MethodHandles.lookup());
        this.deferredAccessor = executionLookup.findVirtual(
            executionType,
            "deferred",
            MethodType.methodType(boolean.class)
        );
        this.processedUpdatesAccessor = executionLookup.findVirtual(
            executionType,
            "processedUpdates",
            MethodType.methodType(int.class)
        );

        this.bucketPlan = this.createBucketPlan();
        this.verifyQueueContract();
        Object alwaysApply = this.action(() -> true);
        this.queue = this.createQueue(sequence -> alwaysApply);
    }

    @Benchmark
    public Object orderRepresentativeTableRegions() throws Throwable {
        return this.applyQueue.invoke(this.coordinator, this.queue);
    }

    private List<Integer> createBucketPlan() {
        List<Integer> buckets = new ArrayList<>(EXPECTED_REGION_UPDATES);
        buckets.add(BUCKET_BOARD);
        this.repeat(buckets, BUCKET_BACKGROUND, WALL_TILE_REGIONS);
        buckets.add(BUCKET_BOARD);
        buckets.add(BUCKET_REACTION_PROMPT);
        for (int seat = 0; seat < SEATS; seat++) {
            buckets.add(BUCKET_BACKGROUND);
            buckets.add(BUCKET_REACTION_PROMPT);
            buckets.add(BUCKET_TURN_STATE);
            this.repeat(buckets, BUCKET_HAND, HAND_TILES_PER_SEAT);
            this.repeat(buckets, BUCKET_HAND, HAND_TILES_PER_SEAT);
            this.repeat(buckets, BUCKET_TURN_STATE, DISCARDS_PER_SEAT);
            this.repeat(buckets, BUCKET_TURN_STATE, MELD_TILES_PER_SEAT);
        }
        if (buckets.size() != EXPECTED_REGION_UPDATES) {
            throw new IllegalStateException(
                "Representative region count changed: expected=" + EXPECTED_REGION_UPDATES + ", actual=" + buckets.size()
            );
        }
        return List.copyOf(buckets);
    }

    private void repeat(List<Integer> buckets, int bucket, int count) {
        for (int index = 0; index < count; index++) {
            buckets.add(bucket);
        }
    }

    private Object createQueue(LongFunction<Object> actionFactory) throws Throwable {
        Object created = this.queueConstructor.newInstance();
        for (int index = 0; index < this.bucketPlan.size(); index++) {
            long sequence = index;
            this.addToQueue.invoke(created, this.bucketPlan.get(index), actionFactory.apply(sequence));
        }
        return created;
    }

    private Object action(BooleanSupplier result) {
        return Proxy.newProxyInstance(
            this.actionType.getClassLoader(),
            new Class<?>[] {this.actionType},
            (proxy, method, arguments) -> {
                if (method.getName().equals("apply") && method.getParameterCount() == 0) {
                    return result.getAsBoolean();
                }
                throw new UnsupportedOperationException(method.toString());
            }
        );
    }

    private void verifyQueueContract() throws Throwable {
        List<Long> expectedOrder = new ArrayList<>(this.bucketPlan.size());
        for (int index = 0; index < this.bucketPlan.size(); index++) {
            expectedOrder.add((long) index);
        }
        expectedOrder.sort(
            Comparator.comparingInt((Long sequence) -> this.bucketPlan.get(sequence.intValue()))
                .thenComparingLong(Long::longValue)
        );

        List<Long> observedOrder = new ArrayList<>(this.bucketPlan.size());
        Object updates = this.createQueue(sequence -> this.action(() -> {
            observedOrder.add(sequence);
            return true;
        }));
        Object execution = this.applyQueue.invoke(this.coordinator, updates);
        this.assertExecution(execution, false, EXPECTED_REGION_UPDATES);
        if (!expectedOrder.equals(observedOrder)) {
            throw new IllegalStateException("Region update priority/sequence order changed");
        }

        int failureIndex = EXPECTED_REGION_UPDATES / 2;
        long failureSequence = expectedOrder.get(failureIndex);
        observedOrder.clear();
        updates = this.createQueue(sequence -> this.action(() -> {
            observedOrder.add(sequence);
            return sequence != failureSequence;
        }));
        execution = this.applyQueue.invoke(this.coordinator, updates);
        this.assertExecution(execution, true, failureIndex);
        if (!expectedOrder.subList(0, failureIndex + 1).equals(observedOrder)) {
            throw new IllegalStateException("Deferred region update boundary changed");
        }
    }

    private void assertExecution(Object execution, boolean expectedDeferred, int expectedProcessed) throws Throwable {
        boolean deferred = (boolean) this.deferredAccessor.invoke(execution);
        int processed = (int) this.processedUpdatesAccessor.invoke(execution);
        if (deferred != expectedDeferred || processed != expectedProcessed) {
            throw new IllegalStateException(
                "Queue result changed: deferred=" + deferred + ", processed=" + processed
            );
        }
    }
}
