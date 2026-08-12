package top.ellan.mahjong.spi;

/** Preferred interaction surface for an authorized action. */
public enum ActionPlacement {
    /** Action is selected directly from a tile in the player's hand. */
    HAND_TILE,
    /** Action is shown in the primary action row. */
    ACTION_ROW,
    /** Action is shown in the secondary action row. */
    SECONDARY_ROW
}
