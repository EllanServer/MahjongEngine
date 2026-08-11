package top.ellan.mahjong.application.lobby.usecase;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.application.lobby.command.LobbyCommand;
import top.ellan.mahjong.application.lobby.runtime.HostedLobby;
import top.ellan.mahjong.application.lobby.runtime.LobbyTableDirectory;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.SeatId;

/** Small platform-neutral facade for command, UI, and reconnect use cases. */
public final class LobbyUseCases {
    private final LobbyTableDirectory lobbies;

    public LobbyUseCases(LobbyTableDirectory lobbies) {
        this.lobbies = Objects.requireNonNull(lobbies, "lobbies");
    }

    public CompletionStage<TableActionResult> join(
            TableId tableId, SeatId seatId, PlayerId actor) {
        return command(tableId, new LobbyCommand.JoinSeat(actor, seatId));
    }

    public CompletionStage<TableActionResult> leave(PlayerId actor) {
        HostedLobby hosted = lobbies.findByPlayer(actor).orElse(null);
        return hosted == null
                ? rejected("not-in-lobby")
                : hosted.actor().command(new LobbyCommand.Leave(actor));
    }

    public CompletionStage<TableActionResult> spectate(TableId tableId, PlayerId actor) {
        return command(tableId, new LobbyCommand.Spectate(actor));
    }

    public CompletionStage<TableActionResult> unspectate(PlayerId actor) {
        HostedLobby hosted = lobbies.findByPlayer(actor).orElse(null);
        return hosted == null
                ? rejected("not-in-lobby")
                : hosted.actor().command(new LobbyCommand.Unspectate(actor));
    }

    public CompletionStage<TableActionResult> toggleReady(PlayerId actor) {
        HostedLobby hosted = lobbies.findByPlayer(actor).orElse(null);
        return hosted == null
                ? rejected("not-in-lobby")
                : hosted.actor().command(new LobbyCommand.ToggleReady(actor));
    }

    public CompletionStage<TableActionResult> transferOwner(
            PlayerId actor, SeatId targetSeat) {
        HostedLobby hosted = lobbies.findByPlayer(actor).orElse(null);
        return hosted == null
                ? rejected("not-in-lobby")
                : hosted.actor().command(new LobbyCommand.TransferOwner(actor, targetSeat));
    }

    public CompletionStage<TableActionResult> addBot(PlayerId actor, SeatId seatId) {
        HostedLobby hosted = lobbies.findByPlayer(actor).orElse(null);
        return hosted == null
                ? rejected("not-in-lobby")
                : hosted.actor().command(new LobbyCommand.AddBot(actor, seatId));
    }

    public CompletionStage<TableActionResult> removeBot(PlayerId actor, SeatId seatId) {
        HostedLobby hosted = lobbies.findByPlayer(actor).orElse(null);
        return hosted == null
                ? rejected("not-in-lobby")
                : hosted.actor().command(new LobbyCommand.RemoveBot(actor, seatId));
    }

    public CompletionStage<TableActionResult> start(PlayerId actor) {
        HostedLobby hosted = lobbies.findByPlayer(actor).orElse(null);
        return hosted == null
                ? rejected("not-in-lobby")
                : hosted.actor().command(new LobbyCommand.Start(actor));
    }

    public CompletionStage<TableActionResult> changeRules(
            PlayerId actor,
            RuleId ruleId,
            ProfileId profileId,
            Map<String, String> configuration) {
        HostedLobby hosted = lobbies.findByPlayer(actor).orElse(null);
        return hosted == null
                ? rejected("not-in-lobby")
                : hosted.actor()
                        .command(
                                new LobbyCommand.ChangeRules(
                                        actor, ruleId, profileId, configuration));
    }

    private CompletionStage<TableActionResult> command(TableId tableId, LobbyCommand command) {
        HostedLobby hosted = lobbies.find(tableId).orElse(null);
        return hosted == null ? rejected("unknown-lobby") : hosted.actor().command(command);
    }

    private static CompletionStage<TableActionResult> rejected(String reason) {
        return CompletableFuture.completedFuture(
                new TableActionResult(TableActionCode.REJECTED_BY_RULES, 0, reason));
    }
}
