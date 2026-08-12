package top.ellan.mahjong.spi;

import java.util.Map;
import java.util.Objects;

/** Deep size checks for provider-authored view metadata. */
final class RuleOutputLimits {
    private static final int MAX_ATTRIBUTE_KEY_CHARS = 96;
    private static final int MAX_ATTRIBUTE_VALUE_CHARS = 1_024;
    private static final int MAX_ATTRIBUTE_CHARS = 65_536;

    private RuleOutputLimits() {}

    static Map<String, String> copyAttributes(Map<String, String> attributes) {
        long characters = 0;
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "attribute key");
            String value = Objects.requireNonNull(entry.getValue(), "attribute value");
            if (key.length() > MAX_ATTRIBUTE_KEY_CHARS
                    || value.length() > MAX_ATTRIBUTE_VALUE_CHARS) {
                throw new IllegalArgumentException("Rule view attribute is too large");
            }
            characters += (long) key.length() + value.length();
            if (characters > MAX_ATTRIBUTE_CHARS) {
                throw new IllegalArgumentException("Rule view attributes exceed 64 Ki characters");
            }
        }
        return Map.copyOf(attributes);
    }
}
