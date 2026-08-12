package top.ellan.mahjong.persistence.sql.lobby;

import top.ellan.mahjong.persistence.sql.common.PersistenceConflictException;
import top.ellan.mahjong.persistence.sql.connection.SqlConnectionFactory;
import top.ellan.mahjong.persistence.sql.match.JdbcMatchRepository;
import top.ellan.mahjong.persistence.sql.match.MatchInstanceRecord;
import top.ellan.mahjong.persistence.sql.schema.SqlSchemaMigrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.domain.lobby.LobbyPhase;
import top.ellan.mahjong.domain.lobby.LobbySeat;
import top.ellan.mahjong.domain.match.MatchBinding;
import top.ellan.mahjong.domain.match.MatchId;
import top.ellan.mahjong.domain.table.TableAnchor;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.domain.table.TableLifecycle;
import top.ellan.mahjong.domain.lobby.TableLobby;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackRef;
import top.ellan.mahjong.spi.RuleStateSnapshot;
import top.ellan.mahjong.spi.SeatId;

class JdbcTableLobbyRepositoryTest {
    private JdbcTableLobbyRepository lobbies;
    private JdbcMatchRepository matches;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        SqlConnectionFactory connections = () -> DriverManager.getConnection(url, "sa", "");
        new SqlSchemaMigrator(connections).migrate();
        lobbies = new JdbcTableLobbyRepository(connections);
        matches = new JdbcMatchRepository(connections);
    }

    @Test
    void lobbyRoundTripUsesCanonicalBinaryConfigurationAndRecoversSeatsOffline()
            throws Exception {
        TableLobby lobby = readyLobby();
        TableAnchor anchor = anchor(lobby.tableId());

        lobbies.create(lobby, anchor);
        TableLobby recovered = lobbies.find(lobby.tableId()).orElseThrow();

        assertEquals(lobby.configuration(), recovered.configuration());
        assertEquals(lobby.occupiedSeatCount(), recovered.occupiedSeatCount());
        assertFalse(recovered.readyToStart());
        recovered.seats().forEach(seat -> assertFalse(seat.ready()));
    }

    @Test
    void matchActivationRetainsReusableLobbyShellInTheSameTransaction() throws Exception {
        TableLobby lobby = readyLobby();
        TableAnchor anchor = anchor(lobby.tableId());
        lobbies.create(lobby, anchor);
        MatchInstanceRecord match = match(lobby.tableId());

        matches.createRecoverableMatchFromLobby(
                match, lobby.matchParticipants(), snapshot(), anchor);

        TableLobby reusable = lobbies.find(lobby.tableId()).orElseThrow();
        assertEquals(lobby.ownerId(), reusable.ownerId());
        assertEquals(lobby.configuration(), reusable.configuration());
        assertFalse(reusable.readyToStart());
        assertTrue(matches.find(match.binding().matchId()).isPresent());
    }

    @Test
    void missingLobbyRollsBackTheEntireInitialMatchBoundary() {
        TableId tableId = TableId.random();
        MatchInstanceRecord match = match(tableId);

        assertThrows(
                PersistenceConflictException.class,
                () ->
                        matches.createRecoverableMatchFromLobby(
                                match, java.util.List.of(), snapshot(), anchor(tableId)));
        assertTrue(
                org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                                () -> matches.find(match.binding().matchId()))
                        .isEmpty());
    }

    @Test
    void completedMatchResetsReusableLobbyInOneRepositoryOperation() throws Exception {
        TableLobby lobby = readyLobby();
        lobbies.create(lobby, anchor(lobby.tableId()));

        TableLobby reusable =
                lobbies.resetForReuse(
                                lobby.tableId(),
                                Set.of(player(1)),
                                Instant.parse("2026-08-09T00:00:02Z"))
                        .orElseThrow();

        assertEquals(player(2), reusable.ownerId());
        assertTrue(reusable.seats().getFirst().occupant().isEmpty());
        assertFalse(reusable.readyToStart());
        assertEquals(reusable, lobbies.find(lobby.tableId()).orElseThrow());
    }

    @Test
    void recoverableMatchProbeIsScopedToOneTableAndIgnoresTerminalMatches()
            throws Exception {
        TableLobby lobby = readyLobby();
        TableId tableId = lobby.tableId();
        lobbies.create(lobby, anchor(tableId));
        MatchInstanceRecord match = match(tableId);
        matches.createRecoverableMatchFromLobby(
                match, lobby.matchParticipants(), snapshot(), anchor(tableId));

        assertTrue(matches.hasRecoverableMatch(tableId));
        assertFalse(matches.hasRecoverableMatch(TableId.random()));

        matches.updateStatus(
                match.binding().matchId(),
                TableLifecycle.FINISHED,
                Instant.parse("2026-08-09T00:00:03Z"));
        assertFalse(matches.hasRecoverableMatch(tableId));
    }

    private static TableLobby readyLobby() {
        ArrayList<LobbySeat> seats = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            seats.add(
                    LobbySeat.empty(new SeatId(index))
                            .occupiedBy(player(index + 1))
                            .withReady(true));
        }
        return new TableLobby(
                TableId.random(),
                8,
                player(1),
                new RuleId("riichi"),
                new ProfileId("mahjong-soul"),
                Map.of("a", "一", "z", "last"),
                seats,
                Set.of(player(9)),
                LobbyPhase.WAITING,
                Instant.parse("2026-08-09T00:00:00Z"));
    }

    private static MatchInstanceRecord match(TableId tableId) {
        Instant now = Instant.parse("2026-08-09T00:00:01Z");
        MatchBinding binding =
                new MatchBinding(
                        MatchId.random(),
                        new RulePackRef(
                                new RuleId("riichi"), "1.0.0", "a".repeat(64), 1),
                        new ProfileId("mahjong-soul"),
                        "b".repeat(64),
                        now);
        return new MatchInstanceRecord(binding, tableId, TableLifecycle.ACTIVE, now, 0);
    }

    private static RuleStateSnapshot snapshot() {
        return new RuleStateSnapshot(
                1,
                0,
                new byte[0],
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    private static TableAnchor anchor(TableId tableId) {
        return new TableAnchor(tableId, UUID.randomUUID().toString(), 0.5, 64, 0.5, 0, 0);
    }

    private static PlayerId player(int suffix) {
        return new PlayerId(new UUID(0, suffix));
    }
}
