package top.ellan.mahjong.spi;

/** Semantic placement zone; concrete geometry remains a presentation concern. */
public enum RuleViewZone {
    /** Face-down or otherwise public physical wall tiles. */
    WALL,
    /** Tiles held by one seated player. */
    HAND,
    /** Tiles discarded by one seated player. */
    DISCARD,
    /** Exposed meld tiles owned by one seated player. */
    MELD,
    /** Bonus or flower tiles displayed beside a player's hand. */
    FLOWER,
    /** Tiles displayed as part of a winning claim. */
    WIN_CLAIM,
    /** Dora or other rule-defined indicator tiles. */
    INDICATOR,
    /** Physical point or declaration sticks. */
    POINT_STICK,
    /** Rule-defined auxiliary table presentation. */
    AUXILIARY
}
