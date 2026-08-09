package top.ellan.mahjong.plugin.command.handler;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.plugin.command.CommandSupport;
import top.ellan.mahjong.plugin.command.SubcommandHandler;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.SeatId;

/** Player lobby actions; each delegates to the application actor facade. */
public final class LobbyActionHandler implements SubcommandHandler {
    private static final Set<String> NAMES =
            Set.of("join", "leave", "spectate", "unspectate", "ready", "bot", "start", "mode");
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
                        requireLength(arguments, 3, "Usage: /mahjong join <table-id> <seat>");
                        yield support.runtime()
                                .lobbyUseCases()
                                .join(
                                        TableId.parse(arguments[1]),
                                        seat(arguments[2]),
                                        actor);
                    }
                    case "leave" -> {
                        requireLength(arguments, 1, "Usage: /mahjong leave");
                        yield support.runtime().lobbyUseCases().leave(actor);
                    }
                    case "spectate" -> {
                        requireLength(arguments, 2, "Usage: /mahjong spectate <table-id>");
                        yield support.runtime()
                                .lobbyUseCases()
                                .spectate(TableId.parse(arguments[1]), actor);
                    }
                    case "unspectate" -> {
                        requireLength(arguments, 1, "Usage: /mahjong unspectate");
                        yield support.runtime().lobbyUseCases().unspectate(actor);
                    }
                    case "ready" -> {
                        requireLength(arguments, 1, "Usage: /mahjong ready");
                        yield support.runtime().lobbyUseCases().toggleReady(actor);
                    }
                    case "bot" -> {
                        requireLength(arguments, 3, "Usage: /mahjong bot <add|remove> <seat>");
                        SeatId target = seat(arguments[2]);
                        yield switch (arguments[1].toLowerCase(Locale.ROOT)) {
                            case "add" -> support.runtime().lobbyUseCases().addBot(actor, target);
                            case "remove" -> support.runtime().lobbyUseCases().removeBot(actor, target);
                            default -> throw new IllegalArgumentException(
                                    "Usage: /mahjong bot <add|remove> <seat>");
                        };
                    }
                    case "start" -> {
                        requireLength(arguments, 1, "Usage: /mahjong start");
                        yield support.runtime().lobbyUseCases().start(actor);
                    }
                    case "mode" -> {
                        if (arguments.length < 2 || arguments.length > 3) {
                            throw new IllegalArgumentException(
                                    "Usage: /mahjong mode <riichi|mcr|sichuan> [profile]");
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
                    default -> throw new IllegalArgumentException("Unknown lobby action");
                };
        support.complete(
                sender,
                result,
                value -> value.code() + " revision=" + value.revision() + " " + value.reasonCode());
    }

    @Override
    public List<String> complete(CommandSender sender, String[] arguments) {
        if (arguments.length == 2 && "mode".equalsIgnoreCase(arguments[0])) {
            return CommandSupport.filter(arguments[1], List.of("riichi", "mcr", "sichuan"));
        }
        if (arguments.length == 3 && "join".equalsIgnoreCase(arguments[0])) {
            return CommandSupport.filter(
                    arguments[2], List.of("east", "south", "west", "north"));
        }
        if (arguments.length == 2 && "bot".equalsIgnoreCase(arguments[0])) {
            return CommandSupport.filter(arguments[1], List.of("add", "remove"));
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
                    default -> throw new IllegalArgumentException("Seat must be east/south/west/north");
                });
    }

    private static void requireLength(String[] arguments, int expected, String usage) {
        if (arguments.length != expected) {
            throw new IllegalArgumentException(usage);
        }
    }
}
