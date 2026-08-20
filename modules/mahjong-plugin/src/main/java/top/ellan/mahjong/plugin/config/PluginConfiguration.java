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
        CraftEngineAssets craftEngineAssets,
        LayoutGeometry layoutGeometry,
        ViewSettings viewSettings,
        OpeningSettings openingSettings,
        RankingSettings ranking) {
    public PluginConfiguration {
        Objects.requireNonNull(database, "database");
        registryUrl = Objects.requireNonNull(registryUrl, "registryUrl").trim();
        Objects.requireNonNull(gameRooms, "gameRooms");
        craftEngineBundleFolder = requireToken(craftEngineBundleFolder, "bundle folder");
        Objects.requireNonNull(craftEngineAssets, "craftEngineAssets");
        Objects.requireNonNull(layoutGeometry, "layoutGeometry");
        Objects.requireNonNull(viewSettings, "viewSettings");
        Objects.requireNonNull(openingSettings, "openingSettings");
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
                new CraftEngineAssets(
                        config.getString(
                                "craftengine.assets.table",
                                "mahjongpaper:table_visual"),
                        config.getString(
                                "craftengine.assets.seat",
                                "mahjongpaper:seat_chair"),
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
                                "mahjongpaper:action_button_hitbox"),
                        config.getString(
                                "craftengine.assets.action-hitbox-prefix",
                                "mahjongpaper:action_button_hitbox_"),
                        config.getString(
                                "craftengine.assets.opening-die-slot-prefix",
                                "mahjongpaper:opening_die_slot_")),
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
                        config.getInt("presentation.capacity.actions", 64)),
                new ViewSettings(
                        config.getBoolean("presentation.overhead.enabled", true),
                        config.getDouble("presentation.overhead.height", 4.5D),
                        config.getInt("presentation.overhead.transition-ticks", 16)),
                new OpeningSettings(
                        config.getInt("presentation.opening.roll-ticks", 20),
                        config.getInt("presentation.opening.reveal-ticks", 12)),
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

    private static String requireAsset(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if (!normalized.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Invalid " + label);
        }
        return normalized;
    }

    public record CraftEngineAssets(
            String table,
            String seat,
            String standingBack,
            String flatBack,
            String handHitbox,
            String actionHitbox,
            String actionHitboxPrefix,
            String openingDieSlotPrefix) {
        public CraftEngineAssets {
            table = requireAsset(table, "table furniture");
            seat = requireAsset(seat, "seat furniture");
            standingBack = requireAsset(standingBack, "standing back furniture");
            flatBack = requireAsset(flatBack, "flat back furniture");
            handHitbox = requireAsset(handHitbox, "hand hitbox furniture");
            actionHitbox = requireAsset(actionHitbox, "action hitbox furniture");
            actionHitboxPrefix = requireAsset(
                    actionHitboxPrefix, "action hitbox furniture prefix");
            openingDieSlotPrefix = requireAsset(
                    openingDieSlotPrefix, "opening die slot furniture prefix");
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

    public record OpeningSettings(int rollTicks, int revealTicks) {
        public OpeningSettings {
            if (rollTicks < 1 || rollTicks > 200 || revealTicks < 1 || revealTicks > 200) {
                throw new IllegalArgumentException("opening timings must be between 1 and 200 ticks");
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
