package top.ellan.mahjong.plugin.command.handler;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.ellan.mahjong.plugin.command.CommandSupport;
import top.ellan.mahjong.plugin.command.SubcommandHandler;
import top.ellan.mahjong.spi.PlayerId;

/** Manual trustee control for a human seat in an active match. */
public final class MatchAutomationHandler implements SubcommandHandler {
    private static final Set<String> NAMES = Set.of("auto", "trustee");
    private final CommandSupport support;

    public MatchAutomationHandler(CommandSupport support) {
        this.support = java.util.Objects.requireNonNull(support, "support");
    }

    @Override
    public Set<String> names() {
        return NAMES;
    }

    @Override
    public void execute(CommandSender sender, String[] arguments) {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("Usage: /mahjong auto <on|off>");
        }
        Player player = support.requirePlayer(sender);
        boolean enabled = switch (arguments[1].toLowerCase(Locale.ROOT)) {
            case "on" -> true;
            case "off" -> false;
            default -> throw new IllegalArgumentException("Usage: /mahjong auto <on|off>");
        };
        support.complete(
                sender,
                support.runtime().setAutomation(
                        new PlayerId(player.getUniqueId()), enabled),
                result -> result.code() + " " + result.reasonCode());
    }

    @Override
    public List<String> complete(CommandSender sender, String[] arguments) {
        return arguments.length == 2
                ? CommandSupport.filter(arguments[1], List.of("on", "off"))
                : List.of();
    }
}
