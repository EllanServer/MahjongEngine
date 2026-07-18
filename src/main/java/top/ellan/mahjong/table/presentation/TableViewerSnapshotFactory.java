package top.ellan.mahjong.table.presentation;

import top.ellan.mahjong.presentation.TableFeedbackPolicy;
import top.ellan.mahjong.model.MahjongVariant;
import top.ellan.mahjong.riichi.ReactionOptions;
import top.ellan.mahjong.riichi.RoundResolution;
import top.ellan.mahjong.table.action.PlayerActionEntry;
import top.ellan.mahjong.table.action.PlayerActionPhase;
import top.ellan.mahjong.table.action.PlayerActionSnapshot;
import top.ellan.mahjong.table.action.PlayerActionSnapshotFactory;
import top.ellan.mahjong.table.core.DelimitedFingerprintBuilder;
import top.ellan.mahjong.table.core.TableSessionMutator;
import top.ellan.mahjong.render.snapshot.TableSpectatorSeatOverlaySnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerActionOverlaySnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerActionButtonSnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerActionBarSnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerHudSnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerHudPresentationSnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerOverlaySnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerPromptSnapshot;
import top.ellan.mahjong.render.scene.TableRenderConstants;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

public final class TableViewerSnapshotFactory {
    private final TableSessionMutator session;
    private final PlayerActionSnapshotFactory actionSnapshotFactory;

    public TableViewerSnapshotFactory(TableSessionMutator session) {
        this.session = session;
        this.actionSnapshotFactory = new PlayerActionSnapshotFactory(session);
    }

    public Component createStateSummary(Player player) {
        if (!this.session.hasRoundController()) {
            return this.session.plugin().messages().render(player, "command.table_not_started");
        }

        Locale locale = this.session.plugin().messages().resolveLocale(player);
        UUID viewerId = player.getUniqueId();
        PlayerActionSnapshot actionSnapshot = this.actionSnapshotFactory.capture(viewerId);
        ViewerSummarySnapshot summary = this.captureViewerSummarySnapshot(locale, viewerId, actionSnapshot, true);
        return this.session.plugin().messages().render(
            player,
            "command.rule_summary",
            this.session.plugin().messages().tag("summary", summary.commandStateSummary())
        );
    }

    public Component createViewerOverlay(Player viewer) {
        Locale locale = this.session.plugin().messages().resolveLocale(viewer);
        UUID viewerId = viewer.getUniqueId();
        PlayerActionSnapshot actionSnapshot = this.actionSnapshotFactory.capture(viewerId);
        return this.viewerOverlay(locale, this.captureViewerSummarySnapshot(locale, viewerId, actionSnapshot, false));
    }

    public TableViewerOverlaySnapshot captureViewerOverlaySnapshot(Player viewer) {
        Locale locale = this.session.plugin().messages().resolveLocale(viewer);
        UUID viewerId = viewer.getUniqueId();
        String regionKey = "viewer-overlay:" + viewerId;
        boolean spectator = this.session.isSpectator(viewerId);
        PlayerActionSnapshot actionSnapshot = this.actionSnapshotFactory.capture(viewerId);
        ViewerSummarySnapshot summary = this.captureViewerSummarySnapshot(locale, viewerId, actionSnapshot, true);
        boolean overheadActive = this.isOverheadActive(viewerId);
        List<TableViewerActionButtonSnapshot> actionButtons = this.viewerActionButtons(locale, viewer, spectator, actionSnapshot);
        Component overlay = this.viewerOverlay(locale, summary);
        TableViewerPromptSnapshot prompt = this.viewerPromptSnapshot(locale, viewerId, spectator || overheadActive, summary);
        TableViewerActionOverlaySnapshot actions = this.viewerActionOverlaySnapshot(locale, viewerId, spectator, actionButtons);
        List<TableSpectatorSeatOverlaySnapshot> seatOverlays = List.of();
        String fingerprint = this.viewerOverlayFingerprint(locale, viewerId, summary);
        return new TableViewerOverlaySnapshot(viewerId, regionKey, spectator, overlay, prompt, actions, seatOverlays, fingerprint);
    }

    public TableViewerHudPresentationSnapshot captureViewerHudPresentationSnapshot(Locale locale, UUID viewerId) {
        long secondsRemaining = this.session.actionDeadlineSecondsRemaining(viewerId);
        ViewerHudPollState pollState = this.captureViewerHudPollState(viewerId, secondsRemaining > 0L);
        return new TableViewerHudPresentationSnapshot(
            this.captureViewerHudSnapshot(locale, viewerId, pollState),
            secondsRemaining <= 0L
                ? TableViewerActionBarSnapshot.hidden()
                : this.captureViewerActionBarSnapshot(locale, pollState.actionPhase(), secondsRemaining)
        );
    }

