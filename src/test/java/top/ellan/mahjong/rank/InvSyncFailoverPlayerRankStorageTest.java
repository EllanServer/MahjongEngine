package top.ellan.mahjong.rank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class InvSyncFailoverPlayerRankStorageTest {
    @Test
    void switchesToFullDatabaseFallbackAfterRuntimeApiFailure() {
        InvSyncPlayerRankStorage invSync = mock(InvSyncPlayerRankStorage.class);
        PlayerRankStorage fallback = mock(PlayerRankStorage.class);
        when(invSync.healthy()).thenReturn(false);
        when(fallback.backend()).thenReturn(PlayerRankStorage.Backend.DATABASE_FALLBACK);
        when(fallback.rankingEnabled()).thenReturn(true);
        InvSyncFailoverPlayerRankStorage storage = new InvSyncFailoverPlayerRankStorage(
            invSync,
            fallback,
            Logger.getLogger("InvSyncFailoverPlayerRankStorageTest")
        );

        assertEquals(PlayerRankStorage.Backend.DATABASE_FALLBACK, storage.backend());
        assertEquals(true, storage.rankingEnabled());
    }
}
