package top.ellan.mahjong.spi;

/** Semantic role of one tile in an open meld; physical placement is owned by the shared SPI. */
public enum RuleMeldTileRole {
    /** A tile supplied from the caller's concealed hand. */
    ORDINARY,
    /** The sideways discard that identifies which opponent supplied the meld. */
    CLAIMED,
    /** The fourth tile stacked on the original claimed tile when a pung becomes a kong. */
    ADDED
}
