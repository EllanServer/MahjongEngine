package top.ellan.mahjong.rank;

import top.ellan.mahjong.config.PluginSettings;
import top.ellan.mahjong.db.DatabaseService;
import top.ellan.mahjong.runtime.ServerScheduler;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;

public final class PlayerRankStorageFactory {
    private PlayerRankStorageFactory() {
    }

    public static PlayerRankStorage create(
        Plugin owner,
        Supplier<DatabaseService> database,
        Supplier<PluginSettings> settings,
        ServerScheduler scheduler,
        Logger logger
    ) {
        PluginSettings current = settings.get();
        if (current != null && current.rankingEnabled() && current.rankingInvSyncEnabled()) {
            Plugin invSync = owner.getServer().getPluginManager().getPlugin("InvSync");
            if (invSync != null && invSync.isEnabled()) {
                try {
                    InvSyncPlayerRankStorage storage = InvSyncPlayerRankStorage.createAndRegister(
                        invSync.getClass().getClassLoader(),
                        database,
                        settings,
                        scheduler,
                        logger
                    );
                    logger.info(
                        "Player rank backend=INVSYNC (authoritative player profiles); database remains global projection/history/table storage."
                    );
                    if (current.rankingInvSyncFallbackToDatabase()) {
                        return new InvSyncFailoverPlayerRankStorage(
                            storage,
                            new DatabasePlayerRankStorage(database),
                            logger
                        );
                    }
                    return storage;
                } catch (ReflectiveOperationException | LinkageError exception) {
                    logger.log(
                        Level.WARNING,
                        "InvSync is installed but its documented 2.x addon API is incompatible; player rank storage will use the configured fallback.",
                        exception
                    );
                }
            } else {
                logger.info("InvSync is absent or disabled; player rank storage will use the configured fallback.");
            }
        }

        if (current == null || current.rankingInvSyncFallbackToDatabase()) {
            PlayerRankStorage fallback = new DatabasePlayerRankStorage(database);
            logger.info("Player rank backend=" + fallback.backend() + " (full database fallback; ranking remains enabled when configured).");
            return fallback;
        }
        logger.warning("Player rank backend=UNAVAILABLE because InvSync could not be used and database fallback is disabled.");
        return new UnavailablePlayerRankStorage();
    }

    private static final class UnavailablePlayerRankStorage implements PlayerRankStorage {
        @Override
        public Backend backend() {
            return Backend.UNAVAILABLE;
        }

        @Override
        public boolean rankingEnabled() {
            return false;
        }

        @Override
        public Map<top.ellan.mahjong.model.MahjongVariant, top.ellan.mahjong.db.MahjongSoulRankProfile> loadProfiles(
            UUID playerId,
            String displayName
        ) throws PlayerRankStorageException {
            throw new PlayerRankStorageException(PlayerRankStorageException.Reason.UNAVAILABLE, "Player rank storage is unavailable");
        }

        @Override
        public CompletableFuture<Void> persistMatchRanksAsync(
            String operationId,
            String tableId,
            top.ellan.mahjong.model.MahjongVariant mode,
            top.ellan.mahjong.riichi.model.MahjongRule.GameLength length,
            List<top.ellan.mahjong.table.core.TableFinalStanding> standings
        ) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
