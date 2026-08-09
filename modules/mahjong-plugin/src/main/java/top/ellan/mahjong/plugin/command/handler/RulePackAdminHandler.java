package top.ellan.mahjong.plugin.command.handler;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.bukkit.command.CommandSender;
import top.ellan.mahjong.plugin.command.CommandSupport;
import top.ellan.mahjong.plugin.command.SubcommandHandler;
import top.ellan.mahjong.runtime.RulePackInventory;
import top.ellan.mahjong.runtime.RulePackVerification;
import top.ellan.mahjong.spi.RuleId;

/** Signed official rule-pack lifecycle commands. */
public final class RulePackAdminHandler implements SubcommandHandler {
    private final CommandSupport support;

    public RulePackAdminHandler(CommandSupport support) {
        this.support = java.util.Objects.requireNonNull(support, "support");
    }

    @Override
    public Set<String> names() {
        return Set.of("rules");
    }

    @Override
    public void execute(CommandSender sender, String[] arguments) {
        if (arguments.length < 2 || "list".equalsIgnoreCase(arguments[1])) {
            support.complete(sender, support.runtime().listRules(), RulePackAdminHandler::inventory);
            return;
        }
        support.requireAdmin(sender);
        switch (arguments[1].toLowerCase(java.util.Locale.ROOT)) {
            case "install", "update" -> install(sender, arguments);
            case "verify" -> verify(sender, arguments);
            case "activate" -> activate(sender, arguments);
            case "gc" ->
                    support.complete(
                            sender,
                            support.runtime().collectRuleGarbage(),
                            value -> "QUARANTINED " + value);
            default -> throw new IllegalArgumentException("Unknown rules subcommand");
        }
    }

    @Override
    public List<String> complete(CommandSender sender, String[] arguments) {
        if (arguments.length == 2) {
            return CommandSupport.filter(
                    arguments[1], List.of("list", "install", "update", "verify", "activate", "gc"));
        }
        if (arguments.length == 3
                && List.of("install", "update", "verify", "activate")
                        .contains(arguments[1].toLowerCase(java.util.Locale.ROOT))) {
            return CommandSupport.filter(arguments[2], List.of("riichi", "mcr", "sichuan"));
        }
        return List.of();
    }

    private void install(CommandSender sender, String[] arguments) {
        if (arguments.length < 3 || arguments.length > 4) {
            throw new IllegalArgumentException(
                    "Usage: /mahjong rules " + arguments[1] + " <id> [version]");
        }
        Optional<String> version =
                arguments.length == 4 ? Optional.of(arguments[3]) : Optional.empty();
        support.complete(
                sender,
                support.runtime().installRule(CommandSupport.ruleId(arguments[2]), version),
                value -> "INSTALLED " + value);
    }

    private void verify(CommandSender sender, String[] arguments) {
        if (arguments.length > 3) {
            throw new IllegalArgumentException("Usage: /mahjong rules verify [id]");
        }
        Optional<RuleId> ruleId =
                arguments.length == 3
                        ? Optional.of(CommandSupport.ruleId(arguments[2]))
                        : Optional.empty();
        support.complete(
                sender,
                support.runtime().verifyRules(ruleId),
                RulePackAdminHandler::verification);
    }

    private void activate(CommandSender sender, String[] arguments) {
        if (arguments.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: /mahjong rules activate <id> <version>");
        }
        support.complete(
                sender,
                support.runtime()
                        .activateRule(CommandSupport.ruleId(arguments[2]), arguments[3]),
                value -> "ACTIVATION_PENDING_RESTART " + value);
    }

    private static String inventory(RulePackInventory inventory) {
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

    private static String verification(List<RulePackVerification> results) {
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
}
