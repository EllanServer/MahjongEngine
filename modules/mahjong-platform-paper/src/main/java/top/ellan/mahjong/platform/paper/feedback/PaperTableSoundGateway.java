package top.ellan.mahjong.platform.paper.feedback;

import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import top.ellan.mahjong.application.feedback.TableCueBatch;
import top.ellan.mahjong.application.feedback.TablePresentationCuePort;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RulePresentationCue;
import top.ellan.mahjong.spi.RulePresentationCueType;

/** Schedules bounded sound delivery onto each target player's Folia-owned scheduler. */
public final class PaperTableSoundGateway implements TablePresentationCuePort {
    private final Plugin plugin;

    public PaperTableSoundGateway(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void publish(TableCueBatch batch) {
        Objects.requireNonNull(batch, "batch");
        for (PlayerId playerId : batch.audience()) {
            if (!hasCueFor(batch, playerId)) {
                continue;
            }
            Player player = plugin.getServer().getPlayer(playerId.value());
            if (player == null) {
                continue;
            }
            try {
                player.getScheduler().run(
                        plugin,
                        ignored -> playApplicable(batch, playerId, player),
                        null);
            } catch (RuntimeException schedulingFailure) {
                plugin.getLogger().log(
                        Level.FINE,
                        "Skipped table sound scheduling for " + batch.tableId(),
                        schedulingFailure);
            }
        }
    }

    private static boolean hasCueFor(TableCueBatch batch, PlayerId playerId) {
        for (RulePresentationCue cue : batch.cues()) {
            if (cue.target().isEmpty() || cue.target().orElseThrow().equals(playerId)) {
                return true;
            }
        }
        return false;
    }

    private static void playApplicable(
            TableCueBatch batch, PlayerId playerId, Player player) {
        if (!player.isOnline()) {
            return;
        }
        for (RulePresentationCue cue : batch.cues()) {
            if (cue.target().isPresent()
                    && !cue.target().orElseThrow().equals(playerId)) {
                continue;
            }
            SoundProfile profile = profile(cue.type());
            player.playSound(
                    player.getLocation(),
                    profile.key(),
                    profile.volume(),
                    profile.pitch());
        }
    }

    private static SoundProfile profile(RulePresentationCueType type) {
        String key = type == RulePresentationCueType.RIICHI
                ? "minecraft:block.note_block.bell"
                : "mahjongcraft:" + type.name().toLowerCase(Locale.ROOT);
        return switch (type) {
            case TILE_SHUFFLE -> new SoundProfile(key, 0.9F, 1.2F);
            case TILE_DRAW -> new SoundProfile(key, 0.65F, 1.05F);
            case TILE_DISCARD -> new SoundProfile(key, 0.75F, 1.05F);
            case REACTION_CHI, REACTION_PON, REACTION_KAN ->
                    new SoundProfile(key, 0.8F, 1.1F);
            case RIICHI -> new SoundProfile(key, 0.8F, 1.25F);
            case ROUND_WIN, ROUND_DRAW -> new SoundProfile(key, 0.9F, 1.0F);
            case TURN_CHANGE -> new SoundProfile(key, 0.5F, 1.6F);
        };
    }

    private record SoundProfile(String key, float volume, float pitch) {}
}
