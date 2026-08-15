package top.ellan.mahjong.plugin.command.handler;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.ellan.mahjong.application.history.PlayerMatchHistoryEntry;
import top.ellan.mahjong.application.history.PlayerMatchOutcome;
import top.ellan.mahjong.application.history.PlayerRankingEntry;
import top.ellan.mahjong.application.history.PlayerRankingPage;
import top.ellan.mahjong.plugin.command.CommandSupport;
import top.ellan.mahjong.plugin.command.SubcommandHandler;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleId;

/** Player-only asynchronous entry point for durable match history and rankings. */
public final class PlayerRecordHandler implements SubcommandHandler {
    private static final int PAGE_SIZE = 10;
    private static final List<String> RULE_IDS = List.of("mcr", "riichi", "sichuan");

    private final CommandSupport support;

    public PlayerRecordHandler(CommandSupport support) {
        this.support = java.util.Objects.requireNonNull(support, "support");
    }

    @Override
    public Set<String> names() {
        return Set.of("history", "rank", "ranking", "leaderboard", "lb");
    }

    @Override
    public void execute(CommandSender sender, String[] arguments) {
        Player player = support.requirePlayer(sender);
        PlayerId playerId = new PlayerId(player.getUniqueId());
        String root = arguments[0].toLowerCase(Locale.ROOT);
        if ("history".equals(root)) {
            history(sender, playerId, arguments);
        } else if ("leaderboard".equals(root) || "lb".equals(root)) {
            leaderboard(sender, playerId, arguments);
        } else {
            ranking(sender, playerId, arguments);
        }
    }

    private void history(CommandSender sender, PlayerId playerId, String[] arguments) {
        if (arguments.length > 2) {
            throw CommandSupport.usage("/mahjong history [page]");
        }
        int page = arguments.length == 2 ? page(arguments[1]) : 1;
        CompletionStage<List<PlayerMatchHistoryEntry>> query =
                support.runtime().playerHistory(playerId, page, PAGE_SIZE);
        query.whenComplete((entries, failure) -> {
            if (failure != null) {
                fail(sender, failure);
                return;
            }
            if (entries.isEmpty()) {
                support.reply(
                        sender,
                        CommandSupport.message(
                                "mahjongpaper.command.history_empty",
                                "No match history on page %s.",
                                page));
                return;
            }
            support.reply(
                    sender,
                    CommandSupport.message(
                            "mahjongpaper.command.history_header",
                            "Match history - page %s:",
                            page));
            entries.forEach(entry -> historyEntry(sender, entry));
        });
    }

    private void historyEntry(CommandSender sender, PlayerMatchHistoryEntry entry) {
        if (entry.outcome().isEmpty()) {
            support.reply(
                    sender,
                    CommandSupport.message(
                            "mahjongpaper.command.history_pending",
                            "%s - %s/%s - %s - seat %s - %s",
                            entry.updatedAt(),
                            entry.ruleId(),
                            entry.profileId(),
                            entry.lifecycle(),
                            entry.seatId(),
                            entry.matchId()));
            return;
        }
        PlayerMatchOutcome outcome = entry.outcome().orElseThrow();
        support.reply(
                sender,
                CommandSupport.message(
                        "mahjongpaper.command.history_entry",
                        "%s - %s/%s - place %s - score %s - rank points %s - %s",
                        entry.updatedAt(),
                        entry.ruleId(),
                        entry.profileId(),
                        outcome.placement(),
                        outcome.score(),
                        points(outcome.rankingPointsMilli()),
                        entry.matchId()));
    }

    private void ranking(CommandSender sender, PlayerId playerId, String[] arguments) {
        if (arguments.length > 3) {
            throw CommandSupport.usage("/mahjong rank [riichi|mcr|sichuan] [page]");
        }
        RuleId ruleId = arguments.length >= 2
                ? officialRule(arguments[1])
                : currentRule(playerId);
        int page = arguments.length == 3 ? page(arguments[2]) : 1;
        queryRanking(sender, playerId, ruleId, page);
    }

    /** v1.5 leaderboard entry point: rule may be omitted and defaults to the current table. */
    private void leaderboard(CommandSender sender, PlayerId playerId, String[] arguments) {
        if (arguments.length > 3) {
            throw CommandSupport.usage("/mahjong leaderboard [riichi|mcr|sichuan] [page]");
        }
        RuleId ruleId;
        int page = 1;
        if (arguments.length == 1) {
            ruleId = currentRule(playerId);
        } else if (arguments.length == 2) {
            if (isPage(arguments[1])) {
                ruleId = currentRule(playerId);
                page = page(arguments[1]);
            } else {
                ruleId = officialRule(arguments[1]);
            }
        } else {
            ruleId = officialRule(arguments[1]);
            page = page(arguments[2]);
        }
        queryRanking(sender, playerId, ruleId, page);
    }

