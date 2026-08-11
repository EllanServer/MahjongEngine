package top.ellan.mahjong.presentation.node;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SceneAssetGrammarTest {
    @Test
    void assetValidationMatchesTheFormerRegularExpression() {
        List<String> valid = List.of(
                "a:b",
                "mahjongpaper:tile_standing_m5-red.v2",
                "namespace.with-dash:path/to.asset_2-");
        List<String> invalid = List.of(
                "",
                ":path",
                "namespace:",
                "namespace:path:extra",
                "NameSpace:path",
                "namespace:Path",
                "name/space:path",
                "namespace:path?query");

        valid.forEach(value -> {
            assertTrue(SceneAssetGrammar.validAsset(value));
            assertTrue(SceneAssetGrammar.validAsset(value));
        });
        invalid.forEach(value -> assertFalse(SceneAssetGrammar.validAsset(value)));
    }

    @Test
    void variantValidationMatchesTheFormerRegularExpression() {
        List<String> valid = List.of("ground", "double_face_6", "variant.v2-beta");
        List<String> invalid = List.of("", "face/6", "face:6", "Face_6", "face 6");

        valid.forEach(value -> {
            assertTrue(SceneAssetGrammar.validVariant(value));
            assertTrue(SceneAssetGrammar.validVariant(value));
        });
        invalid.forEach(value -> assertFalse(SceneAssetGrammar.validVariant(value)));
    }
}
