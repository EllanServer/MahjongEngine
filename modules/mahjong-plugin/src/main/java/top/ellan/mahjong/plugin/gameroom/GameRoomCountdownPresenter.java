package top.ellan.mahjong.plugin.gameroom;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import top.ellan.mahjong.plugin.MahjongPaperPlugin;
import top.ellan.mahjong.plugin.i18n.LocalizedMessageCatalog;
import top.ellan.mahjong.spi.PlayerId;

/**
 * v1.5 countdown warning cadence for game-room departures: every 15 seconds before the final
 * ten, then 10, 8, 6, 5, 4, 3, 2, and 1 seconds. The controller keeps one timer per table.
 */
final class GameRoomCountdownPresenter {
    private GameRoomCountdownPresenter() {}

    static void sendCountdownWarnings(
            MahjongPaperPlugin plugin,
            LocalizedMessageCatalog messages,
            List<Map.Entry<PlayerId, Long>> deadlines) {
        long now = System.nanoTime();
        deadlines.forEach(entry -> {
            PlayerId playerId = entry.getKey();
            long deadline = entry.getValue();
            int remaining = remainingSeconds(deadline, now);
            if (!isCountdownWarningSecond(remaining)) {
                return;
            }
            Player player = plugin.getServer().getPlayer(playerId.value());
            if (player == null || !player.isOnline()) {
                return;
            }
            player.getScheduler()
                    .run(
                            plugin,
                            ignored ->
                                    player.sendMessage(
                                            Component.text(
                                                    String.format(
                                                            player.locale(),
                                                            messages.resolve(
                                                                    player.locale(),
                                                                    "mahjongpaper.gameroom.countdown",
                                                                    "Match ends in %s seconds if you do not return."),
                                                            remaining),
                                                    NamedTextColor.RED)),
                            null);
        });
    }

    static long nextWarningInstant(long earliestDeadline, long now) {
        if (now >= earliestDeadline || remainingSeconds(earliestDeadline, now) <= 1) {
            return Long.MAX_VALUE;
        }
        int remaining = remainingSeconds(earliestDeadline, now);
        int target = nextWarningSecond(remaining);
        return target <= 0
                ? Long.MAX_VALUE
                : earliestDeadline - Duration.ofSeconds(target).toNanos();
    }

    static int remainingSeconds(long deadline, long now) {
        if (now >= deadline) {
            return 0;
        }
        long nanos = deadline - now;
        return (int) Math.min(Integer.MAX_VALUE, (nanos + 999_999_999L) / 1_000_000_000L);
    }

    static int nextWarningSecond(int remainingSeconds) {
        if (remainingSeconds <= 1) {
            return remainingSeconds == 1 ? 1 : 0;
        }
        if (remainingSeconds <= 10) {
            return switch (remainingSeconds) {
                case 10 -> 8;
                case 9 -> 8;
                case 8 -> 6;
                case 7 -> 6;
                case 6 -> 5;
                default -> remainingSeconds - 1;
            };
        }
        int multiple = ((remainingSeconds - 1) / 15) * 15;
        return multiple > 10 ? multiple : 10;
    }

    static boolean isCountdownWarningSecond(int remainingSeconds) {
        if (remainingSeconds <= 0) {
            return false;
        }
        if (remainingSeconds <= 10) {
            return remainingSeconds == 10
                    || remainingSeconds == 8
                    || remainingSeconds == 6
                    || remainingSeconds <= 5;
        }
        return remainingSeconds % 15 == 0;
    }
}
