package top.ellan.mahjong.plugin;

import java.nio.file.Path;
import java.util.Objects;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Validated restart-scoped configuration. */
public record PluginConfiguration(
        Database database,
        String registryUrl,
        String craftEngineBundleFolder,
        String tileBackFurniture,
        String interactionFurniture) {
    public PluginConfiguration {
        Objects.requireNonNull(database, "database");
        registryUrl = Objects.requireNonNull(registryUrl, "registryUrl").trim();
        craftEngineBundleFolder = requireToken(craftEngineBundleFolder, "bundle folder");
        tileBackFurniture = requireAsset(tileBackFurniture, "tile back furniture");
        interactionFurniture = requireAsset(interactionFurniture, "interaction furniture");
    }

    public static PluginConfiguration load(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        plugin.saveDefaultConfig();
        FileConfiguration config = plugin.getConfig();
        String configuredUrl = config.getString("database.jdbc-url", "").trim();
        Path defaultDatabase =
                plugin.getDataFolder().toPath().resolve("data/mahjong").toAbsolutePath().normalize();
        String jdbcUrl =
                configuredUrl.isEmpty()
                        ? "jdbc:h2:file:"
                                + defaultDatabase.toString().replace('\\', '/')
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE"
                        : configuredUrl;
        int poolSize = config.getInt("database.maximum-pool-size", 8);
        if (poolSize < 2 || poolSize > 32) {
            throw new IllegalArgumentException("database.maximum-pool-size must be 2..32");
        }
        return new PluginConfiguration(
                new Database(
                        jdbcUrl,
                        config.getString("database.username", "sa"),
                        config.getString("database.password", ""),
                        poolSize),
                config.getString("rules.registry-url", ""),
                config.getString("craftengine.bundle-folder", "mahjongpaper"),
                config.getString(
                        "craftengine.tile-back-furniture",
                        "mahjongpaper:tile_standing_face_down_back"),
                config.getString(
                        "craftengine.interaction-furniture",
                        "mahjongpaper:hand_tile_hitbox"));
    }

    private static String requireToken(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if (!normalized.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid " + label);
        }
        return normalized;
    }

    private static String requireAsset(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if (!normalized.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Invalid " + label);
        }
        return normalized;
    }

    public record Database(String jdbcUrl, String username, String password, int maximumPoolSize) {
        public Database {
            jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl").trim();
            username = Objects.requireNonNull(username, "username");
            password = Objects.requireNonNull(password, "password");
            if (!jdbcUrl.startsWith("jdbc:")) {
                throw new IllegalArgumentException("database.jdbc-url must start with jdbc:");
            }
            if (maximumPoolSize < 2 || maximumPoolSize > 32) {
                throw new IllegalArgumentException("maximumPoolSize must be 2..32");
            }
        }
    }
}
