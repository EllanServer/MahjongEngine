package top.ellan.mahjong.runtime.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.ellan.mahjong.runtime.registry.RulePackRegistryEntry;
import top.ellan.mahjong.runtime.registry.RuleResourcePackArtifact;
import top.ellan.mahjong.runtime.security.Hashing;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePresentationCueType;
import top.ellan.mahjong.spi.SpiVersion;

class RuleResourcePackInspectorTest {
    @Test
    void verifiesResourceOnlyBundleAndLoadsSoundCatalog(@TempDir Path directory)
            throws Exception {
        Path archive = directory.resolve("riichi-resource-pack.zip");
        writeResourcePack(archive);
        RulePackRegistryEntry entry =
                new RulePackRegistryEntry(
                        new RuleId("riichi"),
                        "2.0.2",
                        URI.create("https://example.invalid/riichi.jar"),
                        "0".repeat(64),
                        SpiVersion.CURRENT,
                        ">=2.0.0",
                        1,
                        Optional.of(
                                new RuleResourcePackArtifact(
                                        URI.create("https://example.invalid/riichi-resources.zip"),
                                        Hashing.sha256(archive),
                                        Files.size(archive))));

        InspectedRuleResourcePack inspected =
                new RuleResourcePackInspector().inspect(archive, entry);

        assertEquals("riichi", inspected.ruleId().value());
        assertEquals(
                "mahjong_riichi_v2_0_2:tile_draw",
                inspected.sounds().cues().get(RulePresentationCueType.TILE_DRAW).key());
        assertEquals(
                "mahjong_riichi_v2_0_2:opening_dice",
                inspected.sounds().openingDice().key());
    }

    private static void writeResourcePack(Path archive) throws Exception {
        LinkedHashMap<String, byte[]> craft = new LinkedHashMap<>();
        craft.put(
                "pack.yml",
                "namespace: mahjong_riichi_v2_0_2\n".getBytes(StandardCharsets.UTF_8));
        craft.put(
                "resourcepack/assets/mahjong_riichi_v2_0_2/sounds.json",
                ("{\"tile_draw\":{\"sounds\":[\"mahjong_riichi_v2_0_2:tile_draw\"]},"
                                + "\"opening_dice\":{\"sounds\":[\"mahjong_riichi_v2_0_2:tile_draw\"]},"
                                + "\"opening_wall_break\":{\"sounds\":[\"mahjong_riichi_v2_0_2:tile_draw\"]}}")
                        .getBytes(StandardCharsets.UTF_8));
        craft.put(
                "resourcepack/assets/mahjong_riichi_v2_0_2/sounds/tile_draw.ogg",
                new byte[] {0x4f, 0x67, 0x67, 0x53});
        StringBuilder manifest = new StringBuilder();
        for (Map.Entry<String, byte[]> item : craft.entrySet()) {
            manifest.append(sha256(item.getValue()))
                    .append("  ")
                    .append(item.getKey())
                    .append('\n');
        }
        craft.put(
                "_bundle_manifest.sha256",
                manifest.toString().getBytes(StandardCharsets.UTF_8));
        String index = String.join("\n", craft.keySet().stream().sorted().toList()) + "\n";

        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            add(
                    output,
                    "META-INF/mahjong-rule-resources.properties",
                    "format=1\nid=riichi\nversion=2.0.2\n".getBytes(StandardCharsets.UTF_8));
            add(
                    output,
                    "META-INF/mahjong-rule-sounds.properties",
                    ("cue.TILE_DRAW=mahjong_riichi_v2_0_2:tile_draw,0.65,1.05\n"
                                    + "opening.dice=mahjong_riichi_v2_0_2:opening_dice,0.7,1.0\n"
                                    + "opening.wall=mahjong_riichi_v2_0_2:opening_wall_break,0.8,1.0\n")
                            .getBytes(StandardCharsets.UTF_8));
            add(output, "META-INF/RESOURCEPACK_ATTRIBUTION.md", new byte[] {0x23});
            for (Map.Entry<String, byte[]> item : craft.entrySet()) {
                add(output, "craftengine/" + item.getKey(), item.getValue());
            }
            add(output, "craftengine/_bundle_index.txt", index.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void add(ZipOutputStream output, String name, byte[] bytes) throws Exception {
        output.putNextEntry(new ZipEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
