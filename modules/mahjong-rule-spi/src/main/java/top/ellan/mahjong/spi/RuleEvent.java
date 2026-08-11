package top.ellan.mahjong.spi;

import java.util.Objects;
import java.util.regex.Pattern;

/** Canonical event summary persisted by the core. */
public record RuleEvent(String type, byte[] canonicalPayload) {
    private static final Pattern VALID_TYPE = Pattern.compile("[a-z][a-z0-9._-]{0,63}");

    public RuleEvent {
        type = Objects.requireNonNull(type, "type");
        canonicalPayload = Objects.requireNonNull(canonicalPayload, "canonicalPayload").clone();
        if (!VALID_TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("Invalid event type: " + type);
        }
        if (canonicalPayload.length > 1_048_576) {
            throw new IllegalArgumentException("Event payload exceeds 1 MiB");
        }
    }

    @Override
    public byte[] canonicalPayload() {
        return canonicalPayload.clone();
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof RuleEvent event
                        && type.equals(event.type)
                        && java.util.Arrays.equals(canonicalPayload, event.canonicalPayload);
    }

    @Override
    public int hashCode() {
        return 31 * type.hashCode() + java.util.Arrays.hashCode(canonicalPayload);
    }
}
