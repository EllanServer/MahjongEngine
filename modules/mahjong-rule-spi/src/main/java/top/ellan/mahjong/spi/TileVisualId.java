package top.ellan.mahjong.spi;

import java.util.Objects;
import java.util.regex.Pattern;

/** Asset-level tile face identifier understood by the presentation layer. */
public record TileVisualId(String value) implements Comparable<TileVisualId> {
    private static final Pattern VALID = Pattern.compile("[a-z0-9][a-z0-9._:/-]{0,95}");

    public TileVisualId {
        value = Objects.requireNonNull(value, "value");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid tile visual id: " + value);
        }
    }

    @Override
    public int compareTo(TileVisualId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
