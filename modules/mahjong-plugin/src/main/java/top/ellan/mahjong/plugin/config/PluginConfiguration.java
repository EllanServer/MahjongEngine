package top.ellan.mahjong.plugin.config;

import java.nio.file.Path;
import java.util.Objects;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import top.ellan.mahjong.domain.match.RankRoom;

/** Validated restart-scoped configuration. */
public record PluginConfiguration(
        Database database,
        String registryUrl,
        GameRoomSettings gameRooms,
        String craftEngineBundleFolder,
        ViewSettings viewSettings,
        RankingSettings ranking) {
    public PluginConfiguration {
        Objects.requireNonNull(database, "database");
        registryUrl = Objects.requireNonNull(registryUrl, "registryUrl").trim();
        Objects.requireNonNull(gameRooms, "gameRooms");
        craftEngineBundleFolder = requireToken(craftEngineBundleFolder, "bundle folder");
        Objects.requireNonNull(viewSettings, "viewSettings");
        Objects.requireNonNull(ranking, "ranking");
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
                new GameRoomSettings(
                        config.getBoolean("game-rooms.enabled", true),
                        config.getBoolean("game-rooms.restrict-new-tables", true),
                        config.getBoolean("game-rooms.enter-exit-messages", true),
                        config.getInt("game-rooms.leave-countdown-seconds", 60),
                        config.getInt("game-rooms.default-radius", 10),
                        config.getInt("game-rooms.default-height", 8),
                        requireToken(
                                config.getString("game-rooms.file", "game-rooms.yml"),
                                "game-room file")),
                config.getString("craftengine.bundle-folder", "mahjongpaper"),
                new ViewSettings(
                        config.getBoolean("presentation.overhead.enabled", true),
                        config.getDouble("presentation.overhead.height", 4.5D),
                        config.getInt("presentation.overhead.transition-ticks", 16)),
                new RankingSettings(
                        config.getBoolean("ranking.enabled", true),
                        rankRoom(config.getString("ranking.east-room", "SILVER"), "east-room"),
                        rankRoom(config.getString("ranking.south-room", "GOLD"), "south-room")));
    }

    /** An unknown room name must fail the load rather than silently rank everyone in silver. */
    private static RankRoom rankRoom(String raw, String label) {
        try {
            return RankRoom.parse(raw);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(
                    "ranking." + label + " must be BRONZE, SILVER, GOLD, JADE or THRONE", unknown);
        }
    }

    private static String requireToken(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if (!normalized.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid " + label);
        }
        return normalized;
    }

    public record ViewSettings(boolean overheadEnabled, double overheadHeight, int transitionTicks) {
        public ViewSettings {
            if (!Double.isFinite(overheadHeight)
                    || overheadHeight < 2.0D
                    || overheadHeight > 8.0D) {
                throw new IllegalArgumentException("overheadHeight must be between 2 and 8 blocks");
            }
            if (transitionTicks < 1 || transitionTicks > 40) {
                throw new IllegalArgumentException("transitionTicks must be between 1 and 40");
            }
        }
    }

    /**
     * Room tiers the shared rank ladder awards points from, one per match length. 1.5.0 defaulted
     * east-only games to the silver room and full east-south games to gold.
     */
    public record RankingSettings(boolean enabled, RankRoom eastRoom, RankRoom southRoom) {
        public RankingSettings {
            Objects.requireNonNull(eastRoom, "eastRoom");
            Objects.requireNonNull(southRoom, "southRoom");
        }
    }

    public record GameRoomSettings(
            boolean enabled,
            boolean restrictNewTables,
            boolean enterExitMessages,
            int leaveCountdownSeconds,
            int defaultRadius,
            int defaultHeight,
            String file) {
        public GameRoomSettings {
            if (leaveCountdownSeconds < 5 || leaveCountdownSeconds > 600) {
                throw new IllegalArgumentException(
                        "game-room leave countdown must be between 5 and 600 seconds");
            }
            if (defaultRadius < 3 || defaultRadius > 127) {
                throw new IllegalArgumentException(
                        "game-room radius must be between 3 and 127 blocks");
            }
            if (defaultHeight < 4 || defaultHeight > 128) {
                throw new IllegalArgumentException(
                        "game-room height must be between 4 and 128 blocks");
            }
            file = requireToken(file, "game-room file");
        }
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
