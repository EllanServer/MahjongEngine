package top.ellan.mahjong.plugin.command.handler;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.ellan.mahjong.application.table.TableActionResult;
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
                    case "join" -> {
                        requireLength(arguments, 3, "/mahjong join <table-id> <seat>");
                        yield support.runtime()
                                .lobbyUseCases()
                                .join(
                                        TableId.parse(arguments[1]),
                                        seat(arguments[2]),
                                        actor);
                    }
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
                    case "start" -> {
                        requireLength(arguments, 1, "/mahjong start");
                        yield support.runtime().lobbyUseCases().start(actor);
                    }
                    case "mode" -> {
                        if (arguments.length < 2 || arguments.length > 3) {
                            throw CommandSupport.usage(
                                    "/mahjong mode <riichi|mcr|sichuan> [profile]");
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
            return CommandSupport.filter(arguments[1], List.of("riichi", "mcr", "sichuan"));
        }
        if (arguments.length == 3 && "mode".equalsIgnoreCase(arguments[0])) {
            return CommandSupport.filter(
                    arguments[2], support.profileIds(arguments[1]));
        }
        if (arguments.length == 2
                && ("join".equalsIgnoreCase(arguments[0])
                        || "spectate".equalsIgnoreCase(arguments[0]))) {
            return CommandSupport.filter(arguments[1], support.tableIds());
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
