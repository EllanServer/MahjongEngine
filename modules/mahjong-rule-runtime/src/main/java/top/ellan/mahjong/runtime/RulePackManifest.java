package top.ellan.mahjong.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import top.ellan.mahjong.spi.RuleId;

/** Static descriptor read before any rule-pack bytecode is initialized. */
public record RulePackManifest(
        RuleId ruleId,
        String version,
        String spiVersion,
        String requiredCoreVersion,
        int stateSchemaVersion,
        Set<String> requiredResources) {
    public static final String PATH = "META-INF/mahjong-rule-pack.properties";

    public RulePackManifest {
        Objects.requireNonNull(ruleId, "ruleId");
        version = Objects.requireNonNull(version, "version");
        spiVersion = Objects.requireNonNull(spiVersion, "spiVersion");
        requiredCoreVersion = Objects.requireNonNull(requiredCoreVersion, "requiredCoreVersion");
        if (stateSchemaVersion < 1) {
            throw new IllegalArgumentException("stateSchemaVersion must be positive");
        }
        requiredResources = Set.copyOf(Objects.requireNonNull(requiredResources, "requiredResources"));
    }

    public static RulePackManifest read(ZipFile jar) throws RulePackException {
        ZipEntry entry = jar.getEntry(PATH);
        if (entry == null
                || entry.isDirectory()
                || entry.getSize() < 0
                || entry.getSize() > 64 * 1024) {
            throw new RulePackException("Rule pack has no valid " + PATH);
        }
        Properties properties = new Properties();
        try (InputStream input = jar.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(64 * 1024 + 1);
            if (bytes.length > 64 * 1024 || input.read() >= 0) {
                throw new RulePackException("Rule-pack manifest exceeds 64 KiB");
            }
            properties.load(new ByteArrayInputStream(bytes));
        } catch (IOException failure) {
            throw new RulePackException("Unable to read rule-pack manifest", failure);
        }
        Set<String> expected =
                Set.of(
                        "id",
                        "version",
                        "spiVersion",
                        "requiredCoreVersion",
                        "stateSchemaVersion",
                        "requiredResources");
        if (!properties.stringPropertyNames().equals(expected)) {
            throw new RulePackException(
                    "Rule-pack manifest fields differ from the SPI format: "
                            + properties.stringPropertyNames());
        }
        try {
            String resources = properties.getProperty("requiredResources").trim();
            Set<String> required =
                    resources.isEmpty()
                            ? Set.of()
                            : Arrays.stream(resources.split(","))
                                    .map(String::trim)
                                    .peek(RulePackManifest::validateResourceName)
                                    .collect(Collectors.toUnmodifiableSet());
            return new RulePackManifest(
                    new RuleId(properties.getProperty("id")),
                    properties.getProperty("version"),
                    properties.getProperty("spiVersion"),
                    properties.getProperty("requiredCoreVersion"),
                    Integer.parseInt(properties.getProperty("stateSchemaVersion")),
                    required);
        } catch (IllegalArgumentException failure) {
            throw new RulePackException("Invalid rule-pack manifest", failure);
        }
    }

    static void validateResourceName(String name) {
        if (name.isBlank()
                || name.startsWith("/")
                || name.contains("\\")
                || Arrays.asList(name.split("/")).contains("..")) {
            throw new IllegalArgumentException("Unsafe required resource: " + name);
        }
    }
}
