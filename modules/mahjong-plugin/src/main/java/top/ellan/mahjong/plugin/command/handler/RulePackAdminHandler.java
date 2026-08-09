package top.ellan.mahjong.plugin.command.handler;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.bukkit.command.CommandSender;
import top.ellan.mahjong.plugin.command.CommandSupport;
import top.ellan.mahjong.plugin.command.CommandMessage;
import top.ellan.mahjong.plugin.command.SubcommandHandler;
import top.ellan.mahjong.runtime.admin.RulePackInventory;
import top.ellan.mahjong.runtime.admin.RulePackVerification;
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
                            value -> CommandSupport.message(
                                    "mahjongpaper.command.rules_quarantined",
                                    "Quarantined %s rule-pack versions.",
                                    value));
            default -> throw CommandSupport.failure(
                    "mahjongpaper.command.unknown_rules_subcommand",
                    "Unknown rules subcommand.");
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
            throw CommandSupport.usage(
                    "/mahjong rules " + arguments[1] + " <id> [version]");
        }
        Optional<String> version =
                arguments.length == 4 ? Optional.of(arguments[3]) : Optional.empty();
        support.complete(
                sender,
                support.runtime().installRule(CommandSupport.ruleId(arguments[2]), version),
                value -> CommandSupport.message(
                        "mahjongpaper.command.rule_installed", "Installed %s.", value));
    }

    private void verify(CommandSender sender, String[] arguments) {
        if (arguments.length > 3) {
            throw CommandSupport.usage("/mahjong rules verify [id]");
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
            throw CommandSupport.usage("/mahjong rules activate <id> <version>");
        }
        support.complete(
                sender,
                support.runtime()
                        .activateRule(CommandSupport.ruleId(arguments[2]), arguments[3]),
                value -> CommandSupport.message(
                        "mahjongpaper.command.rule_activation_pending",
                        "Activation pending restart: %s.",
                        value));
    }

    private static CommandMessage inventory(RulePackInventory inventory) {
        if (inventory.installed().isEmpty()) {
            return CommandSupport.message(
                    "mahjongpaper.command.no_rule_packs", "No rule packs are installed.");
        }
        String detail = inventory.installed().stream()
                .map(
                        pack ->
                                pack.ruleId()
                                        + ":"
                                        + pack.version()
                                        + (pack.active() ? "[active]" : "")
                                        + (pack.pending() ? "[pending-restart]" : ""))
                .collect(java.util.stream.Collectors.joining(", "));
        return CommandSupport.message(
                "mahjongpaper.command.rules_result", "Rule packs: %s", detail);
    }

    private static CommandMessage verification(List<RulePackVerification> results) {
        if (results.isEmpty()) {
            return CommandSupport.message(
                    "mahjongpaper.command.no_rule_packs_matched",
                    "No installed rule packs matched.");
        }
        String detail = results.stream()
                .map(
                        result ->
                                result.ruleId()
                                        + ":"
                                        + result.version()
                                        + '='
                                        + (result.valid() ? "valid" : result.detail()))
                .collect(java.util.stream.Collectors.joining(", "));
        return CommandSupport.message(
                "mahjongpaper.command.rules_result", "Rule packs: %s", detail);
    }
}
