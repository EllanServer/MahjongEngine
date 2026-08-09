package top.ellan.mahjong.persistence.sql;

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
import top.ellan.mahjong.domain.LobbyPhase;
import top.ellan.mahjong.domain.LobbySeat;
import top.ellan.mahjong.domain.MatchBinding;
import top.ellan.mahjong.domain.MatchId;
import top.ellan.mahjong.domain.TableAnchor;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.domain.TableLifecycle;
import top.ellan.mahjong.domain.TableLobby;
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
    void matchActivationConsumesLobbyInTheSameTransaction() throws Exception {
        TableLobby lobby = readyLobby();
        TableAnchor anchor = anchor(lobby.tableId());
        lobbies.create(lobby, anchor);
        MatchInstanceRecord match = match(lobby.tableId());

        matches.createRecoverableMatchFromLobby(
                match, lobby.matchParticipants(), snapshot(), anchor);

        assertTrue(lobbies.find(lobby.tableId()).isEmpty());
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
