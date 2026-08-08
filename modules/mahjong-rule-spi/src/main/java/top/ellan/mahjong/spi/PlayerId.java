package top.ellan.mahjong.spi;

import java.util.Objects;
import java.util.UUID;

/** Platform-neutral identity of a player. */
public record PlayerId(UUID value) implements Comparable<PlayerId> {
    public PlayerId {
        Objects.requireNonNull(value, "value");
    }

    public static PlayerId parse(String value) {
        return new PlayerId(UUID.fromString(value));
    }

    @Override
    public int compareTo(PlayerId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
