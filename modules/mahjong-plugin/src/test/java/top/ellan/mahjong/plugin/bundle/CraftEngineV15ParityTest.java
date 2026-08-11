package top.ellan.mahjong.plugin.bundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CraftEngineV15ParityTest {
    private static final String CONFIGURATION =
            "craftengine/mahjongpaper/configuration/mahjong.yml";

    @Test
    void tableKeepsOnlyTheLoweredThreeByThreeCollisionGrid() throws IOException {
        String table = section(configuration(), "mahjongpaper:table_visual:", "mahjongpaper:hand_tile_hitbox:");

        assertEquals(9, occurrences(table, "type: shulker"));
        assertEquals(9, occurrences(table, "-1.5"));
        assertFalse(table.contains("seat_chair_model"));
        assertFalse(table.contains("type: interaction"));
    }

    @Test
    void chairIsIndependentFurnitureWithTheV15SeatHitbox() throws IOException {
        String chair = section(configuration(), "mahjongpaper:seat_chair:", "mahjongpaper:dice_rolling_model:");

        assertTrue(chair.contains("item: mahjongpaper:seat_chair_model"));
        assertTrue(chair.contains("type: shulker"));
        assertTrue(chair.contains("position: 0,-1.5,0"));
        assertTrue(chair.contains("- 0,-1.5,0"));
    }

    @Test
    void handInteractionBoxMatchesV15Dimensions() throws IOException {
        String hand = section(configuration(), "mahjongpaper:hand_tile_hitbox:", "mahjongpaper:action_button_hitbox:");

        assertTrue(hand.contains("width: 0.1"));
        assertTrue(hand.contains("height: 0.18"));
    }

    @Test
    void actionButtonsUseV15HeightAndCeOwnedLocalizedWidthBuckets() throws IOException {
        String source = configuration();
        String base = section(
                source,
                "mahjongpaper:action_button_hitbox:",
                "mahjongpaper:seat_chair_model:");
        String variants = section(
                source,
                "config_factory#action_button_hitboxes:",
                "config_factory#point_sticks:");

        assertTrue(base.contains("width: 0.7"));
        assertTrue(base.contains("height: 0.22"));
        assertEquals(16, occurrences(variants, "- {size:"));
        assertTrue(variants.contains("- {size: \"070\", width: 0.7}"));
        assertTrue(variants.contains("- {size: \"220\", width: 2.2}"));
        assertTrue(variants.contains("width: \"${width}\""));
        assertTrue(variants.contains("height: 0.22"));
    }

    private static String configuration() throws IOException {
        try (InputStream input = CraftEngineV15ParityTest.class
                .getClassLoader()
                .getResourceAsStream(CONFIGURATION)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String section(String text, String startMarker, String endMarker) {
        int start = text.indexOf(startMarker);
        int end = text.indexOf(endMarker, start + startMarker.length());
        assertTrue(start >= 0 && end > start);
        return text.substring(start, end);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
