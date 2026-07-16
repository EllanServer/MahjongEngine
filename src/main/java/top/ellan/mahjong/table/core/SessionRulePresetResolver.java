package top.ellan.mahjong.table.core;

import top.ellan.mahjong.model.MahjongVariant;

import top.ellan.mahjong.riichi.model.MahjongRule;
import java.util.Locale;

final class SessionRulePresetResolver {
    private SessionRulePresetResolver() {
    }

    static Preset resolve(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        return switch (rawValue.toUpperCase(Locale.ROOT)) {
            case "MAJSOUL_TONPUU", "TONPUU", "TONPUSEN", "EAST" ->
                new Preset(MahjongVariant.RIICHI, majsoulRule(MahjongRule.GameLength.EAST));
            case "MAJSOUL_HANCHAN", "HANCHAN", "TWO_WIND", "SOUTH" ->
                new Preset(MahjongVariant.RIICHI, majsoulRule(MahjongRule.GameLength.TWO_WIND));
            case "GB", "GUOBIAO", "ZHONGGUO", "CHINESE_OFFICIAL" ->
                new Preset(MahjongVariant.GB, gbRule());
            case "SICHUAN", "SCMJ", "SICHUAN_MAHJONG" ->
                new Preset(MahjongVariant.SICHUAN, sichuanRule());
            default -> null;
        };
    }

    static MahjongRule defaultRuleFor(MahjongVariant variant) {
        return switch (variant) {
            case RIICHI -> majsoulRule(MahjongRule.GameLength.TWO_WIND);
            case GB -> gbRule();
            case SICHUAN -> sichuanRule();
        };
    }

    static MahjongRule majsoulRule(MahjongRule.GameLength length) {
        return new MahjongRule(
            length,
            MahjongRule.ThinkingTime.NORMAL,
            25000,
            30000,
            MahjongRule.MinimumHan.ONE,
            true,
            MahjongRule.RedFive.THREE,
            true,
            false,
            MahjongRule.RonMode.MULTI_RON,
            MahjongRule.RiichiProfile.MAJSOUL
        );
    }

    static MahjongRule gbRule() {
        // MCR accumulates net table points over a fixed sixteen-hand match.
        // Zero is therefore both the score origin and a harmless end threshold;
        // advanceMatchState reaches it only after the configured four winds.
        return chineseRule(MahjongRule.GameLength.FOUR_WIND, 0, 0);
    }

    static MahjongRule sichuanRule() {
        // T/TFMJ records the eight-hand room score as net gains and losses,
        // rather than borrowing the 25,000-point ledger used by riichi.
        return chineseRule(MahjongRule.GameLength.TWO_WIND, 0, 0);
    }

    private static MahjongRule chineseRule(MahjongRule.GameLength length, int startingPoints, int goalPoints) {
        return new MahjongRule(
            length,
            MahjongRule.ThinkingTime.NORMAL,
            startingPoints,
            goalPoints,
            MahjongRule.MinimumHan.ONE,
            true,
            MahjongRule.RedFive.NONE,
            false,
            false,
            MahjongRule.RonMode.MULTI_RON,
            MahjongRule.RiichiProfile.MAJSOUL
        );
    }

    record Preset(MahjongVariant variant, MahjongRule rule) {
    }
}
