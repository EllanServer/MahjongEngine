package top.ellan.mahjong.runtime;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackProvider;
import top.ellan.mahjong.spi.SpiVersion;

class RulePackLoaderBoundaryTest {
    @TempDir Path temporaryDirectory;

    @Test
    void rejectsFatJarsThatEmbedTheSharedSpi() throws Exception {
        Path jar = temporaryDirectory.resolve("bad-rule-pack.jar");
        try (OutputStream output = Files.newOutputStream(jar);
                ZipOutputStream zip = new ZipOutputStream(output)) {
            add(
                    zip,
                    RulePackManifest.PATH,
                    ("id=riichi\nversion=1.0.0\nspiVersion="
                                    + SpiVersion.CURRENT
                                    + "\nrequiredCoreVersion=>=2.0.0\nstateSchemaVersion=1\n"
                                    + "requiredResources=\n")
                            .getBytes(StandardCharsets.ISO_8859_1));
            add(
                    zip,
                    "META-INF/services/" + RulePackProvider.class.getName(),
                    "example.Provider\n".getBytes(StandardCharsets.UTF_8));
            add(zip, "top/ellan/mahjong/spi/RuleState.class", new byte[] {1});
        }
        String sha = Hashing.sha256(jar);
        RulePackRegistryEntry expected =
                new RulePackRegistryEntry(
                        new RuleId("riichi"),
                        "1.0.0",
                        URI.create("https://example.invalid/rule.jar"),
                        sha,
                        SpiVersion.CURRENT,
                        ">=2.0.0",
                        Files.size(jar));

        RulePackException failure =
                assertThrows(
                        RulePackException.class,
                        () -> new RulePackLoader("2.0.0").load(jar, expected));
        assertTrue(failure.getMessage().contains("exclude mahjong-rule-spi"));
    }

    private static void add(ZipOutputStream zip, String name, byte[] content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }
}
