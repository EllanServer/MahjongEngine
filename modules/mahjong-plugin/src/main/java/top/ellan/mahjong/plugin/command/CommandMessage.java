package top.ellan.mahjong.plugin.command;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;

/** Client-translated player message with an English console fallback. */
public record CommandMessage(
        String translationKey, String consolePattern, List<String> arguments) {
    public CommandMessage {
        translationKey = Objects.requireNonNull(translationKey, "translationKey").trim();
        if (!translationKey.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid command translation key");
        }
        consolePattern = Objects.requireNonNull(consolePattern, "consolePattern");
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
    }

    public static CommandMessage of(String key, String fallback, Object... arguments) {
        return new CommandMessage(
                key,
                fallback,
                Arrays.stream(arguments).map(String::valueOf).toList());
    }

    public Component playerComponent() {
        Component[] components = arguments.stream().map(Component::text).toArray(Component[]::new);
        return Component.translatable(translationKey, components);
    }

    public String consoleText() {
        return String.format(Locale.ROOT, consolePattern, arguments.toArray());
    }
}
