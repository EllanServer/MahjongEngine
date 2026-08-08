package top.ellan.mahjong.command;

import java.util.List;
import org.bukkit.command.CommandSender;

/** Platform command boundary for blocking signed rule-pack administration. */
public interface RulePackCommandHandler {
    void execute(CommandSender sender, String[] args);

    List<String> suggestions(String[] args);

    static RulePackCommandHandler unavailable(String reason) {
        String detail = reason == null || reason.isBlank() ? "not configured" : reason;
        return new RulePackCommandHandler() {
            @Override
            public void execute(CommandSender sender, String[] args) {
                sender.sendMessage("Rule-pack administration unavailable: " + detail);
            }

            @Override
            public List<String> suggestions(String[] args) {
                return List.of("list");
            }
        };
    }
}
