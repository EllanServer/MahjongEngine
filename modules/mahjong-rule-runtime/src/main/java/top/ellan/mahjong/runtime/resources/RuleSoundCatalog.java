package top.ellan.mahjong.runtime.resources;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import top.ellan.mahjong.spi.RulePresentationCueType;

/** All transient sound mappings owned by one rule resource pack. */
public record RuleSoundCatalog(
        Map<RulePresentationCueType, RuleSoundProfile> cues,
        RuleSoundProfile openingDice,
        RuleSoundProfile openingWall) {
    public RuleSoundCatalog {
        EnumMap<RulePresentationCueType, RuleSoundProfile> copy =
                new EnumMap<>(RulePresentationCueType.class);
        copy.putAll(Objects.requireNonNull(cues, "cues"));
        cues = Map.copyOf(copy);
        Objects.requireNonNull(openingDice, "openingDice");
        Objects.requireNonNull(openingWall, "openingWall");
    }
}
