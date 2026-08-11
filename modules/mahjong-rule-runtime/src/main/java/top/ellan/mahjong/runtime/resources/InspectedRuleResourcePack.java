package top.ellan.mahjong.runtime.resources;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackRef;

/** Verified resource-only companion ready for platform installation. */
public record InspectedRuleResourcePack(
        RuleId ruleId,
        String version,
        String jarSha256,
        Path archive,
        RuleSoundCatalog sounds) {
    public InspectedRuleResourcePack {
        Objects.requireNonNull(ruleId, "ruleId");
        version = Objects.requireNonNull(version, "version");
        jarSha256 = Objects.requireNonNull(jarSha256, "jarSha256").toLowerCase(Locale.ROOT);
        if (!jarSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid rule JAR SHA-256");
        }
        archive = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        Objects.requireNonNull(sounds, "sounds");
    }

    public boolean belongsTo(RulePackRef reference) {
        Objects.requireNonNull(reference, "reference");
        return ruleId.equals(reference.ruleId())
                && version.equals(reference.version())
                && jarSha256.equals(reference.jarSha256());
    }
}
