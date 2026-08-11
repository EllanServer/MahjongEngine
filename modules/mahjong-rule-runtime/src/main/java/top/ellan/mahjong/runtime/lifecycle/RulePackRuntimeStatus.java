package top.ellan.mahjong.runtime.lifecycle;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackRef;

/**
 * Runtime status; one broken variant does not prevent other variants from loading.
 *
 * <p>{@code supersededInUse} lists versions that no longer receive new matches but are still
 * running older ones, which is the observable half of two-generation coexistence.</p>
 */
public record RulePackRuntimeStatus(
        Map<RuleId, RulePackRef> loadedActive,
        Map<RuleId, RulePackRef> pendingRestart,
        Map<RuleId, String> failures,
        Map<RuleId, RulePackRef> supersededInUse) {
    public RulePackRuntimeStatus {
        loadedActive = Map.copyOf(Objects.requireNonNull(loadedActive, "loadedActive"));
        pendingRestart = Map.copyOf(Objects.requireNonNull(pendingRestart, "pendingRestart"));
        failures = Map.copyOf(Objects.requireNonNull(failures, "failures"));
        supersededInUse = Map.copyOf(Objects.requireNonNull(supersededInUse, "supersededInUse"));
    }

    public RulePackRuntimeStatus(
            Map<RuleId, RulePackRef> loadedActive,
            Map<RuleId, RulePackRef> pendingRestart,
            Map<RuleId, String> failures) {
        this(loadedActive, pendingRestart, failures, Map.of());
    }

    public Set<RuleId> disabledRuleIds() {
        return failures.keySet();
    }
}
