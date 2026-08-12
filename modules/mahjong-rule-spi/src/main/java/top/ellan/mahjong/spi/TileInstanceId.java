package top.ellan.mahjong.spi;

/**
 * Physical tile identity; distinct copies of the same face must have distinct values.
 *
 * @param value non-negative match-local tile identity
 */
public record TileInstanceId(long value) implements Comparable<TileInstanceId> {
    public TileInstanceId {
        if (value < 0) {
            throw new IllegalArgumentException("Tile instance id must be non-negative");
        }
    }

    @Override
    public int compareTo(TileInstanceId other) {
        return Long.compare(value, other.value);
    }
}
