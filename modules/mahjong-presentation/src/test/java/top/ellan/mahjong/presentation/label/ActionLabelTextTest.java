package top.ellan.mahjong.presentation.label;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ActionLabelTextTest {
    @Test
    void resolvesSemanticTilesWithoutExposingInternalIds() {
        Map<String, String> translations = Map.of(
                "mahjongpaper.action.chii", "吃",
                "mahjongpaper.tile.m2", "二万",
                "mahjongpaper.tile.m3", "三万");

        assertEquals(
                "吃 二万 三万",
                ActionLabelText.resolve(
                        "action.chii:tile.m2:tile.m3",
                        (key, fallback) -> translations.getOrDefault(key, fallback)));
    }
}
