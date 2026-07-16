package top.ellan.mahjong.table.presentation;

import top.ellan.mahjong.table.core.TableSessionContext;
import top.ellan.mahjong.model.MahjongVariant;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.riichi.model.MahjongRule;
import java.util.Locale;
import java.util.UUID;

public final class TablePublicTextFactory {
    private final TableSessionContext session;

    public TablePublicTextFactory(TableSessionContext session) {
        this.session = session;
    }

    public String waitingSummary(Locale locale) {
        return this.session.plugin().messages().plain(
            locale,
            "table.waiting_summary",
            this.session.plugin().messages().number(locale, "seats", this.session.size()),
            this.session.plugin().messages().number(locale, "bots", this.session.botCount()),
            this.session.plugin().messages().number(locale, "spectators", this.session.spectatorCount()),
            this.session.plugin().messages().tag("rules", this.ruleSummary(locale))
        ) + " | " + this.readySummary(locale);
    }

    public String waitingDisplaySummary(Locale locale) {
        return this.session.plugin().messages().plain(
            locale,
            "table.waiting_display_summary",
            this.session.plugin().messages().number(locale, "seats", this.session.size()),
            this.session.plugin().messages().number(locale, "bots", this.session.botCount()),
            this.session.plugin().messages().number(locale, "spectators", this.session.spectatorCount())
        ) + " | " + this.readySummary(locale);
    }

    public String ruleDisplaySummary(Locale locale) {
        MahjongVariant variant = this.session.currentVariant();
        MahjongRule rule = this.currentRule();
        if (variant != MahjongVariant.RIICHI) {
            return this.session.plugin().messages().plain(
                locale,
                "table.rule_display_summary_gb",
                this.session.plugin().messages().tag("variant", this.variantLabel(locale, variant)),
                this.session.plugin().messages().tag("thinking", this.ruleThinkingLabel(locale, rule.getThinkingTime())),
                this.session.plugin().messages().number(locale, "start", rule.getStartingPoints()),
                this.session.plugin().messages().number(locale, "goal", rule.getMinPointsToWin())
            );
        }
        return this.session.plugin().messages().plain(
            locale,
            "table.rule_display_summary",
            this.session.plugin().messages().tag("variant", this.variantLabel(locale, variant)),
            this.session.plugin().messages().tag("length", this.ruleLengthLabel(locale, rule.getLength())),
            this.session.plugin().messages().tag("thinking", this.ruleThinkingLabel(locale, rule.getThinkingTime())),
            this.session.plugin().messages().tag("red", this.ruleRedFiveLabel(locale, rule.getRedFive())),
            this.session.plugin().messages().tag("min_han", this.ruleMinimumHanLabel(locale, rule.getMinimumHan())),
            this.session.plugin().messages().number(locale, "start", rule.getStartingPoints()),
            this.session.plugin().messages().number(locale, "goal", rule.getMinPointsToWin())
        );
    }

    public String ruleSummary(Locale locale) {
        MahjongVariant variant = this.session.currentVariant();
        MahjongRule rule = this.currentRule();
        if (variant != MahjongVariant.RIICHI) {
            return this.session.plugin().messages().plain(
                locale,
                "table.rule_summary_gb",
                this.session.plugin().messages().tag("variant", this.variantLabel(locale, variant)),
                this.session.plugin().messages().tag("thinking", this.ruleThinkingLabel(locale, rule.getThinkingTime())),
                this.session.plugin().messages().tag("spectate", this.booleanLabel(locale, rule.getSpectate())),
                this.session.plugin().messages().number(locale, "start_points", rule.getStartingPoints()),
                this.session.plugin().messages().number(locale, "goal", rule.getMinPointsToWin())
            );
        }
        return this.session.plugin().messages().plain(
            locale,
            "table.rule_summary",
            this.session.plugin().messages().tag("variant", this.variantLabel(locale, variant)),
            this.session.plugin().messages().tag("length", this.ruleLengthLabel(locale, rule.getLength())),
            this.session.plugin().messages().tag("thinking", this.ruleThinkingLabel(locale, rule.getThinkingTime())),
            this.session.plugin().messages().tag("min_han", this.ruleMinimumHanLabel(locale, rule.getMinimumHan())),
            this.session.plugin().messages().tag("spectate", this.booleanLabel(locale, rule.getSpectate())),
            this.session.plugin().messages().tag("red_five", this.ruleRedFiveLabel(locale, rule.getRedFive())),
            this.session.plugin().messages().tag("open_tanyao", this.booleanLabel(locale, rule.getOpenTanyao())),
            this.session.plugin().messages().tag("local_yaku", this.booleanLabel(locale, rule.getLocalYaku())),
            this.session.plugin().messages().number(locale, "start_points", rule.getStartingPoints()),
            this.session.plugin().messages().number(locale, "goal", rule.getMinPointsToWin())
        );
    }

    public String roundDisplay(Locale locale) {
        if (!this.session.hasRoundController()) {
            return this.session.plugin().messages().plain(locale, "table.round_not_started");
        }
        if (this.session.currentVariant() == MahjongVariant.SICHUAN) {
            int handNumber = this.session.roundWind().index() * SeatWind.values().length + this.session.roundIndex() + 1;
            return this.session.plugin().messages().plain(
                locale,
                "table.round_display.sichuan",
                this.session.plugin().messages().number(locale, "hand", handNumber)
            );
        }
        if (this.session.currentVariant() == MahjongVariant.GB) {
            return this.session.plugin().messages().plain(
                locale,
                "table.round_display.gb",
                this.session.plugin().messages().tag("wind", this.roundWindText(locale)),
                this.session.plugin().messages().number(locale, "round", this.session.roundIndex() + 1)
            );
        }
        return this.session.plugin().messages().plain(
            locale,
            "table.round_display",
            this.session.plugin().messages().tag("wind", this.roundWindText(locale)),
            this.session.plugin().messages().number(locale, "round", this.session.roundIndex() + 1),
            this.session.plugin().messages().number(locale, "honba", this.session.honbaCount())
        );
    }

