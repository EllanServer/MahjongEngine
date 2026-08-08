package top.ellan.mahjong.bootstrap;

import java.util.Objects;
import java.util.Optional;
import top.ellan.mahjong.runtime.RulePackRuntimeStatus;

/** Fail-closed startup report; an unavailable subsystem does not hide the lobby/core plugin. */
public record MahjongArchitectureStatus(
        boolean eventStoreAvailable,
        Optional<RulePackRuntimeStatus> rulePacks,
        Optional<String> ruleAdministrationDisabledReason) {
    public MahjongArchitectureStatus {
        rulePacks = Objects.requireNonNull(rulePacks, "rulePacks");
        ruleAdministrationDisabledReason = Objects.requireNonNull(
            ruleAdministrationDisabledReason,
            "ruleAdministrationDisabledReason"
        );
    }
}
