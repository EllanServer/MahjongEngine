package top.ellan.mahjong.application.lobby.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.application.SecureActionTokenIssuer;
import top.ellan.mahjong.application.TableActionCode;
import top.ellan.mahjong.application.TableActorConfig;
import top.ellan.mahjong.application.TableProjection;
import top.ellan.mahjong.application.lobby.command.LobbyCommand;
import top.ellan.mahjong.application.lobby.command.LobbyReducer;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.domain.TableLobby;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.SeatId;

class LobbyTableActorTest {
    @Test
    void boundedSingleWriterPublishesOnlyImmutableLatestFrames() {
        List<TableProjection> frames = new ArrayList<>();
        List<TableLobby> observed = new ArrayList<>();
        AtomicInteger starts = new AtomicInteger();
        LobbyTableActor actor =
                new LobbyTableActor(
                        Runnable::run,
                        frames::add,
                        new SecureActionTokenIssuer(),
                        new LobbyReducer(),
                        observed::add,
                        (state, source) -> starts.incrementAndGet(),
                        new TableActorConfig(8, 8, 16),
                        lobby());

        assertEquals(
                TableActionCode.TABLE_BLOCKED,
                actor.command(new LobbyCommand.JoinSeat(player(1), new SeatId(0)))
                        .toCompletableFuture()
                        .join()
                        .code());
        actor.start();
        for (int index = 0; index < 4; index++) {
            actor.command(new LobbyCommand.JoinSeat(player(index + 1), new SeatId(index)))
                    .toCompletableFuture()
                    .join();
            actor.command(new LobbyCommand.ToggleReady(player(index + 1)))
                    .toCompletableFuture()
                    .join();
        }
        actor.command(new LobbyCommand.Start(player(1))).toCompletableFuture().join();

        assertEquals(1, starts.get());
        assertEquals(actor.state().revision() + 1, frames.size());
        assertEquals(actor.state().revision(), observed.getLast().revision());
        assertTrue(actor.latestProjection().orElseThrow().authorizedActions().values().stream()
                .allMatch(List::isEmpty));
        assertFalse(actor.state().readyToStart());
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
