package top.ellan.mahjong.presentation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Shared presentation policy for table feedback.
 *
 * <p>The table has several possible output surfaces, so each surface has one
 * responsibility: the boss bar owns persistent state, the table-side prompt
 * owns the current decision, the centre label owns short-lived public events,
 * chat owns errors and administrative messages, and sounds reinforce state
 * changes without repeating the text channel.</p>
 */
public final class TableFeedbackPolicy {
    public static final int DECISION_TEXT_BUDGET = 44;
    public static final int ACTION_LABEL_BUDGET = 20;
    public static final int PLAYER_NAME_BUDGET = 12;
    public static final int SEAT_LABEL_BUDGET = 32;
    public static final long NORMAL_ANNOUNCEMENT_TICKS = 40L;
    public static final long CRITICAL_ANNOUNCEMENT_TICKS = 60L;

    private TableFeedbackPolicy() {
    }

    public enum Channel {
        HUD,
        DECISION,
        ANNOUNCEMENT,
        CHAT,
        SOUND
    }

    public enum Priority {
        STATUS(10),
        GUIDANCE(20),
        TURN(40),
        REQUIRED_ACTION(70),
        REACTION(80),
        RESULT(100);

        private final int weight;

        Priority(int weight) {
            this.weight = weight;
        }

        int weight() {
            return this.weight;
        }
    }

    public record DecisionCue(String eventKey, Priority priority, String primary, String detail) {
        public DecisionCue {
            eventKey = Objects.toString(eventKey, "").trim();
            priority = priority == null ? Priority.STATUS : priority;
            primary = normalizeText(primary);
            detail = normalizeText(detail);
        }
    }

    public record ResolvedDecision(String eventKey, Priority priority, String text) {
        private static ResolvedDecision empty() {
            return new ResolvedDecision("", Priority.STATUS, "");
        }

        public boolean visible() {
            return !this.text.isBlank();
        }
    }

    /** Selects one decision by priority and merges its non-duplicate detail. */
    public static ResolvedDecision resolveDecision(Collection<DecisionCue> cues) {
        if (cues == null || cues.isEmpty()) {
            return ResolvedDecision.empty();
        }
        DecisionCue selected = null;
        for (DecisionCue cue : cues) {
            if (cue == null || cue.primary().isBlank()) {
                continue;
            }
            if (selected == null || cue.priority().weight() > selected.priority().weight()) {
                selected = cue;
            }
        }
        if (selected == null) {
            return ResolvedDecision.empty();
        }
        String text = selected.primary();
        if (!selected.detail().isBlank() && !sameText(selected.primary(), selected.detail())) {
            text = text + " · " + selected.detail();
        }
        return new ResolvedDecision(
            selected.eventKey(),
            selected.priority(),
            compactText(text, DECISION_TEXT_BUDGET)
        );
    }

    /** Merges stable status segments once, stopping before the visual budget is exceeded. */
    public static String mergeStatus(Collection<String> segments, int maxVisualUnits) {
        if (segments == null || segments.isEmpty() || maxVisualUnits <= 0) {
            return "";
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String segment : segments) {
            String normalized = normalizeText(segment);
            if (!normalized.isBlank()) {
                unique.add(normalized);
            }
        }
        StringBuilder merged = new StringBuilder();
        for (String segment : unique) {
            String candidate = merged.isEmpty() ? segment : merged + " | " + segment;
            if (visualUnits(candidate) > maxVisualUnits) {
                break;
            }
            if (!merged.isEmpty()) {
                merged.append(" | ");
            }
            merged.append(segment);
        }
        if (merged.isEmpty() && !unique.isEmpty()) {
            return compactText(unique.iterator().next(), maxVisualUnits);
        }
        return merged.toString();
    }

    public static String compactPlayerName(String playerName) {
        return compactText(playerName, PLAYER_NAME_BUDGET);
    }

    public static String compactActionLabel(String label) {
        return compactText(label, ACTION_LABEL_BUDGET);
    }

    public static String compactSeatLabel(String status, String playerName) {
        return mergeStatus(
            List.of(Objects.toString(status, ""), compactPlayerName(playerName)),
            SEAT_LABEL_BUDGET
        );
    }

    public static String joinPlayerNames(Collection<String> playerNames) {
        if (playerNames == null || playerNames.isEmpty()) {
            return "";
        }
        List<String> compact = new ArrayList<>();
        for (String playerName : playerNames) {
            String value = compactPlayerName(playerName);
            if (!value.isBlank() && !compact.contains(value)) {
                compact.add(value);
            }
        }
        return String.join(" / ", compact);
    }

    public static long announcementDurationTicks(String actionKey) {
        return switch (Objects.toString(actionKey, "")) {
            case "table.action.ron", "table.action.tsumo" -> CRITICAL_ANNOUNCEMENT_TICKS;
            default -> NORMAL_ANNOUNCEMENT_TICKS;
        };
    }

    public static String compactText(String text, int maxVisualUnits) {
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
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            int unit = visualUnits(codePoint);
            if (width + unit > contentBudget) {
                break;
            }
            result.appendCodePoint(codePoint);
            width += unit;
            offset += Character.charCount(codePoint);
        }
        return result.toString().stripTrailing() + "…";
    }

    public static int visualUnits(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int width = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            width += visualUnits(codePoint);
            offset += Character.charCount(codePoint);
        }
        return width;
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

    private static boolean sameText(String left, String right) {
        return normalizeText(left).toLowerCase(Locale.ROOT).equals(normalizeText(right).toLowerCase(Locale.ROOT));
    }

    private static String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(value.length());
        boolean lastWhitespace = false;
        for (int offset = 0; offset < value.length();) {
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

    /** Fingerprint gate shared by event-driven channels such as sound and announcements. */
    public static final class DeliveryGate {
        private final Map<DeliveryKey, String> last = new HashMap<>();

        public boolean shouldDeliver(UUID audienceId, Channel channel, String fingerprint) {
            return this.shouldDeliver(audienceId, channel, "default", fingerprint);
        }

        public boolean shouldDeliver(UUID audienceId, Channel channel, String scope, String fingerprint) {
            if (channel == null) {
                return false;
            }
            String normalized = Objects.toString(fingerprint, "").trim();
            DeliveryKey key = new DeliveryKey(
                audienceId == null ? GlobalAudience.ID : audienceId,
                channel,
                Objects.toString(scope, "default")
            );
            if (normalized.isBlank()) {
                this.last.remove(key);
                return false;
            }
            String previous = this.last.put(key, normalized);
            return !normalized.equals(previous);
        }

        public void clear(UUID audienceId) {
            UUID key = audienceId == null ? GlobalAudience.ID : audienceId;
            this.last.keySet().removeIf(deliveryKey -> deliveryKey.audienceId().equals(key));
        }

        public void clear() {
            this.last.clear();
        }

        private record DeliveryKey(UUID audienceId, Channel channel, String scope) {
        }

        private static final class GlobalAudience {
            private static final UUID ID = new UUID(0L, 0L);

            private GlobalAudience() {
            }
        }
    }
}
