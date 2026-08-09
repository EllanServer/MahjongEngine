package top.ellan.mahjong.spi;

/**
 * Platform-neutral, transient feedback emitted by an accepted rule transition.
 *
 * <p>Cues are deliberately separate from persisted {@link RuleEvent}s. Replaying an event log or
 * rebuilding a scene must never replay a sound or other one-shot feedback.</p>
 */
public enum RulePresentationCueType {
    TILE_SHUFFLE,
    TILE_DRAW,
    TILE_DISCARD,
    REACTION_CHI,
    REACTION_PON,
    REACTION_KAN,
    RIICHI,
    ROUND_WIN,
    ROUND_DRAW,
    TURN_CHANGE
}
