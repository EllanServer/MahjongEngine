package top.ellan.mahjong.spi;

import java.util.Objects;
import java.util.Optional;

/**
 * One transient presentation cue. An empty target broadcasts to table viewers; a present target
 * delivers only to that player.
 *
 * @param type presentation effect requested by the rule pack
 * @param target player receiving the cue, or empty for a table-wide broadcast
 */
public record RulePresentationCue(
        RulePresentationCueType type,
        Optional<PlayerId> target) {
    public RulePresentationCue {
        Objects.requireNonNull(type, "type");
        target = Objects.requireNonNull(target, "target");
    }

    public static RulePresentationCue broadcast(RulePresentationCueType type) {
        return new RulePresentationCue(type, Optional.empty());
    }

    public static RulePresentationCue toPlayer(
            RulePresentationCueType type, PlayerId playerId) {
        return new RulePresentationCue(
                type, Optional.of(Objects.requireNonNull(playerId, "playerId")));
    }
}
