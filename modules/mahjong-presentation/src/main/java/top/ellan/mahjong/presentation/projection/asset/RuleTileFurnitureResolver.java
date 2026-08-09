package top.ellan.mahjong.presentation.projection.asset;

import java.util.Objects;
import top.ellan.mahjong.presentation.asset.TableSceneAssets;
import top.ellan.mahjong.presentation.asset.TileAssetName;
import top.ellan.mahjong.spi.RuleViewTile;
import top.ellan.mahjong.spi.RuleViewZone;

/** Maps semantic rule visuals to reusable CraftEngine furniture identifiers. */
public final class RuleTileFurnitureResolver {
    private final TableSceneAssets assets;

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
        String pose = tile.zone() == RuleViewZone.HAND
                ? "tile_standing"
                : "tile_flat_face_up";
        return "mahjongpaper:" + pose + '_' + TileAssetName.from(tile.visualId());
    }

    private static String pointStick(String visualId) {
        int separator = visualId.indexOf("stick/");
        String denomination = separator >= 0
                ? visualId.substring(separator + "stick/".length())
                : visualId;
        if (!denomination.matches("p(?:100|1000|5000|10000)")) {
            throw new IllegalArgumentException("Unsupported point-stick visual id: " + visualId);
        }
        return "mahjongpaper:stick_" + denomination;
    }
}
