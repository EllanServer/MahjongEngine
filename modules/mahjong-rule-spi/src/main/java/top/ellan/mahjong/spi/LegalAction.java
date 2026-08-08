package top.ellan.mahjong.spi;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** A legal action candidate generated solely from rule state. */
public record LegalAction(String key, RuleAction action, Map<String, String> presentation) {
    private static final Pattern VALID_KEY = Pattern.compile("[a-z0-9][a-z0-9._:-]{0,95}");

    public LegalAction {
        key = Objects.requireNonNull(key, "key");
        Objects.requireNonNull(action, "action");
        presentation = Map.copyOf(Objects.requireNonNull(presentation, "presentation"));
        if (!VALID_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid legal-action key: " + key);
        }
    }
}
