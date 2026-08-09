package top.ellan.mahjong.spi;

/** Additional in-plane rotation requested by a rule view. */
public enum RuleTileRotation {
    NATURAL(0),
    CLOCKWISE(1),
    COUNTERCLOCKWISE(-1);

    private final int quarterTurns;

    RuleTileRotation(int quarterTurns) {
        this.quarterTurns = quarterTurns;
    }

    public int quarterTurns() {
        return quarterTurns;
    }
}
