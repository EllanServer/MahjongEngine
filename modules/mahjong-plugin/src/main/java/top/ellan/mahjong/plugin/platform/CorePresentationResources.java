package top.ellan.mahjong.plugin.platform;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Properties;
import top.ellan.mahjong.presentation.asset.TableSceneAssets;
import top.ellan.mahjong.presentation.layout.TableGeometry;

/** Loads the presentation contract shipped inside the CE resource pack. */
record CorePresentationResources(
        TableSceneAssets assets,
        TableGeometry geometry,
        String openingDieSlotPrefix,
        int openingRollTicks,
        int openingRevealTicks) {
    private static final String RESOURCE =
            "craftengine/mahjongpaper/resourcepack/assets/mahjongcraft/mahjong_presentation.properties";

    CorePresentationResources {
        Objects.requireNonNull(assets, "assets");
        Objects.requireNonNull(geometry, "geometry");
        openingDieSlotPrefix = requireAsset(openingDieSlotPrefix, "asset.opening-die-slot-prefix");
        requireRange(openingRollTicks, "opening.roll-ticks", 1, 200);
        requireRange(openingRevealTicks, "opening.reveal-ticks", 1, 200);
    }

    static CorePresentationResources load(ClassLoader loader) {
        Objects.requireNonNull(loader, "loader");
        Properties values = new Properties();
        try (InputStream input = loader.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing CE presentation resource " + RESOURCE);
            }
            values.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read CE presentation resource " + RESOURCE, failure);
        }
        int schema = integer(values, "schema");
        if (schema != 1) {
            throw new IllegalArgumentException("Unsupported CE presentation schema " + schema);
        }
        String actionPrefix = asset(values, "asset.action-hitbox-prefix");
        TableSceneAssets assets = new TableSceneAssets(
                asset(values, "asset.table"),
                asset(values, "asset.seat"),
                asset(values, "asset.standing-back"),
                asset(values, "asset.flat-back"),
                asset(values, "asset.hand-hitbox"),
                asset(values, "asset.action-hitbox"),
                TableSceneAssets.actionInteractionVariants(actionPrefix));
        TableGeometry geometry = new TableGeometry(
                decimal(values, "geometry.tile-width"),
                decimal(values, "geometry.tile-height"),
                decimal(values, "geometry.tile-depth"),
                decimal(values, "geometry.tile-gap"),
                decimal(values, "geometry.surface-height"),
                decimal(values, "geometry.hand-radius"),
                decimal(values, "geometry.wall-radius"),
                decimal(values, "geometry.table-half-length"),
                decimal(values, "geometry.emphasis-raise"),
                decimal(values, "geometry.action-column-spacing"),
                decimal(values, "geometry.action-row-spacing"),
                decimal(values, "geometry.secondary-action-offset"),
                integer(values, "capacity.hand-tiles"),
                integer(values, "capacity.discards"),
                integer(values, "capacity.meld-tiles"),
                integer(values, "capacity.point-sticks"),
                integer(values, "capacity.auxiliary-tiles"),
                integer(values, "capacity.actions"));
        return new CorePresentationResources(
                assets,
                geometry,
                asset(values, "asset.opening-die-slot-prefix"),
                integer(values, "opening.roll-ticks"),
                integer(values, "opening.reveal-ticks"));
    }

    private static String asset(Properties values, String key) {
        return requireAsset(required(values, key), key);
    }

    private static String requireAsset(String value, String key) {
        String normalized = Objects.requireNonNull(value, key).trim();
        if (!normalized.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Invalid " + key);
        }
        return normalized;
    }

    private static int integer(Properties values, String key) {
        try {
            return Integer.parseInt(required(values, key));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid integer " + key, invalid);
        }
    }

    private static double decimal(Properties values, String key) {
        try {
            double value = Double.parseDouble(required(values, key));
            if (!Double.isFinite(value)) {
                throw new NumberFormatException("not finite");
            }
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid decimal " + key, invalid);
        }
    }

    private static String required(Properties values, String key) {
        String value = values.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing CE presentation property " + key);
        }
        return value.trim();
    }

    private static void requireRange(int value, String key, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " must be " + minimum + ".." + maximum);
        }
    }
}
