package top.ellan.mahjong.application.lobby.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.application.lobby.command.LobbyCommand;
import top.ellan.mahjong.application.lobby.command.LobbyReducer;
import top.ellan.mahjong.domain.lobby.TableLobby;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.spi.ActionPlacement;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.SeatId;

class LobbyProjectionFactoryTest {
    @Test
    void ownerReceivesOneRevisionBoundTransferActionPerEligibleHumanSeat() {
        PlayerId owner = player(1);
        PlayerId successor = player(2);
        TableLobby state = TableLobby.create(
                TableId.random(),
                owner,
                new RuleId("riichi"),
                new ProfileId("mahjong-soul"),
                Map.of(),
                4,
                Instant.EPOCH);
        LobbyReducer reducer = new LobbyReducer();
        state = reducer.apply(state, new LobbyCommand.JoinSeat(owner, new SeatId(0))).state();
        state = reducer.apply(state, new LobbyCommand.JoinSeat(successor, new SeatId(1))).state();
        AtomicLong tokens = new AtomicLong();
        LobbyProjectionFrame frame = new LobbyProjectionFactory(
                        (actor, revision) ->
                                new ActionToken(
                                        new UUID(0, tokens.incrementAndGet()), actor, revision))
                .create(state);

        var transfer = frame.projection().authorizedActions().get(owner).stream()
                .filter(action -> action.legalAction().key().startsWith("lobby.transfer_owner:"))
                .toList();

        assertEquals(1, transfer.size());
        assertEquals(
                "action.transfer_owner_south",
                transfer.getFirst().legalAction().actionPresentation().labelKey());
        LobbyCommand.TransferOwner command = assertInstanceOf(
                LobbyCommand.TransferOwner.class,
                frame.actionCatalog().get(transfer.getFirst().token().value()));
        assertEquals(new SeatId(1), command.targetSeat());
        assertTrue(frame.projection().authorizedActions().get(successor).stream()
                .noneMatch(action ->
                        action.legalAction().key().startsWith("lobby.transfer_owner:")));
    }

    @Test
    void readyAndLeaveShareOneSideBySideActionRow() {
        PlayerId owner = player(10);
        TableLobby state = TableLobby.create(
                TableId.random(),
                owner,
                new RuleId("riichi"),
                new ProfileId("mahjong-soul"),
                Map.of(),
                4,
                Instant.EPOCH);
        state = new LobbyReducer()
                .apply(state, new LobbyCommand.JoinSeat(owner, new SeatId(0)))
                .state();
        AtomicLong tokens = new AtomicLong();

        var actions = new LobbyProjectionFactory(
                        (actor, revision) ->
                                new ActionToken(new UUID(0, tokens.incrementAndGet()), actor, revision))
                .create(state)
                .projection()
                .authorizedActions()
                .get(owner);

        assertEquals(
                List.of("lobby.ready", "lobby.leave"),
                actions.stream().map(action -> action.legalAction().key()).toList());
        assertTrue(actions.stream()
                .allMatch(action ->
                        action.legalAction().actionPresentation().placement()
                                == ActionPlacement.ACTION_ROW));
    }

    private static PlayerId player(long suffix) {
        return new PlayerId(new UUID(0, suffix));
    }
}
