package top.ellan.mahjong.persistence.sql.recovery;

import java.util.Objects;
import top.ellan.mahjong.spi.RuleState;

/** State proven by deterministic provider replay through the last committed event. */
public record VerifiedRecovery(RuleState state, long stateRevision, long eventSequence) {
    public VerifiedRecovery {
        Objects.requireNonNull(state, "state");
        if (stateRevision < 0 || eventSequence < 0) {
            throw new IllegalArgumentException("Recovery coordinates must be non-negative");
        }
    }
}
