package top.ellan.mahjong.platform.paper.feedback;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import top.ellan.mahjong.spi.RulePresentationCueType;

/** Paper playback profiles loaded from one verified rule resource pack. */
public record RuleSoundProfiles(
        Map<RulePresentationCueType, PaperSoundProfile> cues,
        PaperSoundProfile openingDice,
        PaperSoundProfile openingWall) {
    public RuleSoundProfiles {
        EnumMap<RulePresentationCueType, PaperSoundProfile> copy =
                new EnumMap<>(RulePresentationCueType.class);
        copy.putAll(Objects.requireNonNull(cues, "cues"));
        cues = Map.copyOf(copy);
        Objects.requireNonNull(openingDice, "openingDice");
        Objects.requireNonNull(openingWall, "openingWall");
    }
}