    public TableViewerHudSnapshot captureViewerHudSnapshot(Locale locale, UUID viewerId) {
        return this.captureViewerHudSnapshot(locale, viewerId, this.captureViewerHudPollState(viewerId, false));
    }

    private TableViewerHudSnapshot captureViewerHudSnapshot(Locale locale, UUID viewerId, ViewerHudPollState pollState) {
        float progress = this.hudProgress(pollState);
        BossBar.Color color = this.hudColor(viewerId, pollState);

        if (!pollState.hasRoundController()) {
            return this.buildWaitingHudSnapshot(locale, progress, color);
        }

        RoundResolution lastResolution = pollState.roundFinished() ? this.session.lastResolution() : null;
        if (lastResolution != null) {
            String round = this.session.roundDisplay(locale);
            String resolutionTitle = this.resolutionLabel(locale, lastResolution.getTitle());
            Component title = this.session.plugin().messages().render(
                locale,
                "hud.finished",
                this.session.plugin().messages().tag("round", round),
                this.session.plugin().messages().tag("title", resolutionTitle)
            );
            String stateSignature = this.hudSignatureBuilder(locale, progress, color)
                .field(round)
                .field(resolutionTitle)
                .toString();
            return new TableViewerHudSnapshot(title, progress, color, stateSignature);
        }

        if (!pollState.started()) {
            return this.buildWaitingHudSnapshot(locale, progress, color);
        }

        String round = this.session.roundDisplay(locale);
        String turn = this.session.currentTurnDisplayName();
        int wall = this.session.remainingWallCount();
        Component title = this.buildRoundHudTitle(locale, round, turn, wall);
        String stateSignature = this.hudSignatureBuilder(locale, progress, color)
            .field(round)
            .field(turn)
            .field(wall)
            .toString();
        return new TableViewerHudSnapshot(title, progress, color, stateSignature);
    }

    public TableViewerActionBarSnapshot captureViewerActionBarSnapshot(Locale locale, UUID viewerId) {
        long secondsRemaining = this.session.actionDeadlineSecondsRemaining(viewerId);
        if (secondsRemaining <= 0L) {
            return TableViewerActionBarSnapshot.hidden();
        }
        return this.captureViewerActionBarSnapshot(locale, this.captureViewerActionPhase(viewerId), secondsRemaining);
    }

    private TableViewerActionBarSnapshot captureViewerActionBarSnapshot(
        Locale locale,
        PlayerActionPhase phase,
        long secondsRemaining
    ) {
        String messageKey = switch (phase) {
            case TURN -> "actionbar.deadline.discard";
            case REACTION -> "actionbar.deadline.reaction";
            case SICHUAN_EXCHANGE -> "actionbar.deadline.sichuan_exchange";
            case SICHUAN_DING_QUE -> "actionbar.deadline.sichuan_ding_que";
            case NONE, WAITING -> null;
        };
        if (messageKey == null) {
            return TableViewerActionBarSnapshot.hidden();
        }
        Component message = this.session.plugin().messages().render(
            locale,
            messageKey,
            this.session.plugin().messages().number(locale, "seconds", secondsRemaining)
        );
        return new TableViewerActionBarSnapshot(message, true);
    }

    private TableViewerHudSnapshot buildWaitingHudSnapshot(
        Locale locale,
        float progress,
        BossBar.Color color
    ) {
        String waitingSummary = this.session.waitingDisplaySummary(locale);
        Component title = this.session.plugin().messages().render(
            locale,
            "hud.waiting",
            this.session.plugin().messages().tag("table_id", this.session.id()),
            this.session.plugin().messages().tag("summary", waitingSummary)
        );
        String stateSignature = this.hudSignatureBuilder(locale, progress, color)
            .field(this.session.id())
            .field(waitingSummary)
            .toString();
        return new TableViewerHudSnapshot(title, progress, color, stateSignature);
    }

    private DelimitedFingerprintBuilder hudSignatureBuilder(
        Locale locale,
        float progress,
        BossBar.Color color
    ) {
        return fingerprintBuilder(192)
            .field(locale.toLanguageTag())
            .field(progress)
            .field(color);
    }

