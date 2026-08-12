package top.ellan.mahjong.spi;

/** Additional in-plane rotation requested by a rule view. */
public enum RuleTileRotation {
    /** Uses the presentation zone's natural orientation. */
    NATURAL(0),
    /** Rotates the tile one quarter-turn clockwise. */
    CLOCKWISE(1),
    /** Rotates the tile one quarter-turn counterclockwise. */
    COUNTERCLOCKWISE(-1);

    private final int quarterTurns;

    RuleTileRotation(int quarterTurns) {
        this.quarterTurns = quarterTurns;
    }

    /**
     * Returns the signed number of clockwise quarter-turns.
     *
     * @return signed clockwise quarter-turn count
     */
    public int quarterTurns() {
        return quarterTurns;
    }
}
