package top.ellan.mahjong.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import top.ellan.mahjong.domain.CompetitionRef;
import top.ellan.mahjong.domain.ParticipantRole;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.domain.TableParticipant;
import top.ellan.mahjong.persistence.sql.StoredTableAnchor;
import top.ellan.mahjong.runtime.RulePackInventory;
import top.ellan.mahjong.runtime.RulePackVerification;
import top.ellan.mahjong.spi.MatchSeed;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.SeatId;

/** Thin command ingress. It captures immutable input and returns before rule or SQL work begins. */
public final class MahjongCommand implements CommandExecutor, TabCompleter {
    private final MahjongPaperPlugin plugin;
    private final MahjongRuntime runtime;

    public MahjongCommand(MahjongPaperPlugin plugin, MahjongRuntime runtime) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public boolean onCommand(
            CommandSender sender, Command command, String label, String[] arguments) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(arguments, "arguments");
        try {
            if (arguments.length == 0) {
                reply(sender, "MahjongPaper 2.0 — " + runtime.status());
                reply(sender, "/mahjong <create|list|state|remove|rules>");
                return true;
            }
            switch (arguments[0].toLowerCase(Locale.ROOT)) {
                case "create" -> create(sender, arguments);
                case "list" -> list(sender);
                case "state" -> state(sender, arguments);
                case "remove" -> remove(sender, arguments);
                case "rules" -> rules(sender, arguments);
                default -> reply(sender, "Unknown subcommand");
            }
        } catch (RuntimeException failure) {
            reply(sender, "FAILED: " + safeMessage(failure));
        }
        return true;
    }

    private void create(CommandSender sender, String[] arguments) {
        if (!(sender instanceof Player owner)) {
            throw new IllegalArgumentException("Only a player can anchor a table");
        }
        if (arguments.length != 6) {
            throw new IllegalArgumentException(
                    "Usage: /mahjong create <riichi|mcr|sichuan> <profile> <p2> <p3> <p4>");
        }
        RuleId ruleId = ruleId(arguments[1]);
        ProfileId profileId = new ProfileId(arguments[2].toLowerCase(Locale.ROOT));
        List<Player> players = new ArrayList<>(4);
        players.add(owner);
        for (int index = 3; index < 6; index++) {
            Player player = Bukkit.getPlayerExact(arguments[index]);
            if (player == null || !player.isOnline()) {
                throw new IllegalArgumentException("Player is not online: " + arguments[index]);
            }
            players.add(player);
        }
        if (players.stream().map(Player::getUniqueId).distinct().count() != 4) {
            throw new IllegalArgumentException("A match requires four distinct players");
        }
        TableId tableId = TableId.random();
        Location source = owner.getLocation();
        Location anchor =
                new Location(
                        Objects.requireNonNull(source.getWorld(), "player world"),
                        Math.floor(source.getX()) + 0.5D,
                        Math.floor(source.getY()),
                        Math.floor(source.getZ()) + 0.5D,
                        source.getYaw(),
                        0.0F);
        List<TableParticipant> participants =
                java.util.stream.IntStream.range(0, players.size())
                        .mapToObj(
                                index ->
                                        new TableParticipant(
                                                new PlayerId(
                                                        players.get(index).getUniqueId()),
                                                ParticipantRole.PLAYER,
                                                Optional.of(new SeatId(index))))
                        .toList();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StoredTableAnchor storedAnchor =
                new StoredTableAnchor(
                        tableId,
                        anchor.getWorld().getUID().toString(),
                        anchor.getX(),
                        anchor.getY(),
                        anchor.getZ(),
                        anchor.getYaw(),
                        anchor.getPitch());
        NewRulePackMatch request =
                new NewRulePackMatch(
                        tableId,
                        ruleId,
                        profileId,
                        new MatchSeed(random.nextLong(), random.nextLong()),
                        participants,
                        Map.of(),
                        CompetitionRef.none(),
                        storedAnchor);
        reply(sender, "Creating table " + tableId + " asynchronously…");
        complete(
                sender,
                runtime.create(request, anchor),
                started ->
                        "CREATED "
                                + started.tableId()
                                + " match="
                                + started.binding().matchId()
                                + " rule="
                                + started.binding().rulePack());
    }

    private void list(CommandSender sender) {
        List<StartedRulePackMatch> tables = runtime.liveTables().list();
        reply(sender, "Live tables: " + tables.size());
        for (StartedRulePackMatch table : tables) {
            reply(
                    sender,
                    table.tableId()
                            + " "
                            + table.binding().rulePack()
                            + " "
                            + table.actor().snapshot().lifecycle()
                            + " rev="
                            + table.actor().snapshot().revision());
        }
    }

    private void state(CommandSender sender, String[] arguments) {
        StartedRulePackMatch table;
        if (arguments.length >= 2) {
            table =
                    runtime.liveTables()
                            .find(TableId.parse(arguments[1]))
                            .orElseThrow(() -> new IllegalArgumentException("Unknown table"));
        } else if (sender instanceof Player player) {
            table =
                    runtime.liveTables()
                            .findByPlayer(new PlayerId(player.getUniqueId()))
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "You do not belong to a live table"));
        } else {
            throw new IllegalArgumentException("Console must provide a table id");
        }
        var snapshot = table.actor().snapshot();
        reply(
                sender,
                table.tableId()
                        + " lifecycle="
                        + snapshot.lifecycle()
                        + " revision="
                        + snapshot.revision()
                        + " mailbox="
                        + snapshot.mailboxDepth()
                        + " ruleInFlight="
                        + snapshot.ruleCalculationInFlight()
                        + " outbox="
                        + snapshot.outboxHealth());
    }

    private void remove(CommandSender sender, String[] arguments) {
        requireAdmin(sender);
        if (arguments.length != 2) {
            throw new IllegalArgumentException("Usage: /mahjong remove <table-id>");
        }
        TableId tableId = TableId.parse(arguments[1]);
        complete(sender, runtime.remove(tableId), ignored -> "REMOVED " + tableId);
    }

    private void rules(CommandSender sender, String[] arguments) {
        if (arguments.length < 2 || "list".equalsIgnoreCase(arguments[1])) {
            complete(sender, runtime.listRules(), MahjongCommand::formatInventory);
            return;
        }
        requireAdmin(sender);
        switch (arguments[1].toLowerCase(Locale.ROOT)) {
            case "install", "update" -> {
                if (arguments.length < 3 || arguments.length > 4) {
                    throw new IllegalArgumentException(
                            "Usage: /mahjong rules "
                                    + arguments[1]
                                    + " <id> [version]");
                }
                Optional<String> version =
                        arguments.length == 4
                                ? Optional.of(arguments[3])
                                : Optional.empty();
                complete(
                        sender,
                        runtime.installRule(ruleId(arguments[2]), version),
                        result -> "INSTALLED " + result);
            }
            case "verify" -> {
                if (arguments.length > 3) {
                    throw new IllegalArgumentException(
                            "Usage: /mahjong rules verify [id]");
                }
                Optional<RuleId> requested =
                        arguments.length == 3
                                ? Optional.of(ruleId(arguments[2]))
                                : Optional.empty();
                complete(sender, runtime.verifyRules(requested), MahjongCommand::formatVerification);
            }
            case "activate" -> {
                if (arguments.length != 4) {
                    throw new IllegalArgumentException(
                            "Usage: /mahjong rules activate <id> <version>");
                }
                complete(
                        sender,
                        runtime.activateRule(ruleId(arguments[2]), arguments[3]),
                        result -> "ACTIVATION_PENDING_RESTART " + result);
            }
            case "gc" ->
                    complete(
                            sender,
                            runtime.collectRuleGarbage(),
                            result -> "QUARANTINED " + result);
            default -> throw new IllegalArgumentException("Unknown rules subcommand");
        }
    }

    private <T> void complete(
            CommandSender sender,
            CompletionStage<T> stage,
            java.util.function.Function<T, String> formatter) {
        stage.whenComplete(
                (value, failure) -> {
                    String message =
                            failure == null
                                    ? formatter.apply(value)
                                    : "FAILED: " + safeMessage(unwrap(failure));
                    reply(sender, message);
                });
    }

    private static String formatInventory(RulePackInventory inventory) {
        if (inventory.installed().isEmpty()) {
            return "No rule packs installed";
        }
        return inventory.installed().stream()
                .map(
                        pack ->
                                pack.ruleId()
                                        + ":"
                                        + pack.version()
                                        + (pack.active() ? "[active]" : "")
                                        + (pack.pending() ? "[pending-restart]" : ""))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String formatVerification(List<RulePackVerification> results) {
        if (results.isEmpty()) {
            return "No installed rule packs matched";
        }
        return results.stream()
                .map(
                        result ->
                                result.ruleId()
                                        + ":"
                                        + result.version()
                                        + '='
                                        + (result.valid() ? "valid" : result.detail()))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private void reply(CommandSender sender, String message) {
        Runnable send = () -> sender.sendMessage(Component.text(message));
        if (sender instanceof Player player) {
            player.getScheduler().run(plugin, ignored -> send.run(), null);
        } else {
            Bukkit.getGlobalRegionScheduler().execute(plugin, send);
        }
    }

    private static RuleId ruleId(String raw) {
        String normalized = raw.toLowerCase(Locale.ROOT);
        return new RuleId(
                switch (normalized) {
                    case "richi" -> "riichi";
                    case "gb" -> "mcr";
                    default -> normalized;
                });
    }

    private static void requireAdmin(CommandSender sender) {
        if (!sender.hasPermission("mahjongpaper.admin")) {
            throw new IllegalArgumentException("Missing mahjongpaper.admin");
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] arguments) {
        if (arguments.length == 1) {
            return filter(arguments[0], List.of("create", "list", "state", "remove", "rules"));
        }
        if (arguments.length == 2 && "rules".equalsIgnoreCase(arguments[0])) {
            return filter(arguments[1], List.of("list", "install", "update", "verify", "activate", "gc"));
        }
        if ((arguments.length == 2 && "create".equalsIgnoreCase(arguments[0]))
                || (arguments.length == 3
                        && "rules".equalsIgnoreCase(arguments[0])
                        && List.of("install", "update", "verify", "activate")
                                .contains(arguments[1].toLowerCase(Locale.ROOT)))) {
            return filter(arguments[arguments.length - 1], List.of("riichi", "mcr", "sichuan"));
        }
        return List.of();
    }

    private static List<String> filter(String prefix, List<String> values) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(normalized)).toList();
    }
}