    private Component viewerOverlay(Locale locale, ViewerSummarySnapshot summary) {
        if (!this.session.hasRoundController()) {
            return this.waitingOverlay(locale, summary);
        }
        if (!this.session.isStarted()) {
            if (this.session.isRoundFinished() && this.session.lastResolution() != null) {
                return this.session.plugin().messages().render(
                    locale,
                    "overlay.finished",
                    this.session.plugin().messages().tag("round", summary.round()),
                    this.session.plugin().messages().tag("title", summary.resolutionTitle())
                );
            }
            return this.waitingOverlay(locale, summary);
        }
        return this.buildActiveOverlay(locale, summary);
    }

    private Component buildActiveOverlay(Locale locale, ViewerSummarySnapshot summary) {
        MahjongVariant variant = this.session.currentVariant();
        if (variant == MahjongVariant.RIICHI) {
            return this.session.plugin().messages().render(
                locale,
                "overlay.active",
                this.session.plugin().messages().tag("role", summary.roleLabel()),
                this.session.plugin().messages().tag("round", summary.round()),
                this.session.plugin().messages().tag("dealer", summary.dealer()),
                this.session.plugin().messages().tag("turn", summary.turn()),
                this.session.plugin().messages().number(locale, "wall", summary.wall()),
                this.session.plugin().messages().number(locale, "riichi_pool", summary.riichiPool()),
                this.session.plugin().messages().tag("dora", summary.doraSummary()),
                this.session.plugin().messages().tag("last_discard", summary.lastDiscardSummary()),
                this.session.plugin().messages().tag("prompt", "")
            );
        }
        return this.session.plugin().messages().render(
            locale,
            "overlay.active",
            this.session.plugin().messages().tag("role", summary.roleLabel()),
            this.session.plugin().messages().tag("round", summary.round()),
            this.session.plugin().messages().tag("dealer", summary.dealer()),
            this.session.plugin().messages().tag("turn", summary.turn()),
            this.session.plugin().messages().number(locale, "wall", summary.wall()),
            this.session.plugin().messages().number(locale, "riichi_pool", 0),
            this.session.plugin().messages().tag("dora", ""),
            this.session.plugin().messages().tag("last_discard", summary.lastDiscardSummary()),
            this.session.plugin().messages().tag("prompt", "")
        );
    }

    private Component waitingOverlay(Locale locale, ViewerSummarySnapshot summary) {
        return this.session.plugin().messages().render(
            locale,
            "overlay.waiting",
            this.session.plugin().messages().tag("table_id", this.session.id()),
            this.session.plugin().messages().tag("summary", summary.waitingSummary())
        );
    }

    private ViewerSummarySnapshot captureViewerSummarySnapshot(
        Locale locale,
        UUID viewerId,
        PlayerActionSnapshot actionSnapshot,
        boolean includeCommandStateSummary
    ) {
        boolean spectator = this.session.isSpectator(viewerId);
        boolean hasRoundController = this.session.hasRoundController();
        boolean started = hasRoundController && this.session.isStarted();
        String waitingSummary = started ? "" : this.session.waitingDisplaySummary(locale);
        if (!hasRoundController) {
            return new ViewerSummarySnapshot(
                spectator,
                waitingSummary,
                "",
                "",
                "",
                0,
                0,
                "",
                this.viewerRoleLabel(locale, viewerId),
                "",
                "",
                "",
                ""
            );
        }

        String round = this.session.roundDisplay(locale);
        String dealer = this.session.dealerName(locale);
        String turn = this.session.currentTurnDisplayName();
        int wall = this.session.remainingWallCount();
        int riichiPool = this.session.riichiPoolCount();
        String doraSummary = this.session.currentVariant() == MahjongVariant.RIICHI ? this.doraSummary(locale) : "";
        String roleLabel = this.viewerRoleLabel(locale, viewerId);
        String lastDiscardSummary = this.lastDiscardSummary(locale);
        ReactionOptions options = this.session.availableReactions(viewerId);
        RoundResolution lastResolution = this.session.lastResolution();
        String resolutionTitle = lastResolution == null
            ? ""
            : this.resolutionLabel(locale, lastResolution.getTitle());
        String viewerPrompt = this.viewerPrompt(locale, viewerId, actionSnapshot, options, spectator);
        String commandStateSummary = includeCommandStateSummary
            ? this.buildCommandStateSummary(locale, round, turn, wall, options, resolutionTitle)
            : "";
        return new ViewerSummarySnapshot(
            spectator,
            waitingSummary,
            round,
            dealer,
            turn,
            wall,
            riichiPool,
            doraSummary,
            roleLabel,
            lastDiscardSummary,
            viewerPrompt,
            resolutionTitle,
            commandStateSummary
        );
    }

