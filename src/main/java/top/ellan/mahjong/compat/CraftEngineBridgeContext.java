package top.ellan.mahjong.compat;

import top.ellan.mahjong.config.PluginSettings;
import top.ellan.mahjong.debug.DebugService;
import top.ellan.mahjong.i18n.MessageService;
import top.ellan.mahjong.runtime.AsyncService;
import top.ellan.mahjong.runtime.ServerScheduler;
import java.io.InputStream;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

final class CraftEngineBridgeContext {
    private static final String CRAFT_ENGINE_PLUGIN_NAME = "CraftEngine";

    private final Services plugin;
    private volatile Plugin craftEnginePlugin;

    CraftEngineBridgeContext(Services plugin) {
        this.plugin = plugin;
    }

    Services plugin() {
        return this.plugin;
    }

    Plugin bukkitPlugin() {
        return this.plugin.bukkitPlugin();
    }

    Plugin craftEnginePlugin() {
        Plugin cached = this.craftEnginePlugin;
        if (cached != null) {
            return cached;
        }
        Plugin exact = this.findCraftEnginePlugin();
        if (exact != null) {
            this.craftEnginePlugin = exact;
        }
        return exact;
    }

    boolean isPluginEnabled(String pluginName) {
        Plugin candidate = this.plugin.getServer().getPluginManager().getPlugin(pluginName);
        return candidate != null && candidate.isEnabled();
    }

    private Plugin findCraftEnginePlugin() {
        Plugin exact = this.plugin.getServer().getPluginManager().getPlugin(CRAFT_ENGINE_PLUGIN_NAME);
        if (exact != null) {
            return exact;
        }
        for (Plugin candidate : this.plugin.getServer().getPluginManager().getPlugins()) {
            if (candidate.getName().equalsIgnoreCase(CRAFT_ENGINE_PLUGIN_NAME)) {
                return candidate;
            }
        }
        return null;
    }

    interface Services {
        Plugin bukkitPlugin();

        Server getServer();

        Logger getLogger();

        InputStream getResource(String path);

        boolean isEnabled();

        ServerScheduler scheduler();

        AsyncService async();

        DebugService debug();

        MessageService messages();

        PluginSettings settings();
    }
}
