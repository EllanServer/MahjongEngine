package top.ellan.mahjong.application.lobby.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.application.security.SecureActionTokenIssuer;
import top.ellan.mahjong.application.table.TableActorConfig;
import top.ellan.mahjong.application.lobby.actor.LobbyTableActor;
import top.ellan.mahjong.application.lobby.command.LobbyCommand;
import top.ellan.mahjong.application.lobby.command.LobbyReducer;
import top.ellan.mahjong.domain.table.TableAnchor;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.domain.lobby.TableLobby;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.SeatId;

class LobbyTableDirectoryTest {
    @Test
    void reservationsAndActorChangesMaintainConstantTimePlayerLookup() {
        LobbyTableDirectory directory = new LobbyTableDirectory();
        TableLobby initial = lobby();
        assertTrue(directory.reserve(initial.tableId(), initial.ownerId()));
        assertEquals(Set.of(initial.tableId()), directory.tableIds());
        LobbyTableActor actor = actor(initial, directory);
        HostedLobby hosted =
                new HostedLobby(
                        new TableAnchor(
                                initial.tableId(), UUID.randomUUID().toString(), 0, 64, 0, 0, 0),
                        actor);
        directory.completeReservation(hosted);
        assertEquals(Set.of(initial.tableId()), directory.tableIds());
        actor.start();

        actor.command(new LobbyCommand.JoinSeat(player(2), new SeatId(1)))
                .toCompletableFuture()
                .join();

        assertEquals(hosted, directory.findByPlayer(player(2)).orElseThrow());
        assertFalse(directory.reserve(TableId.random(), player(2)));
        assertEquals(hosted, directory.remove(initial.tableId()).orElseThrow());
        assertTrue(directory.findByPlayer(player(2)).isEmpty());
        assertTrue(directory.tableIds().isEmpty());
    }

    private static LobbyTableActor actor(
            TableLobby state, LobbyTableDirectory directory) {
        return new LobbyTableActor(
                Runnable::run,
                ignored -> {},
                new SecureActionTokenIssuer(),
                new LobbyReducer(),
                directory,
                (ignored, actor) -> {},
                new TableActorConfig(8, 8, 16),
                state);
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