    private String buildCommandStateSummary(
        Locale locale,
        String round,
        String turn,
        int wall,
        ReactionOptions options,
        String resolutionTitle
    ) {
        StringBuilder builder = new StringBuilder(128);
        builder.append(this.session.plugin().messages().plain(locale, "state.label.round")).append(' ').append(round);
        builder.append(" | ").append(this.session.plugin().messages().plain(locale, "state.label.turn")).append(' ').append(turn);
        builder.append(" | ").append(this.session.plugin().messages().plain(locale, "state.label.wall")).append(' ').append(wall);
        builder.append(" | ").append(this.session.plugin().messages().plain(locale, "state.label.spectators")).append(' ').append(this.session.spectatorCount());
        if (this.session.hasPendingReaction() && options != null) {
            builder.append(" | ").append(this.session.plugin().messages().plain(locale, "state.label.reactions"));
            this.appendReactionActionLabels(builder, locale, options);
        }
        if (this.session.lastResolution() != null) {
            builder.append(" | ")
                .append(this.session.plugin().messages().plain(locale, "state.label.resolution"))
                .append(' ')
                .append(resolutionTitle);
        }
        if (!this.session.isStarted() && !this.session.isRoundFinished()) {
            builder.append(" | ")
                .append(this.session.plugin().messages().plain(locale, "state.label.next_round"))
                .append(' ')
                .append(this.session.plugin().messages().plain(
                    locale,
                    "state.ready_summary",
                    this.session.plugin().messages().number(locale, "ready", this.session.readyCount()),
                    this.session.plugin().messages().number(locale, "total", this.session.size())
                ));
        }
        if (this.session.isRoundFinished()) {
            builder.append(" | ").append(this.session.plugin().messages().plain(locale, "state.match_finished"));
        }
        return builder.toString();
    }

    private String viewerRoleLabel(Locale locale, UUID viewerId) {
        return this.session.plugin().messages().plain(locale, this.session.isSpectator(viewerId) ? "hud.role_spectator" : "hud.role_player");
    }

    private String doraSummary(Locale locale) {
        String labels = this.session.doraIndicators().stream()
            .map(tile -> DoraIndicatorMapper.doraFromIndicator(tile))
            .map(tile -> this.tileLabel(locale, tile.name()))
            .collect(java.util.stream.Collectors.joining(","));
        if (labels.isBlank()) {
            labels = "-";
        }
        return this.session.plugin().messages().plain(
            locale,
            "ui.dora",
            this.session.plugin().messages().tag("value", labels)
        );
    }

    private Component buildRoundHudTitle(Locale locale, String round, String turn, int wall) {
        return this.session.plugin().messages().render(
            locale,
            "hud.round_compact",
            this.session.plugin().messages().tag("round", round),
            this.session.plugin().messages().tag("turn", turn),
            this.session.plugin().messages().number(locale, "wall", wall)
        );
    }

    private String viewerPrompt(
        Locale locale,
        UUID viewerId,
        PlayerActionSnapshot actionSnapshot,
        ReactionOptions options,
        boolean spectator
    ) {
        if (spectator || actionSnapshot == null || actionSnapshot.phase() == PlayerActionPhase.NONE) {
            return "";
        }
        TableFeedbackPolicy.DecisionCue cue = switch (actionSnapshot.phase()) {
            case REACTION -> options == null ? null : new TableFeedbackPolicy.DecisionCue(
                "reaction",
                TableFeedbackPolicy.Priority.REACTION,
                this.session.plugin().messages().plain(locale, "overlay.prompt.choose_reaction"),
                this.suggestedReaction(locale, options)
            );
            case TURN -> new TableFeedbackPolicy.DecisionCue(
                "turn",
                TableFeedbackPolicy.Priority.TURN,
                this.session.plugin().messages().plain(locale, "overlay.your_turn"),
                this.discardSuggestion(locale, viewerId)
            );
            case SICHUAN_EXCHANGE -> new TableFeedbackPolicy.DecisionCue(
                "sichuan-exchange",
                TableFeedbackPolicy.Priority.REQUIRED_ACTION,
                this.sichuanExchangePrompt(locale, actionSnapshot),
                ""
            );
            case SICHUAN_DING_QUE -> new TableFeedbackPolicy.DecisionCue(
                "sichuan-dingque",
                TableFeedbackPolicy.Priority.REQUIRED_ACTION,
                this.session.plugin().messages().plain(locale, "overlay.prompt.sichuan_dingque"),
                ""
            );
            case WAITING, NONE -> null;
        };
        return TableFeedbackPolicy.resolveDecision(cue == null ? List.of() : List.of(cue)).text();
    }

