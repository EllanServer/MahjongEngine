package top.ellan.mahjong.craftengine.bundle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CraftEngineVersionTest {
    @Test
    void acceptsOnlyThePinned268ApiLine() {
        assertDoesNotThrow(() -> CraftEngineVersion.requireSupported("26.8"));
        assertDoesNotThrow(() -> CraftEngineVersion.requireSupported("26.8.1-SNAPSHOT"));
    }

    @Test
    void rejectsOtherApiLinesAndUnknownVersions() {
        assertThrows(IllegalStateException.class, () -> CraftEngineVersion.requireSupported("26.7.4"));
        assertThrows(IllegalStateException.class, () -> CraftEngineVersion.requireSupported("26.9"));
        assertThrows(IllegalStateException.class, () -> CraftEngineVersion.requireSupported("27.0"));
        assertThrows(IllegalStateException.class, () -> CraftEngineVersion.requireSupported("dev"));
        assertThrows(IllegalStateException.class, () -> CraftEngineVersion.requireSupported(null));
    }
}
