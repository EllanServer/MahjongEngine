package top.ellan.mahjong.runtime.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.ellan.mahjong.runtime.activation.RuleActivationStore;
import top.ellan.mahjong.runtime.catalog.OfficialRuleIds;
import top.ellan.mahjong.runtime.common.RulePackException;
import top.ellan.mahjong.runtime.loading.LoadedRulePack;
import top.ellan.mahjong.runtime.loading.PinnedRulePackLoader;
import top.ellan.mahjong.runtime.loading.RulePackTestPacks;
import top.ellan.mahjong.runtime.storage.RulePackPaths;
import top.ellan.mahjong.spi.RulePackRef;

/**
 * Two-generation coexistence: a replaced version keeps serving its running matches while new
 * matches get the replacement, and only an unreferenced generation may be unloaded.
 */
class RulePackHotSwapTest {
    @TempDir Path temporaryDirectory;

    @Test
    void aReplacedVersionKeepsRunningMatchesWhileNewMatchesUseTheReplacement() throws Exception {
        RulePackRef oldVersion = reference("1.0.0", 'a');
        RulePackRef newVersion = reference("2.0.0", 'b');
        RecordingLoader loader = new RecordingLoader();
        RulePackRuntime runtime = runtime(loader, oldVersion);
        runtime.start();

        Object runningMatch = new Object();
        runtime.acquire(oldVersion, runningMatch);

        // Swapping while a match holds the old generation must report it as still running.
        assertEquals(oldVersion, runtime.promote(newVersion).orElseThrow());
        assertEquals(newVersion, runtime.activeReference(OfficialRuleIds.RIICHI).orElseThrow());
        assertEquals(
                oldVersion, runtime.status().supersededInUse().get(OfficialRuleIds.RIICHI));

        // The recovered/pinned path must still resolve the superseded generation.
        assertSame(
                loader.providerFor(oldVersion), runtime.providerForPinnedMatch(oldVersion));
        assertSame(
                loader.providerFor(newVersion),
                runtime.providerForNewMatch(OfficialRuleIds.RIICHI).orElseThrow());

        assertThrows(RulePackException.class, () -> runtime.unload(oldVersion));

        runtime.release(oldVersion, runningMatch);
        assertTrue(runtime.status().supersededInUse().isEmpty());
        assertFalse(runtime.loadedReferences().contains(oldVersion));
    }

    @Test
    void promotingOverAnIdleGenerationUnloadsItImmediately() throws Exception {
        RulePackRef oldVersion = reference("1.0.0", 'a');
        RulePackRef newVersion = reference("2.0.0", 'b');
        RulePackRuntime runtime = runtime(new RecordingLoader(), oldVersion);
        runtime.start();

        assertTrue(runtime.promote(newVersion).isEmpty());
        assertFalse(runtime.loadedReferences().contains(oldVersion));
        assertTrue(runtime.loadedReferences().contains(newVersion));
    }

    @Test
    void theActiveGenerationCannotBeUnloadedBeforeDeactivation() throws Exception {
        RulePackRef active = reference("1.0.0", 'a');
        RulePackRuntime runtime = runtime(new RecordingLoader(), active);
        runtime.start();

        RulePackException failure =
                assertThrows(RulePackException.class, () -> runtime.unload(active));
        assertTrue(failure.getMessage().contains("Deactivate the rule before unloading"));

        assertTrue(runtime.deactivate(OfficialRuleIds.RIICHI).isEmpty());
        assertTrue(runtime.activeReference(OfficialRuleIds.RIICHI).isEmpty());
        assertFalse(runtime.loadedReferences().contains(active));
    }

    @Test
    void deactivationLeavesALeasedGenerationRunning() throws Exception {
        RulePackRef active = reference("1.0.0", 'a');
        RulePackRuntime runtime = runtime(new RecordingLoader(), active);
        runtime.start();
        Object runningMatch = new Object();
        runtime.acquire(active, runningMatch);

        assertEquals(active, runtime.deactivate(OfficialRuleIds.RIICHI).orElseThrow());
        assertTrue(runtime.providerForNewMatch(OfficialRuleIds.RIICHI).isEmpty());
        assertTrue(runtime.loadedReferences().contains(active));

        runtime.release(active, runningMatch);
        assertFalse(runtime.loadedReferences().contains(active));
    }

    private RulePackRuntime runtime(RecordingLoader loader, RulePackRef initiallyActive)
            throws Exception {
        RulePackPaths paths = new RulePackPaths(temporaryDirectory.resolve("rules"));
        paths.createLayout();
        RuleActivationStore activation = new RuleActivationStore(paths.activationState());
        activation.activateNow(initiallyActive);
        return new RulePackRuntime(paths, loader, activation);
    }

    private static RulePackRef reference(String version, char hashFill) {
        return new RulePackRef(
                OfficialRuleIds.RIICHI, version, Character.toString(hashFill).repeat(64), 1);
    }

    /** Loads one distinct inert pack per coordinate over a minimal on-disk JAR. */
    private static final class RecordingLoader implements PinnedRulePackLoader {
        private final java.util.Map<RulePackRef, top.ellan.mahjong.spi.RulePackProvider> providers =
                new java.util.HashMap<>();

        top.ellan.mahjong.spi.RulePackProvider providerFor(RulePackRef reference) {
            return providers.get(reference);
        }

        @Override
        public LoadedRulePack loadPinned(Path artifact, RulePackRef pinned)
                throws RulePackException {
            try {
                Files.createDirectories(artifact.getParent());
                if (!Files.exists(artifact)) {
                    try (OutputStream output = Files.newOutputStream(artifact);
                            ZipOutputStream zip = new ZipOutputStream(output)) {
                        zip.putNextEntry(new ZipEntry("marker"));
                        zip.write(pinned.version().getBytes(StandardCharsets.UTF_8));
                        zip.closeEntry();
                    }
                }
                LoadedRulePack pack = RulePackTestPacks.create(pinned, artifact);
                providers.put(pinned, pack.provider());
                return pack;
            } catch (Exception failure) {
                throw new RulePackException("stub loader failed", failure);
            }
        }
    }
}
