package top.ellan.mahjong.runtime;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import top.ellan.mahjong.spi.RuleId;

/** First release trust policy: only three built-in rule identities are accepted. */
public final class OfficialRuleIds {
    public static final RuleId RIICHI = new RuleId("riichi");
    public static final RuleId MCR = new RuleId("mcr");
    public static final RuleId SICHUAN = new RuleId("sichuan");
    public static final Set<RuleId> ALL = Set.of(RIICHI, MCR, SICHUAN);
    private static final Map<String, RuleId> INPUTS =
            Map.of(
                    "riichi", RIICHI,
                    "richi", RIICHI,
                    "mcr", MCR,
                    "gb", MCR,
                    "sichuan", SICHUAN);

    private OfficialRuleIds() {}

    public static Optional<RuleId> parseCommandInput(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(INPUTS.get(value.toLowerCase(Locale.ROOT)));
    }
}
