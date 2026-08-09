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
        CraftEngineAssets craftEngineAssets,
        LayoutGeometry layoutGeometry) {
    public PluginConfiguration {
        Objects.requireNonNull(database, "database");
        registryUrl = Objects.requireNonNull(registryUrl, "registryUrl").trim();
        craftEngineBundleFolder = requireToken(craftEngineBundleFolder, "bundle folder");
        Objects.requireNonNull(craftEngineAssets, "craftEngineAssets");
        Objects.requireNonNull(layoutGeometry, "layoutGeometry");
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
                new CraftEngineAssets(
                        config.getString(
                                "craftengine.assets.table",
                                "mahjongpaper:table_visual"),
                        config.getString(
                                "craftengine.assets.standing-back",
                                "mahjongpaper:tile_standing_face_down_back"),
                        config.getString(
                                "craftengine.assets.flat-back",
                                "mahjongpaper:tile_flat_face_down_back"),
                        config.getString(
                                "craftengine.assets.hand-hitbox",
                                "mahjongpaper:hand_tile_hitbox"),
                        config.getString(
                                "craftengine.assets.action-hitbox",
                                "mahjongpaper:action_button_hitbox")),
                new LayoutGeometry(
                        config.getDouble("presentation.geometry.tile-width", 0.1125D),
                        config.getDouble("presentation.geometry.tile-height", 0.15D),
                        config.getDouble("presentation.geometry.tile-depth", 0.075D),
                        config.getDouble("presentation.geometry.tile-gap", 0.0025D),
                        config.getDouble("presentation.geometry.surface-height", 0.52D),
                        config.getDouble("presentation.geometry.hand-radius", 1.225D),
                        config.getDouble("presentation.geometry.wall-radius", 1.0D),
                        config.getDouble("presentation.geometry.table-half-length", 1.4375D),
                        config.getDouble("presentation.geometry.emphasis-raise", 0.06D),
                        config.getDouble("presentation.geometry.action-column-spacing", 0.55D),
                        config.getDouble("presentation.geometry.action-row-spacing", 0.24D),
                        config.getDouble("presentation.geometry.secondary-action-offset", 0.34D),
                        config.getInt("presentation.capacity.hand-tiles", 18),
                        config.getInt("presentation.capacity.discards", 48),
                        config.getInt("presentation.capacity.meld-tiles", 24),
                        config.getInt("presentation.capacity.point-sticks", 64),
                        config.getInt("presentation.capacity.auxiliary-tiles", 32),
                        config.getInt("presentation.capacity.actions", 64)));
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

    public record CraftEngineAssets(
            String table,
            String standingBack,
            String flatBack,
            String handHitbox,
            String actionHitbox) {
        public CraftEngineAssets {
            table = requireAsset(table, "table furniture");
            standingBack = requireAsset(standingBack, "standing back furniture");
            flatBack = requireAsset(flatBack, "flat back furniture");
            handHitbox = requireAsset(handHitbox, "hand hitbox furniture");
            actionHitbox = requireAsset(actionHitbox, "action hitbox furniture");
        }
    }

    public record LayoutGeometry(
            double tileWidth,
            double tileHeight,
            double tileDepth,
            double tileGap,
            double surfaceHeight,
            double handRadius,
            double wallRadius,
            double tableHalfLength,
            double emphasisRaise,
            double actionColumnSpacing,
            double actionRowSpacing,
            double secondaryActionOffset,
            int maxHandTiles,
            int maxDiscards,
            int maxMeldTiles,
            int maxPointSticks,
            int maxAuxiliaryTiles,
            int maxActions) {}

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
