package top.ellan.mahjong.plugin.command.handler;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.plugin.command.CommandSupport;
import top.ellan.mahjong.plugin.command.SubcommandHandler;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleAction;

/** Administrative T/TFMJ referee commands; parsing is isolated from the rule-pack runtime. */
public final class MatchRefereeHandler implements SubcommandHandler {
    private static final PlayerId CONSOLE_AUTHORITY = new PlayerId(UUID.nameUUIDFromBytes(
            "mahjongpaper:console-referee".getBytes(StandardCharsets.UTF_8)));
    private static final List<String> OPERATIONS =
            List.of("false-win", "flower-pig", "penalty", "resolve", "resume");
    private static final List<String> SEATS = List.of("east", "south", "west", "north");

    private final CommandSupport support;

    public MatchRefereeHandler(CommandSupport support) {
        this.support = java.util.Objects.requireNonNull(support, "support");
    }

    @Override
    public Set<String> names() {
        return Set.of("referee");
    }

    @Override
    public void execute(CommandSender sender, String[] arguments) {
        support.requireAdmin(sender);
        if (arguments.length < 4) {
            throw usage();
        }
        TableId tableId = TableId.parse(arguments[1]);
        String operation = arguments[2].toLowerCase(Locale.ROOT);
        int seat = seat(arguments[3]);
        RuleAction action = switch (operation) {
            case "false-win" -> falseWin(arguments, seat);
            case "flower-pig" -> flowerPig(arguments, seat);
            case "penalty" -> penalty(arguments, seat);
            case "resolve" -> resolve(arguments, seat);
            case "resume" -> {
                requireLength(arguments, 4);
                yield SichuanRefereeProtocol.resume(seat);
            }
            default -> throw usage();
        };
        support.complete(
                sender,
                support.runtime().submitReferee(tableId, authority(sender), action),
                result -> resultMessage(tableId, result));
    }

    @Override
    public List<String> complete(CommandSender sender, String[] arguments) {
        if (!sender.hasPermission("mahjongpaper.admin")) {
            return List.of();
        }
        if (arguments.length == 2) {
            return CommandSupport.filter(arguments[1], support.currentTableIds(sender));
        }
        if (arguments.length == 3) {
            return CommandSupport.filter(arguments[2], OPERATIONS);
        }
        if (arguments.length == 4) {
            return CommandSupport.filter(arguments[3], SEATS);
        }
        if (arguments.length == 5) {
            return CommandSupport.filter(arguments[4], switch (arguments[2].toLowerCase(Locale.ROOT)) {
                case "false-win" -> List.of("ordinary", "intentional");
                case "flower-pig" -> List.of("reacquired", "natural", "invalid-win");
                case "penalty" -> List.of(
                        "warning", "1", "2", "4", "8", "16", "24", "stop-win", "suspend");
                case "resolve" -> List.of("confirm", "dismiss");
                default -> List.of();
            });
        }
        return List.of();
    }

    private static RuleAction falseWin(String[] arguments, int seat) {
        requireLength(arguments, 5);
        return SichuanRefereeProtocol.ruling(seat, switch (arguments[4].toLowerCase(Locale.ROOT)) {
            case "ordinary" -> "FALSE_WIN_ON_DISCARD";
            case "intentional" -> "INTENTIONAL_FALSE_WIN";
            default -> throw usage();
        });
    }

    private static RuleAction flowerPig(String[] arguments, int seat) {
        requireLength(arguments, 5);
        return SichuanRefereeProtocol.ruling(seat, switch (arguments[4].toLowerCase(Locale.ROOT)) {
            case "reacquired" -> "FLOWER_PIG_REACQUIRED_MISSING_SUIT";
            case "natural" -> "FLOWER_PIG_NATURAL";
            case "invalid-win" -> "FLOWER_PIG_INVALID_WIN";
            default -> throw usage();
        });
    }

    private static RuleAction penalty(String[] arguments, int seat) {
        requireLength(arguments, 5);
        String ruling = switch (arguments[4].toLowerCase(Locale.ROOT)) {
            case "warning" -> "WARNING";
            case "1", "2", "4", "8", "16", "24" -> "PENALTY_" + arguments[4];
            case "stop-win" -> "STOP_WINNING";
            case "suspend" -> "SUSPEND_COMPETITION";
            default -> throw usage();
        };
        return SichuanRefereeProtocol.ruling(seat, ruling);
    }

    private static RuleAction resolve(String[] arguments, int seat) {
        requireLength(arguments, 5);
        return SichuanRefereeProtocol.resolveDisqualification(
                seat,
                switch (arguments[4].toLowerCase(Locale.ROOT)) {
                    case "confirm" -> true;
                    case "dismiss" -> false;
                    default -> throw usage();
                });
    }

    private static PlayerId authority(CommandSender sender) {
        return sender instanceof Player player ? new PlayerId(player.getUniqueId()) : CONSOLE_AUTHORITY;
    }

    private static int seat(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "east", "0" -> 0;
            case "south", "1" -> 1;
            case "west", "2" -> 2;
            case "north", "3" -> 3;
            default -> throw usage();
        };
    }

    private static void requireLength(String[] arguments, int required) {
        if (arguments.length != required) {
            throw usage();
        }
    }

    private static top.ellan.mahjong.plugin.command.LocalizedCommandException usage() {
        return CommandSupport.usage(
                "/mahjong referee <table-id> <false-win|flower-pig|penalty|resolve|resume> <seat> [ruling]");
    }

    private static top.ellan.mahjong.plugin.command.CommandMessage resultMessage(
            TableId tableId, TableActionResult result) {
        return CommandSupport.message(
                "mahjongpaper.command.referee_result",
                "Referee action for %s: %s (revision %d, %s).",
                tableId,
                result.code(),
                result.revision(),
                result.reasonCode());
    }
}
