package top.ellan.mahjong.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
}
