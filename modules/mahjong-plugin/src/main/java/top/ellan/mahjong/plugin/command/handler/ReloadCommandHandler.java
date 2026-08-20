package top.ellan.mahjong.plugin.command.handler;

import java.util.List;
import java.util.Set;
import org.bukkit.command.CommandSender;
import top.ellan.mahjong.plugin.command.CommandSupport;
import top.ellan.mahjong.plugin.command.SubcommandHandler;

/**
 * Reload entry point aligned with the 1.5.0 contract. 2.0 reloads the bounded runtime indexes that
 * are safe to refresh without replacing the composition root; rule-pack activation and core
 * configuration changes remain restart-scoped by design.
 */
public final class ReloadCommandHandler implements SubcommandHandler {
    private final CommandSupport support;

    public ReloadCommandHandler(CommandSupport support) {
        this.support = java.util.Objects.requireNonNull(support, "support");
    }

    @Override
    public Set<String> names() {
        return Set.of("reload");
    }

    @Override
    public void execute(CommandSender sender, String[] arguments) {
        support.requireAdmin(sender);
        if (arguments.length != 1) {
            throw CommandSupport.usage("/mahjong reload");
        }
        support.complete(
                sender,
                support.runtime().gameRooms().registry().reload(),
                ignored -> CommandSupport.message(
                        "mahjongpaper.command.reload_success",
                        "Reloaded %s indexed game rooms. Rule-pack activation and core config changes require a full restart.",
                        support.runtime().gameRooms().registry().size()));
    }

    @Override
    public List<String> complete(CommandSender sender, String[] arguments) {
        return List.of();
    }
}