    private String sichuanExchangePrompt(Locale locale, PlayerActionSnapshot actionSnapshot) {
        String key = actionSnapshot.waitingOnOthers()
            ? "overlay.prompt.sichuan_exchange_waiting"
            : "overlay.prompt.sichuan_exchange";
        return this.session.plugin().messages().plain(
            locale,
            key,
            this.session.plugin().messages().number(locale, "selected", actionSnapshot.selectedCount()),
            this.session.plugin().messages().number(locale, "target", actionSnapshot.selectedTarget())
        );
    }

    private String viewerOverlayFingerprint(
        Locale locale,
        UUID viewerId,
        ViewerSummarySnapshot summary
    ) {
        DelimitedFingerprintBuilder builder = fingerprintBuilder(256)
            .field(locale.toLanguageTag())
            .field(viewerId);
        if (!this.session.hasRoundController()) {
            return builder
                .field("waiting")
                .field(this.session.id())
                .field(summary.waitingSummary())
                .toString();
        }
        if (!this.session.isStarted()) {
            if (this.session.isRoundFinished() && this.session.lastResolution() != null) {
                return builder
                    .field("finished")
                    .field(summary.round())
                    .field(summary.resolutionTitle())
                    .toString();
            }
            return builder
                .field("waiting")
                .field(this.session.id())
                .field(summary.waitingSummary())
                .toString();
        }
        builder
            .field("active")
            .field(this.session.currentVariant())
            .field(summary.round())
            .field(summary.dealer())
            .field(summary.turn())
            .field(summary.wall())
            .field(summary.roleLabel())
            .field(summary.lastDiscardSummary());
        if (this.session.currentVariant() == MahjongVariant.RIICHI) {
            builder
                .field(summary.riichiPool())
                .field(summary.doraSummary());
        }
        return builder.toString();
    }

    private TableViewerPromptSnapshot viewerPromptSnapshot(
        Locale locale,
        UUID viewerId,
        boolean spectator,
        ViewerSummarySnapshot summary
    ) {
        String prompt = summary.viewerPrompt();
        boolean visible = !spectator && prompt != null && !prompt.isBlank();
        Component component = visible ? Component.text(prompt, NamedTextColor.YELLOW) : Component.empty();
        String fingerprint = fingerprintBuilder(128)
            .field(locale.toLanguageTag())
            .field(viewerId)
            .field(spectator)
            .field(prompt)
            .toString();
        return new TableViewerPromptSnapshot(viewerId, "viewer-prompt:" + viewerId, visible, component, fingerprint);
    }

    private TableViewerActionOverlaySnapshot viewerActionOverlaySnapshot(
        Locale locale,
        UUID viewerId,
        boolean spectator,
        List<TableViewerActionButtonSnapshot> actionButtons
    ) {
        DelimitedFingerprintBuilder builder = fingerprintBuilder(192)
            .field(locale.toLanguageTag())
            .field(viewerId)
            .field(spectator)
            .field(actionButtons.size());
        for (TableViewerActionButtonSnapshot button : actionButtons) {
            builder.field(button.actionId())
                .field(button.label())
                .field(button.color())
                .field(button.command())
                .field(button.hitboxWidth())
                .field(button.placement());
        }
        return new TableViewerActionOverlaySnapshot(viewerId, "viewer-actions:" + viewerId, actionButtons, builder.toString());
    }

    private float hudProgress(ViewerHudPollState pollState) {
        if (!pollState.hasRoundController()) {
            return Math.min(1.0F, this.session.size() / 4.0F);
        }
        if (!pollState.started()) {
            return this.session.size() == 0 ? 0.0F : Math.max(0.0F, Math.min(1.0F, this.session.readyCount() / (float) this.session.size()));
        }
        int remainingWallCount = this.session.remainingWallCount();
        if (remainingWallCount <= 0) {
            return 0.0F;
        }
        float divisor = this.session.currentVariant() == MahjongVariant.RIICHI ? 70.0F : 122.0F;
        return Math.max(0.03F, Math.min(1.0F, remainingWallCount / divisor));
    }

