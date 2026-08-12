package top.ellan.mahjong.spi;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RuleExecutionBudgetTest {
    @Test
    void cooperativeLoopCannotRunPastItsDeadline() {
        Error failure = assertThrows(Error.class, () -> RuleExecutionBudget.call(
                Duration.ofMillis(10),
                () -> {
                    while (true) {
                        RuleExecutionBudget.checkpoint();
                    }
                }));

        assertTrue(RuleExecutionBudget.exceeded(failure));
    }

    @Test
    void rejectsOversizedTransientArraysBeforeAllocation() {
        Error failure = assertThrows(Error.class, () -> RuleExecutionBudget.call(
                Duration.ofSeconds(1),
                () -> {
                    RuleExecutionBudget.checkArrayLength(1_000_001);
                    return null;
                }));

        assertTrue(RuleExecutionBudget.exceeded(failure));
    }

    @Test
    void rejectsOversizedZeroWidthMultiArrays() {
        Error failure = assertThrows(Error.class, () -> RuleExecutionBudget.call(
                Duration.ofSeconds(1),
                () -> {
                    RuleExecutionBudget.checkMultiArray(1_000_001, 0);
                    return null;
                }));

        assertTrue(RuleExecutionBudget.exceeded(failure));
    }

    @Test
    void budgetSignalCannotBeSuppressedThroughThePublicGuard() {
        Error failure = assertThrows(Error.class, () -> RuleExecutionBudget.call(
                Duration.ofSeconds(1),
                () -> {
                    try {
                        RuleExecutionBudget.checkArrayLength(1_000_001);
                        return null;
                    } catch (Throwable caught) {
                        RuleExecutionBudget.rethrowIfExceeded(caught);
                        return null;
                    }
                }));

        assertTrue(RuleExecutionBudget.exceeded(failure));
    }
}