    private void queryRanking(
            CommandSender sender, PlayerId playerId, RuleId ruleId, int page) {
        support.runtime()
                .playerRanking(playerId, ruleId, page, PAGE_SIZE)
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        fail(sender, failure);
                        return;
                    }
                    rankingPage(sender, result);
                });
    }

    private RuleId officialRule(String value) {
        RuleId ruleId = CommandSupport.ruleId(value);
        if (!RULE_IDS.contains(ruleId.value())) {
            throw CommandSupport.failure(
                    "mahjongpaper.command.unsupported_rule",
                    "Unsupported official rule id: %s",
                    ruleId);
        }
        return ruleId;
    }

    private RuleId currentRule(PlayerId playerId) {
        return support.runtime()
                .lobbyTables()
                .findByPlayer(playerId)
                .map(lobby -> lobby.state().ruleId())
                .or(
                        () ->
                                support.runtime()
                                        .liveTables()
                                        .findByPlayer(playerId)
                                        .map(match -> match.binding().rulePack().ruleId()))
                .orElseGet(() -> CommandSupport.ruleId("riichi"));
    }

    private static boolean isPage(String value) {
        try {
            return page(value) > 0;
        } catch (RuntimeException notPage) {
            return false;
        }
    }

    private void rankingPage(CommandSender sender, PlayerRankingPage page) {
        if (page.rankSystem().isEmpty()) {
            support.reply(
                    sender,
                    CommandSupport.message(
                            "mahjongpaper.command.ranking_empty",
                            "No completed %s rankings yet.",
                            page.ruleId()));
            return;
        }
        support.reply(
                sender,
                CommandSupport.message(
                        "mahjongpaper.command.ranking_header",
                        "%s ranking (%s) - page %s:",
                        page.ruleId(),
                        page.rankSystem().orElseThrow(),
                        page.page()));
        page.entries().forEach(entry -> rankingEntry(sender, entry, false));
        page.ownEntry()
                .filter(own -> page.entries().stream()
                        .noneMatch(entry -> entry.playerId().equals(own.playerId())))
                .ifPresent(own -> rankingEntry(sender, own, true));
    }

    private void rankingEntry(
            CommandSender sender, PlayerRankingEntry entry, boolean own) {
        support.reply(
                sender,
                CommandSupport.message(
                        own
                                ? "mahjongpaper.command.ranking_own_entry"
                                : "mahjongpaper.command.ranking_entry",
                        own
                                ? "You: #%s - %s - %s points - %s matches - score %s"
                                : "#%s - %s - %s points - %s matches - score %s",
                        entry.position(),
                        entry.playerId(),
                        points(entry.rankingPointsMilli()),
                        entry.matchCount(),
                        entry.totalScore()));
    }

    private void fail(CommandSender sender, Throwable failure) {
        Throwable cause = CommandSupport.unwrap(failure);
        support.reply(sender, CommandSupport.failed(CommandSupport.safeMessage(cause)));
    }

    private static int page(String value) {
        try {
            int page = Integer.parseInt(value);
            if (page < 1 || page > 100_000) {
                throw new NumberFormatException();
            }
            return page;
        } catch (NumberFormatException failure) {
            throw CommandSupport.failure(
                    "mahjongpaper.command.invalid_page", "Page must be between 1 and 100000.");
        }
    }

    private static String points(long milli) {
        return String.format(Locale.ROOT, "%.3f", milli / 1_000.0d);
    }

    @Override
    public List<String> complete(CommandSender sender, String[] arguments) {
        String root = arguments[0].toLowerCase(Locale.ROOT);
        boolean rankingCommand =
                root.equals("rank")
                        || root.equals("ranking")
                        || root.equals("leaderboard")
                        || root.equals("lb");
        if (rankingCommand && arguments.length == 2) {
            List<String> values = new java.util.ArrayList<>(RULE_IDS);
            values.addAll(pageSuggestions());
            return CommandSupport.filter(arguments[1], values);
        }
        if (arguments.length == 2 && root.equals("history")) {
            return CommandSupport.filter(arguments[1], pageSuggestions());
        }
        if (rankingCommand && arguments.length == 3) {
            return CommandSupport.filter(arguments[2], pageSuggestions());
        }
        return List.of();
    }

    private static List<String> pageSuggestions() {
        return List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
    }
}
