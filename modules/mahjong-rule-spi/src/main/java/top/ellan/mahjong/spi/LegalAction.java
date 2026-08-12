package top.ellan.mahjong.spi;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A legal action candidate generated solely from rule state.
 *
 * @param key stable key distinguishing this candidate in the current state
 * @param action opaque rule action submitted when the candidate is selected
 * @param actionPresentation client presentation metadata for the candidate
 */
public record LegalAction(
        String key,
        RuleAction action,
        ActionPresentation actionPresentation) {
    private static final Pattern VALID_KEY = Pattern.compile("[a-z0-9][a-z0-9._:-]{0,95}");

    /**
     * Creates a validated legal-action candidate.
     *
     * @param key stable key distinguishing this candidate in the current state
     * @param action opaque rule action submitted when the candidate is selected
     * @param actionPresentation client presentation metadata for the candidate
     */
    public LegalAction {
        key = Objects.requireNonNull(key, "key");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actionPresentation, "actionPresentation");
        if (!VALID_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid legal-action key: " + key);
        }
    }
}
