package top.ellan.mahjong.table.core;

import top.ellan.mahjong.model.MahjongVariant;

import top.ellan.mahjong.riichi.model.MahjongRule;
import java.util.List;
import java.util.Locale;

final class SessionRuleCoordinator {
    private final TableSessionMutator session;

    SessionRuleCoordinator(TableSessionMutator session) {
        this.session = session;
    }

    boolean applyRulePreset(String rawValue) {
        SessionRulePresetResolver.Preset preset = SessionRulePresetResolver.resolve(rawValue);
        if (preset == null) {
            return false;
        }
        this.session.setConfiguredVariantInternal(preset.variant());
        this.session.setConfiguredRuleInternal(copyRule(preset.rule()));
        this.session.persistRoomMetadataIfNeededInternal();
        return true;
    }

    boolean setRuleOption(String key, String rawValue) {
        if (this.session.isStarted()) {
            return false;
        }
        try {
            switch (key.toLowerCase(Locale.ROOT)) {
                case "preset", "mode" -> {
                    if (!this.applyRulePreset(rawValue)) {
                        return false;
                    }
                }
                case "variant", "ruleset" -> {
                    MahjongVariant variant = MahjongVariant.valueOf(rawValue.toUpperCase(Locale.ROOT));
                    this.session.setConfiguredVariantInternal(variant);
                    this.session.setConfiguredRuleInternal(SessionRulePresetResolver.defaultRuleFor(variant));
                }
                case "length" -> this.session.configuredRuleInternal().setLength(MahjongRule.GameLength.valueOf(rawValue.toUpperCase(Locale.ROOT)));
                case "thinkingtime", "thinking" -> this.session.configuredRuleInternal().setThinkingTime(MahjongRule.ThinkingTime.valueOf(rawValue.toUpperCase(Locale.ROOT)));
                case "minimumhan", "minhan" -> this.session.configuredRuleInternal().setMinimumHan(MahjongRule.MinimumHan.valueOf(rawValue.toUpperCase(Locale.ROOT)));
                case "spectate" -> this.session.configuredRuleInternal().setSpectate(Boolean.parseBoolean(rawValue));
                case "redfive" -> this.session.configuredRuleInternal().setRedFive(MahjongRule.RedFive.valueOf(rawValue.toUpperCase(Locale.ROOT)));
                case "opentanyao" -> this.session.configuredRuleInternal().setOpenTanyao(Boolean.parseBoolean(rawValue));
                case "localyaku" -> {
                    if (Boolean.parseBoolean(rawValue)) {
                        return false;
                    }
                    this.session.configuredRuleInternal().setLocalYaku(false);
                }
                case "ronmode", "ron" -> this.session.configuredRuleInternal().setRonMode(parseRonMode(rawValue));
                case "riichiprofile", "profile" -> this.session.configuredRuleInternal().setRiichiProfile(parseRiichiProfile(rawValue));
                case "startingpoints", "startpoints" -> this.session.configuredRuleInternal().setStartingPoints(Integer.parseInt(rawValue));
                case "minpointstowin", "goal" -> this.session.configuredRuleInternal().setMinPointsToWin(Integer.parseInt(rawValue));
                default -> {
                    return false;
                }
            }
            this.session.render();
            this.session.persistRoomMetadataIfNeededInternal();
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    List<String> ruleKeys() {
        return List.of("preset", "mode", "variant", "ruleset", "length", "thinkingTime", "minimumHan", "spectate", "redFive", "openTanyao", "ronMode", "riichiProfile", "startingPoints", "minPointsToWin");
    }

    List<String> ruleValues(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "preset", "mode" -> List.of("MAJSOUL_TONPUU", "MAJSOUL_HANCHAN", "GB", "SICHUAN");
            case "variant", "ruleset" -> List.of("RIICHI", "GB", "SICHUAN");
            case "length" -> List.of("ONE_GAME", "EAST", "SOUTH", "TWO_WIND", "FOUR_WIND");
            case "thinkingtime", "thinking" -> List.of("VERY_SHORT", "SHORT", "NORMAL", "LONG", "VERY_LONG");
            case "minimumhan", "minhan" -> List.of("ONE", "TWO", "FOUR", "YAKUMAN");
            case "redfive" -> List.of("NONE", "THREE", "FOUR");
            case "ronmode", "ron" -> List.of("HEAD_BUMP", "MULTI_RON");
            case "riichiprofile", "profile" -> List.of("MAJSOUL", "EARLY_KAN_DORA");
            case "spectate", "opentanyao" -> List.of("true", "false");
            case "localyaku" -> List.of("false");
            case "startingpoints", "startpoints" -> List.of("500", "25000", "30000", "35000");
            case "minpointstowin", "goal" -> List.of("500", "30000", "35000", "40000");
            default -> List.of();
        };
    }

    private static MahjongRule copyRule(MahjongRule rule) {
        return new MahjongRule(
            rule.getLength(),
            rule.getThinkingTime(),
            rule.getStartingPoints(),
            rule.getMinPointsToWin(),
            rule.getMinimumHan(),
            rule.getSpectate(),
            rule.getRedFive(),
            rule.getOpenTanyao(),
            false,
            rule.getRonMode(),
            rule.getRiichiProfile()
        );
    }

    private static MahjongRule.RonMode parseRonMode(String rawValue) {
        if (rawValue == null) {
            throw new IllegalArgumentException("ronMode value is required");
        }
        String normalized = rawValue.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ATAMAHANE", "HEAD_JUMP", "HEADJUMP" -> MahjongRule.RonMode.HEAD_BUMP;
            case "MULTI", "DOUBLE_TRIPLE", "DOUBLERON", "TRIPLERON" -> MahjongRule.RonMode.MULTI_RON;
            default -> MahjongRule.RonMode.valueOf(normalized);
        };
    }

    private static MahjongRule.RiichiProfile parseRiichiProfile(String rawValue) {
        if (rawValue == null) {
            throw new IllegalArgumentException("riichiProfile value is required");
        }
        String normalized = rawValue.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "MJS", "MAHJONGSOUL" -> MahjongRule.RiichiProfile.MAJSOUL;
            case "EARLY_KAN_DORA", "TOURNAMENT" -> MahjongRule.RiichiProfile.EARLY_KAN_DORA;
            default -> MahjongRule.RiichiProfile.valueOf(normalized);
        };
    }
}
