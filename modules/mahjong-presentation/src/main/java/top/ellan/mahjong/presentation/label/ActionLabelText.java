package top.ellan.mahjong.presentation.label;

import java.util.Objects;

/** Resolves one semantic action label and its optional tile/suit arguments. */
public final class ActionLabelText {
    private static final String TRANSLATION_PREFIX = "mahjongpaper.";

    private ActionLabelText() {}

    public static String resolve(String labelKey, TranslationLookup translations) {
        Objects.requireNonNull(labelKey, "labelKey");
        Objects.requireNonNull(translations, "translations");
        ParsedLabel parsed = parse(labelKey);
        StringBuilder text = new StringBuilder(translations.resolve(
                TRANSLATION_PREFIX + parsed.baseKey(), fallback(parsed.baseKey())));
        for (String argument : parsed.arguments()) {
            if (argument.isEmpty()) {
                continue;
            }
            text.append(' ');
            if (isLocalizedArgument(argument)) {
                text.append(translations.resolve(
                        TRANSLATION_PREFIX + argument, fallback(argument)));
            } else {
                text.append(argument);
            }
        }
        return ActionLabelPolicy.compactActionLabel(text.toString());
    }

    private static ParsedLabel parse(String labelKey) {
        int separator = labelKey.indexOf(':');
        if (separator < 0) {
            return new ParsedLabel(labelKey, new String[0]);
        }
        return new ParsedLabel(
                labelKey.substring(0, separator), arguments(labelKey, separator + 1));
    }

    private static String[] arguments(String labelKey, int start) {
        if (start >= labelKey.length()) {
            return new String[0];
        }
        int count = 1;
        for (int index = start; index < labelKey.length(); index++) {
            if (labelKey.charAt(index) == ':') {
                count++;
            }
        }
        String[] result = new String[count];
        int item = 0;
        int segmentStart = start;
        for (int index = start; index <= labelKey.length(); index++) {
            if (index == labelKey.length() || labelKey.charAt(index) == ':') {
                result[item++] = labelKey.substring(segmentStart, index);
                segmentStart = index + 1;
            }
        }
        return result;
    }

    private static boolean isLocalizedArgument(String argument) {
        return argument.startsWith("tile.") || argument.startsWith("suit.");
    }

    private static String fallback(String semanticKey) {
        int separator = semanticKey.lastIndexOf('.');
        String value = separator < 0 ? semanticKey : semanticKey.substring(separator + 1);
        return value.replace('_', ' ');
    }

    @FunctionalInterface
    public interface TranslationLookup {
        String resolve(String translationKey, String fallback);
    }

    private record ParsedLabel(String baseKey, String[] arguments) {}
}
