package top.ellan.mahjong.runtime.loading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.spi.RuleExecutionBudget;

class RulePackClassInstrumenterTest {
    @Test
    void injectedCheckpointsStopLoopsEvenWhenRuleCatchesThrowable() throws Exception {
        Method method = instrumentedMethod(CatchingLoop.class, "run");

        Error failure = assertThrows(Error.class, () -> RuleExecutionBudget.call(
                Duration.ofMillis(10), () -> invokeInt(method)));

        assertTrue(RuleExecutionBudget.exceeded(failure));
    }

    @Test
    void injectedAllocationCheckRunsBeforeLargeArrayAllocation() throws Exception {
        Method method = instrumentedMethod(LargeArray.class, "run");

        Error failure = assertThrows(Error.class, () -> RuleExecutionBudget.call(
                Duration.ofSeconds(1), () -> invokeInt(method)));

        assertTrue(RuleExecutionBudget.exceeded(failure));
    }

    @Test
    void instrumentationPreservesFramesDuringConstructorArguments() throws Exception {
        Method method = instrumentedMethod(NestedConstruction.class, "run");

        assertEquals(2, invokeInt(method));
    }

    private static Method instrumentedMethod(Class<?> source, String name) throws Exception {
        String resource = '/' + source.getName().replace('.', '/') + ".class";
        byte[] original;
        try (var input = source.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing test class resource " + resource);
            }
            original = input.readAllBytes();
        }
        byte[] instrumented = RulePackClassInstrumenter.instrument(original);
        Class<?> type = new BytecodeLoader(source.getClassLoader()).define(instrumented);
        return type.getDeclaredMethod(name);
    }

    private static Integer invokeInt(Method method) {
        try {
            return (Integer) method.invoke(null);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Error error) {
                throw error;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class BytecodeLoader extends ClassLoader {
        private BytecodeLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(byte[] bytecode) {
            return defineClass(null, bytecode, 0, bytecode.length);
        }
    }

    public static final class CatchingLoop {
        private CatchingLoop() {}

        public static int run() {
            try {
                int value = 0;
                while (true) {
                    value++;
                }
            } catch (Throwable ignored) {
                return 7;
            }
        }
    }

    public static final class LargeArray {
        private LargeArray() {}

        public static int run() {
            return new int[1_000_001].length;
        }
    }

    public static final class NestedConstruction {
        private NestedConstruction() {}

        public static int run() {
            return new StringBuilder(String.valueOf(1)).append(Integer.toString(2)).length();
        }
    }
}
