package top.ellan.mahjong.presentation.label;

import java.util.Locale;

/** Visual budgets and hitbox sizing copied from the 1.5 table action overlay. */
public final class ActionLabelPolicy {
    public static final int ACTION_LABEL_BUDGET = 20;
    public static final int BUTTONS_PER_ROW = 4;
    public static final double BUTTON_GAP = 0.16D;
    public static final double PINNED_BUTTON_GAP = 0.34D;
    public static final double EMPTY_ROW_PINNED_EDGE = 0.85D;

    private static final double LABEL_WIDTH_PER_UNIT = 0.085D;
    private static final double LABEL_BASE_WIDTH = 0.24D;
    private static final double LABEL_DECORATION_WIDTH = 0.18D;
    private static final double MIN_BUTTON_WIDTH = 0.7D;
    private static final double MAX_BUTTON_WIDTH = 2.2D;
    private static final double BUTTON_WIDTH_STEP = 0.1D;
    private static final int BUTTON_WIDTH_VARIANTS = 16;
    private static final String[] VARIANT_SUFFIXES = createVariantSuffixes();

    private ActionLabelPolicy() {}

    /** Returns the CE hitbox width bucket which fully contains the rendered compact label. */
    public static double buttonWidth(String label) {
        String compact = compactActionLabel(label);
        double estimated = LABEL_BASE_WIDTH
                + LABEL_DECORATION_WIDTH
                + visualUnits(compact) * LABEL_WIDTH_PER_UNIT;
        double clamped = Math.max(MIN_BUTTON_WIDTH, Math.min(MAX_BUTTON_WIDTH, estimated));
        return variantWidth(variantIndex(clamped));
    }

    public static int variantCount() {
        return BUTTON_WIDTH_VARIANTS;
    }

    public static int variantIndex(double width) {
        if (!Double.isFinite(width)) {
            throw new IllegalArgumentException("Action button width must be finite");
        }
        double clamped = Math.max(MIN_BUTTON_WIDTH, Math.min(MAX_BUTTON_WIDTH, width));
        int index = (int) Math.ceil((clamped - MIN_BUTTON_WIDTH - 1.0E-9D) / BUTTON_WIDTH_STEP);
        return Math.max(0, Math.min(BUTTON_WIDTH_VARIANTS - 1, index));
    }

    public static double variantWidth(int index) {
        requireVariant(index);
        return (70 + index * 10) / 100.0D;
    }

    public static String variantSuffix(int index) {
        requireVariant(index);
        return VARIANT_SUFFIXES[index];
    }

    public static String compactActionLabel(String label) {
        return compactText(label, ACTION_LABEL_BUDGET);
    }

    public static int visualUnits(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int width = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            width += visualUnits(codePoint);
            offset += Character.charCount(codePoint);
        }
        return width;
    }

    static String compactText(String text, int maxVisualUnits) {
        String normalized = normalizeText(text);
        if (maxVisualUnits <= 0 || normalized.isBlank()) {
            return "";
        }
        if (visualUnits(normalized) <= maxVisualUnits) {
            return normalized;
        }
        int contentBudget = Math.max(0, maxVisualUnits - 1);
        StringBuilder result = new StringBuilder(normalized.length());
        int width = 0;
        for (int offset = 0; offset < normalized.length(); ) {
            int codePoint = normalized.codePointAt(offset);
            int unit = visualUnits(codePoint);
            if (width + unit > contentBudget) {
                break;
            }
            result.appendCodePoint(codePoint);
            width += unit;
            offset += Character.charCount(codePoint);
        }
        return result.toString().stripTrailing() + '…';
    }

    private static int visualUnits(int codePoint) {
        int type = Character.getType(codePoint);
        if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK) {
            return 0;
        }
        if (codePoint >= 0x2E80
                || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
                || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HIRAGANA
                || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.KATAKANA) {
            return 2;
        }
        return codePoint >= 0x1F000 ? 2 : 1;
    }

    private static String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(value.length());
        boolean lastWhitespace = false;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            boolean whitespace = Character.isWhitespace(codePoint);
            if (whitespace) {
                if (!lastWhitespace && !normalized.isEmpty()) {
                    normalized.append(' ');
                }
            } else {
                normalized.appendCodePoint(codePoint);
            }
            lastWhitespace = whitespace;
            offset += Character.charCount(codePoint);
        }
        return normalized.toString().trim();
    }

    private static String[] createVariantSuffixes() {
        String[] suffixes = new String[BUTTON_WIDTH_VARIANTS];
        for (int index = 0; index < suffixes.length; index++) {
            suffixes[index] = String.format(Locale.ROOT, "%03d", 70 + index * 10);
        }
        return suffixes;
    }

    private static void requireVariant(int index) {
        if (index < 0 || index >= BUTTON_WIDTH_VARIANTS) {
            throw new IllegalArgumentException("Unknown action hitbox width variant: " + index);
        }
    }
}
