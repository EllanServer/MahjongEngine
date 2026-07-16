package top.ellan.mahjong.rank;

import top.ellan.mahjong.db.DatabaseService;
import top.ellan.mahjong.db.MahjongSoulRankProfile;
import top.ellan.mahjong.model.MahjongVariant;
import top.ellan.mahjong.riichi.model.MahjongRule;
import top.ellan.mahjong.table.core.TableFinalStanding;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Full compatibility fallback used when InvSync is absent or incompatible. */
public final class DatabasePlayerRankStorage implements PlayerRankStorage {
    private final Supplier<DatabaseService> database;

    public DatabasePlayerRankStorage(Supplier<DatabaseService> database) {
        this.database = database;
    }

    @Override
    public Backend backend() {
        return this.rankingEnabled() ? Backend.DATABASE_FALLBACK : Backend.UNAVAILABLE;
    }

    @Override
    public boolean rankingEnabled() {
        DatabaseService service = this.database.get();
        return service != null && service.rankingEnabled();
    }

    @Override
    public Map<MahjongVariant, MahjongSoulRankProfile> loadProfiles(UUID playerId, String displayName)
        throws PlayerRankStorageException {
        DatabaseService service = this.database.get();
        if (service == null || !service.rankingEnabled()) {
            throw new PlayerRankStorageException(PlayerRankStorageException.Reason.UNAVAILABLE, "Rank database is unavailable");
        }
        try {
            return service.loadRankProfiles(playerId, displayName);
        } catch (SQLException exception) {
            throw new PlayerRankStorageException(
                PlayerRankStorageException.Reason.DATABASE_FAILURE,
                "Could not load rank profiles",
                exception
            );
        }
    }

    @Override
    public CompletableFuture<Void> persistMatchRanksAsync(
        String operationId,
        String tableId,
        MahjongVariant mode,
        MahjongRule.GameLength length,
        List<TableFinalStanding> standings
    ) {
        DatabaseService service = this.database.get();
        if (service == null || !service.rankingEnabled()) {
            return CompletableFuture.completedFuture(null);
        }
        return service.persistMatchRanksAsync(operationId, tableId, mode, length, standings);
    }
}
