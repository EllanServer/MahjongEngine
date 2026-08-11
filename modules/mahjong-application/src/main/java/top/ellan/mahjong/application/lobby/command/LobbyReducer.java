package top.ellan.mahjong.application.lobby.command;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import top.ellan.mahjong.domain.lobby.LobbyPhase;
import top.ellan.mahjong.domain.lobby.LobbyBotIdentity;
import top.ellan.mahjong.domain.lobby.LobbySeat;
import top.ellan.mahjong.domain.lobby.SeatPresence;
import top.ellan.mahjong.domain.lobby.TableLobby;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.SeatId;

/** Pure, allocation-bounded lobby transition rules. */
public final class LobbyReducer {
    public LobbyReduction apply(TableLobby state, LobbyCommand command) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(command, "command");
        if (state.phase() == LobbyPhase.CLOSED) {
            return LobbyReduction.rejected(state, "lobby-closed");
        }
        if (state.phase() == LobbyPhase.STARTING
                && !(command instanceof LobbyCommand.SetPresence)) {
            return LobbyReduction.rejected(state, "lobby-starting");
        }
        return switch (command) {
            case LobbyCommand.JoinSeat join -> join(state, join);
            case LobbyCommand.Leave leave -> leave(state, leave.actor());
            case LobbyCommand.Spectate spectate -> spectate(state, spectate.actor());
            case LobbyCommand.Unspectate unspectate -> unspectate(state, unspectate.actor());
            case LobbyCommand.ToggleReady ready -> toggleReady(state, ready.actor());
            case LobbyCommand.TransferOwner transfer -> transferOwner(state, transfer);
            case LobbyCommand.AddBot bot -> addBot(state, bot);
            case LobbyCommand.RemoveBot bot -> removeBot(state, bot);
            case LobbyCommand.Start start -> start(state, start.actor());
            case LobbyCommand.ChangeRules rules -> changeRules(state, rules);
            case LobbyCommand.SetPresence presence -> setPresence(state, presence);
        };
    }

    private static LobbyReduction join(TableLobby state, LobbyCommand.JoinSeat command) {
        int seatIndex = command.seatId().value();
        if (seatIndex >= state.seats().size()) {
            return LobbyReduction.rejected(state, "seat-out-of-range");
        }
        Optional<SeatId> currentSeat = state.seatOf(command.actor());
        if (currentSeat.isPresent()) {
            if (!currentSeat.orElseThrow().equals(command.seatId())) {
                return LobbyReduction.rejected(state, "already-seated");
            }
            LobbySeat current = state.seats().get(seatIndex);
            if (current.presence() == SeatPresence.ONLINE) {
                return LobbyReduction.accepted(state, false, false, "seat-restored");
            }
            ArrayList<LobbySeat> seats = new ArrayList<>(state.seats());
            seats.set(seatIndex, current.withPresence(SeatPresence.ONLINE));
            return changed(state, seats, state.spectators(), "seat-restored");
        }
        LobbySeat requested = state.seats().get(seatIndex);
        if (requested.occupant().isPresent()) {
            return LobbyReduction.rejected(state, "seat-occupied");
        }
        ArrayList<LobbySeat> seats = new ArrayList<>(state.seats());
        seats.set(seatIndex, requested.occupiedBy(command.actor()));
        LinkedHashSet<PlayerId> spectators = new LinkedHashSet<>(state.spectators());
        spectators.remove(command.actor());
        return changed(state, seats, spectators, "seat-joined");
    }

    private static LobbyReduction leave(TableLobby state, PlayerId actor) {
        Optional<SeatId> seat = state.seatOf(actor);
        if (seat.isPresent()) {
            ArrayList<LobbySeat> seats = new ArrayList<>(state.seats());
            int index = seat.orElseThrow().value();
            seats.set(index, seats.get(index).vacated());
            if (state.ownerId().equals(actor)) {
                Optional<PlayerId> successor = firstSeatedHuman(state, seats);
                if (successor.isPresent()) {
                    return changed(
                            state,
                            successor.orElseThrow(),
                            seats,
                            state.spectators(),
                            "seat-left-owner-transferred");
                }
            }
            return changed(state, seats, state.spectators(), "seat-left");
        }
        if (state.spectators().contains(actor)) {
            LinkedHashSet<PlayerId> spectators = new LinkedHashSet<>(state.spectators());
            spectators.remove(actor);
            return changed(state, state.seats(), spectators, "spectator-left");
        }
        return LobbyReduction.rejected(state, "not-in-lobby");
    }

    private static LobbyReduction spectate(TableLobby state, PlayerId actor) {
        if (state.seatOf(actor).isPresent()) {
            return LobbyReduction.rejected(state, "already-seated");
        }
        if (state.spectators().contains(actor)) {
            return LobbyReduction.accepted(state, false, false, "already-spectating");
        }
        LinkedHashSet<PlayerId> spectators = new LinkedHashSet<>(state.spectators());
        spectators.add(actor);
        return changed(state, state.seats(), spectators, "spectating");
    }

    private static LobbyReduction unspectate(TableLobby state, PlayerId actor) {
        if (!state.spectators().contains(actor)) {
            return LobbyReduction.rejected(state, "not-spectating");
        }
        LinkedHashSet<PlayerId> spectators = new LinkedHashSet<>(state.spectators());
        spectators.remove(actor);
        return changed(state, state.seats(), spectators, "spectator-left");
    }

    private static LobbyReduction toggleReady(TableLobby state, PlayerId actor) {
        Optional<SeatId> seat = state.seatOf(actor);
        if (seat.isEmpty()) {
            return LobbyReduction.rejected(state, "not-seated");
        }
        int index = seat.orElseThrow().value();
        LobbySeat current = state.seats().get(index);
        if (state.isBotSeat(current)) {
            return LobbyReduction.rejected(state, "bot-always-ready");
        }
        if (current.presence() != SeatPresence.ONLINE) {
            return LobbyReduction.rejected(state, "seat-offline");
        }
        ArrayList<LobbySeat> seats = new ArrayList<>(state.seats());
        seats.set(index, current.withReady(!current.ready()));
        return changed(state, seats, state.spectators(), current.ready() ? "unready" : "ready");
    }

    private static LobbyReduction transferOwner(
            TableLobby state, LobbyCommand.TransferOwner command) {
        if (!state.ownerId().equals(command.actor())) {
            return LobbyReduction.rejected(state, "owner-required");
        }
        int index = command.targetSeat().value();
        if (index >= state.seats().size()) {
            return LobbyReduction.rejected(state, "seat-out-of-range");
        }
        LobbySeat target = state.seats().get(index);
        if (target.occupant().isEmpty()) {
            return LobbyReduction.rejected(state, "target-seat-empty");
        }
        if (state.isBotSeat(target)) {
            return LobbyReduction.rejected(state, "target-human-required");
        }
        if (target.presence() != SeatPresence.ONLINE) {
            return LobbyReduction.rejected(state, "target-offline");
        }
        PlayerId nextOwner = target.occupant().orElseThrow();
        if (nextOwner.equals(command.actor())) {
            return LobbyReduction.accepted(state, false, false, "owner-unchanged");
        }
        return changed(
                state,
                nextOwner,
                state.seats(),
                state.spectators(),
                "owner-transferred");
    }

    private static LobbyReduction addBot(TableLobby state, LobbyCommand.AddBot command) {
        if (!state.ownerId().equals(command.actor())) {
            return LobbyReduction.rejected(state, "owner-required");
        }
        int index = command.seatId().value();
        if (index >= state.seats().size()) {
            return LobbyReduction.rejected(state, "seat-out-of-range");
        }
        LobbySeat current = state.seats().get(index);
        if (current.occupant().isPresent()) {
            return LobbyReduction.rejected(state, "seat-occupied");
        }
        ArrayList<LobbySeat> seats = new ArrayList<>(state.seats());
        seats.set(index, current.occupiedByReadyBot(
                LobbyBotIdentity.forSeat(state.tableId(), command.seatId())));
        return changed(state, seats, state.spectators(), "bot-added");
    }

    private static LobbyReduction removeBot(
            TableLobby state, LobbyCommand.RemoveBot command) {
        if (!state.ownerId().equals(command.actor())) {
            return LobbyReduction.rejected(state, "owner-required");
        }
        int index = command.seatId().value();
        if (index >= state.seats().size()) {
            return LobbyReduction.rejected(state, "seat-out-of-range");
        }
        LobbySeat current = state.seats().get(index);
        if (!state.isBotSeat(current)) {
            return LobbyReduction.rejected(state, "seat-is-not-bot");
        }
        ArrayList<LobbySeat> seats = new ArrayList<>(state.seats());
        seats.set(index, current.vacated());
        return changed(state, seats, state.spectators(), "bot-removed");
    }

    private static LobbyReduction start(TableLobby state, PlayerId actor) {
        if (!state.ownerId().equals(actor)) {
            return LobbyReduction.rejected(state, "owner-required");
        }
        if (!state.readyToStart()) {
            return LobbyReduction.rejected(state, "not-all-ready");
        }
        TableLobby starting =
                state.withState(state.seats(), state.spectators(), LobbyPhase.STARTING);
        return LobbyReduction.accepted(starting, true, true, "match-starting");
    }

    private static LobbyReduction changeRules(
            TableLobby state, LobbyCommand.ChangeRules command) {
        if (!state.ownerId().equals(command.actor())) {
            return LobbyReduction.rejected(state, "owner-required");
        }
        TableLobby updated =
                state.withRules(
                        command.ruleId(), command.profileId(), command.configuration());
        return LobbyReduction.accepted(updated, true, false, "rules-changed");
    }

    private static LobbyReduction setPresence(
            TableLobby state, LobbyCommand.SetPresence command) {
        Optional<SeatId> seat = state.seatOf(command.actor());
        if (seat.isEmpty()) {
            return LobbyReduction.accepted(state, false, false, "presence-ignored");
        }
        int index = seat.orElseThrow().value();
        LobbySeat current = state.seats().get(index);
        if (current.presence() == command.presence()) {
            return LobbyReduction.accepted(state, false, false, "presence-unchanged");
        }
        ArrayList<LobbySeat> seats = new ArrayList<>(state.seats());
        seats.set(index, current.withPresence(command.presence()));
        return changed(
                state,
                seats,
                state.spectators(),
                state.phase(),
                "presence-changed");
    }

    private static LobbyReduction changed(
            TableLobby state,
            java.util.List<LobbySeat> seats,
            Set<PlayerId> spectators,
            String reason) {
        return changed(state, seats, spectators, LobbyPhase.WAITING, reason);
    }

    private static LobbyReduction changed(
            TableLobby state,
            java.util.List<LobbySeat> seats,
            Set<PlayerId> spectators,
            LobbyPhase phase,
            String reason) {
        return LobbyReduction.accepted(
                state.withState(seats, spectators, phase),
                true,
                false,
                reason);
    }

    private static LobbyReduction changed(
            TableLobby state,
            PlayerId ownerId,
            java.util.List<LobbySeat> seats,
            Set<PlayerId> spectators,
            String reason) {
        return LobbyReduction.accepted(
                state.withOwnerAndState(ownerId, seats, spectators, LobbyPhase.WAITING),
                true,
                false,
                reason);
    }

    private static Optional<PlayerId> firstSeatedHuman(
            TableLobby state, java.util.List<LobbySeat> seats) {
        for (LobbySeat seat : seats) {
            if (seat.occupant().isPresent() && !state.isBotSeat(seat)) {
                return seat.occupant();
            }
        }
        return Optional.empty();
    }
}
