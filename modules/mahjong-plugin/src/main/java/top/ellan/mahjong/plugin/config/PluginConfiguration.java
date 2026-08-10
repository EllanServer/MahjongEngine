package top.ellan.mahjong.plugin.config;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import top.ellan.mahjong.spi.RulePresentationCueType;

/** Validated restart-scoped configuration. */
public record PluginConfiguration(
        Database database,
        String registryUrl,
        String craftEngineBundleFolder,
        CraftEngineAssets craftEngineAssets,
        LayoutGeometry layoutGeometry,
        ViewSettings viewSettings,
        OpeningSettings openingSettings,
        SoundSettings soundSettings) {
    public PluginConfiguration {
        Objects.requireNonNull(database, "database");
        registryUrl = Objects.requireNonNull(registryUrl, "registryUrl").trim();
        craftEngineBundleFolder = requireToken(craftEngineBundleFolder, "bundle folder");
        Objects.requireNonNull(craftEngineAssets, "craftEngineAssets");
        Objects.requireNonNull(layoutGeometry, "layoutGeometry");
        Objects.requireNonNull(viewSettings, "viewSettings");
        Objects.requireNonNull(openingSettings, "openingSettings");
        Objects.requireNonNull(soundSettings, "soundSettings");
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
                                "mahjongpaper:action_button_hitbox"),
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
                        config.getInt("presentation.opening.preview-frames", 3),
                        config.getInt("presentation.opening.roll-ticks", 20),
                        config.getInt("presentation.opening.reveal-ticks", 12)),
                loadSoundSettings(config));
    }

    private static SoundSettings loadSoundSettings(FileConfiguration config) {
        EnumMap<RulePresentationCueType, SoundProfile> cues =
                new EnumMap<>(RulePresentationCueType.class);
        for (RulePresentationCueType type : RulePresentationCueType.values()) {
            String path = "presentation.sound.cues."
                    + type.name().toLowerCase(Locale.ROOT).replace('_', '-');
            cues.put(type, readSound(config, path, defaultCue(type)));
        }
        return new SoundSettings(
                cues,
                loadVariantPrefixes(config),
                readSound(
                        config,
                        "presentation.sound.opening.dice",
                        new SoundProfile("mahjongcraft:opening_dice", 0.7F, 1.0F)),
                readSound(
                        config,
                        "presentation.sound.opening.wall-open",
                        new SoundProfile("mahjongcraft:opening_wall_break", 0.8F, 1.0F)));
    }

    /**
     * Loads the per-rule sound key prefixes (v1.5.0 style variant sounds).
     *
     * <p>When a table runs under a rule whose id is listed here, every cue sound key
     * ({@code mahjongcraft:tile_shuffle}) gets the configured prefix inserted after the
     * namespace ({@code mahjongcraft:gb_tile_shuffle} for the guobiao/MCR family,
     * {@code mahjongcraft:sichuan_tile_shuffle} for Sichuan). Rule ids not listed, or
     * entries mapped to an empty value, keep the base key.
     */
    private static Map<String, String> loadVariantPrefixes(FileConfiguration config) {
        Map<String, String> prefixes = new HashMap<>();
        prefixes.put("mcr", "gb_");
        prefixes.put("sichuan", "sichuan_");
        ConfigurationSection section =
                config.getConfigurationSection("presentation.sound.variant-prefixes");
        if (section != null) {
            for (String ruleId : section.getKeys(false)) {
                String value = section.getString(ruleId);
                if (value == null || value.isBlank()) {
                    prefixes.remove(ruleId);
                } else {
                    prefixes.put(ruleId, requireToken(value, "sound variant prefix"));
                }
            }
        }
        return Map.copyOf(prefixes);
    }

    private static SoundProfile readSound(
            FileConfiguration config, String path, SoundProfile fallback) {
        return new SoundProfile(
                config.getString(path + ".key", fallback.key()),
                (float) config.getDouble(path + ".volume", fallback.volume()),
                (float) config.getDouble(path + ".pitch", fallback.pitch()));
    }

    private static SoundProfile defaultCue(RulePresentationCueType type) {
        String key = type == RulePresentationCueType.RIICHI
                ? "mahjongcraft:riichi"
                : "mahjongcraft:" + type.name().toLowerCase(Locale.ROOT);
        return switch (type) {
            case TILE_SHUFFLE -> new SoundProfile(key, 0.9F, 1.2F);
            case TILE_DRAW -> new SoundProfile(key, 0.65F, 1.05F);
            case TILE_DISCARD -> new SoundProfile(key, 0.75F, 1.05F);
            case REACTION_CHI, REACTION_PON, REACTION_KAN ->
                    new SoundProfile(key, 0.8F, 1.1F);
            case RIICHI -> new SoundProfile(key, 0.8F, 1.25F);
            case ROUND_WIN, ROUND_DRAW -> new SoundProfile(key, 0.9F, 1.0F);
            case TURN_CHANGE -> new SoundProfile(key, 0.5F, 1.6F);
        };
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
            String actionHitbox,
            String openingDieSlotPrefix) {
        public CraftEngineAssets {
            table = requireAsset(table, "table furniture");
            standingBack = requireAsset(standingBack, "standing back furniture");
            flatBack = requireAsset(flatBack, "flat back furniture");
            handHitbox = requireAsset(handHitbox, "hand hitbox furniture");
            actionHitbox = requireAsset(actionHitbox, "action hitbox furniture");
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

    public record OpeningSettings(
            int previewFrames,
            int rollTicks,
            int revealTicks) {
        public OpeningSettings {
            if (previewFrames < 1 || previewFrames > 6) {
                throw new IllegalArgumentException("previewFrames must be between 1 and 6");
            }
            if (rollTicks < 1 || rollTicks > 200 || revealTicks < 1 || revealTicks > 200) {
                throw new IllegalArgumentException("opening timings must be between 1 and 200 ticks");
            }
        }
    }

    public record SoundSettings(
            Map<RulePresentationCueType, SoundProfile> cues,
            Map<String, String> variantPrefixes,
            SoundProfile openingDice,
            SoundProfile openingWall) {
        public SoundSettings {
            cues = Map.copyOf(Objects.requireNonNull(cues, "cues"));
            if (!cues.keySet().equals(EnumSet.allOf(RulePresentationCueType.class))) {
                throw new IllegalArgumentException("sound cues must cover every cue type");
            }
            variantPrefixes = Map.copyOf(Objects.requireNonNull(variantPrefixes, "variantPrefixes"));
            Objects.requireNonNull(openingDice, "openingDice");
            Objects.requireNonNull(openingWall, "openingWall");
        }
    }

    public record SoundProfile(String key, float volume, float pitch) {
        public SoundProfile {
            key = requireAsset(key, "sound key");
            if (!Float.isFinite(volume) || volume < 0.0F || volume > 4.0F) {
                throw new IllegalArgumentException("sound volume must be between 0 and 4");
            }
            if (!Float.isFinite(pitch) || pitch < 0.5F || pitch > 2.0F) {
                throw new IllegalArgumentException("sound pitch must be between 0.5 and 2");
            }
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
