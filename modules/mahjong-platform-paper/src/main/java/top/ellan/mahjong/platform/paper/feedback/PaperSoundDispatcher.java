package top.ellan.mahjong.platform.paper.feedback;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import top.ellan.mahjong.spi.PlayerId;

/** Batches each recipient's sounds into one non-blocking Folia player-scheduler task. */
public final class PaperSoundDispatcher {
    private final Plugin plugin;

    public PaperSoundDispatcher(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void play(PlayerId playerId, List<PaperSoundProfile> profiles) {
        Objects.requireNonNull(playerId, "playerId");
        List<PaperSoundProfile> sounds = List.copyOf(Objects.requireNonNull(profiles, "profiles"));
        if (sounds.isEmpty()) {
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId.value());
        if (player == null) {
            return;
        }
        try {
            player.getScheduler().run(plugin, ignored -> playNow(player, sounds), null);
        } catch (RuntimeException schedulingFailure) {
            plugin.getLogger().log(Level.FINE, "Skipped player sound scheduling", schedulingFailure);
        }
    }

    public void play(List<PlayerId> audience, PaperSoundProfile profile) {
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(profile, "profile");
        List<PaperSoundProfile> single = List.of(profile);
        for (PlayerId playerId : audience) {
            play(playerId, single);
        }
    }

    private static void playNow(Player player, List<PaperSoundProfile> profiles) {
        if (!player.isOnline()) {
            return;
        }
        for (PaperSoundProfile profile : profiles) {
            player.playSound(
                    player.getLocation(), profile.key(), profile.volume(), profile.pitch());
        }
    }
}
