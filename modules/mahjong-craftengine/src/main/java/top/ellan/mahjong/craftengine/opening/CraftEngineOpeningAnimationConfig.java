package top.ellan.mahjong.craftengine.opening;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/** Restart-scoped orchestration values; furniture appearance itself remains in CraftEngine YAML. */
public record CraftEngineOpeningAnimationConfig(
        String diceSlotAssetPrefix,
        Duration rollDuration,
        Duration revealDuration) {
    private static final Pattern ASSET_PREFIX = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public CraftEngineOpeningAnimationConfig {
        diceSlotAssetPrefix = Objects.requireNonNull(
                        diceSlotAssetPrefix, "diceSlotAssetPrefix")
                .trim();
        if (!ASSET_PREFIX.matcher(diceSlotAssetPrefix).matches()) {
            throw new IllegalArgumentException("Invalid CraftEngine dice-slot asset prefix");
        }
        Objects.requireNonNull(rollDuration, "rollDuration");
        Objects.requireNonNull(revealDuration, "revealDuration");
        if (rollDuration.isZero()
                || rollDuration.isNegative()
                || revealDuration.isZero()
                || revealDuration.isNegative()
                || rollDuration.compareTo(Duration.ofSeconds(10)) > 0
                || revealDuration.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("opening durations must be positive");
        }
    }

    String asset(int slot) {
        if (slot < 0 || slot > 3) {
            throw new IllegalArgumentException("dice slot must be between zero and three");
        }
        return diceSlotAssetPrefix + slot;
    }

    String variant(boolean doubleLayout, int point) {
        if (point < 1 || point > 6) {
            throw new IllegalArgumentException("dice point must be between one and six");
        }
        return (doubleLayout ? "double" : "single") + "_face_" + point;
    }

    String rollingVariant(boolean doubleLayout) {
        return (doubleLayout ? "double" : "single") + "_rolling";
    }
}
