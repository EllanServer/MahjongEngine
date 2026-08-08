package top.ellan.mahjong.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackRef;

/** Startup status; one broken variant does not prevent other variants from loading. */
public record RulePackRuntimeStatus(
        Map<RuleId, RulePackRef> loadedActive,
        Map<RuleId, RulePackRef> pendingRestart,
        Map<RuleId, String> failures) {
    public RulePackRuntimeStatus {
        loadedActive = Map.copyOf(Objects.requireNonNull(loadedActive, "loadedActive"));
        pendingRestart = Map.copyOf(Objects.requireNonNull(pendingRestart, "pendingRestart"));
        failures = Map.copyOf(Objects.requireNonNull(failures, "failures"));
    }

    public Set<RuleId> disabledRuleIds() {
        return failures.keySet();
    }
}
