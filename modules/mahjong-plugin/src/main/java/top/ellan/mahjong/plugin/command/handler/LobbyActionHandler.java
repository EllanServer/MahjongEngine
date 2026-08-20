package top.ellan.mahjong.plugin.command.handler;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.ellan.mahjong.application.lobby.runtime.HostedLobby;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.domain.lobby.LobbySeat;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.plugin.command.CommandSupport;
import top.ellan.mahjong.plugin.command.SubcommandHandler;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.SeatId;

/** Player lobby actions; each delegates to the application actor facade. */
public final class LobbyActionHandler implements SubcommandHandler {
    private static final Set<String> NAMES =
            Set.of(
                    "join",
                    "leave",
                    "spectate",
                    "unspectate",
                    "ready",
                    "owner",
                    "transfer",
                    "bot",
                    "addbot",
                    "removebot",
                    "start",
                    "mode");
    private final CommandSupport support;

    public LobbyActionHandler(CommandSupport support) {
        this.support = java.util.Objects.requireNonNull(support, "support");
    }

    @Override
    public Set<String> names() {
        return NAMES;
    }

    @Override
    public void execute(CommandSender sender, String[] arguments) {
        Player player = support.requirePlayer(sender);
        PlayerId actor = new PlayerId(player.getUniqueId());
        String action = arguments[0].toLowerCase(Locale.ROOT);
        java.util.concurrent.CompletionStage<TableActionResult> result =
                switch (action) {
                    case "join" -> join(arguments, actor);
                    case "leave" -> {
                        requireLength(arguments, 1, "/mahjong leave");
                        yield support.runtime().leave(actor);
                    }
                    case "spectate" -> {
                        requireLength(arguments, 2, "/mahjong spectate <table-id>");
                        yield support.runtime()
                                .lobbyUseCases()
                                .spectate(TableId.parse(arguments[1]), actor);
                    }
                    case "unspectate" -> {
                        requireLength(arguments, 1, "/mahjong unspectate");
                        yield support.runtime().unspectate(actor);
                    }
                    case "ready" -> {
                        requireLength(arguments, 1, "/mahjong ready");
                        yield support.runtime().lobbyUseCases().toggleReady(actor);
                    }
                    case "owner", "transfer" -> {
                        requireLength(arguments, 2, "/mahjong owner <seat>");
                        yield support.runtime()
                                .lobbyUseCases()
                                .transferOwner(actor, seat(arguments[1]));
                    }
                    case "bot" -> {
                        requireLength(arguments, 3, "/mahjong bot <add|remove> <seat>");
                        SeatId target = seat(arguments[2]);
                        yield switch (arguments[1].toLowerCase(Locale.ROOT)) {
                            case "add" -> support.runtime().lobbyUseCases().addBot(actor, target);
                            case "remove" -> support.runtime().lobbyUseCases().removeBot(actor, target);
                            default -> throw CommandSupport.usage(
                                    "/mahjong bot <add|remove> <seat>");
                        };
                    }
                    case "addbot" -> bot(arguments, actor, true);
                    case "removebot" -> bot(arguments, actor, false);
                    case "start" -> start(arguments, actor);
                    case "mode" -> {
                        if (arguments.length < 2 || arguments.length > 3) {
                            throw CommandSupport.usage(
                                    "/mahjong mode <riichi|mcr|sichuan|MAJSOUL_HANCHAN|MAJSOUL_TONPUU|GB|SICHUAN> [profile]");
                        }
                        RuleId ruleId = CommandSupport.ruleId(arguments[1]);
                        ProfileId profile =
                                arguments.length == 3
                                        ? new ProfileId(arguments[2].toLowerCase(Locale.ROOT))
                                        : CommandSupport.defaultProfile(ruleId);
                        yield support.runtime()
                                .lobbyUseCases()
                                .changeRules(actor, ruleId, profile, Map.of());
                    }
                    default -> throw CommandSupport.failure(
                            "mahjongpaper.command.unknown_subcommand", "Unknown subcommand.");
                };
        support.complete(
                sender,
                result,
                value -> CommandSupport.message(
                        "mahjongpaper.command.action_result",
                        "%s - revision %s - %s",
                        value.code(),
                        value.revision(),
                        value.reasonCode()));
    }

    @Override
    public List<String> complete(CommandSender sender, String[] arguments) {
        if (arguments.length == 2 && "mode".equalsIgnoreCase(arguments[0])) {
            return CommandSupport.filter(
                    arguments[1],
                    List.of(
                            "MAJSOUL_HANCHAN",
                            "MAJSOUL_TONPUU",
                            "GB",
                            "SICHUAN",
                            "riichi",
                            "mcr",
                            "sichuan"));
        }
        if (arguments.length == 3 && "mode".equalsIgnoreCase(arguments[0])) {
            return CommandSupport.filter(
                    arguments[2], support.profileIds(arguments[1]));
        }
        if (arguments.length == 2
                && ("join".equalsIgnoreCase(arguments[0])
                        || "spectate".equalsIgnoreCase(arguments[0]))) {
            return CommandSupport.filter(arguments[1], support.currentTableIds(sender));
        }
        if (arguments.length == 3 && "join".equalsIgnoreCase(arguments[0])) {
            return CommandSupport.filter(
                    arguments[2], List.of("east", "south", "west", "north"));
        }
        if (arguments.length == 2 && "bot".equalsIgnoreCase(arguments[0])) {
            return CommandSupport.filter(arguments[1], List.of("add", "remove"));
        }
        if (arguments.length == 2
                && ("owner".equalsIgnoreCase(arguments[0])
                        || "transfer".equalsIgnoreCase(arguments[0]))) {
            return CommandSupport.filter(
                    arguments[1], List.of("east", "south", "west", "north"));
        }
        if (arguments.length == 3 && "bot".equalsIgnoreCase(arguments[0])) {
            return CommandSupport.filter(
                    arguments[2], List.of("east", "south", "west", "north"));
        }
        return List.of();
    }

