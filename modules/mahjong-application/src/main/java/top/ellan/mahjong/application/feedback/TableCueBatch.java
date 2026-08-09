package top.ellan.mahjong.application.feedback;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePresentationCue;

/** Immutable one-shot feedback emitted only after an accepted in-memory commit. */
public record TableCueBatch(
        TableId tableId,
        RuleId ruleId,
        long revision,
        List<PlayerId> audience,
        List<RulePresentationCue> cues) {
    public TableCueBatch {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(ruleId, "ruleId");
        if (revision < 1) {
            throw new IllegalArgumentException("cue revision must follow an accepted transition");
        }
        audience = List.copyOf(Objects.requireNonNull(audience, "audience"));
        cues = List.copyOf(Objects.requireNonNull(cues, "cues"));
        if (cues.isEmpty()) {
            throw new IllegalArgumentException("a cue batch cannot be empty");
        }
        Set<PlayerId> distinctAudience = new HashSet<>(audience);
        if (distinctAudience.size() != audience.size()) {
            throw new IllegalArgumentException("cue audience contains duplicates");
        }
        for (RulePresentationCue cue : cues) {
            cue.target().ifPresent(target -> {
                if (!distinctAudience.contains(target)) {
                    throw new IllegalArgumentException("cue targets a non-participant");
                }
            });
        }
    }
}
