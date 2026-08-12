package top.ellan.mahjong.spi;

/**
 * Fixed 128-bit seed supplied by the core; packs must derive all randomness from it.
 *
 * @param high high-order 64 bits of the seed
 * @param low low-order 64 bits of the seed
 */
public record MatchSeed(long high, long low) {}
