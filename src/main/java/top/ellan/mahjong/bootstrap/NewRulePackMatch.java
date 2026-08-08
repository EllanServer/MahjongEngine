package top.ellan.mahjong.bootstrap;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import top.ellan.mahjong.application.SceneProjectionPort;
import top.ellan.mahjong.domain.CompetitionRef;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.domain.TableParticipant;
import top.ellan.mahjong.spi.MatchSeed;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleId;

/** Immutable new-match command captured before leaving a Paper/CraftEngine event thread. */
public record NewRulePackMatch(
        TableId tableId,
        RuleId ruleId,
        ProfileId profileId,
        MatchSeed seed,
        List<TableParticipant> participants,
        Map<String, String> configuration,
        CompetitionRef competitionRef,
        SceneProjectionPort projector) {
    public NewRulePackMatch {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(seed, "seed");
        participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
        configuration = Map.copyOf(Objects.requireNonNull(configuration, "configuration"));
        Objects.requireNonNull(competitionRef, "competitionRef");
        Objects.requireNonNull(projector, "projector");
    }
}