    /**
     * Aligns with the 1.5.0 start contract: an owner whose lobby is fully ready starts it,
     * everyone else toggles their own ready state.
     */
    private CompletionStage<TableActionResult> start(String[] arguments, PlayerId actor) {
        requireLength(arguments, 1, "/mahjong start");
        HostedLobby lobby = support.runtime().lobbyTables().findByPlayer(actor).orElse(null);
        if (lobby != null
                && lobby.state().ownerId().equals(actor)
                && lobby.state().readyToStart()) {
            return support.runtime().lobbyUseCases().start(actor);
        }
        return support.runtime().lobbyUseCases().toggleReady(actor);
    }

    /** Aligns with the 1.5.0 join contract: with no seat argument the first empty seat is selected. */
    private CompletionStage<TableActionResult> join(String[] arguments, PlayerId actor) {
        if (arguments.length == 3) {
            return support.runtime()
                    .lobbyUseCases()
                    .join(TableId.parse(arguments[1]), seat(arguments[2]), actor);
        }
        if (arguments.length == 2) {
            TableId tableId = TableId.parse(arguments[1]);
            HostedLobby lobby =
                    support.runtime()
                            .lobbyTables()
                            .find(tableId)
                            .orElseThrow(
                                    () ->
                                            CommandSupport.failure(
                                                    "mahjongpaper.command.unknown_lobby",
                                                    "Unknown waiting lobby %s.",
                                                    tableId));
            SeatId target =
                    firstEmptySeat(lobby)
                            .orElseThrow(
                                    () ->
                                            CommandSupport.failure(
                                                    "mahjongpaper.command.no_empty_seat",
                                                    "No empty seat is available at table %s.",
                                                    tableId));
            return support.runtime().lobbyUseCases().join(tableId, target, actor);
        }
        throw CommandSupport.usage("/mahjong join <table-id> [seat]");
    }

    /** Aligns with the 1.5.0 bot contract: add fills the first empty seat, remove clears the first bot. */
    private CompletionStage<TableActionResult> bot(
            String[] arguments, PlayerId actor, boolean add) {
        String command = add ? "/mahjong addbot" : "/mahjong removebot";
        requireLength(arguments, 1, command);
        HostedLobby lobby =
                support.runtime()
                        .lobbyTables()
                        .findByPlayer(actor)
                        .orElseThrow(
                                () ->
                                        CommandSupport.failure(
                                                "mahjongpaper.command.not_at_lobby",
                                                "You do not belong to a waiting lobby."));
        SeatId target =
                (add ? firstEmptySeat(lobby) : firstBotSeat(lobby))
                        .orElseThrow(
                                () ->
                                        CommandSupport.failure(
                                                add
                                                        ? "mahjongpaper.command.no_empty_seat"
                                                        : "mahjongpaper.command.no_bot_seat",
                                                add
                                                        ? "No empty seat is available at table %s."
                                                        : "No bot occupies a seat at table %s.",
                                                lobby.tableId()));
        return add
                ? support.runtime().lobbyUseCases().addBot(actor, target)
                : support.runtime().lobbyUseCases().removeBot(actor, target);
    }

    private static Optional<SeatId> firstEmptySeat(HostedLobby lobby) {
        for (LobbySeat seat : lobby.state().seats()) {
            if (seat.occupant().isEmpty()) {
                return Optional.of(seat.seatId());
            }
        }
        return Optional.empty();
    }

    private static Optional<SeatId> firstBotSeat(HostedLobby lobby) {
        for (LobbySeat seat : lobby.state().seats()) {
            if (lobby.state().isBotSeat(seat)) {
                return Optional.of(seat.seatId());
            }
        }
        return Optional.empty();
    }

    private static SeatId seat(String value) {
        return new SeatId(
                switch (value.toLowerCase(Locale.ROOT)) {
                    case "east", "0" -> 0;
                    case "south", "1" -> 1;
                    case "west", "2" -> 2;
                    case "north", "3" -> 3;
                    default -> throw CommandSupport.failure(
                            "mahjongpaper.command.invalid_seat",
                            "Seat must be east, south, west, or north.");
                });
    }

    private static void requireLength(String[] arguments, int expected, String command) {
        if (arguments.length != expected) {
            throw CommandSupport.usage(command);
        }
    }
}
