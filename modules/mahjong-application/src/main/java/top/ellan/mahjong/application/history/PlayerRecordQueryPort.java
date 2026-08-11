package top.ellan.mahjong.application.history;

import java.util.List;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleId;

/** Blocking history boundary; callers must use the bounded platform I/O executor. */
public interface PlayerRecordQueryPort {
    List<PlayerMatchHistoryEntry> history(PlayerId playerId, int offset, int limit)
            throws Exception;

    PlayerRankingPage ranking(
            PlayerId requestingPlayer, RuleId ruleId, int page, int pageSize)
            throws Exception;
}
