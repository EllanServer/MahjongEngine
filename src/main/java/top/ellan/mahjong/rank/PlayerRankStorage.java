package top.ellan.mahjong.rank;

import top.ellan.mahjong.db.MahjongSoulRankProfile;
import top.ellan.mahjong.model.MahjongVariant;
import top.ellan.mahjong.riichi.model.MahjongRule;
import top.ellan.mahjong.table.core.TableFinalStanding;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Authoritative storage boundary for per-player rank profiles.
 *
 * <p>Global projections such as leaderboards and rank history deliberately stay
 * in {@code DatabaseService}; implementations of this port decide which system
 * owns the mutable player profile.</p>
 */
public interface PlayerRankStorage extends AutoCloseable {
    enum Backend {
        INVSYNC,
        DATABASE_FALLBACK,
        UNAVAILABLE
    }

    Backend backend();

    boolean rankingEnabled();

    /** Returns all mode profiles. InvSync implementations only support players in their synchronized online cache. */
    Map<MahjongVariant, MahjongSoulRankProfile> loadProfiles(UUID playerId, String displayName) throws PlayerRankStorageException;

    CompletableFuture<Void> persistMatchRanksAsync(
        String operationId,
        String tableId,
        MahjongVariant mode,
        MahjongRule.GameLength length,
        List<TableFinalStanding> standings
    );

    @Override
    default void close() {
    }
}
