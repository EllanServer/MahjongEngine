package top.ellan.mahjong.domain;

import java.util.Locale;
import java.util.Optional;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleId;

/** Explicit migration map. Unknown or custom legacy modes require administrator review. */
public final class LegacyRuleMapping {
    private LegacyRuleMapping() {}

    public static Optional<Target> resolve(String legacyMode) {
        if (legacyMode == null) {
            return Optional.empty();
        }
        return switch (legacyMode.toUpperCase(Locale.ROOT)) {
            case "RIICHI" -> Optional.of(new Target(new RuleId("riichi"), new ProfileId("mahjongsoul")));
            case "GB" -> Optional.of(new Target(new RuleId("mcr"), new ProfileId("green-book")));
            case "SICHUAN" ->
                    Optional.of(
                            new Target(
                                    new RuleId("sichuan"),
                                    new ProfileId("t-tfmj-01-2024")));
            default -> Optional.empty();
        };
    }

    public record Target(RuleId ruleId, ProfileId profileId) {
        public Target {
            java.util.Objects.requireNonNull(ruleId, "ruleId");
            java.util.Objects.requireNonNull(profileId, "profileId");
        }
    }
}
