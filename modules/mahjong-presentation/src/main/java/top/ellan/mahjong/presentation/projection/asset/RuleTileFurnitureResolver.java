package top.ellan.mahjong.presentation.projection.asset;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import top.ellan.mahjong.presentation.asset.TableSceneAssets;
import top.ellan.mahjong.presentation.asset.TileAssetName;
import top.ellan.mahjong.spi.RuleViewTile;
import top.ellan.mahjong.spi.RuleViewZone;
import top.ellan.mahjong.spi.TileVisualId;

/** Maps semantic rule visuals to reusable CraftEngine furniture identifiers. */
public final class RuleTileFurnitureResolver {
    private static final int FACE_CACHE_CAPACITY = 128;

    private final TableSceneAssets assets;
    private final Map<TileVisualId, String> standingFaces = new ConcurrentHashMap<>();
    private final Map<TileVisualId, String> flatFaces = new ConcurrentHashMap<>();

    public RuleTileFurnitureResolver(TableSceneAssets assets) {
        this.assets = Objects.requireNonNull(assets, "assets");
    }

    public String resolve(RuleViewTile tile) {
        if (tile.zone() == RuleViewZone.POINT_STICK) {
            return pointStick(tile.visualId().value());
        }
        if (!tile.faceUp()) {
            return tile.zone() == RuleViewZone.HAND
                    ? assets.standingBackFurniture()
                    : assets.flatBackFurniture();
        }
        return tile.zone() == RuleViewZone.HAND
                ? faceAsset(tile.visualId(), standingFaces, "tile_standing")
                : faceAsset(tile.visualId(), flatFaces, "tile_flat_face_up");
    }

    private static String faceAsset(
            TileVisualId visualId, Map<TileVisualId, String> cache, String pose) {
        String cached = cache.get(visualId);
        if (cached != null) {
            return cached;
        }
        String resolved = "mahjongpaper:" + pose + '_' + TileAssetName.from(visualId);
        if (cache.size() >= FACE_CACHE_CAPACITY) {
            return resolved;
        }
        String raced = cache.putIfAbsent(visualId, resolved);
        return raced == null ? resolved : raced;
    }

    private static String pointStick(String visualId) {
        int separator = visualId.indexOf("stick/");
        String denomination = separator >= 0
                ? visualId.substring(separator + "stick/".length())
                : visualId;
        return switch (denomination) {
            case "p100" -> "mahjongpaper:stick_p100";
            case "p1000" -> "mahjongpaper:stick_p1000";
            case "p5000" -> "mahjongpaper:stick_p5000";
            case "p10000" -> "mahjongpaper:stick_p10000";
            default -> throw new IllegalArgumentException(
                    "Unsupported point-stick visual id: " + visualId);
        };
    }
}
