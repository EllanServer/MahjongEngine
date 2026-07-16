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

/** Measures the private priority queue used to apply a representative table render. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class RegionUpdateQueueBenchmark {
    private static final int PRIORITY_REACTION_PROMPT = 400;
    private static final int PRIORITY_HAND = 320;
    private static final int PRIORITY_TURN_STATE = 240;
    private static final int PRIORITY_BOARD = 160;
    private static final int PRIORITY_BACKGROUND = 80;
    private static final int WALL_TILE_REGIONS = 136;
    private static final int SEATS = 4;
    private static final int HAND_TILES_PER_SEAT = 11;
    private static final int DISCARDS_PER_SEAT = 18;
    private static final int MELD_TILES_PER_SEAT = 4;
    private static final int EXPECTED_REGION_UPDATES = 327;

    private TableRegionDisplayCoordinator coordinator;
    private Class<?> actionType;
    private Constructor<?> updateConstructor;
    private MethodHandle applyQueue;
    private MethodHandle deferredAccessor;
    private MethodHandle processedUpdatesAccessor;
    private List<Integer> priorityPlan;
    private List<Object> template;
    private List<Object> working;

    @Setup(Level.Trial)
    public void setUp() throws Throwable {
        this.coordinator = new TableRegionDisplayCoordinator(null, null);
        Class<?> coordinatorType = TableRegionDisplayCoordinator.class;
        Class<?> updateType = Class.forName(coordinatorType.getName() + "$QueuedRegionUpdate");
        Class<?> executionType = Class.forName(coordinatorType.getName() + "$QueueExecution");
        this.actionType = Class.forName(coordinatorType.getName() + "$RegionUpdateAction");

        this.updateConstructor = updateType.getDeclaredConstructor(int.class, long.class, this.actionType);
        this.updateConstructor.setAccessible(true);

        MethodHandles.Lookup coordinatorLookup = MethodHandles.privateLookupIn(coordinatorType, MethodHandles.lookup());
        this.applyQueue = coordinatorLookup.findVirtual(
            coordinatorType,
            "applyQueue",
            MethodType.methodType(executionType, List.class)
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

        this.priorityPlan = this.createPriorityPlan();
        this.verifyQueueContract();
        Object alwaysApply = this.action(() -> true);
        this.template = this.createUpdates(sequence -> alwaysApply);
        this.working = new ArrayList<>(this.template);
    }

    @Setup(Level.Invocation)
    public void restoreProductionOrder() {
        for (int index = 0; index < this.template.size(); index++) {
            this.working.set(index, this.template.get(index));
        }
    }

    @Benchmark
    public Object orderRepresentativeTableRegions() throws Throwable {
        return this.applyQueue.invoke(this.coordinator, this.working);
    }

    private List<Integer> createPriorityPlan() {
        List<Integer> priorities = new ArrayList<>(EXPECTED_REGION_UPDATES);
        priorities.add(PRIORITY_BOARD);
        this.repeat(priorities, PRIORITY_BACKGROUND, WALL_TILE_REGIONS);
        priorities.add(PRIORITY_BOARD);
        priorities.add(PRIORITY_REACTION_PROMPT);
        for (int seat = 0; seat < SEATS; seat++) {
            priorities.add(PRIORITY_BACKGROUND);
            priorities.add(PRIORITY_REACTION_PROMPT);
            priorities.add(PRIORITY_TURN_STATE);
            this.repeat(priorities, PRIORITY_HAND, HAND_TILES_PER_SEAT);
            this.repeat(priorities, PRIORITY_HAND, HAND_TILES_PER_SEAT);
            this.repeat(priorities, PRIORITY_TURN_STATE, DISCARDS_PER_SEAT);
            this.repeat(priorities, PRIORITY_TURN_STATE, MELD_TILES_PER_SEAT);
        }
        if (priorities.size() != EXPECTED_REGION_UPDATES) {
            throw new IllegalStateException(
                "Representative region count changed: expected=" + EXPECTED_REGION_UPDATES + ", actual=" + priorities.size()
            );
        }
        return List.copyOf(priorities);
    }

    private void repeat(List<Integer> priorities, int priority, int count) {
        for (int index = 0; index < count; index++) {
            priorities.add(priority);
        }
    }

    private List<Object> createUpdates(LongFunction<Object> actionFactory) throws ReflectiveOperationException {
        List<Object> updates = new ArrayList<>(this.priorityPlan.size());
        for (int index = 0; index < this.priorityPlan.size(); index++) {
            long sequence = index;
            updates.add(this.updateConstructor.newInstance(
                this.priorityPlan.get(index),
                sequence,
                actionFactory.apply(sequence)
            ));
        }
        return updates;
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
        List<Long> expectedOrder = new ArrayList<>(this.priorityPlan.size());
        for (int index = 0; index < this.priorityPlan.size(); index++) {
            expectedOrder.add((long) index);
        }
        expectedOrder.sort(
            Comparator.comparingInt((Long sequence) -> this.priorityPlan.get(sequence.intValue()))
                .reversed()
                .thenComparingLong(Long::longValue)
        );

        List<Long> observedOrder = new ArrayList<>(this.priorityPlan.size());
        List<Object> updates = this.createUpdates(sequence -> this.action(() -> {
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
        updates = this.createUpdates(sequence -> this.action(() -> {
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
