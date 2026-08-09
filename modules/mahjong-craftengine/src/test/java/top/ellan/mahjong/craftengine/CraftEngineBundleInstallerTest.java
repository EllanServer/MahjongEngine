package top.ellan.mahjong.craftengine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CraftEngineBundleInstallerTest {
    @Test
    void unchangedDetectionRequiresTheExactHashedBundle(@TempDir Path directory)
            throws Exception {
        Path configuration = directory.resolve("configuration/mahjong.yml");
        Files.createDirectories(configuration.getParent());
        Files.writeString(configuration, "items: {}\n", StandardCharsets.UTF_8);
        Files.writeString(
                directory.resolve("_bundle_manifest.sha256"),
                "manifest\n",
                StandardCharsets.UTF_8);
        Map<String, String> hashes =
                Map.of("configuration/mahjong.yml", sha256(configuration));

        assertTrue(CraftEngineBundleInstaller.matchesInstalledBundle(directory, hashes));

        Path unexpected = directory.resolve("unexpected.yml");
        Files.writeString(unexpected, "extra\n", StandardCharsets.UTF_8);
        assertFalse(CraftEngineBundleInstaller.matchesInstalledBundle(directory, hashes));

        Files.delete(unexpected);
        Files.writeString(configuration, "items: changed\n", StandardCharsets.UTF_8);
        assertFalse(CraftEngineBundleInstaller.matchesInstalledBundle(directory, hashes));
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(Files.readAllBytes(path)));
    }
}
