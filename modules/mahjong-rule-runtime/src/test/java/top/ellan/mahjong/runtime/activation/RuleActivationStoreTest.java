package top.ellan.mahjong.runtime.activation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.runtime.catalog.OfficialRuleIds;
import top.ellan.mahjong.spi.RulePackRef;

class RuleActivationStoreTest {
    @TempDir Path temporaryDirectory;

    @Test
    void pluginReloadCannotActivateButAFullJvmRestartCan() throws Exception {
        Path stateFile = temporaryDirectory.resolve("activation-state.json");
        RulePackRef reference =
                new RulePackRef(OfficialRuleIds.RIICHI, "1.2.3", "a".repeat(64), 1);
        RuleActivationStore firstJvm = new RuleActivationStore(stateFile, () -> 100L);
        firstJvm.requestActivation(reference);

        RuleActivationState sameJvm = firstJvm.promoteForStartup();
        assertTrue(sameJvm.active().isEmpty());
        assertEquals(reference, sameJvm.pending().get(OfficialRuleIds.RIICHI));

        RuleActivationStore restartedJvm = new RuleActivationStore(stateFile, () -> 200L);
        RuleActivationState promoted = restartedJvm.promoteForStartup();
        assertEquals(reference, promoted.active().get(OfficialRuleIds.RIICHI));
        assertTrue(promoted.pending().isEmpty());
    }

    @Test
    void immediateActivationTakesEffectWithoutARestartAndRemembersWhatItReplaced() throws Exception {
        Path stateFile = temporaryDirectory.resolve("activation-state.json");
        RuleActivationStore store = new RuleActivationStore(stateFile, () -> 100L);
        RulePackRef first = new RulePackRef(OfficialRuleIds.RIICHI, "1.0.0", "a".repeat(64), 1);
        RulePackRef second = new RulePackRef(OfficialRuleIds.RIICHI, "2.0.0", "b".repeat(64), 1);

        assertEquals(first, store.activateNow(first).active().get(OfficialRuleIds.RIICHI));
        RuleActivationState swapped = store.activateNow(second);

        assertEquals(second, swapped.active().get(OfficialRuleIds.RIICHI));
        assertEquals(first, swapped.previous().get(OfficialRuleIds.RIICHI));
        // The selection must survive without another JVM, unlike the pending path.
        assertTrue(swapped.pending().isEmpty());
        assertEquals(
                second,
                new RuleActivationStore(stateFile, () -> 100L)
                        .promoteForStartup()
                        .active()
                        .get(OfficialRuleIds.RIICHI));
    }

    @Test
    void rollbackRestoresThePreviousVersion() throws Exception {
        Path stateFile = temporaryDirectory.resolve("activation-state.json");
        RuleActivationStore store = new RuleActivationStore(stateFile, () -> 100L);
        RulePackRef first = new RulePackRef(OfficialRuleIds.RIICHI, "1.0.0", "a".repeat(64), 1);
        RulePackRef second = new RulePackRef(OfficialRuleIds.RIICHI, "2.0.0", "b".repeat(64), 1);
        store.activateNow(first);
        store.activateNow(second);

        RuleActivationState rolledBack = store.rollback(OfficialRuleIds.RIICHI);

        assertEquals(first, rolledBack.active().get(OfficialRuleIds.RIICHI));
        assertEquals(second, rolledBack.previous().get(OfficialRuleIds.RIICHI));
    }

    @Test
    void deactivationStopsNewMatchesAndStaysRollbackable() throws Exception {
        Path stateFile = temporaryDirectory.resolve("activation-state.json");
        RuleActivationStore store = new RuleActivationStore(stateFile, () -> 100L);
        RulePackRef reference =
                new RulePackRef(OfficialRuleIds.RIICHI, "1.0.0", "a".repeat(64), 1);
        store.activateNow(reference);

        RuleActivationState deactivated = store.deactivate(OfficialRuleIds.RIICHI);
        assertTrue(deactivated.active().isEmpty());
        assertEquals(reference, deactivated.previous().get(OfficialRuleIds.RIICHI));

        assertEquals(
                reference,
                store.rollback(OfficialRuleIds.RIICHI).active().get(OfficialRuleIds.RIICHI));
    }

    @Test
    void readsALegacyStateFileWithoutThePreviousField() throws Exception {
        Path stateFile = temporaryDirectory.resolve("activation-state.json");
        java.nio.file.Files.writeString(
                stateFile,
                "{\"format\":1,\"active\":{\"riichi\":{\"version\":\"1.0.0\",\"sha256\":\""
                        + "a".repeat(64)
                        + "\",\"schema\":1}},\"pending\":{},\"pendingJvmStartMillis\":-1}");

        RuleActivationState state = new RuleActivationStore(stateFile, () -> 100L).read();

        assertEquals("1.0.0", state.active().get(OfficialRuleIds.RIICHI).version());
        assertTrue(state.previous().isEmpty());
    }
}
