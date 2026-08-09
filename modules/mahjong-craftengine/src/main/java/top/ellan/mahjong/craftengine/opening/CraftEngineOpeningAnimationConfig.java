package top.ellan.mahjong.craftengine.opening;

import java.time.Duration;
import java.util.Objects;

/** Restart-scoped orchestration values; furniture appearance itself remains in CraftEngine YAML. */
public record CraftEngineOpeningAnimationConfig(
        String diceFaceAssetPrefix,
        int previewFrames,
        Duration rollDuration,
        Duration revealDuration,
        double diceSpacing,
        double tableHeight) {
    public CraftEngineOpeningAnimationConfig {
        diceFaceAssetPrefix = Objects.requireNonNull(
                        diceFaceAssetPrefix, "diceFaceAssetPrefix")
                .trim();
        if (!diceFaceAssetPrefix.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Invalid CraftEngine dice asset prefix");
        }
        if (previewFrames < 1 || previewFrames > 6) {
            throw new IllegalArgumentException("previewFrames must be between one and six");
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
        if (!Double.isFinite(diceSpacing)
                || diceSpacing < 0.1D
                || diceSpacing > 0.5D
                || !Double.isFinite(tableHeight)
                || tableHeight < 0.1D
                || tableHeight > 2.0D) {
            throw new IllegalArgumentException("opening dice geometry is outside its safe range");
        }
    }

    String asset(int point) {
        if (point < 1 || point > 6) {
            throw new IllegalArgumentException("dice point must be between one and six");
        }
        return diceFaceAssetPrefix + point;
    }
}
