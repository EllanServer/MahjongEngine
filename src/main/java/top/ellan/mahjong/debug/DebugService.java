package top.ellan.mahjong.debug;

import top.ellan.mahjong.config.PluginSettings;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

public final class DebugService {
    private final Logger logger;
    private final boolean enabled;
    private final Set<String> categories;

    public DebugService(Logger logger, PluginSettings.DebugSettings settings) {
        this(logger, settings != null && settings.enabled(), settings == null ? java.util.List.of() : settings.categories());
    }

    public DebugService(Logger logger, boolean enabled, Iterable<String> categories) {
        this.logger = logger;
        this.enabled = enabled;
        this.categories = categories == null
            ? Set.of()
            : Set.copyOf(normalize(categories));
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public boolean isCategoryEnabled(String category) {
        if (!this.enabled) {
            return false;
        }
        if (this.categories.isEmpty()) {
            return true;
        }
        String normalized = category.toLowerCase(Locale.ROOT);
        return this.categories.contains("*") || this.categories.contains(normalized);
    }

    public void log(String category, String message) {
        if (this.isCategoryEnabled(category)) {
            this.logger.info("[debug/" + category + "] " + message);
        }
    }

    private static Set<String> normalize(Iterable<String> categories) {
        java.util.LinkedHashSet<String> normalized = new java.util.LinkedHashSet<>();
        for (String value : categories) {
            if (value == null || value.isBlank()) {
                continue;
            }
            normalized.add(value.toLowerCase(Locale.ROOT));
        }
        return normalized;
    }
}
