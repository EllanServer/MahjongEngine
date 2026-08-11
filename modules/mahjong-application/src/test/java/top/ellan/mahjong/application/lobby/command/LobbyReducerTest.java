package top.ellan.mahjong.application.lobby.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.domain.lobby.LobbyPhase;
import top.ellan.mahjong.domain.lobby.SeatPresence;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.domain.table.ParticipantRole;
import top.ellan.mahjong.domain.lobby.TableLobby;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.SeatId;

class LobbyReducerTest {
    private final LobbyReducer reducer = new LobbyReducer();

    @Test
    void fourPlayersJoinReadyAndOnlyTheOwnerCanStart() {
        TableLobby state = lobby();
        for (int index = 0; index < 4; index++) {
            state = apply(state, new LobbyCommand.JoinSeat(player(index + 1), new SeatId(index)));
            state = apply(state, new LobbyCommand.ToggleReady(player(index + 1)));
        }

        LobbyReduction rejected = reducer.apply(state, new LobbyCommand.Start(player(2)));
        LobbyReduction accepted = reducer.apply(state, new LobbyCommand.Start(player(1)));

        assertFalse(rejected.accepted());
        assertTrue(accepted.accepted());
        assertTrue(accepted.startRequested());
        assertEquals(LobbyPhase.STARTING, accepted.state().phase());
    }

    @Test
    void disconnectClearsReadinessWithoutVacatingThePhysicalSeat() {
        TableLobby state = apply(lobby(), new LobbyCommand.JoinSeat(player(1), new SeatId(0)));
        state = apply(state, new LobbyCommand.ToggleReady(player(1)));

        state =
                apply(
                        state,
                        new LobbyCommand.SetPresence(player(1), SeatPresence.OFFLINE));

        assertEquals(player(1), state.seats().getFirst().occupant().orElseThrow());
        assertFalse(state.seats().getFirst().ready());
        assertEquals(SeatPresence.OFFLINE, state.seats().getFirst().presence());
    }

    @Test
    void changingRuleModeKeepsSeatsAndResetsEveryReadyFlag() {
        TableLobby state = apply(lobby(), new LobbyCommand.JoinSeat(player(1), new SeatId(0)));
        state = apply(state, new LobbyCommand.ToggleReady(player(1)));

        state =
                apply(
                        state,
                        new LobbyCommand.ChangeRules(
                                player(1),
                                new RuleId("mcr"),
                                new ProfileId("green-book"),
                                Map.of()));

        assertEquals(new RuleId("mcr"), state.ruleId());
        assertEquals(player(1), state.seats().getFirst().occupant().orElseThrow());
        assertFalse(state.seats().getFirst().ready());
    }

    @Test
    void leavingOwnerTransfersControlToTheFirstRemainingSeat() {
        TableLobby state = lobby();
        state = apply(state, new LobbyCommand.JoinSeat(player(1), new SeatId(0)));
        state = apply(state, new LobbyCommand.JoinSeat(player(3), new SeatId(2)));
        state = apply(state, new LobbyCommand.JoinSeat(player(2), new SeatId(1)));

        LobbyReduction reduction = reducer.apply(state, new LobbyCommand.Leave(player(1)));

        assertTrue(reduction.accepted(), reduction.reasonCode());
        assertEquals("seat-left-owner-transferred", reduction.reasonCode());
        assertEquals(player(2), reduction.state().ownerId());
        assertTrue(reduction.state().seatOf(player(1)).isEmpty());
        LobbyReduction ownerAction =
                reducer.apply(
                        reduction.state(),
                        new LobbyCommand.ChangeRules(
                                player(2),
                                new RuleId("mcr"),
                                new ProfileId("green-book"),
                                Map.of()));
        assertTrue(ownerAction.accepted(), ownerAction.reasonCode());
    }

    @Test
    void leavingNonOwnerDoesNotChangeLobbyOwnership() {
        TableLobby state = lobby();
        state = apply(state, new LobbyCommand.JoinSeat(player(1), new SeatId(0)));
        state = apply(state, new LobbyCommand.JoinSeat(player(2), new SeatId(1)));

        state = apply(state, new LobbyCommand.Leave(player(2)));

        assertEquals(player(1), state.ownerId());
    }

    @Test
    void ownerCanExplicitlyTransferToAnOnlineHumanButNeverToABot() {
        TableLobby state = lobby();
        state = apply(state, new LobbyCommand.JoinSeat(player(1), new SeatId(0)));
        state = apply(state, new LobbyCommand.JoinSeat(player(2), new SeatId(1)));
        state = apply(state, new LobbyCommand.AddBot(player(1), new SeatId(2)));

        LobbyReduction botRejected =
                reducer.apply(state, new LobbyCommand.TransferOwner(player(1), new SeatId(2)));
        assertFalse(botRejected.accepted());
        assertEquals("target-human-required", botRejected.reasonCode());

        state = apply(state, new LobbyCommand.TransferOwner(player(1), new SeatId(1)));
        assertEquals(player(2), state.ownerId());
        LobbyReduction formerOwner = reducer.apply(
                state,
                new LobbyCommand.ChangeRules(
                        player(1),
                        new RuleId("mcr"),
                        new ProfileId("green-book"),
                        Map.of()));
        assertFalse(formerOwner.accepted());
        assertEquals("owner-required", formerOwner.reasonCode());
    }

    @Test
    void ownerCanFillAndRemoveSeatsWithRuleNeutralReadyBots() {
        TableLobby state = apply(lobby(), new LobbyCommand.JoinSeat(player(1), new SeatId(0)));
        for (int index = 1; index < 4; index++) {
            state = apply(state, new LobbyCommand.AddBot(player(1), new SeatId(index)));
        }

        assertEquals(3, state.matchParticipants().stream()
                .filter(participant -> participant.role() == ParticipantRole.BOT)
                .count());
        assertTrue(state.seats().get(1).ready());
        state = apply(state, new LobbyCommand.RemoveBot(player(1), new SeatId(2)));
        assertTrue(state.seats().get(2).occupant().isEmpty());
    }

    @Test
    void ruleChangesAndRecoveryKeepBotsOnlineAndReady() {
        TableLobby state = apply(lobby(), new LobbyCommand.JoinSeat(player(1), new SeatId(0)));
        state = apply(state, new LobbyCommand.AddBot(player(1), new SeatId(1)));
        state = apply(state, new LobbyCommand.ChangeRules(
                player(1), new RuleId("sichuan"), new ProfileId("t-tfmj-01-2024"), Map.of()));

        assertTrue(state.seats().get(1).ready());
        assertEquals(SeatPresence.ONLINE, state.seats().get(1).presence());
        TableLobby recovered = state.recoveredOffline();
        assertTrue(recovered.seats().get(1).ready());
        assertEquals(SeatPresence.ONLINE, recovered.seats().get(1).presence());
        assertEquals(SeatPresence.OFFLINE, recovered.seats().getFirst().presence());
    }

    private TableLobby apply(TableLobby state, LobbyCommand command) {
        LobbyReduction reduction = reducer.apply(state, command);
        assertTrue(reduction.accepted(), reduction.reasonCode());
        return reduction.state();
    }

    private static TableLobby lobby() {
        return TableLobby.create(
                TableId.random(),
                player(1),
                new RuleId("riichi"),
                new ProfileId("mahjong-soul"),
                Map.of(),
                4,
                Instant.EPOCH);
    }

    private static PlayerId player(int suffix) {
        return new PlayerId(new UUID(0, suffix));
    }
}
