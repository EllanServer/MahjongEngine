package top.ellan.mahjong.platform.paper.feedback;

import java.util.Locale;
import java.util.Objects;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackRef;

/** Sound profiles bound to the exact signed JAR coordinate that emitted their cues. */
public record RuleSoundBinding(
        RuleId ruleId,
        String version,
        String jarSha256,
        RuleSoundProfiles profiles) {
    public RuleSoundBinding {
        Objects.requireNonNull(ruleId, "ruleId");
        version = Objects.requireNonNull(version, "version");
        jarSha256 = Objects.requireNonNull(jarSha256, "jarSha256").toLowerCase(Locale.ROOT);
        if (!jarSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid rule JAR SHA-256");
        }
        Objects.requireNonNull(profiles, "profiles");
    }

    public boolean matches(RulePackRef reference) {
        Objects.requireNonNull(reference, "reference");
        return ruleId.equals(reference.ruleId())
                && version.equals(reference.version())
                && jarSha256.equals(reference.jarSha256());
    }
}
