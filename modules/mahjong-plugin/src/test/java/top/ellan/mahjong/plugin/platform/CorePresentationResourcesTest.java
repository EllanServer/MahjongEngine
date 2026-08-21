package top.ellan.mahjong.plugin.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CorePresentationResourcesTest {
    @Test
    void loadsAssetsGeometryCapacitiesAndOpeningTimingsFromCePack() {
        CorePresentationResources presentation = CorePresentationResources.load(
                CorePresentationResourcesTest.class.getClassLoader());

        assertEquals("mahjongpaper:table_visual", presentation.assets().tableFurniture());
        assertEquals(0.1125D, presentation.geometry().tileWidth());
        assertEquals(18, presentation.geometry().maxHandTiles());
        assertEquals("mahjongpaper:opening_die_slot_", presentation.openingDieSlotPrefix());
        assertEquals(20, presentation.openingRollTicks());
        assertEquals(12, presentation.openingRevealTicks());
        assertTrue(presentation.assets().actionInteractionFurnitureVariants().size() > 1);
    }

    @Test
    void operationalPluginConfigContainsNoCeVisualConfiguration() throws IOException {
        try (InputStream input = CorePresentationResourcesTest.class
                .getClassLoader()
                .getResourceAsStream("config.yml")) {
            assertNotNull(input);
            String config = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(config.contains("craftengine.assets"));
            assertFalse(config.contains("  assets:"));
            assertFalse(config.contains("  geometry:"));
            assertFalse(config.contains("  opening:"));
            assertFalse(config.contains("  capacity:"));
        }
    }
}
