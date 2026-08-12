package top.ellan.mahjong.plugin.command;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.ellan.mahjong.plugin.MahjongPaperPlugin;
import top.ellan.mahjong.plugin.MahjongRuntime;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleId;

/** Shared platform reply and canonical-id helpers; it contains no table behavior. */
public final class CommandSupport {
    private final MahjongPaperPlugin plugin;
    private final MahjongRuntime runtime;

    public CommandSupport(MahjongPaperPlugin plugin, MahjongRuntime runtime) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public MahjongRuntime runtime() {
        return runtime;
    }

    public Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        throw failure(
                "mahjongpaper.command.player_only", "Only a player can use this command.");
    }

    public void requireAdmin(CommandSender sender) {
        if (!sender.hasPermission("mahjongpaper.admin")) {
            throw failure(
                    "mahjongpaper.command.admin_required",
                    "Missing permission mahjongpaper.admin.");
        }
    }

    public void reply(CommandSender sender, CommandMessage message) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(message, "message");
        Component rendered = Component.text(text(sender, message));
        Runnable send = () -> sender.sendMessage(rendered);
        if (sender instanceof Player player) {
            player.getScheduler().run(plugin, ignored -> send.run(), null);
        } else {
            Bukkit.getGlobalRegionScheduler().execute(plugin, send);
        }
    }

    /** Resolves player-facing text on the server so command UI never depends on client assets. */
    public String text(
            CommandSender sender, String key, String fallback, Object... arguments) {
        return text(sender, message(key, fallback, arguments));
    }

    private String text(CommandSender sender, CommandMessage message) {
        if (sender instanceof Player player) {
            return runtime.messages()
                    .format(
                            player.locale(),
                            message.translationKey(),
                            message.consolePattern(),
                            message.arguments());
        }
        return message.consoleText();
    }

    public <T> void complete(
            CommandSender sender,
            CompletionStage<T> stage,
            Function<T, CommandMessage> formatter) {
        Objects.requireNonNull(stage, "stage")
                .whenComplete(
                        (value, failure) -> {
                            if (failure != null) {
                                Throwable cause = unwrap(failure);
                                reply(
                                        sender,
                                        cause instanceof LocalizedCommandException localized
                                                ? localized.reply()
                                                : failed(safeMessage(cause)));
                                return;
                            }
                            try {
                                reply(sender, formatter.apply(value));
                            } catch (RuntimeException formattingFailure) {
                                reply(sender, failed(safeMessage(formattingFailure)));
                            }
                        });
    }

    public static CommandMessage message(String key, String fallback, Object... arguments) {
        return CommandMessage.of(key, fallback, arguments);
    }

    public static LocalizedCommandException failure(
            String key, String fallback, Object... arguments) {
        return new LocalizedCommandException(message(key, fallback, arguments));
    }

    public static LocalizedCommandException usage(String command) {
        return failure("mahjongpaper.command.usage", "Usage: %s", command);
    }

    public static CommandMessage failed(String detail) {
        return message("mahjongpaper.command.failed", "Failed: %s", detail);
    }

    public static RuleId ruleId(String raw) {
        String normalized = Objects.requireNonNull(raw, "raw").toLowerCase(Locale.ROOT);
        return new RuleId(
                switch (normalized) {
                    case "richi" -> "riichi";
                    case "gb" -> "mcr";
                    default -> normalized;
                });
    }

    public static ProfileId defaultProfile(RuleId ruleId) {
        return new ProfileId(
                switch (ruleId.value()) {
                    case "riichi" -> "mahjong-soul";
                    case "mcr" -> "green-book";
                    case "sichuan" -> "t-tfmj-01-2024";
                    default -> throw new IllegalArgumentException("Unsupported official rule id");
                });
    }

    /** Active provider profiles, with official defaults available during early startup. */
    public List<String> profileIds(RuleId ruleId) {
        Objects.requireNonNull(ruleId, "ruleId");
        try {
            List<String> active =
                    runtime.ruleDescriptors().stream()
                            .filter(descriptor -> descriptor.ruleId().equals(ruleId))
                            .flatMap(descriptor -> descriptor.profiles().stream())
                            .map(profile -> profile.id().value())
                            .distinct()
                            .sorted()
                            .toList();
            if (!active.isEmpty()) {
                return active;
            }
        } catch (IllegalStateException ignored) {
            // Completion can run while the asynchronous rule registry is still starting.
        }
        try {
            return List.of(defaultProfile(ruleId).value());
        } catch (IllegalArgumentException unsupported) {
            return List.of();
        }
    }

    /** Completion-safe overload: an unfinished or invalid rule token simply has no profiles. */
    public List<String> profileIds(String rawRuleId) {
        try {
            return profileIds(ruleId(rawRuleId));
        } catch (IllegalArgumentException invalidRuleId) {
            return List.of();
        }
    }

    /** Returns at most the sender's current table; completion must never enumerate tables. */
    public List<String> currentTableIds(CommandSender sender) {
        if (!(Objects.requireNonNull(sender, "sender") instanceof Player player)) {
            return List.of();
        }
        PlayerId playerId = new PlayerId(player.getUniqueId());
        return runtime.lobbyTables()
                .findByPlayer(playerId)
                .map(lobby -> List.of(lobby.tableId().toString()))
                .or(
                        () ->
                                runtime.liveTables()
                                        .findByPlayer(playerId)
                                        .map(match -> List.of(match.tableId().toString())))
                .orElseGet(List::of);
    }

    public static List<String> filter(String prefix, List<String> values) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(normalized)).toList();
    }

    public static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }
}
