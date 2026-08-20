package top.ellan.mahjong.plugin.history;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import top.ellan.mahjong.application.history.RankProgression;
import top.ellan.mahjong.application.history.RankProgressionPort;
import top.ellan.mahjong.application.history.RankProgressionRequest;
import top.ellan.mahjong.domain.match.RankMatchLength;
import top.ellan.mahjong.domain.match.RankProfile;
import top.ellan.mahjong.domain.match.RankRoom;
import top.ellan.mahjong.plugin.config.PluginConfiguration;
import top.ellan.mahjong.spi.PlayerId;

/**
 * Resolves the configured room and match length, then applies the shared ladder.
 *
 * <p>The ladder itself is variant independent, so this only supplies deployment configuration. Match
 * length is read from the rule profile the match ran: a profile naming an east-only game is ranked
 * against the east room, everything else against the south room, which matches how 1.5.0 chose
 * between its two configured rooms.
 *
 * <p>Called from the terminal-result transaction on the persistence IO thread. It reads an immutable
 * settings snapshot and performs no blocking work, so it adds no synchronisation of its own.
 */
public final class ConfiguredRankProgression implements RankProgressionPort {
    private final Supplier<PluginConfiguration> settings;

    public ConfiguredRankProgression(Supplier<PluginConfiguration> settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public Map<PlayerId, RankProfile> advance(RankProgressionRequest request) {
        Objects.requireNonNull(request, "request");
        PluginConfiguration.RankingSettings ranking = settings.get().ranking();
        if (!ranking.enabled()) {
            return Map.of();
        }
        RankMatchLength length = lengthOf(request.profileId());
        RankRoom room = length == RankMatchLength.EAST ? ranking.eastRoom() : ranking.southRoom();
        return RankProgression.apply(request.standings(), request.current(), room, length)
                .entrySet()
                .stream()
                .collect(
                        java.util.stream.Collectors.toMap(
                                Map.Entry::getKey, entry -> entry.getValue().updated()));
    }

    /**
     * An east-only game is named by its profile. Every pack spells it differently — {@code tonpuusen}
     * in riichi, {@code east} elsewhere — so both spellings are recognised.
     */
    private static RankMatchLength lengthOf(String profileId) {
        String normalized = profileId.toLowerCase(Locale.ROOT);
        boolean eastOnly = normalized.contains("tonpuusen")
                || normalized.contains("east-only")
                || normalized.contains("east_only")
                || normalized.endsWith("-east")
                || normalized.endsWith("_east")
                || normalized.equals("east");
        return eastOnly ? RankMatchLength.EAST : RankMatchLength.SOUTH;
    }
}