    private BossBar.Color hudColor(UUID viewerId, ViewerHudPollState pollState) {
        if (!pollState.hasRoundController()) {
            return BossBar.Color.WHITE;
        }
        if (pollState.roundFinished()) {
            return BossBar.Color.PURPLE;
        }
        if (!pollState.started()) {
            return BossBar.Color.YELLOW;
        }
        if (pollState.reactionOptions() != null) {
            return BossBar.Color.RED;
        }
        if (this.session.currentSeat() == this.session.seatOf(viewerId)) {
            return BossBar.Color.GREEN;
        }
        return pollState.spectator() ? BossBar.Color.BLUE : BossBar.Color.WHITE;
    }

    private ViewerHudPollState captureViewerHudPollState(UUID viewerId, boolean includeActionPhase) {
        boolean hasRoundController = this.session.hasRoundController();
        boolean roundFinished = hasRoundController && this.session.isRoundFinished();
        boolean started = hasRoundController && this.session.isStarted();
        boolean spectator = viewerId == null || this.session.isSpectator(viewerId);
        ReactionOptions reactionOptions = started && !roundFinished
            ? this.session.availableReactions(viewerId)
            : null;
        PlayerActionPhase actionPhase = includeActionPhase
            ? this.captureViewerActionPhase(viewerId, hasRoundController, started, spectator, reactionOptions)
            : PlayerActionPhase.NONE;
        return new ViewerHudPollState(
            hasRoundController,
            roundFinished,
            started,
            spectator,
            reactionOptions,
            actionPhase
        );
    }

    private PlayerActionPhase captureViewerActionPhase(UUID viewerId) {
        if (viewerId == null) {
            return PlayerActionPhase.NONE;
        }
        boolean hasRoundController = this.session.hasRoundController();
        boolean started = hasRoundController && this.session.isStarted();
        boolean spectator = this.session.isSpectator(viewerId);
        ReactionOptions reactionOptions = started && !spectator ? this.session.availableReactions(viewerId) : null;
        return this.captureViewerActionPhase(
            viewerId,
            hasRoundController,
            started,
            spectator,
            reactionOptions
        );
    }

    private PlayerActionPhase captureViewerActionPhase(
        UUID viewerId,
        boolean hasRoundController,
        boolean started,
        boolean spectator,
        ReactionOptions reactionOptions
    ) {
        if (viewerId == null || spectator || !hasRoundController || !started) {
            return PlayerActionPhase.NONE;
        }
        if (reactionOptions != null && this.session.hasPendingReaction()) {
            return PlayerActionPhase.REACTION;
        }
        if (this.session.canChooseSichuanMissingSuit(viewerId)) {
            return PlayerActionPhase.SICHUAN_DING_QUE;
        }
        if (this.session.isSichuanExchangePhase(viewerId)) {
            return PlayerActionPhase.SICHUAN_EXCHANGE;
        }
        return this.session.currentSeat() == this.session.seatOf(viewerId)
            ? PlayerActionPhase.TURN
            : PlayerActionPhase.WAITING;
    }

    private String suggestedReaction(Locale locale, ReactionOptions options) {
        if (options.getSuggestedResponse() == null) {
            return "";
        }
        return this.session.plugin().messages().plain(
            locale,
            "table.suggested_action",
            this.session.plugin().messages().tag("action", this.reactionLabel(locale, options.getSuggestedResponse()))
        );
    }

    private void appendReactionActionLabels(StringBuilder builder, Locale locale, ReactionOptions options) {
        if (options.getCanRon()) {
            builder.append(' ').append(this.session.plugin().messages().plain(locale, "table.action.ron"));
        }
        if (options.getCanPon()) {
            builder.append(' ').append(this.session.plugin().messages().plain(locale, "table.action.pon"));
        }
        if (options.getCanMinkan()) {
            builder.append(' ').append(this.session.plugin().messages().plain(locale, "table.action.minkan"));
        }
        if (!options.getChiiPairs().isEmpty()) {
            builder.append(' ').append(this.session.plugin().messages().plain(locale, "table.action.chii"));
        }
    }

    private String discardSuggestion(Locale locale, UUID viewerId) {
        List<top.ellan.mahjong.riichi.RiichiDiscardSuggestion> suggestions = this.session.suggestedDiscardSuggestions(viewerId);
        if (suggestions.isEmpty()) {
            return "";
        }
        top.ellan.mahjong.riichi.RiichiDiscardSuggestion best = suggestions.get(0);
        String labels = this.suggestedDiscardLabels(locale, suggestions);
        if (labels.isBlank()) {
            return "";
        }
        return this.session.plugin().messages().plain(
            locale,
            "table.suggested_discards_detail",
            this.session.plugin().messages().tag("tiles", labels),
            this.session.plugin().messages().number(locale, "shanten", best.getShantenNum()),
            this.session.plugin().messages().number(locale, "advance", best.getAdvanceCount()),
            this.session.plugin().messages().number(locale, "improvement", best.getImprovementCount())
        );
    }

