package top.ellan.mahjong.platform.paper.feedback;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import top.ellan.mahjong.spi.RulePackRef;
import top.ellan.mahjong.spi.RulePresentationCueType;

/** Thread-safe snapshot of sound profiles supplied by exact rule resource coordinates. */
public final class PaperRuleSoundCatalog {
    private volatile Map<Coordinate, RuleSoundProfiles> profiles = Map.of();

    public Optional<PaperSoundProfile> cue(
            RulePackRef reference, RulePresentationCueType type) {
        RuleSoundProfiles rule = profiles.get(Coordinate.from(reference));
        return rule == null
                ? Optional.empty()
                : Optional.ofNullable(rule.cues().get(Objects.requireNonNull(type, "type")));
    }

    public Optional<PaperSoundProfile> openingDice(RulePackRef reference) {
        RuleSoundProfiles rule = profiles.get(Coordinate.from(reference));
        return rule == null ? Optional.empty() : Optional.of(rule.openingDice());
    }

    public Optional<PaperSoundProfile> openingWall(RulePackRef reference) {
        RuleSoundProfiles rule = profiles.get(Coordinate.from(reference));
        return rule == null ? Optional.empty() : Optional.of(rule.openingWall());
    }

    public synchronized void replaceAll(Collection<RuleSoundBinding> replacement) {
        LinkedHashMap<Coordinate, RuleSoundProfiles> copy = new LinkedHashMap<>();
        for (RuleSoundBinding binding : List.copyOf(replacement)) {
            if (copy.put(Coordinate.from(binding), binding.profiles()) != null) {
                throw new IllegalArgumentException("Duplicate rule sound coordinate");
            }
        }
        profiles = Map.copyOf(copy);
    }

    public synchronized void put(RuleSoundBinding binding) {
        Objects.requireNonNull(binding, "binding");
        LinkedHashMap<Coordinate, RuleSoundProfiles> copy = new LinkedHashMap<>(profiles);
        copy.put(Coordinate.from(binding), binding.profiles());
        profiles = Map.copyOf(copy);
    }

    public synchronized void remove(RulePackRef reference) {
        Coordinate coordinate = Coordinate.from(reference);
        if (!profiles.containsKey(coordinate)) {
            return;
        }
        LinkedHashMap<Coordinate, RuleSoundProfiles> copy = new LinkedHashMap<>(profiles);
        copy.remove(coordinate);
        profiles = Map.copyOf(copy);
    }

    private record Coordinate(
            top.ellan.mahjong.spi.RuleId ruleId, String version, String jarSha256) {
        private static Coordinate from(RulePackRef reference) {
            Objects.requireNonNull(reference, "reference");
            return new Coordinate(
                    reference.ruleId(), reference.version(), reference.jarSha256());
        }

        private static Coordinate from(RuleSoundBinding binding) {
            return new Coordinate(binding.ruleId(), binding.version(), binding.jarSha256());
        }
    }
}
