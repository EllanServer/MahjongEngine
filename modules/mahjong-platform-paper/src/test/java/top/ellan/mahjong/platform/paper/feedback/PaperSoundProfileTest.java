package top.ellan.mahjong.platform.paper.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PaperSoundProfileTest {
    @Test
    void acceptsNamespacedBoundedParameters() {
        PaperSoundProfile profile =
                new PaperSoundProfile("mahjongcraft:opening_dice", 0.7F, 1.0F);

        assertEquals("mahjongcraft:opening_dice", profile.key());
    }

    @Test
    void rejectsInvalidKeysAndUnsafeAudioRanges() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PaperSoundProfile("opening_dice", 0.7F, 1.0F));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PaperSoundProfile("mahjongcraft:opening_dice", 4.1F, 1.0F));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PaperSoundProfile("mahjongcraft:opening_dice", 0.7F, 0.4F));
    }
}