    private String suggestedDiscardLabels(
        Locale locale,
        List<top.ellan.mahjong.riichi.RiichiDiscardSuggestion> suggestions
    ) {
        top.ellan.mahjong.riichi.RiichiDiscardSuggestion best = suggestions.get(0);
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        for (top.ellan.mahjong.riichi.RiichiDiscardSuggestion suggestion : suggestions) {
            if (!this.hasSameDiscardShape(best, suggestion) || labels.size() >= 3) {
                break;
            }
            labels.add(this.session.tileLabelForDisplay(locale, suggestion.getTile().name()));
        }
        return String.join("/", labels);
    }

    private boolean hasSameDiscardShape(
        top.ellan.mahjong.riichi.RiichiDiscardSuggestion left,
        top.ellan.mahjong.riichi.RiichiDiscardSuggestion right
    ) {
        return left.getShantenNum() == right.getShantenNum()
            && left.getAdvanceCount() == right.getAdvanceCount()
            && left.getGoodShapeAdvanceCount() == right.getGoodShapeAdvanceCount()
            && left.getImprovementCount() == right.getImprovementCount()
            && left.getGoodShapeImprovementCount() == right.getGoodShapeImprovementCount();
    }

    private String reactionLabel(Locale locale, top.ellan.mahjong.riichi.ReactionResponse response) {
        return switch (response.getType()) {
            case RON -> this.session.plugin().messages().plain(locale, "table.action.ron");
            case PON -> this.session.plugin().messages().plain(locale, "table.action.pon");
            case MINKAN -> this.session.plugin().messages().plain(locale, "table.action.minkan");
            case SKIP -> this.session.plugin().messages().plain(locale, "table.action.skip");
            case CHII -> {
                if (response.getChiiPair() == null) {
                    yield this.session.plugin().messages().plain(locale, "table.action.chii");
                }
                yield this.session.plugin().messages().plain(locale, "table.action.chii") + " "
                    + this.session.tileLabelForDisplay(locale, response.getChiiPair().getFirst().name()) + " "
                    + this.session.tileLabelForDisplay(locale, response.getChiiPair().getSecond().name());
            }
        };
    }

    private String resolutionLabel(Locale locale, String title) {
        String key = "resolution." + title.toLowerCase(Locale.ROOT);
        return this.session.plugin().messages().contains(locale, key) ? this.session.plugin().messages().plain(locale, key) : title;
    }

    private String lastDiscardSummary(Locale locale) {
        if (this.session.lastPublicDiscardPlayerId() == null || this.session.lastPublicDiscardTile() == null) {
            return this.session.plugin().messages().plain(locale, "table.last_discard_none");
        }
        return this.session.plugin().messages().plain(
            locale,
            "table.last_discard",
            this.session.plugin().messages().tag("player", this.session.displayName(this.session.lastPublicDiscardPlayerId(), locale)),
            this.session.plugin().messages().tag("tile", this.tileLabel(locale, this.session.lastPublicDiscardTile().name()))
        );
    }

    private String tileLabel(Locale locale, String tileName) {
        String key = "tile." + tileName.toLowerCase(Locale.ROOT);
        return this.session.plugin().messages().contains(locale, key) ? this.session.plugin().messages().plain(locale, key) : tileName.toLowerCase(Locale.ROOT);
    }

