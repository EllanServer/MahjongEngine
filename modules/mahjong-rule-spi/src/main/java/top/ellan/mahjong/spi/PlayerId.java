package top.ellan.mahjong.spi;

import java.util.Objects;
import java.util.UUID;

/**
 * Platform-neutral identity of a player.
 *
 * @param value stable UUID supplied by the platform
 */
public record PlayerId(UUID value) implements Comparable<PlayerId> {
    /**
     * Creates a platform-neutral player identity.
     *
     * @param value stable UUID supplied by the platform
     */
    public PlayerId {
        Objects.requireNonNull(value, "value");
    }

    /**
     * Parses a canonical UUID string as a player identity.
     *
     * @param value canonical UUID string
     * @return parsed player identity
     */
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
