package top.ellan.mahjong.config;

import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.YamlDocument;
import net.momirealms.sparrow.yaml.serializer.NodeSerializer;
import net.momirealms.sparrow.yaml.serializer.NodeSerializers;
import top.ellan.mahjong.model.MahjongVariant;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class PluginSettings {
    private static final SparrowYaml YAML = SparrowYaml.builder().build();
    private static final NodeSerializer<List<String>> STRING_LIST = NodeSerializers.STRING.listOf();

    private final DebugSettings debug;
    private final DatabaseSettings database;
    private final TablesSettings tables;
    private final GameRoomsSettings gameRooms;
    private final RankingSettings ranking;
    private final CraftEngineSettings craftEngine;
    private final RulesSettings rules;

    private PluginSettings(
        DebugSettings debug,
        DatabaseSettings database,
        TablesSettings tables,
        GameRoomsSettings gameRooms,
        RankingSettings ranking,
        CraftEngineSettings craftEngine,
        RulesSettings rules
    ) {
        this.debug = debug;
        this.database = database;
        this.tables = tables;
        this.gameRooms = gameRooms;
        this.ranking = ranking;
        this.craftEngine = craftEngine;
        this.rules = rules;
    }

    public static PluginSettings load(Path path) throws IOException {
        return from(YAML.load(Objects.requireNonNull(path, "path")));
    }

    public static PluginSettings parse(String yaml) {
        try {
            return from(YAML.load(Objects.requireNonNull(yaml, "yaml")));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to parse YAML configuration", exception);
        }
    }

    public static PluginSettings defaults() {
        return parse("");
    }

    public static PluginSettings from(YamlDocument config) {
        Objects.requireNonNull(config, "config");

        String sharedTileItemIdPrefix = string(
            config,
            "mahjongpaper:",
            "integrations.craftengine.items.tileItemIdPrefix",
            "integrations.craftengine.items.tile-item-id-prefix",
            "craftengine.items.tileItemIdPrefix",
            "craftengine.items.tile-item-id-prefix"
        );
        DebugSettings debug = new DebugSettings(
            bool(config, false, "debug.enabled"),
            stringList(config, "debug.categories")
        );
        DatabaseSettings database = new DatabaseSettings(
            bool(config, true, "database.enabled"),
            bool(config, false, "database.failOnError"),
            string(config, "h2", "database.connection.type", "database.type").trim().toLowerCase(Locale.ROOT),
            new DatabaseConnectionSettings(
                string(config, "127.0.0.1", "database.connection.host", "database.host"),
                integer(config, 3306, "database.connection.port", "database.port"),
                string(config, "mahjongpaper", "database.connection.name", "database.name"),
                string(
                    config,
                    "useUnicode=true&characterEncoding=utf8&useSsl=false",
                    "database.connection.parameters",
                    "database.parameters"
                )
            ),
            new DatabaseCredentialsSettings(
                string(config, "root", "database.credentials.username", "database.username"),
                string(config, "change_me", "database.credentials.password", "database.password")
            ),
            new DatabaseH2Settings(
                string(config, "data/mahjongpaper", "database.h2.path"),
                string(config, "sa", "database.h2.username"),
                string(config, "", "database.h2.password"),
                string(
                    config,
                    "MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
                    "database.h2.parameters"
                )
            ),
            new DatabasePoolSettings(
                integer(config, 10, "database.pool.maxSize", "database.pool.maximumPoolSize"),
                integer(config, 2, "database.pool.minIdle", "database.pool.minimumIdle"),
                longValue(config, 10000L, "database.pool.connectionTimeoutMillis")
            )
        );
        TablesSettings tables = new TablesSettings(
            Math.max(1, integer(config, 3, "tables.startupRebuildBatchSize", "tables.startup-rebuild-batch-size")),
            bool(config, false, "tables.allowFreeMoveDuringRound", "tables.allow-free-move-during-round"),
            new TablePersistenceSettings(
                bool(config, true, "tables.persistence.enabled", "tablePersistence.enabled"),
                string(config, "tables.yml", "tables.persistence.file", "tablePersistence.file")
            ),
            new OverheadViewSettings(
                bool(config, true, "tables.overheadView.enabled", "tables.overhead-view.enabled"),
                Math.max(
                    3.0D,
                    Math.min(6.0D, decimal(config, 4.5D, "tables.overheadView.height", "tables.overhead-view.height"))
                ),
                Math.max(
                    1,
                    Math.min(
                        40,
                        integer(
                            config,
                            16,
                            "tables.overheadView.transitionTicks",
                            "tables.overheadView.transition-ticks",
                            "tables.overhead-view.transitionTicks",
                            "tables.overhead-view.transition-ticks"
                        )
                    )
                )
            )
        );
        GameRoomsSettings gameRooms = new GameRoomsSettings(
            bool(config, true, "gameRooms.enabled", "game-rooms.enabled", "gamerooms.enabled"),
            bool(
                config,
                true,
                "gameRooms.restrictNewTables",
                "gameRooms.restrict-new-tables",
                "game-rooms.restrictNewTables",
                "game-rooms.restrict-new-tables",
                "gamerooms.restrictNewTables",
                "gamerooms.restrict-new-tables"
            ),
            bool(
                config,
                true,
                "gameRooms.enterExitMessages",
                "gameRooms.enter-exit-messages",
                "game-rooms.enterExitMessages",
                "game-rooms.enter-exit-messages",
                "gamerooms.enterExitMessages",
                "gamerooms.enter-exit-messages"
            ),
            Math.max(
                5,
                integer(
                    config,
                    60,
                    "gameRooms.leaveCountdownSeconds",
                    "gameRooms.leave-countdown-seconds",
                    "game-rooms.leaveCountdownSeconds",
                    "game-rooms.leave-countdown-seconds",
                    "gamerooms.leaveCountdownSeconds",
                    "gamerooms.leave-countdown-seconds"
                )
            ),
            Math.max(
                2,
                integer(
                    config,
                    10,
                    "gameRooms.defaultRadius",
                    "gameRooms.default-radius",
                    "game-rooms.defaultRadius",
                    "game-rooms.default-radius",
                    "gamerooms.defaultRadius",
                    "gamerooms.default-radius"
                )
            ),
            Math.max(
                3,
                integer(
                    config,
                    8,
                    "gameRooms.defaultHeight",
                    "gameRooms.default-height",
                    "game-rooms.defaultHeight",
                    "game-rooms.default-height",
                    "gamerooms.defaultHeight",
                    "gamerooms.default-height"
                )
            ),
            string(config, "game-rooms.yml", "gameRooms.file", "game-rooms.file", "gamerooms.file")
        );
        RankingSettings ranking = new RankingSettings(
            bool(config, true, "ranking.enabled"),
            string(config, "SILVER", "ranking.eastRoom"),
            string(config, "GOLD", "ranking.southRoom"),
            new InvSyncRankSettings(
                bool(config, true, "ranking.playerStorage.invSync.enabled", "ranking.player-storage.invsync.enabled"),
                bool(
                    config,
                    true,
                    "ranking.playerStorage.invSync.fallbackToDatabase",
                    "ranking.player-storage.invsync.fallback-to-database"
                )
            )
        );
        CraftEngineSettings craftEngine = new CraftEngineSettings(
            bool(
                config,
                true,
                "integrations.craftengine.exportBundleOnEnable",
                "integrations.craftengine.bundle.exportOnEnable",
                "craftengine.exportBundleOnEnable",
                "craftengine.bundle.exportOnEnable"
            ),
            string(
                config,
                "mahjongpaper",
                "integrations.craftengine.bundle.folder",
                "integrations.craftengine.bundle.bundleFolder",
                "craftengine.bundle.folder",
                "craftengine.bundle.bundleFolder"
            ),
            bool(
                config,
                true,
                "integrations.craftengine.compatibility.injectAntiCheatPacketEventsMappings",
                "integrations.craftengine.compatibility.compatibility.injectAntiCheatPacketEventsMappings",
                "craftengine.compatibility.injectAntiCheatPacketEventsMappings",
                "craftengine.compatibility.compatibility.injectAntiCheatPacketEventsMappings"
            ),
            new CraftEngineItemsSettings(
                bool(
                    config,
                    true,
                    "integrations.craftengine.items.preferCustomItems",
                    "integrations.craftengine.items.items.preferCustomItems",
                    "craftengine.items.preferCustomItems",
                    "craftengine.items.items.preferCustomItems"
                ),
                sharedTileItemIdPrefix,
                string(
                    config,
                    sharedTileItemIdPrefix,
                    "integrations.craftengine.items.riichiTileItemIdPrefix",
                    "integrations.craftengine.items.riichi-tile-item-id-prefix",
                    "craftengine.items.riichiTileItemIdPrefix",
                    "craftengine.items.riichi-tile-item-id-prefix"
                ),
                string(
                    config,
                    sharedTileItemIdPrefix,
                    "integrations.craftengine.items.gbTileItemIdPrefix",
                    "integrations.craftengine.items.gb-tile-item-id-prefix",
                    "craftengine.items.gbTileItemIdPrefix",
                    "craftengine.items.gb-tile-item-id-prefix"
                )
            ),
            new CraftEngineFurnitureSettings(
                bool(
                    config,
                    true,
                    "integrations.craftengine.furniture.preferHitboxInteraction",
                    "integrations.craftengine.furniture.furniture.preferHitboxInteraction",
                    "craftengine.furniture.preferHitboxInteraction",
                    "craftengine.furniture.furniture.preferHitboxInteraction"
                ),
                string(
                    config,
                    "mahjongpaper:table_visual",
                    "integrations.craftengine.furniture.tableFurnitureId",
                    "integrations.craftengine.furniture.table-furniture-id",
                    "craftengine.furniture.tableFurnitureId",
                    "craftengine.furniture.table-furniture-id"
                ),
                string(
                    config,
                    "mahjongpaper:seat_chair",
                    "integrations.craftengine.furniture.seatFurnitureId",
                    "integrations.craftengine.furniture.seat-furniture-id",
                    "craftengine.furniture.seatFurnitureId",
                    "craftengine.furniture.seat-furniture-id"
                )
            )
        );
        RulesSettings rules = new RulesSettings(
            bool(config, true, "rules.enabled"),
            string(config, "", "rules.registryUrl", "rules.registry-url")
        );
        return new PluginSettings(debug, database, tables, gameRooms, ranking, craftEngine, rules);
    }

    private static boolean bool(YamlDocument config, boolean defaultValue, String... paths) {
        return value(config, NodeSerializers.BOOLEAN, defaultValue, paths);
    }

    private static int integer(YamlDocument config, int defaultValue, String... paths) {
        return value(config, NodeSerializers.INT, defaultValue, paths);
    }

    private static long longValue(YamlDocument config, long defaultValue, String... paths) {
        return value(config, NodeSerializers.LONG, defaultValue, paths);
    }

    private static double decimal(YamlDocument config, double defaultValue, String... paths) {
        return value(config, NodeSerializers.DOUBLE, defaultValue, paths);
    }

    private static String string(YamlDocument config, String defaultValue, String... paths) {
        return value(config, NodeSerializers.STRING, defaultValue, paths);
    }

    private static List<String> stringList(YamlDocument config, String... paths) {
        return value(config, STRING_LIST, List.of(), paths);
    }

    private static <T> T value(YamlDocument config, NodeSerializer<T> serializer, T defaultValue, String... paths) {
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            Object[] route = path.split("\\.");
            if (config.getNodeOrNull(route) != null) {
                return config.get(serializer, route);
            }
        }
        return defaultValue;
    }

    public DebugSettings debug() {
        return this.debug;
    }

    public DatabaseSettings database() {
        return this.database;
    }

    public TablesSettings tables() {
        return this.tables;
    }

    public GameRoomsSettings gameRooms() {
        return this.gameRooms;
    }

    public RankingSettings ranking() {
        return this.ranking;
    }

    public CraftEngineSettings craftEngine() {
        return this.craftEngine;
    }

    public RulesSettings rules() {
        return this.rules;
    }

    public boolean databaseFailOnError() {
        return this.database.failOnError();
    }

    public boolean tablePersistenceEnabled() {
        return this.tables.persistence().enabled();
    }

    public String tablePersistenceFile() {
        return this.tables.persistence().file();
    }

    public int tableStartupRebuildBatchSize() {
        return this.tables.startupRebuildBatchSize();
    }

    public boolean tableFreeMoveDuringRound() {
        return this.tables.allowFreeMoveDuringRound();
    }

    public String craftEngineTileItemIdPrefix() {
        return this.craftEngine.items().tileItemIdPrefix();
    }

    public String craftEngineRiichiTileItemIdPrefix() {
        return this.craftEngine.items().riichiTileItemIdPrefix();
    }

    public String craftEngineGbTileItemIdPrefix() {
        return this.craftEngine.items().gbTileItemIdPrefix();
    }

    public String craftEngineTileItemIdPrefix(MahjongVariant variant) {
        if (variant != MahjongVariant.RIICHI) {
            return this.craftEngine.items().gbTileItemIdPrefix();
        }
        return this.craftEngine.items().riichiTileItemIdPrefix();
    }

    public String craftEngineTableFurnitureId() {
        return this.craftEngine.furniture().tableFurnitureId();
    }

    public String craftEngineSeatFurnitureId() {
        return this.craftEngine.furniture().seatFurnitureId();
    }

    public boolean rankingEnabled() {
        return this.ranking.enabled();
    }

    public String rankingEastRoom() {
        return this.ranking.eastRoom();
    }

    public String rankingSouthRoom() {
        return this.ranking.southRoom();
    }

    public boolean rankingInvSyncEnabled() {
        return this.ranking.playerStorage().enabled();
    }

    public boolean rankingInvSyncFallbackToDatabase() {
        return this.ranking.playerStorage().fallbackToDatabase();
    }

    public record DebugSettings(boolean enabled, java.util.List<String> categories) {
        public DebugSettings {
            categories = categories == null ? java.util.List.of() : java.util.List.copyOf(categories);
        }
    }

    public record DatabaseSettings(
        boolean enabled,
        boolean failOnError,
        String type,
        DatabaseConnectionSettings connection,
        DatabaseCredentialsSettings credentials,
        DatabaseH2Settings h2,
        DatabasePoolSettings pool
    ) {
    }

    public record DatabaseConnectionSettings(String host, int port, String name, String parameters) {
    }

    public record DatabaseCredentialsSettings(String username, String password) {
    }

    public record DatabaseH2Settings(String path, String username, String password, String parameters) {
    }

    public record DatabasePoolSettings(int maxSize, int minIdle, long connectionTimeoutMillis) {
    }

    public record TablesSettings(
        int startupRebuildBatchSize,
        boolean allowFreeMoveDuringRound,
        TablePersistenceSettings persistence,
        OverheadViewSettings overheadView
    ) {
    }

    public record TablePersistenceSettings(boolean enabled, String file) {
    }

    public record OverheadViewSettings(boolean enabled, double height, int transitionTicks) {
    }

    public record GameRoomsSettings(
        boolean enabled,
        boolean restrictNewTables,
        boolean enterExitMessages,
        int leaveCountdownSeconds,
        int defaultRadius,
        int defaultHeight,
        String file
    ) {
    }

    public record RankingSettings(boolean enabled, String eastRoom, String southRoom, InvSyncRankSettings playerStorage) {
    }

    public record InvSyncRankSettings(boolean enabled, boolean fallbackToDatabase) {
    }

    public record CraftEngineSettings(
        boolean exportBundleOnEnable,
        String bundleFolder,
        boolean injectAntiCheatPacketEventsMappings,
        CraftEngineItemsSettings items,
        CraftEngineFurnitureSettings furniture
    ) {
    }

    public record CraftEngineItemsSettings(
        boolean preferCustomItems,
        String tileItemIdPrefix,
        String riichiTileItemIdPrefix,
        String gbTileItemIdPrefix
    ) {
    }

    public record CraftEngineFurnitureSettings(
        boolean preferHitboxInteraction,
        String tableFurnitureId,
        String seatFurnitureId
    ) {
    }

    public record RulesSettings(boolean enabled, String registryUrl) {
        public RulesSettings {
            registryUrl = registryUrl == null ? "" : registryUrl.trim();
        }
    }
}
