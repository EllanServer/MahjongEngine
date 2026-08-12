package top.ellan.mahjong.runtime.loading;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.spi.RuleExecutionBudget;

class RulePackBytecodePolicyTest {
    @Test
    void rejectsSynchronizedRuleMethods() throws Exception {
        RulePackCapabilities.PolicyViolation failure = assertThrows(
                RulePackCapabilities.PolicyViolation.class,
                () -> RulePackBytecodePolicy.inspectClass(bytecode(SynchronizedRule.class)));

        assertTrue(failure.getMessage().contains("monitor synchronization"));
    }

    @Test
    void rejectsThreadCreationAndDirectBudgetControl() throws Exception {
        assertThrows(
                RulePackCapabilities.PolicyViolation.class,
                () -> RulePackBytecodePolicy.inspectClass(bytecode(ThreadRule.class)));
        RulePackCapabilities.PolicyViolation budgetFailure = assertThrows(
                RulePackCapabilities.PolicyViolation.class,
                () -> RulePackBytecodePolicy.inspectClass(bytecode(BudgetRule.class)));

        assertTrue(budgetFailure.getMessage().contains("host execution-budget capability"));
    }

    @Test
    void permitsTheNarrowConcurrentMapUsedByOfficialCaches() throws Exception {
        assertDoesNotThrow(
                () -> RulePackBytecodePolicy.inspectClass(bytecode(ConcurrentCacheRule.class)));
    }

    @Test
    void permitsArrayCloneWhenItsComponentTypeIsAllowed() throws Exception {
        assertDoesNotThrow(
                () -> RulePackBytecodePolicy.inspectClass(bytecode(ArrayCloneRule.class)));
    }

    @Test
    void rejectsLibrariesLeakedFromTheServerClasspath() throws Exception {
        RulePackCapabilities.PolicyViolation failure = assertThrows(
                RulePackCapabilities.PolicyViolation.class,
                () -> RulePackBytecodePolicy.inspectClass(bytecode(ExternalLibraryRule.class)));

        assertTrue(failure.getMessage().contains("forbidden capability"));
    }

    private static byte[] bytecode(Class<?> type) throws IOException {
        String resource = '/' + type.getName().replace('.', '/') + ".class";
        try (var input = type.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing test class resource " + resource);
            }
            return input.readAllBytes();
        }
    }

    static final class SynchronizedRule {
        synchronized int evaluate() {
            return 1;
        }
    }

    static final class ThreadRule {
        Thread create() {
            return new Thread();
        }
    }

    static final class BudgetRule {
        void resetHostBudget() {
            RuleExecutionBudget.checkpoint();
        }
    }

    static final class ConcurrentCacheRule {
        int evaluate() {
            return new ConcurrentHashMap<String, Integer>().size();
        }
    }

    static final class ArrayCloneRule {
        String[] copy(String[] values) {
            return values.clone();
        }
    }

    static final class ExternalLibraryRule {
        String descriptor() {
            return org.objectweb.asm.Type.getDescriptor(String.class);
        }
    }
}
