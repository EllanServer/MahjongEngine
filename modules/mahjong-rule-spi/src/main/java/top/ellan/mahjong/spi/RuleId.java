package top.ellan.mahjong.spi;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable identifier of a rule family.
 *
 * @param value normalized rule-family identifier
 */
public record RuleId(String value) implements Comparable<RuleId> {
    private static final Pattern VALID = Pattern.compile("[a-z][a-z0-9-]{1,31}");

    public RuleId {
        value = Objects.requireNonNull(value, "value").toLowerCase(Locale.ROOT);
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid rule id: " + value);
        }
    }

    public static RuleId of(String value) {
        return new RuleId(value);
    }

    @Override
    public int compareTo(RuleId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