    private List<TableViewerActionButtonSnapshot> viewerActionButtons(
        Locale locale,
        Player viewer,
        boolean spectator,
        PlayerActionSnapshot actionSnapshot
    ) {
        if (spectator || !this.session.hasRoundController()) {
            return List.of();
        }
        UUID viewerId = viewer.getUniqueId();
        boolean active = this.isOverheadActive(viewerId);
        if (active) {
            // Overhead is a read-only camera view. Shift restores the player to the original
            // seat-local hitboxes, which remain the authoritative controls for every decision.
            return List.of();
        }
        boolean cameraAvailable = this.session.plugin().tableManager() == null
            || this.session.plugin().tableManager().overheadViews().isAvailable();
        boolean overheadAvailable = this.session.isStarted()
            && this.session.seatOf(viewerId) != null
            && this.session.plugin().settings().tables().overheadView().enabled()
            && viewer.isInsideVehicle()
            && cameraAvailable;
        if (!actionSnapshot.hasActions() && !overheadAvailable) {
            return List.of();
        }
        java.util.ArrayList<TableViewerActionButtonSnapshot> buttons = new java.util.ArrayList<>(
            actionSnapshot.actions().size() + (overheadAvailable ? 1 : 0)
        );
        int index = 0;
        for (PlayerActionEntry action : actionSnapshot.actions()) {
            this.addButton(
                buttons,
                this.actionButtonId(action, index),
                TableFeedbackPolicy.compactActionLabel(this.actionLabel(locale, action)),
                action.color(),
                action.command(),
                TableViewerActionButtonSnapshot.Placement.ACTION_ROW
            );
            index++;
        }
        if (overheadAvailable) {
            String labelKey = "table.action.view_river";
            String label = TableFeedbackPolicy.compactActionLabel(this.session.plugin().messages().plain(locale, labelKey));
            float hitboxWidth = this.actionButtonHitboxWidth(label);
            buttons.add(new TableViewerActionButtonSnapshot(
                "view-river",
                label,
                NamedTextColor.AQUA,
                "view:river",
                hitboxWidth,
                TableViewerActionButtonSnapshot.Placement.RIGHT_SIDE
            ));
        }
        return List.copyOf(buttons);
    }

    private boolean isOverheadActive(UUID viewerId) {
        return this.session.plugin().tableManager() != null
            && this.session.plugin().tableManager().overheadViews().isActive(viewerId);
    }

    private void addButton(
        List<TableViewerActionButtonSnapshot> buttons,
        String actionId,
        String label,
        NamedTextColor color,
        String command
    ) {
        this.addButton(buttons, actionId, label, color, command, TableViewerActionButtonSnapshot.Placement.ACTION_ROW);
    }

    private void addButton(
        List<TableViewerActionButtonSnapshot> buttons,
        String actionId,
        String label,
        NamedTextColor color,
        String command,
        TableViewerActionButtonSnapshot.Placement placement
    ) {
        float hitboxWidth = this.actionButtonHitboxWidth(label);
        buttons.add(new TableViewerActionButtonSnapshot(actionId, label, color, command, hitboxWidth, placement));
    }

    private String actionButtonId(PlayerActionEntry action, int index) {
        String source = action.command();
        if (source == null || source.isBlank()) {
            source = action.actionId().name();
        }
        return source.toLowerCase(Locale.ROOT).replace(':', '-') + "-" + index;
    }

    private String actionLabel(Locale locale, PlayerActionEntry action) {
        String label = this.session.plugin().messages().plain(locale, action.labelKey());
        List<String> arguments = action.arguments();
        if (arguments.isEmpty()) {
            return label;
        }
        return switch (action.actionId()) {
            case CHII -> arguments.size() < 2
                ? label
                : label + " " + this.session.tileLabelForDisplay(locale, arguments.get(0)) + " "
                    + this.session.tileLabelForDisplay(locale, arguments.get(1));
            case ANKAN, KAKAN -> label + " " + this.session.tileLabelForDisplay(locale, arguments.get(0));
            case RIICHI -> label + " " + this.session.tileLabelForDisplay(locale, arguments.get(0));
            default -> label;
        };
    }

    private float actionButtonHitboxWidth(String label) {
        if (label == null || label.isBlank()) {
            return 0.7F;
        }
        int visualUnits = TableFeedbackPolicy.visualUnits(label);
        float estimated = TableRenderConstants.ACTION_LABEL_BASE_WIDTH
            + TableRenderConstants.ACTION_LABEL_DECORATION_WIDTH
            + visualUnits * TableRenderConstants.ACTION_LABEL_WIDTH_PER_UNIT;
        return Math.max(
            TableRenderConstants.ACTION_LABEL_MIN_WIDTH,
            Math.min(TableRenderConstants.ACTION_LABEL_MAX_WIDTH, estimated)
        );
    }

    private static DelimitedFingerprintBuilder fingerprintBuilder(int capacity) {
        return DelimitedFingerprintBuilder.create(capacity);
    }

    private record ViewerSummarySnapshot(
        boolean spectator,
        String waitingSummary,
        String round,
        String dealer,
        String turn,
        int wall,
        int riichiPool,
        String doraSummary,
        String roleLabel,
        String lastDiscardSummary,
        String viewerPrompt,
        String resolutionTitle,
        String commandStateSummary
    ) {
    }

    private record ViewerHudPollState(
        boolean hasRoundController,
        boolean roundFinished,
        boolean started,
        boolean spectator,
        ReactionOptions reactionOptions,
        PlayerActionPhase actionPhase
    ) {
    }

}
