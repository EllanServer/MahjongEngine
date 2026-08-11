package top.ellan.mahjong.runtime.loading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.ellan.mahjong.runtime.common.RulePackException;
import top.ellan.mahjong.spi.SpiVersion;

/**
 * Caching must remove repeated work for an unchanged file without ever weakening verification of a
 * changed one.
 */
class ArtifactInspectionsTest {
    @TempDir Path temporaryDirectory;

    @Test
    void anUnchangedArtifactIsInspectedOnlyOnce() throws Exception {
        Path jar = jar("riichi", "2.0.1");
        ArtifactInspections inspections = new ArtifactInspections();
        AtomicInteger validations = new AtomicInteger();

        var first = inspections.inspect(jar, (archive, manifest) -> validations.incrementAndGet());
        var second = inspections.inspect(jar, (archive, manifest) -> validations.incrementAndGet());

        assertEquals(1, validations.get(), "the archive must not be re-walked for an unchanged file");
        assertEquals(first.sha256(), second.sha256());
        assertEquals("2.0.1", second.manifest().version());
    }

    @Test
    void aReplacedArtifactIsRehashedInsteadOfTrusted() throws Exception {
        Path jar = jar("riichi", "2.0.1");
        FileTime originalTimestamp = Files.getLastModifiedTime(jar);
        long originalSize = Files.size(jar);
        ArtifactInspections inspections = new ArtifactInspections();
        String original = inspections.inspect(jar, (archive, manifest) -> {}).sha256();

        // Preserve every cheap metadata key that previously identified the cache entry. Only the
        // content digest can reliably distinguish the replacement.
        Files.delete(jar);
        Path replaced = jar("riichi", "2.0.2");
        Files.setLastModifiedTime(replaced, originalTimestamp);
        assertEquals(jar, replaced);
        assertEquals(originalSize, Files.size(replaced));
        assertEquals(originalTimestamp, Files.getLastModifiedTime(replaced));
        AtomicInteger validations = new AtomicInteger();
        var after = inspections.inspect(jar, (archive, manifest) -> validations.incrementAndGet());

        assertEquals(1, validations.get(), "a replaced artifact must be validated again");
        assertNotEquals(original, after.sha256());
        assertEquals("2.0.2", after.manifest().version());
    }

    @Test
    void aRejectedArtifactIsNeverCached() throws Exception {
        Path jar = jar("riichi", "2.0.1");
        FileTime stamp = Files.getLastModifiedTime(jar);
        ArtifactInspections inspections = new ArtifactInspections();

        // A rejected archive must never enter the cache, otherwise a later load would inherit the
        // verdict without re-running the checks.
        assertThrows(
                RulePackException.class,
                () -> inspections.inspect(
                        jar,
                        (archive, manifest) -> {
                            throw new RulePackException("rejected");
                        }));
        AtomicInteger validations = new AtomicInteger();
        inspections.inspect(jar, (archive, manifest) -> validations.incrementAndGet());

        assertEquals(1, validations.get(), "a rejected archive must not be cached");
        assertTrue(Files.getLastModifiedTime(jar).equals(stamp), "the test must not rewrite the jar");
    }

    private Path jar(String ruleId, String version) throws Exception {
        Path jar = temporaryDirectory.resolve(ruleId + "-rule-pack.jar");
        try (OutputStream output = Files.newOutputStream(jar);
                ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(RulePackManifest.PATH));
            zip.write(
                    ("id=" + ruleId + "\nversion=" + version + "\nspiVersion="
                                    + SpiVersion.CURRENT
                                    + "\nrequiredCoreVersion=>=2.0.0\nstateSchemaVersion=1\n"
                                    + "requiredResources=\n")
                            .getBytes(StandardCharsets.ISO_8859_1));
            zip.closeEntry();
        }
        return jar;
    }
}
