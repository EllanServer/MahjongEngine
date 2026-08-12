package top.ellan.mahjong.runtime.loading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.runtime.registry.RulePackRegistryEntry;
import top.ellan.mahjong.runtime.security.Hashing;

class OfficialRulePackCompatibilityTest {
    @Test
    void currentRuntimeLoadsOfficialRulePackBuiltByGitHubActions() throws Exception {
        String configured = System.getenv("RULE_PACK_ARTIFACT");
        Assumptions.assumeTrue(configured != null && !configured.isBlank());
        Path artifact = Path.of(configured).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(artifact));

        RulePackManifest manifest;
        try (ZipFile jar = new ZipFile(artifact.toFile())) {
            manifest = RulePackManifest.read(jar);
        }
        String sha256 = Hashing.sha256(artifact);
        RulePackRegistryEntry expected = new RulePackRegistryEntry(
                manifest.ruleId(),
                manifest.version(),
                URI.create("https://ci.invalid/" + artifact.getFileName()),
                sha256,
                manifest.spiVersion(),
                manifest.requiredCoreVersion(),
                Files.size(artifact));

        try (LoadedRulePack loaded = new RulePackLoader("2.0.0").load(artifact, expected)) {
            assertEquals(manifest.ruleId(), loaded.reference().ruleId());
            assertEquals(manifest.version(), loaded.reference().version());
        }
    }
}
