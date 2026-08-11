package top.ellan.mahjong.persistence.sql.event;

import top.ellan.mahjong.persistence.sql.common.PersistenceConflictException;
import top.ellan.mahjong.persistence.sql.connection.SqlConnectionFactory;
import top.ellan.mahjong.persistence.sql.match.JdbcMatchRepository;
import top.ellan.mahjong.persistence.sql.match.MatchInstanceRecord;
import top.ellan.mahjong.persistence.sql.recovery.MatchRecoveryData;
import top.ellan.mahjong.persistence.sql.schema.SqlSchemaMigrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.application.persistence.MatchEventRecord;
import top.ellan.mahjong.application.persistence.MatchWriteBatch;
import top.ellan.mahjong.application.persistence.SnapshotWrite;
import top.ellan.mahjong.domain.match.MatchBinding;
import top.ellan.mahjong.domain.match.MatchId;
import top.ellan.mahjong.domain.table.ParticipantRole;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.domain.table.TableLifecycle;
import top.ellan.mahjong.domain.table.TableParticipant;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RuleEvent;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RuleMatchResult;
import top.ellan.mahjong.spi.RulePackRef;
import top.ellan.mahjong.spi.RulePlayerResult;
import top.ellan.mahjong.spi.RuleStateSnapshot;
import top.ellan.mahjong.spi.SeatId;

