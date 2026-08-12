package top.ellan.mahjong.spi;

/**
 * Platform-neutral, transient feedback emitted by an accepted rule transition.
 *
 * <p>Cues are deliberately separate from persisted {@link RuleEvent}s. Replaying an event log or
 * rebuilding a scene must never replay a sound or other one-shot feedback.</p>
 */
public enum RulePresentationCueType {
    /** Tiles are being shuffled for a new hand. */
    TILE_SHUFFLE,
    /** A player draws a tile. */
    TILE_DRAW,
    /** A player discards a tile. */
    TILE_DISCARD,
    /** A chi reaction is accepted. */
    REACTION_CHI,
    /** A pon reaction is accepted. */
    REACTION_PON,
    /** A kan reaction or declaration is accepted. */
    REACTION_KAN,
    /** A player declares riichi. */
    RIICHI,
    /** A hand ends with one or more winners. */
    ROUND_WIN,
    /** A hand ends in a draw. */
    ROUND_DRAW,
    /** Control advances to another player. */
    TURN_CHANGE
}
