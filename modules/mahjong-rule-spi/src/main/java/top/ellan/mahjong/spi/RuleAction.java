package top.ellan.mahjong.spi;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Canonical opaque action passed through the parent classloader boundary.
 *
 * @param type stable action type identifier
 * @param payload bounded rule-defined action payload
 */
public record RuleAction(String type, byte[] payload) {
    private static final Pattern VALID_TYPE = Pattern.compile("[a-z][a-z0-9._-]{0,63}");

    public RuleAction {
        type = Objects.requireNonNull(type, "type");
        payload = Objects.requireNonNull(payload, "payload");
        if (!VALID_TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("Invalid action type: " + type);
        }
        if (payload.length > 65_536) {
            throw new IllegalArgumentException("Action payload exceeds 64 KiB");
        }
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof RuleAction action
                        && type.equals(action.type)
                        && java.util.Arrays.equals(payload, action.payload);
    }

    @Override
    public int hashCode() {
        return 31 * type.hashCode() + java.util.Arrays.hashCode(payload);
    }
}
