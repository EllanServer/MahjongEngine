package top.ellan.mahjong.plugin;

import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;
import top.ellan.mahjong.plugin.command.MahjongCommand;
import top.ellan.mahjong.plugin.config.PluginConfiguration;

/** MahjongPaper 2.0 entry point. Only the actor/rule-pack/CraftEngine architecture is composed. */
public final class MahjongPaperPlugin extends JavaPlugin {
    private MahjongRuntime runtime;

    @Override
    public void onEnable() {
        try {
            PluginConfiguration configuration = PluginConfiguration.load(this);
            runtime = new MahjongRuntime(this, configuration);
            MahjongCommand command = new MahjongCommand(this, runtime);
            registerCommand("mahjong", command);
            runtime.start();
            getLogger().info("MahjongPaper 2.0 is initializing asynchronously");
        } catch (RuntimeException failure) {
            getLogger().log(Level.SEVERE, "MahjongPaper 2.0 failed closed during enable", failure);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
    }
}
