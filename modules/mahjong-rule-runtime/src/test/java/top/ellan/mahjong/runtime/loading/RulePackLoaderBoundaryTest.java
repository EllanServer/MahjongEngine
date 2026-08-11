package top.ellan.mahjong.runtime.loading;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.runtime.common.RulePackException;
import top.ellan.mahjong.runtime.registry.RulePackRegistryEntry;
import top.ellan.mahjong.runtime.security.Hashing;
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

    @Test
    void rejectsJarsThatShadeCoreClassesOutsideTheRulePackage() throws Exception {
        Path jar = jarWith("top/ellan/mahjong/domain/table/TableId.class");

        RulePackException failure = assertThrows(
                RulePackException.class, () -> new RulePackLoader("2.0.0").load(jar, entry(jar)));
        assertTrue(failure.getMessage().contains("must not shade core classes"));
    }

    @Test
    void rejectsJarsThatRegisterAJdbcDriver() throws Exception {
        Path jar = jarWith("META-INF/services/java.sql.Driver");

        RulePackException failure = assertThrows(
                RulePackException.class, () -> new RulePackLoader("2.0.0").load(jar, entry(jar)));
        assertTrue(failure.getMessage().contains("must not register a JDBC driver"));
    }

    @Test
    void rejectsPresentationResourcesMixedIntoTheRuleJar() throws Exception {
        Path jar = jarWith("assets/riichi/sounds/tile_draw.ogg");

        RulePackException failure = assertThrows(
                RulePackException.class, () -> new RulePackLoader("2.0.0").load(jar, entry(jar)));
        assertTrue(failure.getMessage().contains("must not contain resource-pack content"));
    }

    @Test
    void acceptsTheIntermediateCoreDirectoryEntryAShadedPackContains() throws Exception {
        // Shaded rule packs necessarily contain the parent directory entries of their own package.
        // Those carry no bytecode, so rejecting them would reject every real pack.
        Path jar = jarWith("top/ellan/mahjong/");

        RulePackException failure = assertThrows(
                RulePackException.class, () -> new RulePackLoader("2.0.0").load(jar, entry(jar)));
        assertTrue(
                failure.getMessage().contains("provider probe failed"),
                "directory entry must not be treated as a shaded class: " + failure.getMessage());
    }

    @Test
    void acceptsTheRulePacksOwnClassesUnderTheRulesPackage() throws Exception {
        Path jar = jarWith("top/ellan/mahjong/rules/riichi/Engine.class");

        // The declared provider class is absent, so loading must fail during the ServiceLoader
        // probe rather than during archive validation. Reaching the probe proves the rule pack's own
        // package survived the shading check.
        RulePackException failure = assertThrows(
                RulePackException.class, () -> new RulePackLoader("2.0.0").load(jar, entry(jar)));
        assertTrue(
                failure.getMessage().contains("provider probe failed"),
                "unexpected failure stage: " + failure.getMessage());
    }

    private Path jarWith(String extraEntry) throws Exception {
        Path jar = temporaryDirectory.resolve(
                "pack-" + Integer.toHexString(extraEntry.hashCode()) + ".jar");
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
            add(zip, extraEntry, new byte[] {1});
        }
        return jar;
    }

    private static RulePackRegistryEntry entry(Path jar) throws Exception {
        return new RulePackRegistryEntry(
                new RuleId("riichi"),
                "1.0.0",
                URI.create("https://example.invalid/rule.jar"),
                Hashing.sha256(jar),
                SpiVersion.CURRENT,
                ">=2.0.0",
                Files.size(jar));
    }

    private static void add(ZipOutputStream zip, String name, byte[] content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }
}
