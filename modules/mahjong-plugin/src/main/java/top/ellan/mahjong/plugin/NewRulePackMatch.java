package top.ellan.mahjong.plugin;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import top.ellan.mahjong.domain.CompetitionRef;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.domain.TableParticipant;
import top.ellan.mahjong.persistence.sql.StoredTableAnchor;
import top.ellan.mahjong.spi.MatchSeed;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleId;

/** Immutable command captured before leaving the Paper event thread. */
public record NewRulePackMatch(
        TableId tableId,
        RuleId ruleId,
        ProfileId profileId,
        MatchSeed seed,
        List<TableParticipant> participants,
        Map<String, String> configuration,
        CompetitionRef competitionRef,
        StoredTableAnchor anchor) {
    public NewRulePackMatch {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(seed, "seed");
        participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
        configuration = Map.copyOf(Objects.requireNonNull(configuration, "configuration"));
        Objects.requireNonNull(competitionRef, "competitionRef");
        Objects.requireNonNull(anchor, "anchor");
        if (!tableId.equals(anchor.tableId())) {
            throw new IllegalArgumentException("Command and anchor table ids differ");
        }
    }
}