class JdbcEventStoreTest {
    private SqlConnectionFactory connections;
    private JdbcMatchRepository matches;
    private JdbcEventStore events;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        connections = () -> DriverManager.getConnection(url, "sa", "");
        new SqlSchemaMigrator(connections).migrate();
        matches = new JdbcMatchRepository(connections);
        events = new JdbcEventStore(connections, Runnable::run);
    }

    @Test
    void retriesAreIdempotentAndRecoveryGroupsEventsByAcceptedAction() throws Exception {
        MatchInstanceRecord match = match();
        RuleStateSnapshot initial = snapshot(0, 0);
        List<TableParticipant> participants = List.of(
                new TableParticipant(
                        new PlayerId(UUID.fromString(
                                "00000000-0000-0000-0000-000000000001")),
                        ParticipantRole.PLAYER,
                        Optional.of(new SeatId(0))),
                new TableParticipant(
                        new PlayerId(UUID.fromString(
                                "00000000-0000-0000-0000-000000000002")),
                        ParticipantRole.SPECTATOR,
                        Optional.empty()));
        matches.createRecoverableMatch(match, participants, initial);
        MatchWriteBatch batch = batch(match.binding().matchId(), false);

        assertEquals(2, events.appendBatch(batch).toCompletableFuture().join().committedSequence());
        assertEquals(2, events.appendBatch(batch).toCompletableFuture().join().committedSequence());

        MatchRecoveryData recovered = matches.recover(match.binding().matchId());
        assertEquals(0, recovered.snapshot().sequence());
        assertEquals(0, recovered.snapshotStateRevision());
        assertEquals(1, recovered.actionsAfterSnapshot().size());
        assertEquals(2, recovered.actionsAfterSnapshot().getFirst().expectedEvents().size());
        assertEquals(2, recovered.match().lastCommittedSequence());
        assertEquals(Set.copyOf(participants), Set.copyOf(recovered.participants()));
    }

    @Test
    void retryWithDifferentCanonicalPayloadFailsClosed() throws Exception {
        MatchInstanceRecord match = match();
        matches.createRecoverableMatch(match, snapshot(0, 0));
        events.appendBatch(batch(match.binding().matchId(), false)).toCompletableFuture().join();

        CompletionException failure =
                assertThrows(
                        CompletionException.class,
                        () ->
                                events.appendBatch(batch(match.binding().matchId(), true))
                                        .toCompletableFuture()
                                        .join());
        assertInstanceOf(PersistenceConflictException.class, failure.getCause());
    }

    @Test
    void terminalSnapshotPersistsResultsAndRankLedgerExactlyOnce() throws Exception {
        MatchInstanceRecord match = match();
        PlayerId first = PlayerId.parse("00000000-0000-0000-0000-000000000001");
        PlayerId second = PlayerId.parse("00000000-0000-0000-0000-000000000002");
        List<TableParticipant> participants = List.of(
                new TableParticipant(
                        first, ParticipantRole.PLAYER, Optional.of(new SeatId(0))),
                new TableParticipant(
                        second, ParticipantRole.PLAYER, Optional.of(new SeatId(1))));
        matches.createRecoverableMatch(match, participants, snapshot(0, 0));
        MatchWriteBatch eventsOnly = batch(match.binding().matchId(), false);
        RuleMatchResult result = new RuleMatchResult(
                "riichi.mahjong-soul.v1",
                List.of(
                        new RulePlayerResult(
                                first, new SeatId(0), 1, 31_000, 4_000, new byte[] {1}),
                        new RulePlayerResult(
                                second, new SeatId(1), 2, 19_000, 3_000, new byte[] {2})));
        SnapshotWrite terminal = new SnapshotWrite(
                match.binding().matchId(),
                1,
                Instant.parse("2026-08-08T00:00:01Z"),
                snapshot(2, 1),
                Optional.of(TableLifecycle.FINISHED),
                Optional.of(result));
        MatchWriteBatch batch = new MatchWriteBatch(
                match.binding().matchId(), eventsOnly.events(), Optional.of(terminal));

        events.appendBatch(batch).toCompletableFuture().join();
        events.appendBatch(batch).toCompletableFuture().join();

        try (var connection = connections.open();
                var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery(
                    "SELECT COUNT(*), SUM(ranking_points_milli) FROM player_result")) {
                rows.next();
                assertEquals(2, rows.getInt(1));
                assertEquals(7_000L, rows.getLong(2));
            }
            try (var rows = statement.executeQuery(
                    "SELECT COUNT(*), SUM(ranking_points_milli) FROM rank_ledger")) {
                rows.next();
                assertEquals(2, rows.getInt(1));
                assertEquals(7_000L, rows.getLong(2));
            }
            try (var rows = statement.executeQuery(
                    "SELECT status FROM match_instance")) {
                rows.next();
                assertEquals(TableLifecycle.FINISHED.name(), rows.getString(1));
            }
        }
    }

    private static MatchInstanceRecord match() {
        Instant now = Instant.parse("2026-08-08T00:00:00Z");
        MatchId matchId = MatchId.random();
        RulePackRef reference =
                new RulePackRef(new RuleId("riichi"), "1.0.0", "a".repeat(64), 1);
        MatchBinding binding =
                new MatchBinding(
                        matchId,
                        reference,
                        new ProfileId("standard"),
                        "b".repeat(64),
                        now);
        return new MatchInstanceRecord(
                binding, TableId.random(), TableLifecycle.ACTIVE, now, 0);
    }

    private static MatchWriteBatch batch(MatchId matchId, boolean mutateSecondEvent) {
        PlayerId actor =
                new PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        RuleAction action = new RuleAction("discard", new byte[] {7});
        Instant now = Instant.parse("2026-08-08T00:00:01Z");
        String before = "0".repeat(64);
        String after = "1".repeat(64);
        return new MatchWriteBatch(
                matchId,
                List.of(
                        new MatchEventRecord(
                                matchId,
                                1,
                                1,
                                now,
                                actor,
                                action,
                                new RuleEvent("discarded", new byte[] {1}),
                                before,
                                after),
                        new MatchEventRecord(
                                matchId,
                                2,
                                1,
                                now,
                                actor,
                                action,
                                new RuleEvent(
                                        "turn-advanced",
                                        new byte[] {(byte) (mutateSecondEvent ? 9 : 2)}),
                                before,
                                after)),
                Optional.empty());
    }

    private static RuleStateSnapshot snapshot(long sequence, int state) {
        return new RuleStateSnapshot(
                1,
                sequence,
                ByteBuffer.allocate(4).putInt(state).array(),
                String.format("%064x", state));
    }
}
