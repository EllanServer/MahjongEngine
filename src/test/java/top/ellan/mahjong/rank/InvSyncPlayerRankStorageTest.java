package top.ellan.mahjong.rank;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xbaimiao.invsync.api.addon.TestPlayer;
import com.xbaimiao.invsync.api.addon.TestSaveEvent;
import com.xbaimiao.invsync.api.addon.TestSaveReason;
import com.xbaimiao.invsync.api.addon.TestSyncEvent;
import top.ellan.mahjong.config.PluginSettings;
import top.ellan.mahjong.db.DatabaseService;
import top.ellan.mahjong.db.MahjongSoulRankProfile;
import top.ellan.mahjong.model.MahjongVariant;
import top.ellan.mahjong.riichi.model.MahjongRule;
import top.ellan.mahjong.runtime.ServerScheduler;
import top.ellan.mahjong.table.core.TableFinalStanding;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class InvSyncPlayerRankStorageTest {
    @Test
    void migratesOnlyWhenRemoteDataIsMissingAndWritesMarkerOnSave() throws Exception {
        DatabaseService database = mock(DatabaseService.class);
        when(database.rankingEnabled()).thenReturn(true);
        UUID playerId = UUID.randomUUID();
        Map<MahjongVariant, MahjongSoulRankProfile> legacy = defaults(playerId, "Legacy");
        when(database.loadRankProfiles(playerId, "Player")).thenReturn(legacy);
        InvSyncPlayerRankStorage storage = storage(database);
        TestPlayer player = new TestPlayer(playerId, "Player");

        storage.onSync(new TestSyncEvent(player, new HashMap<>()));
        Map<String, byte[]> saved = new HashMap<>();
        storage.onSave(new TestSaveEvent(player, saved), TestSaveReason.AUTO_SAVE);
        verify(database, times(1)).loadRankProfiles(playerId, "Player");
        PlayerRankPayloadCodec.Decoded decoded = new PlayerRankPayloadCodec().decode(
            playerId,
            saved.get(InvSyncPlayerRankStorage.DATA_KEY)
        );
        assertEquals(true, decoded.migrated());

        storage.onSync(new TestSyncEvent(player, saved));
        verify(database, times(1)).loadRankProfiles(playerId, "Player");
    }

    @Test
    void corruptRemotePayloadIsNeverOverwritten() throws Exception {
        DatabaseService database = mock(DatabaseService.class);
        InvSyncPlayerRankStorage storage = storage(database);
        UUID playerId = UUID.randomUUID();
        TestPlayer player = new TestPlayer(playerId, "Player");
        byte[] corrupt = { 1, 2, 3 };
        Map<String, byte[]> remote = new HashMap<>();
        remote.put(InvSyncPlayerRankStorage.DATA_KEY, corrupt);

        storage.onSync(new TestSyncEvent(player, remote));
        storage.onSave(new TestSaveEvent(player, remote), TestSaveReason.AUTO_SAVE);

        assertArrayEquals(corrupt, remote.get(InvSyncPlayerRankStorage.DATA_KEY));
        assertThrows(PlayerRankStorageException.class, () -> storage.loadProfiles(playerId, "Player"));
        verify(database, never()).loadRankProfiles(playerId, "Player");
    }

    @Test
    void concurrentMatchUpdatesDoNotLoseProfileIncrements() throws Exception {
        DatabaseService database = mock(DatabaseService.class);
        when(database.rankingEnabled()).thenReturn(false);
        InvSyncPlayerRankStorage storage = storage(database);
        List<TableFinalStanding> standings = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            UUID playerId = UUID.randomUUID();
            String name = "P" + index;
            storage.onSync(new TestSyncEvent(new TestPlayer(playerId, name), new HashMap<>()));
            standings.add(new TableFinalStanding(playerId, name, index + 1, 40000 - index * 10000, 0.0D, false));
        }

        List<CompletableFuture<Void>> writes = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            int operation = index;
            writes.add(CompletableFuture.runAsync(() -> storage.persistMatchRanksAsync(
                "operation-" + operation,
                "TABLE",
                MahjongVariant.RIICHI,
                MahjongRule.GameLength.EAST,
                standings
            ).join()));
        }
        CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).join();

        for (TableFinalStanding standing : standings) {
            assertEquals(
                32,
                storage.loadProfiles(standing.playerId(), standing.displayName()).get(MahjongVariant.RIICHI).totalMatches()
            );
        }
    }

    private static InvSyncPlayerRankStorage storage(DatabaseService database) throws Exception {
        PluginSettings settings = PluginSettings.parse("""
            ranking:
              enabled: true
              eastRoom: SILVER
              southRoom: GOLD
            """);
        return InvSyncPlayerRankStorage.createAndRegister(
            InvSyncPlayerRankStorageTest.class.getClassLoader(),
            () -> database,
            () -> settings,
            mock(ServerScheduler.class),
            Logger.getLogger("InvSyncPlayerRankStorageTest")
        );
    }

    private static Map<MahjongVariant, MahjongSoulRankProfile> defaults(UUID playerId, String name) {
        Map<MahjongVariant, MahjongSoulRankProfile> profiles = new EnumMap<>(MahjongVariant.class);
        for (MahjongVariant mode : MahjongVariant.values()) {
            profiles.put(mode, MahjongSoulRankProfile.defaultProfile(playerId, name));
        }
        return profiles;
    }
}
