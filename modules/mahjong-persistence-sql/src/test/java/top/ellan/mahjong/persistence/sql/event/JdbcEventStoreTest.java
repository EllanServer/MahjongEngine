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
import top.ellan.mahjong.domain.MatchBinding;
import top.ellan.mahjong.domain.MatchId;
import top.ellan.mahjong.domain.ParticipantRole;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.domain.TableLifecycle;
import top.ellan.mahjong.domain.TableParticipant;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RuleEvent;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackRef;
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
