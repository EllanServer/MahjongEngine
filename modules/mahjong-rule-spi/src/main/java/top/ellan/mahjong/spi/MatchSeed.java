package top.ellan.mahjong.spi;

/** Fixed 128-bit seed supplied by the core; packs must derive all randomness from it. */
public record MatchSeed(long high, long low) {}
