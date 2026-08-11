package top.ellan.mahjong.spi;

/**
 * Zero-based clockwise seat index; seat zero is the table-local positive-Z side.
 * The upper bound deliberately supports future variants.
 */
public record SeatId(int value) implements Comparable<SeatId> {
    public SeatId {
        if (value < 0 || value > 7) {
            throw new IllegalArgumentException("Seat must be between 0 and 7: " + value);
        }
    }

    @Override
    public int compareTo(SeatId other) {
        return Integer.compare(value, other.value);
    }

    @Override
    public String toString() {
        return Integer.toString(value);
    }
}
