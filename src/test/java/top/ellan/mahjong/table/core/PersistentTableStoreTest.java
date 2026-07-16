package top.ellan.mahjong.table.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import top.ellan.mahjong.db.DatabaseService;
import top.ellan.mahjong.model.MahjongVariant;
import top.ellan.mahjong.riichi.model.MahjongRule;
import top.ellan.mahjong.runtime.AsyncService;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class PersistentTableStoreTest {
    @Test
    void failedStartupLoadBlocksDestructiveEmptyFlush() throws SQLException {
        DatabaseService database = mock(DatabaseService.class);
        when(database.loadPersistentTables()).thenThrow(new SQLException("database offline"));
        when(database.databaseType()).thenReturn("H2");
        PersistentTableStore store = store(database);

        assertTrue(store.load().isEmpty());
        store.flush(List.of());

        verify(database, never()).replacePersistentTables(Mockito.anyList());
    }

    @Test
    void successfulEmptyLoadAllowsEmptyFlush() throws SQLException {
        DatabaseService database = mock(DatabaseService.class);
        when(database.loadPersistentTables()).thenReturn(List.of());
        PersistentTableStore store = store(database);

        assertTrue(store.load().isEmpty());
        store.flush(List.of());

        verify(database).replacePersistentTables(List.of());
    }

    @Test
    void unavailableWorldRowSurvivesLaterSnapshots() throws SQLException {
        DatabaseService database = mock(DatabaseService.class);
        DatabaseService.PersistentTableRecord deferred = row("WAIT01", "missing_world");
        when(database.loadPersistentTables()).thenReturn(List.of(deferred));
        PersistentTableStore store = store(database);

        try (MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("missing_world")).thenReturn(null);
            assertTrue(store.load().isEmpty());
        }
        store.flush(List.of());

        ArgumentCaptor<List<DatabaseService.PersistentTableRecord>> rows = rowsCaptor();
        verify(database).replacePersistentTables(rows.capture());
        assertEquals(List.of(deferred), rows.getValue());
    }

    @Test
    void liveTableOverridesDeferredRowWithoutResurrectingItAfterDeletion() throws SQLException {
        DatabaseService database = mock(DatabaseService.class);
        DatabaseService.PersistentTableRecord deferred = row("SAME01", "missing_world");
        when(database.loadPersistentTables()).thenReturn(List.of(deferred));
        PersistentTableStore store = store(database);

        try (MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("missing_world")).thenReturn(null);
            store.load();
        }

        World liveWorld = mock(World.class);
        when(liveWorld.getName()).thenReturn("live_world");
        TableIdentityPort liveTable = mock(TableIdentityPort.class);
        when(liveTable.isPersistentRoom()).thenReturn(true);
        when(liveTable.id()).thenReturn("same01");
        when(liveTable.center()).thenReturn(new Location(liveWorld, 10.0, 70.0, -5.0));
        when(liveTable.configuredVariant()).thenReturn(MahjongVariant.GB);
        when(liveTable.configuredRuleSnapshot()).thenReturn(new MahjongRule());
        when(liveTable.owner()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));

        store.flush(List.of(liveTable));
        store.flush(List.of());

        ArgumentCaptor<List<DatabaseService.PersistentTableRecord>> rows = rowsCaptor();
        verify(database, times(2)).replacePersistentTables(rows.capture());
        assertEquals(1, rows.getAllValues().get(0).size());
        assertEquals("same01", rows.getAllValues().get(0).get(0).id());
        assertTrue(rows.getAllValues().get(1).isEmpty());
    }

    private static PersistentTableStore store(DatabaseService database) {
        return new PersistentTableStore(() -> database, mock(AsyncService.class), Logger.getAnonymousLogger(), true);
    }

    private static DatabaseService.PersistentTableRecord row(String id, String worldName) {
        return new DatabaseService.PersistentTableRecord(
            id,
            worldName,
            1.0,
            64.0,
            2.0,
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            MahjongVariant.RIICHI,
            new MahjongRule(),
            false
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ArgumentCaptor<List<DatabaseService.PersistentTableRecord>> rowsCaptor() {
        return ArgumentCaptor.forClass((Class) List.class);
    }
}
