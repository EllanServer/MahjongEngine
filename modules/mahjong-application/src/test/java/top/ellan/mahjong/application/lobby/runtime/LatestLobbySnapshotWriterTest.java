package top.ellan.mahjong.application.lobby.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.application.lobby.port.LobbyRepositoryPort;
import top.ellan.mahjong.domain.table.TableAnchor;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.domain.lobby.TableLobby;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleId;

class LatestLobbySnapshotWriterTest {
    @Test
    void rapidFramesCoalesceToTheNewestRevisionWithoutAnUnboundedQueue() {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        RecordingRepository repository = new RecordingRepository();
        List<Throwable> failures = new ArrayList<>();
        LatestLobbySnapshotWriter writer =
                new LatestLobbySnapshotWriter(
                        tasks::add,
                        repository,
                        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                        failures::add);
        TableLobby first = lobby();
        TableLobby second =
                first.withRules(
                        new RuleId("mcr"), new ProfileId("green-book"), Map.of());

        writer.changed(first);
        writer.changed(second);

        assertEquals(1, tasks.size());
        tasks.remove().run();
        assertEquals(List.of(second.revision()), repository.savedRevisions);
        assertTrue(failures.isEmpty());
    }

    private static TableLobby lobby() {
        return TableLobby.create(
                TableId.random(),
                new PlayerId(new UUID(0, 1)),
                new RuleId("riichi"),
                new ProfileId("mahjong-soul"),
                Map.of(),
                4,
                Instant.EPOCH);
    }

    private static final class RecordingRepository implements LobbyRepositoryPort {
        private final List<Long> savedRevisions = new ArrayList<>();

        @Override
        public void create(TableLobby lobby, TableAnchor anchor) {}

        @Override
        public void save(TableLobby lobby, Instant updatedAt) {
            savedRevisions.add(lobby.revision());
        }

        @Override
        public List<TableLobby> list() {
            return List.of();
        }

        @Override
        public Optional<TableLobby> find(TableId tableId) {
            return Optional.empty();
        }

        @Override
        public void delete(TableId tableId) {}
    }
}
