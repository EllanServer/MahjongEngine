package top.ellan.mahjong.plugin.feedback;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import top.ellan.mahjong.application.feedback.HumanDecisionWarning;
import top.ellan.mahjong.application.feedback.HumanDecisionWarningPort;
import top.ellan.mahjong.craftengine.port.PlayerTextResolver;
import top.ellan.mahjong.plugin.MahjongPaperPlugin;

/**
 * Restores the 1.5.0 action-bar warning shown before a seat is played automatically.
 *
 * <p>Delivery is enqueued onto the target player's own scheduler, so the shared deadline scheduler is
 * never blocked and Folia region ownership is respected. An offline seat is simply skipped.
 */
public final class HumanDecisionWarningPresenter implements HumanDecisionWarningPort {
    private final MahjongPaperPlugin plugin;
    private final PlayerTextResolver messages;

    public HumanDecisionWarningPresenter(
            MahjongPaperPlugin plugin, PlayerTextResolver messages) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    @Override
    public void publish(HumanDecisionWarning warning) {
        Objects.requireNonNull(warning, "warning");
        Player player = plugin.getServer().getPlayer(warning.actor().value());
        if (player == null || !player.isOnline()) {
            return;
        }
        player.getScheduler()
                .run(plugin, ignored -> player.sendActionBar(message(player, warning)), null);
    }

    private Component message(Player player, HumanDecisionWarning warning) {
        String key = warning.discardTurn()
                ? "mahjongpaper.feedback.auto_discard_in"
                : "mahjongpaper.feedback.auto_skip_in";
        String fallback =
                warning.discardTurn() ? "Auto-discard in %ss" : "Auto-skip in %ss";
        return Component.text(
                String.format(
                        player.locale(),
                        messages.resolve(player.locale(), key, fallback),
                        warning.remainingSeconds()),
                NamedTextColor.GOLD);
    }
}
