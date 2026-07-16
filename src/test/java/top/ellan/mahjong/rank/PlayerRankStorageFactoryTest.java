package top.ellan.mahjong.rank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import top.ellan.mahjong.config.PluginSettings;
import top.ellan.mahjong.db.DatabaseService;
import top.ellan.mahjong.runtime.ServerScheduler;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

class PlayerRankStorageFactoryTest {
    @Test
    void missingInvSyncKeepsTheCompleteDatabaseBackend() {
        Plugin owner = mock(Plugin.class);
        Server server = mock(Server.class);
        PluginManager pluginManager = mock(PluginManager.class);
        DatabaseService database = mock(DatabaseService.class);
        when(owner.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(pluginManager.getPlugin("InvSync")).thenReturn(null);
        when(database.rankingEnabled()).thenReturn(true);
        PluginSettings settings = PluginSettings.defaults();

        PlayerRankStorage storage = PlayerRankStorageFactory.create(
            owner,
            () -> database,
            () -> settings,
            mock(ServerScheduler.class),
            Logger.getLogger("PlayerRankStorageFactoryTest")
        );

        assertEquals(PlayerRankStorage.Backend.DATABASE_FALLBACK, storage.backend());
    }
}
