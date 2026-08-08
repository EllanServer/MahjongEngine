package top.ellan.mahjong.runtime;

import java.util.Objects;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackRef;

/** Verification result never hides a broken or registry-orphaned installed version. */
public record RulePackVerification(
        RuleId ruleId, String version, boolean valid, RulePackRef reference, String detail) {
    public RulePackVerification {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(version, "version");
        detail = Objects.requireNonNull(detail, "detail");
        if (valid && reference == null) {
            throw new IllegalArgumentException("Valid verification requires provenance");
        }
    }
}