    public String dealerName(Locale locale) {
        UUID dealerId = this.session.playerAt(this.session.dealerSeat());
        if (dealerId == null) {
            return this.session.plugin().messages().plain(locale, "common.unknown");
        }
        return this.session.displayName(dealerId, locale);
    }

    public String seatDisplayName(SeatWind wind, Locale locale) {
        return this.session.plugin().messages().plain(locale, this.displaySeatWind(wind).translationKey());
    }

    public String publicSeatStatus(SeatWind wind) {
        Locale locale = this.session.publicLocale();
        UUID playerId = this.session.playerAt(wind);
        if (playerId == null) {
            return this.session.plugin().messages().plain(
                locale,
                "table.public.seat_empty",
                this.session.plugin().messages().tag("seat", this.seatDisplayName(wind, locale))
            );
        }
        String status = this.waitingSeatStatus(locale, playerId);
        if (this.session.currentVariant() == MahjongVariant.RIICHI && this.session.isStarted() && this.session.isRiichi(playerId)) {
            String riichi = this.session.plugin().messages().plain(locale, "overlay.status_riichi");
            status = status.isBlank() ? riichi : status + " | " + riichi;
        }
        return this.session.plugin().messages().plain(
            locale,
            "table.public.seat_status",
            this.session.plugin().messages().tag("seat", this.seatDisplayName(wind, locale)),
            this.session.plugin().messages().number(locale, "points", this.session.points(playerId)),
            this.session.plugin().messages().tag("status", status.isBlank() ? "" : " | " + status)
        );
    }

    public String publicCenterText() {
        Locale locale = this.session.publicLocale();
        if (this.session.isStarted()) {
            String lastAction = this.centerLastActionSummary(locale);
            return lastAction.isBlank() ? "" : lastAction;
        }
        return this.session.plugin().messages().plain(
            locale,
            "table.public.center_waiting",
            this.session.plugin().messages().tag("table_id", this.session.id()),
            this.session.plugin().messages().tag("summary", this.waitingDisplaySummary(locale)),
            this.session.plugin().messages().tag("rules", this.ruleDisplaySummary(locale))
        );
    }

    private MahjongRule currentRule() {
        return this.session.configuredRuleSnapshot();
    }

    private String roundWindText(Locale locale) {
        return switch (this.session.roundWind()) {
            case EAST -> this.session.plugin().messages().plain(locale, "seat.wind.east");
            case SOUTH -> this.session.plugin().messages().plain(locale, "seat.wind.south");
            case WEST -> this.session.plugin().messages().plain(locale, "seat.wind.west");
            case NORTH -> this.session.plugin().messages().plain(locale, "seat.wind.north");
        };
    }

    private SeatWind displaySeatWind(SeatWind physicalSeat) {
        if (physicalSeat == null || !this.session.hasRoundController()) {
            return physicalSeat;
        }
        SeatWind dealerSeat = this.session.dealerSeat();
        if (dealerSeat == null) {
            return physicalSeat;
        }
        return SeatWind.fromIndex(Math.floorMod(physicalSeat.index() - dealerSeat.index(), SeatWind.values().length));
    }

    private String ruleLengthLabel(Locale locale, MahjongRule.GameLength length) {
        return this.session.plugin().messages().plain(locale, "rule.length." + length.name().toLowerCase(Locale.ROOT));
    }

    private String variantLabel(Locale locale, MahjongVariant variant) {
        return this.session.plugin().messages().plain(locale, variant.translationKey());
    }

    private String ruleThinkingLabel(Locale locale, MahjongRule.ThinkingTime thinkingTime) {
        return this.session.plugin().messages().plain(locale, "rule.thinking." + thinkingTime.name().toLowerCase(Locale.ROOT));
    }

    private String ruleMinimumHanLabel(Locale locale, MahjongRule.MinimumHan minimumHan) {
        return this.session.plugin().messages().plain(locale, "rule.minimum_han." + minimumHan.name().toLowerCase(Locale.ROOT));
    }

    private String ruleRedFiveLabel(Locale locale, MahjongRule.RedFive redFive) {
        return this.session.plugin().messages().plain(locale, "rule.red_five." + redFive.name().toLowerCase(Locale.ROOT));
    }

    private String booleanLabel(Locale locale, boolean value) {
        return this.session.plugin().messages().plain(locale, value ? "common.true" : "common.false");
    }

    private String centerLastActionSummary(Locale locale) {
        return this.session.publicLastActionSummary(locale);
    }

    private String readySummary(Locale locale) {
        return this.session.plugin().messages().plain(
            locale,
            "table.ready_summary",
            this.session.plugin().messages().number(locale, "ready", this.session.readyCount()),
            this.session.plugin().messages().number(locale, "total", this.session.size())
        );
    }

    private String waitingSeatStatus(Locale locale, UUID playerId) {
        if (playerId == null || this.session.isStarted()) {
            return "";
        }
        if (this.session.isQueuedToLeave(playerId)) {
            return this.session.plugin().messages().plain(locale, "table.status.leaving");
        }
        return this.session.plugin().messages().plain(locale, this.session.isReady(playerId) ? "table.status.ready" : "table.status.waiting");
    }
}
