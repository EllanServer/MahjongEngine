package top.ellan.mahjong.command;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.ellan.mahjong.bootstrap.MahjongArchitectureRuntime;
import top.ellan.mahjong.runtime.InstallationResult;
import top.ellan.mahjong.runtime.InstalledRulePack;
import top.ellan.mahjong.runtime.OfficialRuleIds;
import top.ellan.mahjong.runtime.RulePackAdminService;
import top.ellan.mahjong.runtime.RulePackInventory;
import top.ellan.mahjong.runtime.RulePackVerification;
import top.ellan.mahjong.runtime.ServerScheduler;
import top.ellan.mahjong.spi.RuleId;

/** Runs filesystem/network rule commands only on the architecture's bounded I/O executor. */
public final class RulePackCommandCoordinator implements RulePackCommandHandler {
    private static final List<String> OPERATIONS = List.of(
        "list", "install", "update", "verify", "activate", "gc"
    );
    private static final List<String> RULE_IDS = List.of("riichi", "mcr", "sichuan");

    private final MahjongArchitectureRuntime runtime;
    private final ServerScheduler scheduler;

    public RulePackCommandCoordinator(
            MahjongArchitectureRuntime runtime, ServerScheduler scheduler) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(args, "args");
        if (args.length < 2) {
            reply(sender, List.of(usage()));
            return;
        }
        String operation = args[1].toLowerCase(Locale.ROOT);
        CompletionStage<List<String>> result;
        try {
            result = runtime.submitAdmin(() -> executeBlocking(operation, args));
        } catch (RuntimeException rejected) {
            reply(sender, List.of("Rule-pack command queue is full; try again later."));
            return;
        }
        result.whenComplete((lines, failure) -> {
            if (failure == null) {
                reply(sender, lines);
            } else {
                Throwable cause = unwrap(failure);
                String message = cause.getMessage();
                reply(sender, List.of(
                    "Rule-pack command failed: "
                        + cause.getClass().getSimpleName()
                        + (message == null || message.isBlank() ? "" : ": " + message)
                ));
            }
        });
    }

    @Override
    public List<String> suggestions(String[] args) {
        if (args.length == 2) {
            return prefix(args[1], OPERATIONS);
        }
        if (args.length == 3
            && List.of("install", "update", "verify", "activate").contains(
                args[1].toLowerCase(Locale.ROOT))) {
            return prefix(args[2], RULE_IDS);
        }
        return List.of();
    }

    private List<String> executeBlocking(String operation, String[] args) throws Exception {
        return switch (operation) {
            case "list" -> list();
            case "install", "update" -> install(args);
            case "verify" -> verify(args);
            case "activate" -> activate(args);
            case "gc" -> collectGarbage(args);
            default -> List.of(usage());
        };
    }

    private List<String> list() throws Exception {
        if (runtime.ruleInventory().isEmpty()) {
            return List.of("Rule packs are disabled in config.yml.");
        }
        RulePackInventory inventory = runtime.ruleInventory().orElseThrow().read();
        List<String> lines = new ArrayList<>();
        lines.add("Installed rule packs:");
        if (inventory.installed().isEmpty()) {
            lines.add("- none");
        }
        for (InstalledRulePack pack : inventory.installed()) {
            String state = pack.pending() ? "pending-restart" : pack.active() ? "active" : "inactive";
            lines.add("- " + pack.ruleId() + " " + pack.version() + " [" + state + "]");
        }
        runtime.status().ruleAdministrationDisabledReason().ifPresent(
            reason -> lines.add("Signed install/update/verify disabled: " + reason)
        );
        return List.copyOf(lines);
    }

    private List<String> install(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            return List.of("Usage: /mahjong rules install <riichi|mcr|sichuan> [version]");
        }
        RulePackAdminService admin = requireAdmin();
        RuleId ruleId = parseRuleId(args[2]);
        Optional<String> version = args.length == 4 ? Optional.of(args[3]) : Optional.empty();
        InstallationResult installed = admin.install(ruleId, version);
        return List.of(
            (installed.alreadyInstalled() ? "Already installed " : "Installed ")
                + installed.reference().ruleId()
                + ' '
                + installed.reference().version()
                + "; use /mahjong rules activate and fully restart the JVM."
        );
    }

    private List<String> verify(String[] args) throws Exception {
        if (args.length > 3) {
            return List.of("Usage: /mahjong rules verify [riichi|mcr|sichuan]");
        }
        Optional<RuleId> ruleId = args.length == 3
            ? Optional.of(parseRuleId(args[2]))
            : Optional.empty();
        List<RulePackVerification> verified = requireAdmin().verify(ruleId);
        if (verified.isEmpty()) {
            return List.of("No installed rule packs matched the request.");
        }
        return verified.stream()
            .map(item -> "- " + item.ruleId() + ' ' + item.version() + ": "
                + (item.valid() ? "verified" : "INVALID " + item.detail()))
            .toList();
    }

    private List<String> activate(String[] args) throws Exception {
        if (args.length != 4) {
            return List.of("Usage: /mahjong rules activate <riichi|mcr|sichuan> <version>");
        }
        RuleId ruleId = parseRuleId(args[2]);
        requireAdmin().activate(ruleId, args[3]);
        return List.of(
            "Activation queued for " + ruleId + ' ' + args[3]
                + "; it will take effect only after a full JVM restart."
        );
    }

    private List<String> collectGarbage(String[] args) throws Exception {
        if (args.length != 2) {
            return List.of("Usage: /mahjong rules gc");
        }
        List<Path> moved = requireAdmin().collectGarbage();
        return List.of("Moved " + moved.size() + " unreferenced version(s) to quarantine.");
    }

    private RulePackAdminService requireAdmin() {
        return runtime.ruleAdmin().orElseThrow(() -> new IllegalStateException(
            runtime.status().ruleAdministrationDisabledReason().orElse(
                "signed rule-pack administration is unavailable"
            )
        ));
    }

    private static RuleId parseRuleId(String input) {
        return OfficialRuleIds.parseCommandInput(input).orElseThrow(() ->
            new IllegalArgumentException("Unknown rule id; accepted: riichi/richi, mcr/gb, sichuan")
        );
    }

    private void reply(CommandSender sender, List<String> lines) {
        Runnable response = () -> lines.forEach(line -> sender.sendMessage(Component.text(line)));
        if (sender instanceof Player player) {
            scheduler.runEntity(player, response);
        } else {
            scheduler.runGlobal(response);
        }
    }

    private static List<String> prefix(String token, List<String> candidates) {
        String normalized = token == null ? "" : token.toLowerCase(Locale.ROOT);
        return candidates.stream().filter(value -> value.startsWith(normalized)).toList();
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String usage() {
        return "Usage: /mahjong rules <list|install|update|verify|activate|gc>";
    }
}
