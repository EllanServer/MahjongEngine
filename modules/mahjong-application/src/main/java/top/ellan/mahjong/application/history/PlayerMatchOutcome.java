package top.ellan.mahjong.application.history;

/** Final rule-pack outcome attached to one player's match history row. */
public record PlayerMatchOutcome(
        int placement, long score, long rankingPointsMilli) {
    public PlayerMatchOutcome {
        if (placement < 1 || placement > 8) {
            throw new IllegalArgumentException("Placement must be between 1 and 8");
        }
    }
}
