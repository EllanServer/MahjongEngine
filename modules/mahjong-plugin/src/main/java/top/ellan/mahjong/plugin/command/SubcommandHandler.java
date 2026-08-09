package top.ellan.mahjong.plugin.command;

import java.util.List;
import java.util.Set;
import org.bukkit.command.CommandSender;

/** One independently testable command concern. */
public interface SubcommandHandler {
    Set<String> names();

    void execute(CommandSender sender, String[] arguments);

    default List<String> complete(CommandSender sender, String[] arguments) {
        return List.of();
    }
}
