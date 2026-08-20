package top.ellan.mahjong.craftengine.privateview;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import top.ellan.mahjong.craftengine.port.PlayerTextResolver;
import top.ellan.mahjong.presentation.node.HudNode;

/** Builds the 1.5.0-aligned compact round/turn/wall BossBar from one viewer's HUD nodes. */
final class HudTextFormatter {
    private static final Pattern VALUE_KEY_UNSAFE = Pattern.compile("[^a-z0-9_.-]");
    private static final Pattern CAMEL_CASE = Pattern.compile("([a-z])([A-Z])");
    private final PlayerTextResolver messages;

    HudTextFormatter(PlayerTextResolver messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    HudPresentation format(Locale locale, Collection<HudNode> nodes) {
        HashMap<String, String> values = new HashMap<>();
        for (HudNode node : nodes) {
            values.put(semanticKey(node.contentKey()), node.contentValue());
        }
        String phase = values.getOrDefault("phase", "");
        String wall = first(values, "wallRemaining", "wall");
        String current = first(values, "currentPlayer", "currentSeat");
        if (phase.isBlank() && wall.isBlank() && current.isBlank()) {
            return HudPresentation.hidden();
        }

        String round = round(locale, values, phase);
        String turn = current.isBlank() ? semanticValue(locale, phase) : seatValue(locale, current);
        Component title = Component.text(round, NamedTextColor.GOLD)
                .append(Component.text(" | ", NamedTextColor.GRAY))
                .append(Component.text(
                        messages.resolve(locale, "mahjongpaper.hud.turn", "Turn") + " ",
                        NamedTextColor.AQUA))
                .append(Component.text(turn, NamedTextColor.WHITE));
        if (!wall.isBlank()) {
            title = title.append(Component.text(" | ", NamedTextColor.GRAY))
                    .append(Component.text(
                            messages.resolve(locale, "mahjongpaper.hud.wall", "Wall") + " ",
                            NamedTextColor.GREEN))
                    .append(Component.text(wall, NamedTextColor.WHITE));
        }
        return new HudPresentation(
                title,
                progress(wall, values.get("wallCapacity")),
                boundary(phase) ? BossBar.Color.PURPLE : BossBar.Color.BLUE,
                true);
    }

    private String round(Locale locale, Map<String, String> values, String phase) {
        String wind = values.getOrDefault("roundWind", "");
        String hand = first(values, "handNumber", "round");
        if (!wind.isBlank()) {
            return semanticValue(locale, wind) + (hand.isBlank() ? "" : " " + hand);
        }
        if (!hand.isBlank()) {
            return messages.resolve(locale, "mahjongpaper.hud.hand", "Hand") + " " + hand;
        }
        return semanticValue(locale, phase);
    }

    private String seatValue(Locale locale, String value) {
        try {
            int seat = Integer.parseInt(value);
            if (seat >= 0 && seat < 4) {
                return semanticValue(locale, new String[] {"east", "south", "west", "north"}[seat]);
            }
        } catch (NumberFormatException ignored) {
            // Named winds and player-visible names are handled below.
        }
        return semanticValue(locale, value);
    }

    private String semanticValue(Locale locale, String raw) {
        String normalized = VALUE_KEY_UNSAFE
                .matcher(raw.toLowerCase(Locale.ROOT))
                .replaceAll("_");
        if (normalized.isBlank() || normalized.length() > 96) {
            return humanize(raw);
        }
        if (normalized.equals("east")
                || normalized.equals("south")
                || normalized.equals("west")
                || normalized.equals("north")) {
            return messages.resolve(locale, "mahjongpaper.tile." + normalized, humanize(raw));
        }
        return messages.resolve(locale, "mahjongpaper.value." + normalized, humanize(raw));
    }

    private static float progress(String remaining, String capacity) {
        try {
            float result = Float.parseFloat(remaining) / Float.parseFloat(capacity);
            return Math.max(0.0F, Math.min(1.0F, result));
        } catch (NumberFormatException | NullPointerException ignored) {
            return 1.0F;
        }
    }

    private static String first(Map<String, String> values, String first, String second) {
        return values.getOrDefault(first, values.getOrDefault(second, ""));
    }

    private static String semanticKey(String key) {
        int separator = key.indexOf(':');
        return separator < 0 ? key : key.substring(separator + 1);
    }

    private static boolean boundary(String phase) {
        String normalized = phase.toUpperCase(Locale.ROOT);
        return normalized.contains("BETWEEN")
                || normalized.equals("ENDED")
                || normalized.equals("FINISHED");
    }

    private static String humanize(String value) {
        return CAMEL_CASE.matcher(value).replaceAll("$1 $2")
                .replace('_', ' ')
                .replace('.', ' ')
                .replace('-', ' ')
                .toLowerCase(Locale.ROOT);
    }

    record HudPresentation(Component title, float progress, BossBar.Color color, boolean visible) {
        HudPresentation {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(color, "color");
        }

        static HudPresentation hidden() {
            return new HudPresentation(Component.empty(), 0.0F, BossBar.Color.WHITE, false);
        }
    }
}
