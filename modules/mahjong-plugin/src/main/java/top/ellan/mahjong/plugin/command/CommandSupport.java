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
        throw new IllegalArgumentException("Only a player can use this command");
    }

    public void requireAdmin(CommandSender sender) {
        if (!sender.hasPermission("mahjongpaper.admin")) {
            throw new IllegalArgumentException("Missing mahjongpaper.admin");
        }
    }

    public void reply(CommandSender sender, String message) {
        Runnable send = () -> sender.sendMessage(Component.text(message));
        if (sender instanceof Player player) {
            player.getScheduler().run(plugin, ignored -> send.run(), null);
        } else {
            Bukkit.getGlobalRegionScheduler().execute(plugin, send);
        }
    }

    public <T> void complete(
            CommandSender sender,
            CompletionStage<T> stage,
            Function<T, String> formatter) {
        Objects.requireNonNull(stage, "stage")
                .whenComplete(
                        (value, failure) ->
                                reply(
                                        sender,
                                        failure == null
                                                ? formatter.apply(value)
                                                : "FAILED: " + safeMessage(unwrap(failure))));
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
