package top.ellan.mahjong.platform.paper.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackRef;
import top.ellan.mahjong.spi.RulePresentationCueType;

class PaperRuleSoundCatalogTest {
    private static final RuleId RULE_ID = new RuleId("mcr");

    @Test
    void isolatesSoundsForConcurrentRuleVersions() {
        PaperRuleSoundCatalog catalog = new PaperRuleSoundCatalog();
        RulePackRef old = reference("2.0.2", '1');
        RulePackRef current = reference("2.0.3", '2');
        catalog.replaceAll(java.util.List.of(
                binding(old, "mahjong_mcr_v2_0_2:tile_draw"),
                binding(current, "mahjong_mcr_v2_0_3:tile_draw")));

        assertEquals(
                "mahjong_mcr_v2_0_2:tile_draw",
                catalog.cue(old, RulePresentationCueType.TILE_DRAW).orElseThrow().key());
        assertEquals(
                "mahjong_mcr_v2_0_3:tile_draw",
                catalog.cue(current, RulePresentationCueType.TILE_DRAW).orElseThrow().key());
        assertTrue(catalog.cue(reference("2.0.3", '3'), RulePresentationCueType.TILE_DRAW)
                .isEmpty());

        catalog.remove(current);

        assertTrue(catalog.cue(current, RulePresentationCueType.TILE_DRAW).isEmpty());
        assertEquals(
                "mahjong_mcr_v2_0_2:tile_draw",
                catalog.cue(old, RulePresentationCueType.TILE_DRAW).orElseThrow().key());
    }

    private static RulePackRef reference(String version, char hashDigit) {
        return new RulePackRef(RULE_ID, version, Character.toString(hashDigit).repeat(64), 1);
    }

    private static RuleSoundBinding binding(RulePackRef reference, String drawKey) {
        PaperSoundProfile draw = new PaperSoundProfile(drawKey, 0.65F, 1.05F);
        return new RuleSoundBinding(
                reference.ruleId(),
                reference.version(),
                reference.jarSha256(),
                new RuleSoundProfiles(
                        Map.of(RulePresentationCueType.TILE_DRAW, draw),
                        new PaperSoundProfile(
                                drawKey.replace("tile_draw", "opening_dice"), 0.7F, 1.0F),
                        new PaperSoundProfile(
                                drawKey.replace("tile_draw", "opening_wall"), 0.8F, 1.0F)));
    }
}
