package top.ellan.mahjong.spi;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/** Host-owned execution and transient-allocation budget for instrumented rule-pack code. */
public final class RuleExecutionBudget {
    private static final int CHECK_INTERVAL = 256;
    private static final int MAX_ARRAY_LENGTH = 1_000_000;
    private static final long MAX_ARRAY_ELEMENTS = 2_000_000L;
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(5);
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();
    private static final LimitExceeded LIMIT_EXCEEDED = new LimitExceeded();

    private RuleExecutionBudget() {}

    public static <T> T call(Duration timeout, Supplier<T> operation) {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(operation, "operation");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("rule execution timeout must be positive");
        }
        if (timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("rule execution timeout exceeds five minutes");
        }
        long now = System.nanoTime();
        long timeoutNanos = timeout.toNanos();
        long requestedDeadline = now + timeoutNanos;
        State previous = CURRENT.get();
        State current = new State(
                previous == null
                        ? requestedDeadline
                        : earlier(now, previous.deadlineNanos, requestedDeadline));
        CURRENT.set(current);
        try {
            checkpointNow(current);
            return operation.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** Injected at method entries, branches and calls in rule-pack bytecode. */
    public static void checkpoint() {
        State state = CURRENT.get();
        if (state == null) {
            return;
        }
        if (--state.checksUntilClock > 0 && !Thread.currentThread().isInterrupted()) {
            return;
        }
        state.checksUntilClock = CHECK_INTERVAL;
        checkpointNow(state);
    }

    /** Injected before every one-dimensional array allocation. */
    public static void checkArrayLength(int length) {
        State state = CURRENT.get();
        if (state != null && length >= 0) {
            reserve(state, length);
        }
    }

    /** Injected before two-dimensional array allocation. */
    public static void checkMultiArray(int first, int second) {
        reserveMulti(first, second, 1, 1);
    }

    /** Injected before three-dimensional array allocation. */
    public static void checkMultiArray(int first, int second, int third) {
        reserveMulti(first, second, third, 1);
    }

    /** Injected before four-dimensional array allocation. */
    public static void checkMultiArray(int first, int second, int third, int fourth) {
        reserveMulti(first, second, third, fourth);
    }

    public static boolean exceeded(Throwable failure) {
        return failure instanceof LimitExceeded;
    }

    /** Injected at every rule-pack exception handler so a budget signal cannot be swallowed. */
    public static void rethrowIfExceeded(Throwable failure) {
        if (failure instanceof LimitExceeded) {
            throw LIMIT_EXCEEDED;
        }
    }

    private static void reserveMulti(int first, int second, int third, int fourth) {
        State state = CURRENT.get();
        if (state == null || first < 0 || second < 0 || third < 0 || fourth < 0) {
            return;
        }
        if (first > MAX_ARRAY_LENGTH
                || second > MAX_ARRAY_LENGTH
                || third > MAX_ARRAY_LENGTH
                || fourth > MAX_ARRAY_LENGTH) {
            throw LIMIT_EXCEEDED;
        }
        long elements = multiply(first, second);
        elements = multiply(elements, third);
        elements = multiply(elements, fourth);
        reserve(state, elements);
    }

    private static long multiply(long first, long second) {
        if (first == 0 || second == 0) {
            return 0;
        }
        if (first > MAX_ARRAY_ELEMENTS / second) {
            throw LIMIT_EXCEEDED;
        }
        return first * second;
    }

    private static void reserve(State state, long elements) {
        if (elements > MAX_ARRAY_LENGTH
                || state.arrayElements > MAX_ARRAY_ELEMENTS - elements) {
            throw LIMIT_EXCEEDED;
        }
        state.arrayElements += elements;
        checkpointNow(state);
    }

    private static void checkpointNow(State state) {
        if (Thread.currentThread().isInterrupted()
                || System.nanoTime() - state.deadlineNanos >= 0) {
            throw LIMIT_EXCEEDED;
        }
    }

    private static long earlier(long now, long first, long second) {
        return first - now <= second - now ? first : second;
    }

    private static final class State {
        private final long deadlineNanos;
        private int checksUntilClock = CHECK_INTERVAL;
        private long arrayElements;

        private State(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
        }
    }

    /** Stackless and private so rule packs cannot deliberately catch the budget signal by type. */
    private static final class LimitExceeded extends Error {
        private static final long serialVersionUID = 1L;

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}
