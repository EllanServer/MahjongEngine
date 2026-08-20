package top.ellan.mahjong.application.history;

import java.util.Map;
import top.ellan.mahjong.domain.match.RankProfile;
import top.ellan.mahjong.spi.PlayerId;

/**
 * Advances the shared rank ladder for one finished match.
 *
 * <p>Progression is common to every variant, so the platform supplies this port rather than a rule
 * pack. The implementation resolves the configured room and match length and delegates the maths to
 * {@link RankProgression}; it must stay a pure computation because the caller invokes it while
 * holding the terminal-result transaction.
 */
@FunctionalInterface
public interface RankProgressionPort {

    /**
     * Returns each seat's updated profile. An empty map leaves stage progress untouched, which is how
     * a deployment without ranking behaves.
     */
    Map<PlayerId, RankProfile> advance(RankProgressionRequest request);

    /** Records scores and match counts but never moves anyone along the ladder. */
    RankProgressionPort NONE = request -> Map.of();
}
