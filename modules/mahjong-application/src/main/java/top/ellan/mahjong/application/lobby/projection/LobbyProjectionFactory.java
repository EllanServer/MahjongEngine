package top.ellan.mahjong.application.lobby.projection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import top.ellan.mahjong.application.security.ActionTokenIssuer;
import top.ellan.mahjong.application.projection.TableProjection;
import top.ellan.mahjong.application.lobby.command.LobbyCommand;
import top.ellan.mahjong.domain.lobby.LobbyPhase;
import top.ellan.mahjong.domain.lobby.LobbySeat;
import top.ellan.mahjong.domain.table.TableLifecycle;
import top.ellan.mahjong.domain.lobby.TableLobby;
import top.ellan.mahjong.spi.ActionPresentation;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.AuthorizedAction;
import top.ellan.mahjong.spi.LegalAction;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.PrivateRuleView;
import top.ellan.mahjong.spi.PublicRuleView;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RuleTablePresentation;
import top.ellan.mahjong.spi.RuleWallDirection;
import top.ellan.mahjong.spi.RuleWallPresentation;

/** Stateless conversion from lobby domain state to the shared table projection contract. */
public final class LobbyProjectionFactory {
    private final ActionTokenIssuer tokenIssuer;

    public LobbyProjectionFactory(ActionTokenIssuer tokenIssuer) {
        this.tokenIssuer = Objects.requireNonNull(tokenIssuer, "tokenIssuer");
    }

    public LobbyProjectionFrame create(TableLobby state) {
        Objects.requireNonNull(state, "state");
        Map<UUID, LobbyCommand> catalog = new LinkedHashMap<>();
        Map<PlayerId, PrivateRuleView> privateViews = new LinkedHashMap<>();
        Map<PlayerId, List<AuthorizedAction>> actionsByPlayer = new LinkedHashMap<>();
        int ready = 0;
        int bots = 0;
        for (LobbySeat seat : state.seats()) {
            if (seat.ready()) {
                ready++;
            }
            if (seat.occupant().isEmpty()) {
                continue;
            }
            if (state.isBotSeat(seat)) {
                bots++;
                continue;
            }
            PlayerId player = seat.occupant().orElseThrow();
            privateViews.put(
                    player,
                    new PrivateRuleView(
                            state.revision(), player, seat.seatId(), List.of(), Map.of()));
            ArrayList<AuthorizedAction> actions = new ArrayList<>(6);
            if (state.phase() == LobbyPhase.WAITING) {
                authorize(
                        state,
                        catalog,
                        actions,
                        player,
                        new LobbyCommand.ToggleReady(player),
                        "lobby.ready",
                        seat.ready() ? "action.unready" : "action.ready",
                        false,
                        false);
                authorize(
                        state,
                        catalog,
                        actions,
                        player,
                        new LobbyCommand.Leave(player),
                        "lobby.leave",
                        "action.leave",
                        true,
                        false);
                if (state.ownerId().equals(player) && state.readyToStart()) {
                    authorize(
                            state,
                            catalog,
                            actions,
                            player,
                            new LobbyCommand.Start(player),
                            "lobby.start",
                            "action.start",
                            false,
                            true);
                }
                if (state.ownerId().equals(player)) {
                    for (LobbySeat target : state.seats()) {
                        if (target.occupant().isPresent()
                                && !target.occupant().orElseThrow().equals(player)
                                && !state.isBotSeat(target)
                                && target.presence()
                                        == top.ellan.mahjong.domain.lobby.SeatPresence.ONLINE) {
                            authorize(
                                    state,
                                    catalog,
                                    actions,
                                    player,
                                    new LobbyCommand.TransferOwner(player, target.seatId()),
                                    "lobby.transfer_owner:" + target.seatId().value(),
                                    transferLabel(target.seatId().value()),
                                    true,
                                    false);
                        }
                    }
                }
            }
            actionsByPlayer.put(player, List.copyOf(actions));
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("owner", state.ownerId().toString());
        attributes.put("rule", state.ruleId().value());
        attributes.put("profile", state.profileId().value());
        attributes.put("occupied", Integer.toString(state.occupiedSeatCount()));
        attributes.put("ready", Integer.toString(ready));
        attributes.put("bots", Integer.toString(bots));
        attributes.put("spectators", Integer.toString(state.spectators().size()));
        PublicRuleView publicView =
                new PublicRuleView(
                        state.revision(),
                        state.phase().name(),
                        List.of(),
                        attributes,
                        emptyTablePresentation(state.seats().size()));
        return new LobbyProjectionFrame(
                new TableProjection(
                        state.tableId(),
                        state.revision(),
                        state.phase() == LobbyPhase.STARTING
                                ? TableLifecycle.STARTING
                                : TableLifecycle.LOBBY,
                        publicView,
                        privateViews,
                        actionsByPlayer),
                catalog);
    }

    private void authorize(
            TableLobby state,
            Map<UUID, LobbyCommand> catalog,
            List<AuthorizedAction> target,
            PlayerId player,
            LobbyCommand command,
            String actionKey,
            String labelKey,
            boolean secondary,
            boolean emphasized) {
        ActionToken token = tokenIssuer.issue(player, state.revision());
        ActionPresentation presentation =
                secondary
                        ? ActionPresentation.secondaryRow(labelKey)
                        : ActionPresentation.actionRow(labelKey);
        if (emphasized) {
            presentation = presentation.withEmphasis();
        }
        LegalAction legal =
                new LegalAction(
                        actionKey,
                        new RuleAction(actionKey.replace(':', '.'), new byte[0]),
                        presentation);
        if (catalog.put(token.value(), command) != null) {
            throw new IllegalStateException("lobby action token collision");
        }
        target.add(new AuthorizedAction(token, legal));
    }

    private static String transferLabel(int seat) {
        return switch (seat) {
            case 0 -> "action.transfer_owner_east";
            case 1 -> "action.transfer_owner_south";
            case 2 -> "action.transfer_owner_west";
            case 3 -> "action.transfer_owner_north";
            default -> throw new IllegalArgumentException("unsupported lobby seat");
        };
    }

    private static RuleTablePresentation emptyTablePresentation(int seatCount) {
        ArrayList<Integer> wallSides = new ArrayList<>(seatCount);
        for (int index = 0; index < seatCount; index++) {
            wallSides.add(1);
        }
        return new RuleTablePresentation(
                seatCount,
                new RuleWallPresentation(wallSides, 0, RuleWallDirection.CLOCKWISE),
                6,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }
}
