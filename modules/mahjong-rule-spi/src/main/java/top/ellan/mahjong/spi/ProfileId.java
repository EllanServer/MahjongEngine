package top.ellan.mahjong.spi;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable profile name within one rule family. */
public record ProfileId(String value) implements Comparable<ProfileId> {
    private static final Pattern VALID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public ProfileId {
        value = Objects.requireNonNull(value, "value");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid profile id: " + value);
        }
    }

    @Override
    public int compareTo(ProfileId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
