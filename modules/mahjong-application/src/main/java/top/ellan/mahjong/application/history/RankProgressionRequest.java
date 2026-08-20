package top.ellan.mahjong.application.history;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import top.ellan.mahjong.domain.match.RankProfile;
import top.ellan.mahjong.spi.PlayerId;

/**
 * Everything the ladder needs about one finished match, assembled inside the terminal-result
 * transaction.
 *
 * @param ruleId owning rule pack
 * @param profileId rule profile the match ran, which selects the configured room and match length
 * @param rankSystem ranking system the pack reported
 * @param standings rule-reported placement and score per seat
 * @param current each seat's stored profile; absent seats start from {@link RankProfile#initial()}
 */
public record RankProgressionRequest(
        String ruleId,
        String profileId,
        String rankSystem,
        List<RankProgression.Standing> standings,
        Map<PlayerId, RankProfile> current) {

    public RankProgressionRequest {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(rankSystem, "rankSystem");
        standings = List.copyOf(Objects.requireNonNull(standings, "standings"));
        current = Map.copyOf(Objects.requireNonNull(current, "current"));
    }
}
