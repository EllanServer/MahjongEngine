package top.ellan.mahjong.table.core;

import top.ellan.mahjong.model.MahjongVariant;

import org.junit.jupiter.api.Test;
import top.ellan.mahjong.riichi.model.MahjongRule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionRulePresetResolverTest {
    @Test
    void majsoulHanchanPresetMatchesPrimaryGameplay() {
        SessionRulePresetResolver.Preset preset = SessionRulePresetResolver.resolve("MAJSOUL_HANCHAN");

        assertNotNull(preset);
        assertEquals(MahjongVariant.RIICHI, preset.variant());
        MahjongRule rule = preset.rule();
        assertEquals(MahjongRule.GameLength.TWO_WIND, rule.getLength());
        assertEquals(25000, rule.getStartingPoints());
        assertEquals(30000, rule.getMinPointsToWin());
        assertEquals(MahjongRule.MinimumHan.ONE, rule.getMinimumHan());
        assertEquals(MahjongRule.RedFive.THREE, rule.getRedFive());
        assertTrue(rule.getOpenTanyao());
        assertFalse(rule.getLocalYaku());
        assertEquals(MahjongRule.RonMode.MULTI_RON, rule.getRonMode());
        assertEquals(MahjongRule.RiichiProfile.MAJSOUL, rule.getRiichiProfile());
    }

    @Test
    void majsoulTonpuuPresetUsesEastGameWithSameMajsoulRules() {
        SessionRulePresetResolver.Preset preset = SessionRulePresetResolver.resolve("MAJSOUL_TONPUU");

        assertNotNull(preset);
        assertEquals(MahjongVariant.RIICHI, preset.variant());
        MahjongRule rule = preset.rule();
        assertEquals(MahjongRule.GameLength.EAST, rule.getLength());
        assertEquals(MahjongRule.RedFive.THREE, rule.getRedFive());
        assertTrue(rule.getOpenTanyao());
        assertEquals(MahjongRule.RonMode.MULTI_RON, rule.getRonMode());
        assertEquals(MahjongRule.RiichiProfile.MAJSOUL, rule.getRiichiProfile());
    }

    @Test
    void defaultRiichiRuleUsesMajsoulHanchan() {
        MahjongRule rule = SessionRulePresetResolver.defaultRuleFor(MahjongVariant.RIICHI);

        assertEquals(MahjongRule.GameLength.TWO_WIND, rule.getLength());
        assertEquals(MahjongRule.RedFive.THREE, rule.getRedFive());
        assertTrue(rule.getOpenTanyao());
        assertEquals(MahjongRule.RonMode.MULTI_RON, rule.getRonMode());
        assertEquals(MahjongRule.RiichiProfile.MAJSOUL, rule.getRiichiProfile());
    }

    @Test
    void gbPresetUsesTheOfficialFourWindSixteenHandStructure() {
        SessionRulePresetResolver.Preset preset = SessionRulePresetResolver.resolve("GB");

        assertNotNull(preset);
        assertEquals(MahjongVariant.GB, preset.variant());
        MahjongRule rule = preset.rule();
        assertEquals(MahjongRule.GameLength.FOUR_WIND, rule.getLength());
        assertEquals(0, rule.getStartingPoints());
        assertEquals(0, rule.getMinPointsToWin());
        assertEquals(0, rule.getStartingPoints() * 4);

        var round = rule.getLength().getStartingRound();
        for (int hand = 1; hand < 16; hand++) {
            assertFalse(round.isAllLast(rule), "hand " + hand + " must not be all-last");
            round.nextRound();
        }
        assertTrue(round.isAllLast(rule));
        assertEquals(top.ellan.mahjong.riichi.model.Wind.NORTH, round.getWind());
        assertEquals(3, round.getRound());
    }

    @Test
    void sichuanPresetUsesEightHandsAndAZeroBasedScoreLedger() {
        SessionRulePresetResolver.Preset preset = SessionRulePresetResolver.resolve("SICHUAN");

        assertNotNull(preset);
        assertEquals(MahjongVariant.SICHUAN, preset.variant());
        assertEquals(MahjongRule.GameLength.TWO_WIND, preset.rule().getLength());
        assertEquals(0, preset.rule().getStartingPoints());
        assertEquals(0, preset.rule().getMinPointsToWin());
        assertNull(SessionRulePresetResolver.resolve("SICHUAN_TOURNAMENT"), "House rules must not masquerade as the MIL tournament profile");
    }
}
