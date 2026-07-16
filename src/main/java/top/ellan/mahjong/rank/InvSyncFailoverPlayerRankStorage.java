package top.ellan.mahjong.rank;

import top.ellan.mahjong.db.MahjongSoulRankProfile;
import top.ellan.mahjong.model.MahjongVariant;
import top.ellan.mahjong.riichi.model.MahjongRule;
import top.ellan.mahjong.table.core.TableFinalStanding;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** Switches globally to the legacy database only after the registered InvSync callback proves incompatible. */
final class InvSyncFailoverPlayerRankStorage implements PlayerRankStorage {
    private final InvSyncPlayerRankStorage invSync;
    private final PlayerRankStorage fallback;
    private final Logger logger;
    private final AtomicBoolean announced = new AtomicBoolean();

    InvSyncFailoverPlayerRankStorage(InvSyncPlayerRankStorage invSync, PlayerRankStorage fallback, Logger logger) {
        this.invSync = invSync;
        this.fallback = fallback;
        this.logger = logger;
    }

    @Override
    public Backend backend() {
        return this.active().backend();
    }

    @Override
    public boolean rankingEnabled() {
        return this.active().rankingEnabled();
    }

    @Override
    public Map<MahjongVariant, MahjongSoulRankProfile> loadProfiles(UUID playerId, String displayName)
        throws PlayerRankStorageException {
        return this.active().loadProfiles(playerId, displayName);
    }

    @Override
    public CompletableFuture<Void> persistMatchRanksAsync(
        String operationId,
        String tableId,
        MahjongVariant mode,
        MahjongRule.GameLength length,
        List<TableFinalStanding> standings
    ) {
        return this.active().persistMatchRanksAsync(operationId, tableId, mode, length, standings);
    }

    @Override
    public void close() {
        this.invSync.close();
        this.fallback.close();
    }

    private PlayerRankStorage active() {
        if (this.invSync.healthy()) {
            return this.invSync;
        }
        if (this.announced.compareAndSet(false, true)) {
            this.logger.warning("Player rank backend switched from INVSYNC to DATABASE_FALLBACK after a runtime API failure.");
        }
        return this.fallback;
    }
}
