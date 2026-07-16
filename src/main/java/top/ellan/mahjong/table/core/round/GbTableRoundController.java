package top.ellan.mahjong.table.core.round;

import top.ellan.mahjong.gb.jni.GbFanEntry;
import top.ellan.mahjong.gb.jni.GbFanRequest;
import top.ellan.mahjong.gb.jni.GbFanResponse;
import top.ellan.mahjong.gb.jni.GbMeldInput;
import top.ellan.mahjong.gb.jni.GbScoreDelta;
import top.ellan.mahjong.gb.jni.GbSeatPointsInput;
import top.ellan.mahjong.gb.jni.GbTingCandidate;
import top.ellan.mahjong.gb.jni.GbTingRequest;
import top.ellan.mahjong.gb.jni.GbTingResponse;
import top.ellan.mahjong.gb.jni.GbWinRequest;
import top.ellan.mahjong.gb.jni.GbWinResponse;
import top.ellan.mahjong.gb.runtime.GbNativeRulesGateway;
import top.ellan.mahjong.gb.runtime.GbTileEncoding;
import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.scene.MeldView;
import top.ellan.mahjong.riichi.ReactionOptions;
import top.ellan.mahjong.riichi.ReactionResponse;
import top.ellan.mahjong.riichi.ReactionType;
import top.ellan.mahjong.riichi.RoundResolution;
import top.ellan.mahjong.riichi.model.ExhaustiveDraw;
import top.ellan.mahjong.riichi.model.MahjongRound;
import top.ellan.mahjong.riichi.model.MahjongRule;
import top.ellan.mahjong.riichi.model.OpeningDiceRoll;
import top.ellan.mahjong.riichi.model.ScoreItem;
import top.ellan.mahjong.riichi.model.ScoreSettlement;
import top.ellan.mahjong.riichi.model.ScoringStick;
import top.ellan.mahjong.riichi.model.Wind;
import top.ellan.mahjong.riichi.model.YakuSettlement;
import top.ellan.mahjong.model.MahjongVariant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import kotlin.Pair;

public final class GbTableRoundController implements TableRoundController {
    private static final int SICHUAN_CONCEALED_KAN_UNIT = 2;
    private static final int SICHUAN_ADDED_KAN_UNIT = 1;
    private static final int SICHUAN_OPEN_KAN_UNIT = 2;

    enum SichuanPreparationPhase {
        NONE,
        EXCHANGE,
        DING_QUE,
        ACTIVE
    }

    private final MahjongRule rule;
    private final GbNativeRulesGateway nativeGateway;
    private final GbRuleProfile ruleProfile;
    private final EnumMap<SeatWind, UUID> seats;
    private final Map<UUID, SeatWind> seatByPlayerId = new HashMap<>();
    private final Map<UUID, String> displayNames;
    private final IntSupplier dicePointsSupplier;
    private final Supplier<OpeningDiceRoll> openingDiceRollSupplier;
    private final Supplier<List<MahjongTile>> wallSupplier;
    private final MahjongRound round;
    private final GbBotDecisionService botDecisionService;
    private final Map<UUID, Integer> points = new HashMap<>();
    private final Map<UUID, List<MahjongTile>> hands = new HashMap<>();
    private final Map<UUID, List<MahjongTile>> discards = new HashMap<>();
    private final Map<UUID, List<MahjongTile>> flowers = new HashMap<>();
    private final Map<UUID, Boolean> hasDrawnTile = new HashMap<>();
    private final Map<UUID, List<GbMeldState>> melds = new HashMap<>();
    private final Map<UUID, GbTingResponse> tingCache = new ConcurrentHashMap<>();
    private final Set<UUID> dirtyTingPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> roundStartPoints = new HashMap<>();
    private final Set<UUID> settledSichuanPlayers = new HashSet<>();
    private final List<GbReactionResolver.ResolvedGbWin> sichuanWinHistory = new ArrayList<>();
    private final Map<UUID, Integer> sichuanWinScoreDeltas = new HashMap<>();
    private final List<SichuanGangEvent> sichuanGangEvents = new ArrayList<>();
    private final List<SichuanGangEvent> pendingSichuanCallTransferEvents = new ArrayList<>();
    private final Map<UUID, Integer> sichuanPassedWinUnits = new HashMap<>();
    private final Map<UUID, LinkedHashSet<Integer>> selectedExchangeTileIndices = new HashMap<>();
    private final Set<UUID> confirmedExchangePlayers = new HashSet<>();
    private final Map<UUID, SichuanSuit> chosenMissingSuits = new HashMap<>();
    private final SichuanRulesEngine sichuanRulesEngine;
    private final SichuanPreparationFlow sichuanPreparationFlow;
    private final ArrayDeque<MahjongTile> wall = new ArrayDeque<>();
    private boolean started;
    private boolean gameFinished;
    private int dicePoints;
    private int dicePoints2;
    private int currentPlayerIndex;
    private int kanCount;
    private RoundResolution lastResolution;
    private GbReactionResolver.PendingReactionWindow pendingReactionWindow;
    private OpeningDiceRoll pendingDiceRoll;
    private UUID afterKanTsumoPlayer;
    private SeatWind sichuanDealerSeat = SeatWind.EAST;
    private SeatWind nextSichuanDealerSeat;
    private SichuanPreparationPhase sichuanPreparationPhase = SichuanPreparationPhase.NONE;

    public GbTableRoundController(MahjongRule rule, EnumMap<SeatWind, UUID> seats, Map<UUID, String> displayNames, GbNativeRulesGateway nativeGateway) {
        this(rule, seats, displayNames, nativeGateway, GbRuleProfile.GB);
    }

    public GbTableRoundController(
        MahjongRule rule,
        EnumMap<SeatWind, UUID> seats,
        Map<UUID, String> displayNames,
        GbNativeRulesGateway nativeGateway,
        GbRuleProfile ruleProfile
    ) {
        this(rule, seats, displayNames, nativeGateway, ruleProfile, null, OpeningDiceRoll::random, () -> GbRoundSupport.buildWall(ruleProfile));
    }

    public GbTableRoundController(
        MahjongRule rule,
        EnumMap<SeatWind, UUID> seats,
        Map<UUID, String> displayNames,
        GbNativeRulesGateway nativeGateway,
        IntSupplier dicePointsSupplier,
        Supplier<List<MahjongTile>> wallSupplier
    ) {
        this(rule, seats, displayNames, nativeGateway, GbRuleProfile.GB, dicePointsSupplier, null, wallSupplier);
    }

    public GbTableRoundController(
        MahjongRule rule,
        EnumMap<SeatWind, UUID> seats,
        Map<UUID, String> displayNames,
        GbNativeRulesGateway nativeGateway,
        GbRuleProfile ruleProfile,
        IntSupplier dicePointsSupplier,
        Supplier<List<MahjongTile>> wallSupplier
    ) {
        this(rule, seats, displayNames, nativeGateway, ruleProfile, dicePointsSupplier, null, wallSupplier);
    }

    private GbTableRoundController(
        MahjongRule rule,
        EnumMap<SeatWind, UUID> seats,
        Map<UUID, String> displayNames,
        GbNativeRulesGateway nativeGateway,
        GbRuleProfile ruleProfile,
        IntSupplier dicePointsSupplier,
        Supplier<OpeningDiceRoll> openingDiceRollSupplier,
        Supplier<List<MahjongTile>> wallSupplier
    ) {
        this.rule = rule;
        this.nativeGateway = nativeGateway;
        this.ruleProfile = ruleProfile == null ? GbRuleProfile.GB : ruleProfile;
        this.botDecisionService = new GbBotDecisionService(this.ruleProfile.minimumFan());
        this.seats = new EnumMap<>(seats);
        for (Map.Entry<SeatWind, UUID> entry : this.seats.entrySet()) {
            if (entry.getValue() != null) {
                this.seatByPlayerId.put(entry.getValue(), entry.getKey());
            }
        }
        this.displayNames = new HashMap<>(displayNames);
        this.dicePointsSupplier = dicePointsSupplier;
        this.openingDiceRollSupplier = openingDiceRollSupplier;
        this.wallSupplier = wallSupplier;
        this.sichuanRulesEngine = new DefaultSichuanRulesEngine();
        this.sichuanPreparationFlow = new SichuanPreparationFlow(
            this.selectedExchangeTileIndices,
            this.confirmedExchangePlayers,
            this.chosenMissingSuits,
            () -> this.sichuanPreparationPhase,
            phase -> this.sichuanPreparationPhase = phase
        );
        this.round = rule.getLength().getStartingRound();
        for (UUID playerId : seats.values()) {
            if (playerId == null) {
                continue;
            }
            this.points.put(playerId, rule.getStartingPoints());
            this.hands.put(playerId, new ArrayList<>());
            this.discards.put(playerId, new ArrayList<>());
            this.flowers.put(playerId, new ArrayList<>());
            this.hasDrawnTile.put(playerId, false);
            this.melds.put(playerId, new ArrayList<>());
            this.tingCache.put(playerId, new GbTingResponse(false, List.of(), "Round has not started yet."));
        }
    }

    @Override
    public <T> T accept(VariantVisitor<T> visitor) {
        // Sichuan reuses this controller class but is a distinct variant; route the visitor accordingly
        // so callers can disambiguate without re-checking variant() after the dispatch.
        if (this.variant() == MahjongVariant.SICHUAN) {
            return visitor.visitSichuan(this);
        }
        return visitor.visitGb(this);
    }

    @Override
    public MahjongVariant variant() {
        return this.ruleProfile.variant();
    }

    @Override
    public MahjongRule rule() {
        return this.rule;
    }

    @Override
    public boolean started() {
        return this.started;
    }

    @Override
    public boolean gameFinished() {
        return this.gameFinished;
    }

    @Override
    public void startRound() {
        if (this.usesSichuanBloodBattle() && this.nextSichuanDealerSeat != null) {
            this.sichuanDealerSeat = this.nextSichuanDealerSeat;
            this.nextSichuanDealerSeat = null;
        }
        OpeningDiceRoll diceRoll = this.pendingDiceRoll;
        this.pendingDiceRoll = null;
        if (diceRoll == null && this.openingDiceRollSupplier != null) {
            diceRoll = this.openingDiceRollSupplier.get();
        }
        int sichuanBreakStacks;
        if (diceRoll == null) {
            int roll1 = GbRoundSupport.requireValidDicePoints(this.dicePointsSupplier.getAsInt());
            int roll2 = GbRoundSupport.requireValidDicePoints(this.dicePointsSupplier.getAsInt());
            this.dicePoints = roll1;
            this.dicePoints2 = roll2;
            // Legacy deterministic test constructors supply only totals. Production and exact
            // wall tests use OpeningDiceRoll, which retains the individual dice required by T/TFMJ.
            sichuanBreakStacks = Math.max(1, Math.min(6, roll1 / 2));
        } else {
            this.dicePoints = diceRoll.total();
            this.dicePoints2 = diceRoll.total2();
            sichuanBreakStacks = Math.min(diceRoll.firstDie(), diceRoll.secondDie());
        }
        List<MahjongTile> fullWall = this.usesSichuanBloodBattle()
            ? GbRoundSupport.reorderSichuanWallForDice(this.wallSupplier.get(), this.dicePoints, sichuanBreakStacks, this.dealerSeat().index())
            : GbRoundSupport.reorderWallForDice(this.wallSupplier.get(), this.dicePoints, this.dicePoints2, this.dealerSeat().index());
        this.wall.clear();
        this.wall.addAll(fullWall);
        this.pendingReactionWindow = null;
        this.lastResolution = null;
        this.started = true;
        this.gameFinished = false;
        this.currentPlayerIndex = this.dealerSeat().index();
        this.kanCount = 0;
        this.afterKanTsumoPlayer = null;
        this.roundStartPoints.clear();
        this.roundStartPoints.putAll(this.points);
        this.settledSichuanPlayers.clear();
        this.sichuanWinHistory.clear();
        this.sichuanWinScoreDeltas.clear();
        this.sichuanGangEvents.clear();
        this.pendingSichuanCallTransferEvents.clear();
        this.sichuanPassedWinUnits.clear();
        this.sichuanPreparationFlow.reset(this.ruleProfile.useSichuanHuEvaluator(), this.dicePoints);
        for (UUID playerId : this.seats.values()) {
            if (playerId == null) {
                continue;
            }
            this.hands.get(playerId).clear();
            this.discards.get(playerId).clear();
            this.flowers.get(playerId).clear();
            this.hasDrawnTile.put(playerId, false);
            this.melds.get(playerId).clear();
        }

        SeatWind dealer = this.dealerSeat();
        for (int block = 0; block < 3; block++) {
            for (int offset = 0; offset < SeatWind.values().length; offset++) {
                UUID playerId = this.playerAt(this.seatFromDealerOffset(dealer, offset));
                for (int tile = 0; tile < 4; tile++) {
                    this.dealInitialTile(playerId);
                }
            }
        }
        UUID dealerId = this.playerAt(dealer);
        this.dealInitialTile(dealerId);
        for (int offset = 1; offset < SeatWind.values().length; offset++) {
            this.dealInitialTile(this.playerAt(this.seatFromDealerOffset(dealer, offset)));
        }
        this.dealInitialTile(dealerId);
        for (int offset = 0; offset < SeatWind.values().length; offset++) {
            UUID playerId = this.playerAt(this.seatFromDealerOffset(dealer, offset));
            this.hasDrawnTile.put(playerId, playerId.equals(dealerId));
            this.sortHand(playerId);
        }
        if (this.ruleProfile.useSichuanHuEvaluator()) {
            this.seedSichuanPreparationTing();
        } else {
            this.refreshAllTing();
        }
    }

    private SeatWind seatFromDealerOffset(SeatWind dealer, int offset) {
        return SeatWind.fromIndex(Math.floorMod(dealer.index() + offset, SeatWind.values().length));
    }

    @Override
    public boolean discard(UUID playerId, int tileIndex) {
        if (!this.canSelectHandTile(playerId, tileIndex)) {
            return false;
        }
        boolean afterKongDiscard = this.usesSichuanBloodBattle() && Objects.equals(this.afterKanTsumoPlayer, playerId);
        MahjongTile discarded = this.hands.get(playerId).remove(tileIndex);
        this.hasDrawnTile.put(playerId, false);
        this.sortHand(playerId);
        this.discards.get(playerId).add(discarded);
        this.pendingReactionWindow = this.buildPendingReactionWindow(playerId, discarded);
        this.afterKanTsumoPlayer = null;
        if (this.pendingReactionWindow == null) {
            if (afterKongDiscard) {
                this.clearPendingSichuanCallTransferChain();
            }
            this.advanceAfterDiscard();
        }
        this.refreshAllTing();
        return true;
    }

    @Override
    public boolean declareTsumo(UUID playerId) {
        if (!this.isCurrentPlayer(playerId) || this.hasPendingReaction()) {
            return false;
        }
        MahjongTile winningTile = this.drawnTile(playerId);
        if (winningTile == null) {
            return false;
        }
        GbWinResponse response = this.evaluateWinResponse(playerId, null, winningTile, "SELF_DRAW", this.selfDrawFlags(playerId, winningTile));
        if (!this.canWinResponse(response)) {
            return false;
        }
        this.finishWins(List.of(new GbReactionResolver.ResolvedGbWin(playerId, null, winningTile, response)));
        return true;
    }

    @Override
    public boolean react(UUID playerId, ReactionResponse response) {
        if (playerId == null || response == null || this.pendingReactionWindow == null) {
            return false;
        }
        ReactionOptions options = this.pendingReactionWindow.options().get(playerId);
        if (options == null || this.pendingReactionWindow.responses().containsKey(playerId)) {
            return false;
        }
        switch (response.getType()) {
            case RON -> {
                if (!options.getCanRon()) {
                    return false;
                }
            }
            case PON -> {
                if (!options.getCanPon()) {
                    return false;
                }
            }
            case MINKAN -> {
                if (!options.getCanMinkan()) {
                    return false;
                }
            }
            case CHII -> {
                if (response.getChiiPair() == null || !options.getChiiPairs().contains(response.getChiiPair())) {
                    return false;
                }
            }
            case SKIP -> {
                // Passing a win is legal in the T/TFMJ profile, including in the last four draws.
            }
        }
        if (response.getType() != ReactionType.RON) {
            this.rememberSichuanPassedWin(playerId, options);
        }
        this.pendingReactionWindow.responses().put(playerId, response);
        if (!this.pendingReactionWindow.responses().keySet().containsAll(this.pendingReactionWindow.options().keySet())) {
            return true;
        }
        return this.resolvePendingReactions();
    }

    @Override
    public boolean declareKan(UUID playerId, String tileName) {
        if (playerId == null || tileName == null || tileName.isBlank() || !this.canDeclareSelfKanOnCurrentTurn(playerId)) {
            return false;
        }
        MahjongTile target;
        try {
            target = MahjongTile.valueOf(GbRoundSupport.normalizeTileToken(tileName));
        } catch (IllegalArgumentException ex) {
            return false;
        }
        List<MahjongTile> hand = this.hands.get(playerId);
        if (hand == null) {
            return false;
        }
        if (GbRoundSupport.countMatchingTiles(hand, target) >= 4) {
            GbRoundSupport.removeTiles(hand, target, 4);
            this.hasDrawnTile.put(playerId, false);
            this.sortHand(playerId);
            this.melds.get(playerId).add(GbMeldState.ankan(target));
            this.kanCount++;
            this.applySichuanKanSettlement(playerId, this.sichuanActiveOpponents(playerId), SICHUAN_CONCEALED_KAN_UNIT);
            boolean drew = this.drawReplacementTileOrFinish(playerId);
            this.refreshAllTing();
            return drew;
        }
        for (int i = 0; i < this.melds.get(playerId).size(); i++) {
            GbMeldState meld = this.melds.get(playerId).get(i);
            if (meld.type() == GbMeldType.PUNG
                && GbRoundSupport.sameKind(meld.baseTile(), target)
                && this.canUseTileForAddedKong(playerId, hand, target)) {
                GbReactionResolver.PendingReactionWindow robbingKongWindow = this.buildRobbingKongWindow(playerId, target, i);
                if (robbingKongWindow != null) {
                    this.pendingReactionWindow = robbingKongWindow;
                    this.refreshAllTing();
                    return true;
                }
                this.finishAddedKong(playerId, target, i);
                return true;
            }
        }
        return false;
    }

    @Override
    public UUID playerAt(SeatWind wind) {
        return wind == null ? null : this.seats.get(wind);
    }

    @Override
    public int points(UUID playerId) {
        return this.points.getOrDefault(playerId, 0);
    }

    @Override
    public boolean isRiichi(UUID playerId) {
        return false;
    }

    @Override
    public int dicePoints() {
        return this.dicePoints;
    }

    @Override
    public int dicePoints2() {
        return this.dicePoints2;
    }

    @Override
    public int kanCount() {
        return this.kanCount;
    }

    @Override
    public int roundIndex() {
        return this.round.getRound();
    }

    @Override
    public int honbaCount() {
        return this.round.getHonba();
    }

    @Override
    public SeatWind roundWind() {
        return this.roundWindSeat();
    }

    @Override
    public SeatWind dealerSeat() {
        return this.usesSichuanBloodBattle() ? this.sichuanDealerSeat : SeatWind.fromIndex(this.round.getRound());
    }

    @Override
    public SeatWind currentSeat() {
        return SeatWind.fromIndex(this.currentPlayerIndex);
    }

    @Override
    public String currentPlayerDisplayName() {
        UUID playerId = this.currentPlayerId();
        return playerId == null ? "" : this.displayNames.getOrDefault(playerId, playerId.toString());
    }

    @Override
    public List<MahjongTile> hand(UUID playerId) {
        return playerId == null ? List.of() : List.copyOf(this.hands.getOrDefault(playerId, List.of()));
    }

    @Override
    public List<MahjongTile> discards(UUID playerId) {
        return playerId == null ? List.of() : List.copyOf(this.discards.getOrDefault(playerId, List.of()));
    }

    @Override
    public List<MahjongTile> remainingWall() {
        List<MahjongTile> hiddenWall = new ArrayList<>(this.wall.size());
        for (int i = 0; i < this.wall.size(); i++) {
            hiddenWall.add(MahjongTile.UNKNOWN);
        }
        return List.copyOf(hiddenWall);
    }

    @Override
    public int remainingWallCount() {
        return this.wall.size();
    }

    @Override
    public List<MeldView> fuuro(UUID playerId) {
        if (playerId == null) {
            return List.of();
        }
        List<GbMeldState> playerMelds = this.melds.get(playerId);
        List<MeldView> views = new ArrayList<>();
        views.addAll(this.flowerDisplayViews(playerId));
        if (playerMelds != null) {
            for (GbMeldState meld : playerMelds) {
                views.add(this.toMeldView(meld));
            }
        }
        return List.copyOf(views);
    }

    private MeldView toMeldView(GbMeldState meld) {
        List<MahjongTile> visibleTiles = meld.tiles();
        if (meld.type() == GbMeldType.ADDED_KONG && meld.addedKanTile() != null && visibleTiles.size() > 3) {
            visibleTiles = List.copyOf(visibleTiles.subList(0, visibleTiles.size() - 1));
        }
        List<Boolean> faceDownFlags = new ArrayList<>(visibleTiles.size());
        for (int i = 0; i < visibleTiles.size(); i++) {
            faceDownFlags.add(meld.type() == GbMeldType.CONCEALED_KONG && (i == 0 || i == visibleTiles.size() - 1));
        }
        return new MeldView(List.copyOf(visibleTiles), List.copyOf(faceDownFlags), meld.claimTileIndex(), meld.claimYawOffset(), meld.addedKanTile());
    }

    private List<MeldView> flowerDisplayViews(UUID playerId) {
        List<MahjongTile> playerFlowers = this.flowers.getOrDefault(playerId, List.of());
        if (playerFlowers.isEmpty()) {
            return List.of();
        }
        List<MeldView> views = new ArrayList<>((playerFlowers.size() + 3) / 4);
        for (int start = 0; start < playerFlowers.size(); start += 4) {
            List<MahjongTile> chunk = List.copyOf(playerFlowers.subList(start, Math.min(start + 4, playerFlowers.size())));
            List<Boolean> faceDownFlags = new ArrayList<>(chunk.size());
            for (int i = 0; i < chunk.size(); i++) {
                faceDownFlags.add(false);
            }
            views.add(new MeldView(chunk, List.copyOf(faceDownFlags), -1, 0, null));
        }
        return List.copyOf(views);
    }

    @Override
    public List<ScoringStick> scoringSticks(UUID playerId) {
        return List.of();
    }

    @Override
    public List<MahjongTile> doraIndicators() {
        return List.of();
    }

    @Override
    public List<MahjongTile> uraDoraIndicators() {
        return List.of();
    }

    @Override
    public RoundResolution lastResolution() {
        return this.lastResolution;
    }

    @Override
    public ReactionOptions availableReactions(UUID playerId) {
        if (playerId == null
            || this.isSettledInSichuan(playerId)
            || this.pendingReactionWindow == null
            || this.pendingReactionWindow.responses().containsKey(playerId)) {
            return null;
        }
        return this.pendingReactionWindow.options().get(playerId);
    }

    @Override
    public boolean hasPendingReaction() {
        return this.pendingReactionWindow != null;
    }

    @Override
    public String pendingReactionFingerprint() {
        return this.pendingReactionWindow == null ? "" : this.pendingReactionWindow.toString();
    }

    @Override
    public String pendingReactionTileKey() {
        return this.pendingReactionWindow == null ? "" : this.pendingReactionWindow.tile().name();
    }

    @Override
    public boolean isCurrentPlayer(UUID playerId) {
        if (this.isSichuanPreparationPhase()) {
            return this.isSeatedPlayer(playerId)
                && !this.isSettledInSichuan(playerId)
                && this.isSichuanPlayerActionPending(playerId);
        }
        return this.started && !this.isSettledInSichuan(playerId) && Objects.equals(this.currentPlayerId(), playerId);
    }

    @Override
    public boolean canSelectHandTile(UUID playerId, int tileIndex) {
        if (!this.started || this.pendingReactionWindow != null || !this.isSeatedPlayer(playerId) || this.isSettledInSichuan(playerId)) {
            return false;
        }
        if (this.isSichuanExchangePhase()) {
            return this.canSelectSichuanExchangeTile(playerId, tileIndex);
        }
        if (this.isSichuanPreparationPhase()) {
            return false;
        }
        if (!this.isCurrentPlayer(playerId)) {
            return false;
        }
        List<MahjongTile> hand = this.hands.get(playerId);
        if (hand == null || tileIndex < 0 || tileIndex >= hand.size()) {
            return false;
        }
        if (this.ruleProfile.useSichuanHuEvaluator()) {
            SichuanSuit chosenSuit = this.chosenMissingSuits.get(playerId);
            if (chosenSuit != null && this.hasChosenMissingSuitTiles(playerId)) {
                return chosenSuit.matches(hand.get(tileIndex));
            }
        }
        return true;
    }

    @Override
    public boolean handleHandTileClick(UUID playerId, int tileIndex, boolean cancelSelection) {
        boolean result = this.sichuanPreparationFlow.handleHandTileClick(
            playerId,
            tileIndex,
            this.isSeatedPlayer(playerId) && !this.isSettledInSichuan(playerId),
            this.hands.getOrDefault(playerId, List.of()),
            this.activeSeatCount()
        );
        if (result && this.sichuanPreparationFlow.exchangeReadyToApply(this.activeSeatCount())) {
            this.applySichuanExchange();
        }
        return result;
    }

    public boolean submitSichuanExchangeSelection(UUID playerId, List<Integer> tileIndices) {
        boolean result = this.sichuanPreparationFlow.submitExchangeSelection(
            playerId,
            tileIndices,
            this.isSeatedPlayer(playerId) && !this.isSettledInSichuan(playerId),
            this.hands.getOrDefault(playerId, List.of()),
            this.activeSeatCount()
        );
        if (result && this.sichuanPreparationFlow.exchangeReadyToApply(this.activeSeatCount())) {
            this.applySichuanExchange();
        }
        return result;
    }

    @Override
    public List<Integer> selectedHandTileIndices(UUID playerId) {
        return this.sichuanPreparationFlow.selectedHandTileIndices(playerId);
    }

    @Override
    public boolean canDeclareFlower(UUID playerId) {
        if (!this.ruleProfile.includesFlowers()
            || !this.started
            || playerId == null
            || !this.isCurrentPlayer(playerId)
            || this.hasPendingReaction()
            || this.isSichuanPreparationPhase()
            || this.wall.isEmpty()) {
            return false;
        }
        return this.hands.getOrDefault(playerId, List.of()).stream().anyMatch(MahjongTile::isFlower);
    }

    @Override
    public List<Integer> suggestedFlowerIndices(UUID playerId) {
        if (!this.canDeclareFlower(playerId)) {
            return List.of();
        }
        List<MahjongTile> hand = this.hands.getOrDefault(playerId, List.of());
        List<Integer> indices = new ArrayList<>();
        for (int index = 0; index < hand.size(); index++) {
            if (hand.get(index).isFlower()) {
                indices.add(index);
            }
        }
        return List.copyOf(indices);
    }

    @Override
    public boolean declareFlower(UUID playerId, int tileIndex) {
        if (!this.canDeclareFlower(playerId)) {
            return false;
        }
        List<MahjongTile> hand = this.hands.get(playerId);
        if (hand == null || tileIndex < 0 || tileIndex >= hand.size() || !hand.get(tileIndex).isFlower()) {
            return false;
        }

        MahjongTile flower = hand.remove(tileIndex);
        this.flowers.computeIfAbsent(playerId, ignored -> new ArrayList<>()).add(flower);
        MahjongTile replacement = this.wall.removeLast();
        hand.add(replacement);
        this.hasDrawnTile.put(playerId, true);
        this.afterKanTsumoPlayer = null;
        this.sortHand(playerId);
        this.clearSichuanPassedWinAfterDraw(playerId);
        this.refreshAllTing();
        return true;
    }

    @Override
    public boolean canDeclareKan(UUID playerId) {
        return this.canDeclareConcealedKan(playerId) || this.canDeclareAddedKan(playerId);
    }

    @Override
    public boolean canDeclareConcealedKan(UUID playerId) {
        if (!this.canDeclareSelfKanOnCurrentTurn(playerId)) {
            return false;
        }
        List<MahjongTile> hand = this.hands.getOrDefault(playerId, List.of());
        for (MahjongTile tile : hand) {
            if (GbRoundSupport.countMatchingTiles(hand, tile) >= 4) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canDeclareAddedKan(UUID playerId) {
        if (!this.canDeclareSelfKanOnCurrentTurn(playerId)) {
            return false;
        }
        List<MahjongTile> hand = this.hands.getOrDefault(playerId, List.of());
        for (GbMeldState meld : this.melds.getOrDefault(playerId, List.of())) {
            if (meld.type() == GbMeldType.PUNG && this.canUseTileForAddedKong(playerId, hand, meld.baseTile())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<String> suggestedKanTiles(UUID playerId) {
        if (!this.canDeclareSelfKanOnCurrentTurn(playerId)) {
            return List.of();
        }
        List<String> suggestions = new ArrayList<>();
        suggestions.addAll(this.suggestedConcealedKanTiles(playerId));
        for (String tileName : this.suggestedAddedKanTiles(playerId)) {
            if (!suggestions.contains(tileName)) {
                suggestions.add(tileName);
            }
        }
        return List.copyOf(suggestions);
    }

    @Override
    public List<String> suggestedConcealedKanTiles(UUID playerId) {
        if (!this.canDeclareSelfKanOnCurrentTurn(playerId)) {
            return List.of();
        }
        List<String> suggestions = new ArrayList<>();
        List<MahjongTile> hand = this.hands.getOrDefault(playerId, List.of());
        for (MahjongTile tile : hand) {
            String lowered = tile.name().toLowerCase(Locale.ROOT);
            if (GbRoundSupport.countMatchingTiles(hand, tile) >= 4 && !suggestions.contains(lowered)) {
                suggestions.add(lowered);
            }
        }
        return List.copyOf(suggestions);
    }

    @Override
    public List<String> suggestedAddedKanTiles(UUID playerId) {
        if (!this.canDeclareSelfKanOnCurrentTurn(playerId)) {
            return List.of();
        }
        List<String> suggestions = new ArrayList<>();
        List<MahjongTile> hand = this.hands.getOrDefault(playerId, List.of());
        for (GbMeldState meld : this.melds.getOrDefault(playerId, List.of())) {
            if (meld.type() == GbMeldType.PUNG && this.canUseTileForAddedKong(playerId, hand, meld.baseTile())) {
                String lowered = meld.baseTile().name().toLowerCase(Locale.ROOT);
                if (!suggestions.contains(lowered)) {
                    suggestions.add(lowered);
                }
            }
        }
        return List.copyOf(suggestions);
    }

    private boolean canUseTileForAddedKong(UUID playerId, List<MahjongTile> hand, MahjongTile target) {
        if (GbRoundSupport.countMatchingTiles(hand, target) < 1) {
            return false;
        }
        return !this.usesSichuanBloodBattle() || GbRoundSupport.sameKind(this.drawnTile(playerId), target);
    }

    public int suggestedBotDiscardIndex(UUID playerId) {
        if (playerId == null || !this.isCurrentPlayer(playerId) || this.hasPendingReaction() || this.isSichuanPreparationPhase()) {
            return -1;
        }
        List<MahjongTile> hand = this.hands.getOrDefault(playerId, List.of());
        if (hand.isEmpty()) {
            return -1;
        }
        if (this.ruleProfile.includesFlowers()) {
            for (int i = hand.size() - 1; i >= 0; i--) {
                if (hand.get(i).isFlower() && this.canSelectHandTile(playerId, i)) {
                    return i;
                }
            }
        }
        if (this.ruleProfile.useSichuanHuEvaluator()) {
            for (int i = hand.size() - 1; i >= 0; i--) {
                if (this.canSelectHandTile(playerId, i)) {
                    MahjongTile tile = hand.get(i);
                    SichuanSuit chosenSuit = this.chosenMissingSuits.get(playerId);
                    if (chosenSuit != null && chosenSuit.matches(tile)) {
                        return i;
                    }
                }
            }
        }
        return this.botDecisionService.suggestedDiscardIndex(
            hand,
            this.melds.getOrDefault(playerId, List.of()),
            (concealedHand, meldStates) -> this.evaluateTing(playerId, concealedHand, meldStates)
        );
    }

    public ReactionResponse suggestedBotReaction(UUID playerId) {
        ReactionOptions options = this.availableReactions(playerId);
        if (playerId == null || options == null || this.pendingReactionWindow == null) {
            return new ReactionResponse(ReactionType.SKIP, null);
        }
        return this.botDecisionService.suggestedReaction(
            options,
            this.tingOptions(playerId),
            this.pendingReactionWindow.tile(),
            this.seatOf(this.pendingReactionWindow.discarderId()),
            this.seatOf(playerId),
            this.hands.getOrDefault(playerId, List.of()),
            this.melds.getOrDefault(playerId, List.of()),
            (concealedHand, meldStates) -> this.evaluateTing(playerId, concealedHand, meldStates)
        );
    }

    public String suggestedBotKanTile(UUID playerId) {
        if (!this.canDeclareSelfKanOnCurrentTurn(playerId)) {
            return null;
        }
        List<MahjongTile> hand = this.hands.getOrDefault(playerId, List.of());
        if (hand.isEmpty()) {
            return null;
        }
        return this.botDecisionService.suggestedKanTile(
            hand,
            this.melds.getOrDefault(playerId, List.of()),
            this.suggestedKanTiles(playerId),
            (concealedHand, meldStates) -> this.evaluateTing(playerId, concealedHand, meldStates)
        );
    }

    public GbTingResponse tingOptions(UUID playerId) {
        this.refreshTingIfDirty(playerId);
        return this.tingCache.getOrDefault(playerId, new GbTingResponse(false, List.of(), "No ting data."));
    }

    public boolean isSichuanExchangePhase(UUID playerId) {
        return this.sichuanPreparationFlow.isExchangeAvailable(playerId, this.isSeatedPlayer(playerId) && !this.isSettledInSichuan(playerId));
    }

    public boolean canChooseSichuanMissingSuit(UUID playerId) {
        return this.sichuanPreparationFlow.canChooseMissingSuit(playerId, this.isSeatedPlayer(playerId) && !this.isSettledInSichuan(playerId));
    }

    public boolean chooseSichuanMissingSuit(UUID playerId, String suitToken) {
        boolean result = this.sichuanPreparationFlow.chooseMissingSuit(
            playerId,
            suitToken,
            this.isSeatedPlayer(playerId) && !this.isSettledInSichuan(playerId),
            this.activeSeatCount()
        );
        if (!result) {
            return false;
        }
        if (!this.sichuanPreparationFlow.isPreparationPhase()) {
            this.refreshAllTing();
        } else {
            this.seedSichuanPreparationTing();
        }
        return true;
    }

    public boolean canWinByTsumo(UUID playerId) {
        if (this.isSichuanPreparationPhase()) {
            return false;
        }
        GbFanResponse response = this.evaluateSelfDraw(playerId);
        return this.canWinResponse(response);
    }

    private boolean canDeclareSelfKanOnCurrentTurn(UUID playerId) {
        return playerId != null
            && this.isCurrentPlayer(playerId)
            && !this.hasPendingReaction()
            && this.hasDrawnTile(playerId)
            && !this.isSichuanPreparationPhase()
            && this.hasReplacementTileAvailable();
    }

    private GbReactionResolver.PendingReactionWindow buildPendingReactionWindow(UUID discarderId, MahjongTile discardedTile) {
        if (this.isSichuanPreparationPhase() || discardedTile == null || discardedTile.isFlower()) {
            return null;
        }
        SeatWind discarderSeat = this.seatOf(discarderId);
        EnumMap<SeatWind, UUID> reactionSeats = this.reactionSeats();
        List<String> flags = new ArrayList<>(this.discardWinFlags(discardedTile, false));
        if (this.usesSichuanBloodBattle() && Objects.equals(this.afterKanTsumoPlayer, discarderId)) {
            flags.add("AFTER_KONG");
        }
        GbReactionResolver.PendingReactionWindow window = GbReactionResolver.buildPendingReactionWindow(
            discarderId,
            discardedTile,
            discarderSeat,
            reactionSeats,
            this.hands,
            this::canRon,
            this::availableChiiPairs,
            flags
        );
        if (window != null && !this.hasReplacementTileAvailable()) {
            window.options().replaceAll((ignored, options) -> new ReactionOptions(
                options.getCanRon(),
                options.getCanPon(),
                false,
                options.getChiiPairs()
            ));
            window.options().entrySet().removeIf(entry -> !hasAnyReaction(entry.getValue()));
            if (window.options().isEmpty()) {
                return null;
            }
        }
        return window;
    }

    private boolean hasReplacementTileAvailable() {
        return !this.wall.isEmpty();
    }

    private static boolean hasAnyReaction(ReactionOptions options) {
        return options != null
            && (options.getCanRon() || options.getCanPon() || options.getCanMinkan() || !options.getChiiPairs().isEmpty());
    }

    private boolean resolvePendingReactions() {
        GbReactionResolver.PendingReactionWindow pending = this.pendingReactionWindow;
        if (pending == null) {
            return false;
        }
        SeatWind discarderSeat = this.seatOf(pending.discarderId());
        GbReactionResolver.Resolution resolution = GbReactionResolver.resolvePendingReactions(
            pending,
            discarderSeat,
            this::playerAt,
            this.usesSichuanBloodBattle(),
            (playerId, discarderId, tile, flags) -> {
                GbWinResponse win = this.evaluateWinResponse(playerId, discarderId, tile, "DISCARD", flags);
                return this.canWinResponse(win)
                    ? new GbReactionResolver.ResolvedGbWin(playerId, discarderId, tile, win)
                    : null;
            }
        );
        if (!resolution.wins().isEmpty()) {
            if (pending.robbingKong()) {
                this.consumeRobbedAddedKongTile(pending.discarderId(), pending.tile());
            }
            this.finishWins(resolution.wins());
            return true;
        }
        if (resolution.finishAddedKong()) {
            this.pendingReactionWindow = null;
            this.finishAddedKong(pending.discarderId(), pending.tile(), pending.upgradeMeldIndex());
            return true;
        }
        GbReactionResolver.Claim claim = resolution.claim();
        if (claim != null) {
            this.clearSichuanCallTransferAfterUnclaimedKongDiscard(pending);
            this.consumeClaimedDiscard(pending.discarderId(), pending.tile());
            this.pendingReactionWindow = null;
            this.currentPlayerIndex = this.seatOf(claim.playerId()).index();
            if (claim.response().getType() == ReactionType.MINKAN) {
                this.claimOpenKong(claim.playerId(), pending.tile(), discarderSeat);
                this.drawReplacementTileOrFinish(claim.playerId());
            } else if (claim.response().getType() == ReactionType.PON) {
                this.claimPung(claim.playerId(), pending.tile(), discarderSeat);
            } else if (claim.response().getType() == ReactionType.CHII) {
                this.claimChow(claim.playerId(), pending.tile(), discarderSeat, claim.response().getChiiPair());
            }
            this.refreshAllTing();
            return true;
        }
        if (resolution.advanceAfterDiscard()) {
            this.clearSichuanCallTransferAfterUnclaimedKongDiscard(pending);
            this.pendingReactionWindow = null;
            this.advanceAfterDiscard();
            return true;
        }
        return false;
    }

    private void consumeRobbedAddedKongTile(UUID playerId, MahjongTile robbedTile) {
        List<MahjongTile> hand = this.hands.get(playerId);
        if (hand == null || !removeFirstMatchingTile(hand, robbedTile)) {
            throw new IllegalStateException("Robbed added-kong tile is missing from the declarer's hand.");
        }
        this.hasDrawnTile.put(playerId, false);
        this.sortHand(playerId);
    }

    @Override
    public boolean canDeclareTsumo(UUID playerId) {
        return playerId != null && !this.hasPendingReaction() && this.canWinByTsumo(playerId);
    }

    private void claimPung(UUID playerId, MahjongTile claimedTile, SeatWind fromSeat) {
        GbRoundSupport.removeTiles(this.hands.get(playerId), claimedTile, 2);
        this.hasDrawnTile.put(playerId, false);
        this.sortHand(playerId);
        this.melds.get(playerId).add(GbMeldState.pung(claimedTile, fromSeat, this.seatOf(playerId)));
    }

    private void claimOpenKong(UUID playerId, MahjongTile claimedTile, SeatWind fromSeat) {
        GbRoundSupport.removeTiles(this.hands.get(playerId), claimedTile, 3);
        this.hasDrawnTile.put(playerId, false);
        this.sortHand(playerId);
        this.melds.get(playerId).add(GbMeldState.openKong(claimedTile, fromSeat, this.seatOf(playerId)));
        this.kanCount++;
        this.applySichuanKanSettlement(playerId, List.of(this.playerAt(fromSeat)), SICHUAN_OPEN_KAN_UNIT);
    }

    private void claimChow(UUID playerId, MahjongTile claimedTile, SeatWind fromSeat, Pair<top.ellan.mahjong.riichi.model.MahjongTile, top.ellan.mahjong.riichi.model.MahjongTile> pair) {
        MahjongTile first = GbRoundSupport.fromRiichiTile(pair.getFirst());
        MahjongTile second = GbRoundSupport.fromRiichiTile(pair.getSecond());
        GbRoundSupport.removeTiles(this.hands.get(playerId), first, 1);
        GbRoundSupport.removeTiles(this.hands.get(playerId), second, 1);
        this.hasDrawnTile.put(playerId, false);
        this.sortHand(playerId);
        this.melds.get(playerId).add(GbMeldState.chow(claimedTile, first, second, fromSeat));
    }

    private boolean canRon(UUID playerId, SeatWind discarderSeat, MahjongTile winningTile) {
        if (playerId == null || this.isSettledInSichuan(playerId)) {
            return false;
        }
        List<String> flags = new ArrayList<>(this.discardWinFlags(winningTile, false));
        if (this.usesSichuanBloodBattle() && Objects.equals(this.afterKanTsumoPlayer, this.playerAt(discarderSeat))) {
            flags.add("AFTER_KONG");
        }
        GbFanResponse response = this.evaluateFanResponse(
            playerId,
            winningTile,
            "DISCARD",
            discarderSeat,
            flags
        );
        return this.canWinResponse(response) && this.exceedsSichuanPassedWin(playerId, response);
    }

    private void rememberSichuanPassedWin(UUID playerId, ReactionOptions options) {
        if (!this.usesSichuanBloodBattle() || options == null || !options.getCanRon() || this.pendingReactionWindow == null) {
            return;
        }
        GbFanResponse response = this.evaluateFanResponse(
            playerId,
            this.pendingReactionWindow.tile(),
            "DISCARD",
            this.seatOf(this.pendingReactionWindow.discarderId()),
            this.pendingReactionWindow.flags()
        );
        if (!this.canWinResponse(response)) {
            return;
        }
        int scoreUnit = this.sichuanRulesEngine.scoreUnit(Math.max(0, response.getTotalFan()));
        this.sichuanPassedWinUnits.merge(playerId, scoreUnit, Math::max);
    }

    private boolean exceedsSichuanPassedWin(UUID playerId, GbFanResponse response) {
        if (!this.usesSichuanBloodBattle()) {
            return true;
        }
        Integer passedUnit = this.sichuanPassedWinUnits.get(playerId);
        if (passedUnit == null) {
            return true;
        }
        int candidateUnit = this.sichuanRulesEngine.scoreUnit(Math.max(0, response.getTotalFan()));
        return candidateUnit > passedUnit;
    }

    private GbFanResponse evaluateSelfDraw(UUID playerId) {
        if (playerId == null || !this.isCurrentPlayer(playerId)) {
            return new GbFanResponse(false, 0, List.of(), "It is not this player's turn.");
        }
        MahjongTile winningTile = this.drawnTile(playerId);
        if (winningTile == null) {
            return new GbFanResponse(false, 0, List.of(), "Player has no drawn tile.");
        }
        return this.evaluateFanResponse(playerId, winningTile, "SELF_DRAW", null, this.selfDrawFlags(playerId, winningTile));
    }

    private GbFanRequest buildFanRequest(UUID playerId, MahjongTile winningTile, String winType, SeatWind discarderSeat, List<String> flags) {
        List<MahjongTile> concealed = this.concealedHandForWin(playerId, winType);
        List<String> encodedHand = concealed.stream().map(GbTileEncoding::encode).toList();
        SeatWind logicalSeat = this.logicalSeatOf(playerId);
        return new GbFanRequest(
            this.ruleProfile.nativeRuleProfile(),
            encodedHand,
            this.toNativeMelds(playerId),
            GbTileEncoding.encode(winningTile),
            winType,
            GbTileEncoding.encodeWind(logicalSeat),
            GbTileEncoding.encodeWind(this.roundWind()),
            this.encodedFlowers(playerId),
            flags
        );
    }

    private GbTingRequest buildTingRequest(UUID playerId) {
        List<MahjongTile> hand = this.hands.getOrDefault(playerId, List.of());
        List<MahjongTile> concealed = hand.stream()
            .limit(this.hasDrawnTile(playerId) && !hand.isEmpty() ? hand.size() - 1L : hand.size())
            .toList();
        return this.buildTingRequest(playerId, concealed, this.melds.getOrDefault(playerId, List.of()));
    }

    private GbTingRequest buildTingRequest(UUID playerId, List<MahjongTile> concealedHand, List<GbMeldState> meldStates) {
        List<String> encodedHand = concealedHand.stream().map(GbTileEncoding::encode).toList();
        SeatWind logicalSeat = this.logicalSeatOf(playerId);
        return new GbTingRequest(
            this.ruleProfile.nativeRuleProfile(),
            encodedHand,
            this.toNativeMelds(playerId, meldStates),
            GbTileEncoding.encodeWind(logicalSeat),
            GbTileEncoding.encodeWind(this.roundWind()),
            this.encodedFlowers(playerId),
            List.of()
        );
    }

    private GbWinRequest buildWinRequest(UUID winnerId, UUID discarderId, MahjongTile winningTile, String winType, List<String> flags) {
        List<MahjongTile> concealed = this.concealedHandForWin(winnerId, winType);
        List<String> encodedHand = concealed.stream().map(GbTileEncoding::encode).toList();
        List<GbSeatPointsInput> seatPoints = new ArrayList<>(SeatWind.values().length);
        for (SeatWind wind : SeatWind.values()) {
            UUID playerId = this.playerAt(wind);
            if (playerId != null) {
                seatPoints.add(new GbSeatPointsInput(GbTileEncoding.encodeWind(this.logicalSeatOf(playerId)), this.points(playerId)));
            }
        }
        SeatWind logicalWinnerSeat = this.logicalSeatOf(winnerId);
        SeatWind logicalDiscarderSeat = discarderId == null ? null : this.logicalSeatOf(discarderId);
        return new GbWinRequest(
            this.ruleProfile.nativeRuleProfile(),
            encodedHand,
            this.toNativeMelds(winnerId),
            GbTileEncoding.encode(winningTile),
            winType,
            GbTileEncoding.encodeWind(logicalWinnerSeat),
            logicalDiscarderSeat == null ? null : GbTileEncoding.encodeWind(logicalDiscarderSeat),
            GbTileEncoding.encodeWind(logicalWinnerSeat),
            GbTileEncoding.encodeWind(this.roundWind()),
            seatPoints,
            this.encodedFlowers(winnerId),
            flags
        );
    }

    private int minimumFan() {
        return this.ruleProfile.minimumFan();
    }

    private boolean canWinResponse(GbFanResponse response) {
        return response != null
            && response.getValid()
            && qualifyingFan(response.getTotalFan(), response.getFans()) >= this.minimumFan();
    }

    private boolean canWinResponse(GbWinResponse response) {
        return response != null
            && response.getValid()
            && qualifyingFan(response.getTotalFan(), response.getFans()) >= this.minimumFan();
    }

    private static int qualifyingFan(int totalFan, List<GbFanEntry> fans) {
        int flowerFan = 0;
        for (GbFanEntry fan : fans == null ? List.<GbFanEntry>of() : fans) {
            if ("HUAPAI".equals(fan.getName())) {
                flowerFan += fan.getFan() * fan.getCount();
            }
        }
        return Math.max(0, totalFan - flowerFan);
    }

    private List<MahjongTile> concealedHandForWin(UUID playerId, String winType) {
        List<MahjongTile> concealed = new ArrayList<>(this.hands.getOrDefault(playerId, List.of()));
        if ("SELF_DRAW".equals(winType) && this.hasDrawnTile(playerId) && !concealed.isEmpty()) {
            concealed.remove(concealed.size() - 1);
        }
        return List.copyOf(concealed);
    }

    private GbFanResponse evaluateFanResponse(UUID playerId, MahjongTile winningTile, String winType, SeatWind discarderSeat, List<String> flags) {
        if (!this.ruleProfile.useSichuanHuEvaluator()) {
            if (this.hasUnexposedFlowerForWin(playerId, winningTile, winType)) {
                return new GbFanResponse(false, 0, List.of(), "Flowers retained in the concealed hand cannot form a winning hand.");
            }
            return this.nativeGateway.evaluateFan(this.buildFanRequest(playerId, winningTile, winType, discarderSeat, flags));
        }
        if (playerId == null || winningTile == null) {
            return new GbFanResponse(false, 0, List.of(), "Player or winning tile is unavailable.");
        }
        List<MahjongTile> concealed = this.concealedHandForWin(playerId, winType);
        List<GbMeldState> meldStates = this.melds.getOrDefault(playerId, List.of());
        SichuanRulesEngine.FanResult result = this.sichuanRulesEngine.evaluateFan(
            concealed,
            meldStates,
            winningTile,
            winType,
            flags,
            this.isSichuanGoldenSingleWait(playerId)
        );
        if (!result.valid()) {
            return new GbFanResponse(false, 0, List.of(), result.error());
        }
        List<MahjongTile> totalTiles = this.tilesForSichuanWin(playerId, concealed, meldStates, "DISCARD".equals(winType) ? winningTile : null);
        if (!this.isMissingChosenSuit(playerId, totalTiles)) {
            return new GbFanResponse(false, 0, List.of(), "Sichuan Mahjong hand must be missing the declared suit.");
        }
        return new GbFanResponse(true, result.totalFan(), result.fans(), null);
    }

    private GbWinResponse evaluateWinResponse(UUID winnerId, UUID discarderId, MahjongTile winningTile, String winType, List<String> flags) {
        if (!this.ruleProfile.useSichuanHuEvaluator()) {
            if (this.hasUnexposedFlowerForWin(winnerId, winningTile, winType)) {
                return new GbWinResponse(false, "WIN", 0, List.of(), List.of(), "Flowers retained in the concealed hand cannot form a winning hand.");
            }
            return this.nativeGateway.evaluateWin(this.buildWinRequest(winnerId, discarderId, winningTile, winType, flags));
        }
        GbFanResponse fanResponse = this.evaluateFanResponse(winnerId, winningTile, winType, this.seatOf(discarderId), flags);
        if (!this.canWinResponse(fanResponse)) {
            return new GbWinResponse(false, "WIN", 0, List.of(), List.of(), fanResponse.getError());
        }
        int totalFan = Math.max(0, fanResponse.getTotalFan());
        int scoreUnit = this.sichuanRulesEngine.scoreUnit(totalFan);
        return new GbWinResponse(
            true,
            "SELF_DRAW".equals(winType) ? "TSUMO" : "RON",
            totalFan,
            fanResponse.getFans(),
            this.buildSichuanScoreDeltas(winnerId, discarderId, winType, scoreUnit),
            null
        );
    }

    private List<GbScoreDelta> buildSichuanScoreDeltas(UUID winnerId, UUID discarderId, String winType, int scoreUnit) {
        if (winnerId == null) {
            return List.of();
        }
        SeatWind winnerSeat = this.seatOf(winnerId);
        if (winnerSeat == null) {
            return List.of();
        }
        SeatWind discarderSeat = this.seatOf(discarderId);
        List<SeatWind> activeOpponents = new ArrayList<>();
        for (SeatWind wind : SeatWind.values()) {
            UUID playerId = this.playerAt(wind);
            if (playerId == null || playerId.equals(winnerId) || this.isSettledInSichuan(playerId)) {
                continue;
            }
            activeOpponents.add(wind);
        }
        return this.sichuanRulesEngine.winDeltas(winnerSeat, discarderSeat, winType, scoreUnit, activeOpponents);
    }

    private List<String> encodedFlowers(UUID playerId) {
        if (!this.ruleProfile.includesFlowers()) {
            return List.of();
        }
        return this.flowers.getOrDefault(playerId, List.of()).stream().map(GbTileEncoding::encode).toList();
    }

    private List<GbMeldInput> toNativeMelds(UUID playerId) {
        return this.toNativeMelds(playerId, this.melds.getOrDefault(playerId, List.of()));
    }

    private List<GbMeldInput> toNativeMelds(UUID playerId, List<GbMeldState> playerMelds) {
        List<GbMeldInput> inputs = new ArrayList<>(playerMelds.size());
        for (GbMeldState meld : playerMelds) {
            List<String> tiles = meld.tiles().stream().map(GbTileEncoding::encode).toList();
            inputs.add(new GbMeldInput(
                meld.nativeType(),
                tiles,
                meld.claimedTile() == null ? null : GbTileEncoding.encode(meld.claimedTile()),
                meld.fromSeat() == null ? null : GbRoundSupport.relationLabel(this.logicalSeatOf(playerId), this.logicalSeat(meld.fromSeat())),
                meld.open()
            ));
        }
        return List.copyOf(inputs);
    }

    private void finishWins(List<GbReactionResolver.ResolvedGbWin> winners) {
        if (winners.isEmpty()) {
            return;
        }
        Map<UUID, Integer> pointsBeforeWin = new HashMap<>(this.points);
        if (this.usesSichuanBloodBattle() && winners.stream().anyMatch(this::isSichuanAfterKongDiscardWin)) {
            this.transferPendingSichuanGangIncome(winners.get(0).discarderId(), winners);
        } else if (this.usesSichuanBloodBattle()) {
            this.clearPendingSichuanCallTransferChain();
        }
        for (GbReactionResolver.ResolvedGbWin winner : winners) {
            this.applyScoreDeltas(winner.response().getScoreDeltas());
        }
        if (this.usesSichuanBloodBattle()) {
            this.recordSichuanWins(winners, pointsBeforeWin);
            this.pendingReactionWindow = null;
            this.afterKanTsumoPlayer = null;
            if (this.activeSichuanPlayerCount() <= 1) {
                this.finishSichuanBloodBattle(winners.get(winners.size() - 1).response().getTitle());
                return;
            }
            this.currentPlayerIndex = this.nextTurnPivotIndexAfterWins(winners);
            this.advanceAfterDiscard();
            return;
        }
        this.finishRoundWithWinners(winners.get(0).response().getTitle(), winners, pointsBeforeWin);
    }

    private void recordSichuanWins(List<GbReactionResolver.ResolvedGbWin> winners, Map<UUID, Integer> pointsBeforeWin) {
        if (this.sichuanWinHistory.isEmpty() && !winners.isEmpty()) {
            GbReactionResolver.ResolvedGbWin first = winners.get(0);
            UUID nextDealerId = winners.size() > 1 && first.discarderId() != null ? first.discarderId() : first.winnerId();
            this.nextSichuanDealerSeat = this.seatOf(nextDealerId);
        }
        for (GbReactionResolver.ResolvedGbWin winner : winners) {
            UUID winnerId = winner.winnerId();
            if (winnerId == null || this.settledSichuanPlayers.contains(winnerId)) {
                continue;
            }
            this.settledSichuanPlayers.add(winnerId);
            this.sichuanWinHistory.add(winner);
            int pointsBefore = pointsBeforeWin.getOrDefault(winnerId, this.points.getOrDefault(winnerId, 0));
            this.sichuanWinScoreDeltas.put(winnerId, this.points.getOrDefault(winnerId, pointsBefore) - pointsBefore);
        }
    }

    private boolean isSichuanAfterKongDiscardWin(GbReactionResolver.ResolvedGbWin winner) {
        if (winner == null || winner.discarderId() == null || winner.response() == null) {
            return false;
        }
        List<GbFanEntry> fans = winner.response().getFans();
        return fans != null && fans.stream().anyMatch(fan -> "GANG_SHANG_PAO".equals(fan.getName()));
    }

    private int nextTurnPivotIndexAfterWins(List<GbReactionResolver.ResolvedGbWin> winners) {
        if (winners.isEmpty()) {
            return this.currentPlayerIndex;
        }
        GbReactionResolver.ResolvedGbWin first = winners.get(0);
        if ("TSUMO".equals(first.response().getTitle())) {
            SeatWind winnerSeat = this.seatOf(first.winnerId());
            return winnerSeat == null ? this.currentPlayerIndex : winnerSeat.index();
        }
        SeatWind discarderSeat = this.seatOf(first.discarderId());
        return discarderSeat == null ? this.currentPlayerIndex : discarderSeat.index();
    }

    private void finishRoundWithWinners(String title, List<GbReactionResolver.ResolvedGbWin> winners, Map<UUID, Integer> originalPoints) {
        List<YakuSettlement> settlements = this.toWinnerSettlements(winners, originalPoints);
        ScoreSettlement scoreSettlement = new ScoreSettlement(title, this.toScoreItems(originalPoints));
        this.pendingReactionWindow = null;
        this.started = false;
        this.afterKanTsumoPlayer = null;
        this.lastResolution = new RoundResolution(title, List.copyOf(settlements), scoreSettlement, null);
        this.advanceMatchState();
    }

    private void finishSichuanBloodBattle(String title) {
        if (this.sichuanWinHistory.isEmpty()) {
            this.finishExhaustiveDraw();
            return;
        }
        this.finishRoundWithWinners(title, List.copyOf(this.sichuanWinHistory), this.roundStartPointsSnapshot());
    }

    private List<YakuSettlement> toWinnerSettlements(List<GbReactionResolver.ResolvedGbWin> winners, Map<UUID, Integer> originalPoints) {
        List<YakuSettlement> settlements = new ArrayList<>(winners.size());
        for (GbReactionResolver.ResolvedGbWin winner : winners) {
            UUID winnerId = winner.winnerId();
            if (winnerId == null) {
                continue;
            }
            Integer eventScoreDelta = this.sichuanWinScoreDeltas.get(winnerId);
            int winnerDelta = eventScoreDelta != null
                ? eventScoreDelta
                : this.points.getOrDefault(winnerId, originalPoints.getOrDefault(winnerId, 0)) - originalPoints.getOrDefault(winnerId, 0);
            settlements.add(new YakuSettlement(
                this.displayNames.getOrDefault(winnerId, winnerId.toString()),
                winnerId.toString(),
                fanLabels(winner.response().getFans()),
                List.of(),
                List.of(),
                false,
                0,
                false,
                GbRoundSupport.toRiichiTile(winner.winningTile()),
                GbRoundSupport.toRiichiTiles(this.hands.getOrDefault(winnerId, List.of())),
                this.toSettlementMelds(winnerId),
                List.of(),
                List.of(),
                0,
                winner.response().getTotalFan(),
                winnerDelta
            ));
        }
        return List.copyOf(settlements);
    }

    private Map<UUID, Integer> roundStartPointsSnapshot() {
        return this.roundStartPoints.isEmpty() ? new HashMap<>(this.points) : new HashMap<>(this.roundStartPoints);
    }

    private void applyScoreDeltas(List<GbScoreDelta> scoreDeltas) {
        if (scoreDeltas == null) {
            throw new IllegalStateException("Score deltas are required.");
        }
        EnumMap<SeatWind, Integer> aggregated = new EnumMap<>(SeatWind.class);
        long totalDelta = 0L;
        for (GbScoreDelta delta : scoreDeltas) {
            if (delta == null || delta.getSeat() == null) {
                throw new IllegalStateException("Score delta contains an unknown seat.");
            }
            SeatWind logicalWind;
            try {
                logicalWind = SeatWind.valueOf(delta.getSeat());
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("Score delta contains an unknown seat: " + delta.getSeat(), ex);
            }
            try {
                aggregated.merge(logicalWind, delta.getDelta(), (left, right) -> Math.addExact(left, right));
                totalDelta = Math.addExact(totalDelta, (long) delta.getDelta());
            } catch (ArithmeticException ex) {
                throw new IllegalStateException("Score delta overflow.", ex);
            }
        }
        if (totalDelta != 0L) {
            throw new IllegalStateException("Score deltas must conserve table points but summed to " + totalDelta + '.');
        }

        Map<UUID, Integer> updatedPoints = new HashMap<>();
        for (Map.Entry<SeatWind, Integer> entry : aggregated.entrySet()) {
            SeatWind logicalWind = entry.getKey();
            SeatWind physicalWind = this.ruleProfile.useSichuanHuEvaluator()
                ? logicalWind
                : SeatWind.fromIndex(Math.floorMod(
                    logicalWind.index() + this.dealerSeat().index(),
                    SeatWind.values().length
                ));
            UUID playerId = this.playerAt(physicalWind);
            if (playerId == null) {
                throw new IllegalStateException("Score delta references an unoccupied seat: " + logicalWind);
            }
            try {
                updatedPoints.put(playerId, Math.addExact(this.points.getOrDefault(playerId, 0), entry.getValue()));
            } catch (ArithmeticException ex) {
                throw new IllegalStateException("Player score overflow for seat " + logicalWind + '.', ex);
            }
        }
        this.points.putAll(updatedPoints);
    }

    @Override
    public void setPendingDiceRoll(OpeningDiceRoll diceRoll) {
        this.pendingDiceRoll = diceRoll;
    }

    private List<ScoreItem> toScoreItems(Map<UUID, Integer> originalPoints) {
        List<ScoreItem> scoreItems = new ArrayList<>(SeatWind.values().length);
        for (SeatWind wind : SeatWind.values()) {
            UUID playerId = this.playerAt(wind);
            if (playerId == null) {
                continue;
            }
            int origin = originalPoints.getOrDefault(playerId, this.rule.getStartingPoints());
            int updated = this.points.getOrDefault(playerId, origin);
            scoreItems.add(new ScoreItem(this.displayNames.getOrDefault(playerId, playerId.toString()), playerId.toString(), origin, updated - origin));
        }
        return List.copyOf(scoreItems);
    }

    private List<Pair<Boolean, List<top.ellan.mahjong.riichi.model.MahjongTile>>> toSettlementMelds(UUID playerId) {
        List<Pair<Boolean, List<top.ellan.mahjong.riichi.model.MahjongTile>>> result = new ArrayList<>();
        for (GbMeldState meld : this.melds.getOrDefault(playerId, List.of())) {
            result.add(new Pair<>(meld.open(), GbRoundSupport.toRiichiTiles(meld.tiles())));
        }
        return List.copyOf(result);
    }

    private static List<String> fanLabels(List<GbFanEntry> fans) {
        List<String> labels = new ArrayList<>(fans.size());
        for (GbFanEntry fan : fans) {
            labels.add(fan.getCount() > 1 ? fan.getName() + " x" + fan.getCount() : fan.getName());
        }
        return List.copyOf(labels);
    }

    private boolean usesSichuanBloodBattle() {
        return this.ruleProfile.useSichuanHuEvaluator();
    }

    private boolean isSettledInSichuan(UUID playerId) {
        return playerId != null && this.usesSichuanBloodBattle() && this.settledSichuanPlayers.contains(playerId);
    }

    private int activeSichuanPlayerCount() {
        int active = 0;
        for (UUID playerId : this.seats.values()) {
            if (playerId == null || this.isSettledInSichuan(playerId)) {
                continue;
            }
            active++;
        }
        return active;
    }

    private SichuanRulesEngine.FanResult sichuanFanResult(
        UUID playerId,
        List<MahjongTile> concealedHand,
        List<GbMeldState> meldStates,
        MahjongTile winningTile,
        String winType,
        List<String> flags
    ) {
        // Delegate to the single shared rules engine so the table flow and the
        // standalone engine stay byte-for-byte consistent on fan composition.
        return this.sichuanRulesEngine.evaluateFan(
            concealedHand,
            meldStates,
            winningTile,
            winType,
            flags,
            this.isSichuanGoldenSingleWait(playerId)
        );
    }

    private List<GbFanEntry> sichuanFans(
        UUID playerId,
        List<MahjongTile> concealedHand,
        List<GbMeldState> meldStates,
        MahjongTile winningTile,
        String winType,
        List<String> flags
    ) {
        SichuanRulesEngine.FanResult result = this.sichuanFanResult(playerId, concealedHand, meldStates, winningTile, winType, flags);
        return result.valid() ? result.fans() : List.of();
    }

    private boolean isSichuanGoldenSingleWait(UUID playerId) {
        return playerId != null && this.melds.getOrDefault(playerId, List.of()).size() == 4;
    }

    private int sichuanFanTotal(UUID playerId, List<MahjongTile> concealedHand, List<GbMeldState> meldStates, MahjongTile winningTile) {
        SichuanRulesEngine.FanResult result = this.sichuanFanResult(playerId, concealedHand, meldStates, winningTile, "DISCARD", List.of());
        return result.valid() ? Math.max(0, result.totalFan()) : 0;
    }

    private List<MahjongTile> tilesForSichuanWin(UUID playerId, List<MahjongTile> concealedHand, List<GbMeldState> meldStates, MahjongTile winningTile) {
        List<MahjongTile> tiles = new ArrayList<>(concealedHand);
        if (winningTile != null) {
            tiles.add(winningTile);
        }
        if (winningTile == null) {
            List<MahjongTile> hand = this.hands.getOrDefault(playerId, List.of());
            if (this.hasDrawnTile(playerId) && !hand.isEmpty()) {
                tiles.add(hand.get(hand.size() - 1));
            }
        }
        for (GbMeldState meld : meldStates) {
            tiles.addAll(meld.tiles());
        }
        return List.copyOf(tiles);
    }

    private List<MahjongTile> tilesForSichuanSettlement(UUID playerId) {
        List<MahjongTile> tiles = new ArrayList<>(this.hands.getOrDefault(playerId, List.of()));
        for (GbMeldState meld : this.melds.getOrDefault(playerId, List.of())) {
            tiles.addAll(meld.tiles());
        }
        return List.copyOf(tiles);
    }

    private boolean isMissingChosenSuit(UUID playerId, List<MahjongTile> tiles) {
        if (!this.ruleProfile.useSichuanHuEvaluator()) {
            return this.sichuanRulesEngine.isMissingOneSuit(tiles);
        }
        SichuanSuit chosenSuit = this.chosenMissingSuits.get(playerId);
        return chosenSuit != null && tiles.stream().noneMatch(chosenSuit::matches);
    }

    private boolean hasChosenMissingSuitTiles(UUID playerId) {
        SichuanSuit chosenSuit = this.chosenMissingSuits.get(playerId);
        if (chosenSuit == null) {
            return false;
        }
        for (MahjongTile tile : this.hands.getOrDefault(playerId, List.of())) {
            if (chosenSuit.matches(tile)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSichuanPreparationPhase() {
        return this.sichuanPreparationFlow.isPreparationPhase();
    }

    private boolean isSichuanExchangePhase() {
        return this.sichuanPreparationFlow.isExchangePhase();
    }

    private boolean isSichuanDingQuePhase() {
        return this.sichuanPreparationFlow.isDingQuePhase();
    }

    private boolean isSichuanPlayerActionPending(UUID playerId) {
        if (playerId == null || !this.ruleProfile.useSichuanHuEvaluator()) {
            return false;
        }
        return this.sichuanPreparationFlow.isActionPending(playerId);
    }

    private int activeSeatCount() {
        int count = 0;
        for (UUID playerId : this.seats.values()) {
            if (playerId != null && !this.isSettledInSichuan(playerId)) {
                count++;
            }
        }
        return count;
    }

    private boolean canSelectSichuanExchangeTile(UUID playerId, int tileIndex) {
        return this.sichuanPreparationFlow.canSelectExchangeTile(
            playerId,
            tileIndex,
            this.isSeatedPlayer(playerId) && !this.isSettledInSichuan(playerId),
            this.hands.get(playerId)
        );
    }

    private void applySichuanExchange() {
        if (this.sichuanPreparationFlow.applyExchange(this.seats, this.hands, this::rebuildHandAfterSichuanExchange)) {
            this.seedSichuanPreparationTing();
        }
    }

    private void rebuildHandAfterSichuanExchange(UUID playerId, List<Integer> removedIndices, List<MahjongTile> incomingTiles) {
        List<MahjongTile> originalHand = new ArrayList<>(this.hands.getOrDefault(playerId, List.of()));
        if (originalHand.isEmpty()) {
            return;
        }
        boolean hasDrawn = this.hasDrawnTile(playerId);
        MahjongTile drawnTile = hasDrawn ? originalHand.get(originalHand.size() - 1) : null;
        Set<Integer> removed = new HashSet<>(removedIndices);
        List<MahjongTile> remaining = new ArrayList<>(Math.max(0, originalHand.size() - removed.size()) + incomingTiles.size());
        for (int i = 0; i < originalHand.size(); i++) {
            if (!removed.contains(i)) {
                remaining.add(originalHand.get(i));
            }
        }
        List<MahjongTile> incoming = new ArrayList<>(incomingTiles);
        MahjongTile extraTile = null;
        if (hasDrawn) {
            if (drawnTile != null && !removed.contains(originalHand.size() - 1) && removeFirstMatchingTile(remaining, drawnTile)) {
                extraTile = drawnTile;
            } else if (!incoming.isEmpty()) {
                extraTile = incoming.remove(incoming.size() - 1);
            }
        }
        remaining.addAll(incoming);
        remaining.sort((left, right) -> Integer.compare(handTileSort(left), handTileSort(right)));
        if (hasDrawn) {
            if (extraTile == null && !remaining.isEmpty()) {
                extraTile = remaining.remove(remaining.size() - 1);
            }
            if (extraTile != null) {
                remaining.add(extraTile);
            }
        }
        this.hands.put(playerId, remaining);
    }

    private void seedSichuanPreparationTing() {
        String message = this.isSichuanExchangePhase()
            ? "Select three same-suit tiles for Sichuan exchange."
            : "Choose the missing suit for Sichuan Mahjong.";
        for (UUID playerId : this.seats.values()) {
            if (playerId == null) {
                continue;
            }
            this.cacheTing(playerId, new GbTingResponse(false, List.of(), message));
        }
    }

    private static boolean removeFirstMatchingTile(List<MahjongTile> tiles, MahjongTile target) {
        for (int i = 0; i < tiles.size(); i++) {
            if (GbRoundSupport.sameKind(tiles.get(i), target)) {
                tiles.remove(i);
                return true;
            }
        }
        return false;
    }

    private void applySichuanKanSettlement(UUID winnerId, List<UUID> payers, int unit) {
        if (!this.usesSichuanBloodBattle() || winnerId == null || unit <= 0) {
            return;
        }
        SeatWind winnerSeat = this.seatOf(winnerId);
        if (winnerSeat == null) {
            return;
        }
        List<SeatWind> payerSeats = new ArrayList<>();
        Map<UUID, Integer> payments = new LinkedHashMap<>();
        for (UUID payerId : payers) {
            if (payerId == null || payerId.equals(winnerId) || this.isSettledInSichuan(payerId)) {
                continue;
            }
            SeatWind payerSeat = this.seatOf(payerId);
            if (payerSeat != null) {
                payerSeats.add(payerSeat);
                payments.merge(payerId, unit, Integer::sum);
            }
        }
        if (!payments.isEmpty()) {
            this.applyScoreDeltas(this.sichuanRulesEngine.kanDeltas(winnerSeat, payerSeats, unit));
            if (!this.pendingSichuanCallTransferEvents.isEmpty()
                && !winnerId.equals(this.pendingSichuanCallTransferEvents.get(0).winnerId())) {
                this.clearPendingSichuanCallTransferChain();
            }
            SichuanGangEvent event = new SichuanGangEvent(winnerId, Map.copyOf(payments));
            this.sichuanGangEvents.add(event);
            this.pendingSichuanCallTransferEvents.add(event);
        }
    }

    private void transferPendingSichuanGangIncome(UUID discarderId, List<GbReactionResolver.ResolvedGbWin> winners) {
        if (discarderId == null || winners == null || winners.isEmpty() || this.pendingSichuanCallTransferEvents.isEmpty()) {
            this.clearPendingSichuanCallTransferChain();
            return;
        }
        int totalIncome = 0;
        for (SichuanGangEvent event : this.pendingSichuanCallTransferEvents) {
            if (!discarderId.equals(event.winnerId())) {
                this.clearPendingSichuanCallTransferChain();
                return;
            }
            totalIncome += event.payments().values().stream().mapToInt(amount -> Math.max(0, amount)).sum();
        }
        LinkedHashSet<UUID> recipientIds = new LinkedHashSet<>();
        for (GbReactionResolver.ResolvedGbWin winner : winners) {
            if (winner != null && winner.winnerId() != null && this.seatOf(winner.winnerId()) != null) {
                recipientIds.add(winner.winnerId());
            }
        }
        SeatWind discarderSeat = this.seatOf(discarderId);
        if (totalIncome <= 0 || recipientIds.isEmpty() || discarderSeat == null) {
            this.clearPendingSichuanCallTransferChain();
            return;
        }
        int share = Math.floorDiv(totalIncome + recipientIds.size() - 1, recipientIds.size());
        List<GbScoreDelta> deltas = new ArrayList<>();
        deltas.add(new GbScoreDelta(discarderSeat.name(), -(share * recipientIds.size())));
        for (UUID recipientId : recipientIds) {
            deltas.add(new GbScoreDelta(this.seatOf(recipientId).name(), share));
        }
        this.applyScoreDeltas(deltas);
        Set<SichuanGangEvent> transferredByIdentity = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        transferredByIdentity.addAll(this.pendingSichuanCallTransferEvents);
        this.sichuanGangEvents.removeIf(transferredByIdentity::contains);
        this.clearPendingSichuanCallTransferChain();
    }

    private void clearSichuanCallTransferAfterUnclaimedKongDiscard(GbReactionResolver.PendingReactionWindow pending) {
        if (pending != null && pending.flags().contains("AFTER_KONG")) {
            this.clearPendingSichuanCallTransferChain();
        }
    }

    private void clearPendingSichuanCallTransferChain() {
        this.pendingSichuanCallTransferEvents.clear();
    }

    private void refundSichuanGangIncome(Set<UUID> refundingPlayers) {
        if (refundingPlayers == null || refundingPlayers.isEmpty()) {
            return;
        }
        for (int index = this.sichuanGangEvents.size() - 1; index >= 0; index--) {
            SichuanGangEvent event = this.sichuanGangEvents.get(index);
            if (!refundingPlayers.contains(event.winnerId())) {
                continue;
            }
            this.refundSichuanGangEvent(event);
            this.pendingSichuanCallTransferEvents.removeIf(pending -> pending == event);
            this.sichuanGangEvents.remove(index);
        }
    }

    private void refundSichuanGangEvent(SichuanGangEvent event) {
        SeatWind winnerSeat = this.seatOf(event.winnerId());
        if (winnerSeat == null) {
            return;
        }
        List<GbScoreDelta> refund = new ArrayList<>();
        int collected = 0;
        for (Map.Entry<UUID, Integer> payment : event.payments().entrySet()) {
            SeatWind payerSeat = this.seatOf(payment.getKey());
            int amount = Math.max(0, payment.getValue());
            if (payerSeat == null || amount == 0) {
                continue;
            }
            refund.add(new GbScoreDelta(payerSeat.name(), amount));
            collected += amount;
        }
        if (collected == 0) {
            return;
        }
        refund.add(new GbScoreDelta(winnerSeat.name(), -collected));
        this.applyScoreDeltas(refund);
    }

    private List<UUID> sichuanActiveOpponents(UUID playerId) {
        List<UUID> opponents = new ArrayList<>();
        for (UUID candidate : this.seats.values()) {
            if (candidate == null || candidate.equals(playerId) || this.isSettledInSichuan(candidate)) {
                continue;
            }
            opponents.add(candidate);
        }
        return List.copyOf(opponents);
    }

    private void applySichuanExhaustiveDrawSettlement() {
        List<UUID> activePlayers = this.seats.values().stream().filter(player -> player != null && !this.isSettledInSichuan(player)).toList();
        List<SeatWind> activeSeats = new ArrayList<>();
        Set<SeatWind> readySeats = new HashSet<>();
        Set<UUID> notReadyPlayers = new HashSet<>();
        Map<SeatWind, Integer> readyUnits = new EnumMap<>(SeatWind.class);
        for (UUID playerId : activePlayers) {
            SeatWind seat = this.seatOf(playerId);
            if (seat == null) {
                continue;
            }
            activeSeats.add(seat);
            boolean huaZhu = !this.isMissingChosenSuit(playerId, this.tilesForSichuanSettlement(playerId));
            if (huaZhu) {
                notReadyPlayers.add(playerId);
                continue;
            }
            GbTingResponse ting = this.evaluateTing(
                playerId,
                this.currentConcealedHand(playerId),
                this.melds.getOrDefault(playerId, List.of())
            );
            if (ting != null && ting.getValid() && !ting.getWaits().isEmpty()) {
                readySeats.add(seat);
                readyUnits.put(seat, this.sichuanRulesEngine.bestReadyUnit(ting.getWaits()));
            } else {
                notReadyPlayers.add(playerId);
            }
        }
        this.refundSichuanGangIncome(notReadyPlayers);
        this.applyScoreDeltas(this.sichuanRulesEngine.exhaustiveDrawDeltas(activeSeats, readySeats, readyUnits));
    }

    private int bestSichuanReadyUnit(UUID playerId) {
        GbTingResponse response = this.tingOptions(playerId);
        if (response == null || !response.getValid() || response.getWaits().isEmpty()) {
            return 1;
        }
        return this.sichuanRulesEngine.bestReadyUnit(response.getWaits());
    }

    private record SichuanGangEvent(UUID winnerId, Map<UUID, Integer> payments) {
    }

    private EnumMap<SeatWind, UUID> reactionSeats() {
        if (!this.usesSichuanBloodBattle()) {
            return this.seats;
        }
        EnumMap<SeatWind, UUID> filtered = new EnumMap<>(SeatWind.class);
        for (SeatWind wind : SeatWind.values()) {
            UUID playerId = this.seats.get(wind);
            filtered.put(wind, this.isSettledInSichuan(playerId) ? null : playerId);
        }
        return filtered;
    }

    private UUID nextTurnPlayerAfter(int pivotIndex) {
        for (int offset = 1; offset <= SeatWind.values().length; offset++) {
            int index = Math.floorMod(pivotIndex + offset, SeatWind.values().length);
            SeatWind wind = SeatWind.fromIndex(index);
            UUID candidate = this.playerAt(wind);
            if (candidate == null || this.isSettledInSichuan(candidate)) {
                continue;
            }
            this.currentPlayerIndex = index;
            return candidate;
        }
        return null;
    }

    private List<Pair<top.ellan.mahjong.riichi.model.MahjongTile, top.ellan.mahjong.riichi.model.MahjongTile>> availableChiiPairs(UUID playerId, MahjongTile claimedTile) {
        if (this.ruleProfile.useSichuanHuEvaluator()) {
            return List.of();
        }
        if (GbRoundSupport.isHonor(claimedTile) || claimedTile == null || claimedTile.isFlower()) {
            return List.of();
        }
        List<Pair<top.ellan.mahjong.riichi.model.MahjongTile, top.ellan.mahjong.riichi.model.MahjongTile>> pairs = new ArrayList<>();
        List<MahjongTile> hand = this.hands.getOrDefault(playerId, List.of());
        int number = GbRoundSupport.tileNumber(claimedTile);
        MahjongTile prev2 = GbRoundSupport.offsetTile(claimedTile, -2);
        MahjongTile prev1 = GbRoundSupport.offsetTile(claimedTile, -1);
        MahjongTile next1 = GbRoundSupport.offsetTile(claimedTile, 1);
        MahjongTile next2 = GbRoundSupport.offsetTile(claimedTile, 2);
        if (number >= 3 && GbRoundSupport.containsTile(hand, prev2) && GbRoundSupport.containsTile(hand, prev1)) {
            pairs.add(new Pair<>(GbRoundSupport.toRiichiTile(prev2), GbRoundSupport.toRiichiTile(prev1)));
        }
        if (number >= 2 && number <= 8 && GbRoundSupport.containsTile(hand, prev1) && GbRoundSupport.containsTile(hand, next1)) {
            pairs.add(new Pair<>(GbRoundSupport.toRiichiTile(prev1), GbRoundSupport.toRiichiTile(next1)));
        }
        if (number <= 7 && GbRoundSupport.containsTile(hand, next1) && GbRoundSupport.containsTile(hand, next2)) {
            pairs.add(new Pair<>(GbRoundSupport.toRiichiTile(next1), GbRoundSupport.toRiichiTile(next2)));
        }
        return List.copyOf(pairs);
    }

    private void advanceAfterDiscard() {
        UUID current = this.nextTurnPlayerAfter(this.currentPlayerIndex);
        if (current == null) {
            this.started = false;
            return;
        }
        if (!this.drawTile(current, false, false, true)) {
            this.finishExhaustiveDraw();
        }
        this.refreshAllTing();
    }

    private boolean drawReplacementTileOrFinish(UUID playerId) {
        if (this.drawTile(playerId, true, true, true)) {
            return true;
        }
        this.finishExhaustiveDraw();
        return true;
    }

    private void dealInitialTile(UUID playerId) {
        if (playerId == null) {
            throw new IllegalStateException("Cannot deal an initial tile to an unoccupied seat.");
        }
        if (this.wall.isEmpty()) {
            throw new IllegalStateException("Wall exhausted during the initial deal.");
        }
        this.hands.get(playerId).add(this.wall.removeFirst());
    }

    private boolean drawTile(UUID playerId, boolean fromBack) {
        return this.drawTile(playerId, fromBack, false, true);
    }

    private boolean drawTile(UUID playerId, boolean fromBack, boolean markAfterKong) {
        return this.drawTile(playerId, fromBack, markAfterKong, true);
    }

    private boolean drawTile(UUID playerId, boolean fromBack, boolean markAfterKong, boolean markAsDrawn) {
        if (playerId == null || this.wall.isEmpty()) {
            return false;
        }
        if (fromBack) {
            return this.drawReplacementTile(playerId, markAfterKong, markAsDrawn);
        }
        while (!this.wall.isEmpty()) {
            MahjongTile tile = this.wall.removeFirst();
            if (tile.isFlower() && !this.ruleProfile.includesFlowers()) {
                continue;
            }
            this.hands.get(playerId).add(tile);
            this.hasDrawnTile.put(playerId, markAsDrawn);
            this.sortHand(playerId);
            this.afterKanTsumoPlayer = markAfterKong && !tile.isFlower() ? playerId : null;
            this.clearSichuanPassedWinAfterDraw(playerId);
            return true;
        }
        return false;
    }

    private boolean drawReplacementTile(UUID playerId, boolean markAfterKong, boolean markAsDrawn) {
        if (this.wall.isEmpty()) {
            return false;
        }
        MahjongTile tile = this.wall.removeLast();
        if (tile.isFlower() && !this.ruleProfile.includesFlowers()) {
            return this.drawReplacementTile(playerId, markAfterKong, markAsDrawn);
        }
        this.hands.get(playerId).add(tile);
        this.hasDrawnTile.put(playerId, markAsDrawn);
        this.sortHand(playerId);
        this.afterKanTsumoPlayer = markAfterKong && !tile.isFlower() ? playerId : null;
        this.clearSichuanPassedWinAfterDraw(playerId);
        return true;
    }

    private void clearSichuanPassedWinAfterDraw(UUID playerId) {
        if (this.usesSichuanBloodBattle() && playerId != null) {
            this.sichuanPassedWinUnits.remove(playerId);
        }
    }

    private void finishExhaustiveDraw() {
        this.pendingReactionWindow = null;
        this.started = false;
        this.afterKanTsumoPlayer = null;
        if (this.usesSichuanBloodBattle() && !this.sichuanWinHistory.isEmpty()) {
            this.applySichuanExhaustiveDrawSettlement();
            List<YakuSettlement> settlements = this.toWinnerSettlements(List.copyOf(this.sichuanWinHistory), this.roundStartPointsSnapshot());
            ScoreSettlement scoreSettlement = new ScoreSettlement("DRAW", this.toScoreItems(this.roundStartPointsSnapshot()));
            this.lastResolution = new RoundResolution("DRAW", settlements, scoreSettlement, ExhaustiveDraw.NORMAL);
        } else if (this.usesSichuanBloodBattle()) {
            this.applySichuanExhaustiveDrawSettlement();
            this.lastResolution = new RoundResolution("DRAW", List.of(), new ScoreSettlement("DRAW", this.toScoreItems(this.roundStartPointsSnapshot())), ExhaustiveDraw.NORMAL);
        } else {
            this.lastResolution = new RoundResolution("DRAW", List.of(), null, ExhaustiveDraw.NORMAL);
        }
        this.advanceMatchState();
    }

    private void advanceMatchState() {
        if (!this.round.isAllLast(this.rule)) {
            this.round.nextRound();
            this.gameFinished = false;
            return;
        }
        if (this.usesSichuanBloodBattle()) {
            this.gameFinished = true;
            return;
        }
        if (this.points.values().stream().anyMatch(score -> score >= this.rule.getMinPointsToWin())) {
            this.gameFinished = true;
            return;
        }
        Pair<Wind, Integer> finalRound = this.rule.getLength().getFinalRound();
        if (this.round.getWind() == finalRound.getFirst() && this.round.getRound() == finalRound.getSecond()) {
            this.gameFinished = true;
            return;
        }
        this.round.nextRound();
        this.gameFinished = false;
    }

    private GbReactionResolver.PendingReactionWindow buildRobbingKongWindow(UUID discarderId, MahjongTile claimedTile, int upgradeMeldIndex) {
        LinkedHashMap<UUID, ReactionOptions> options = new LinkedHashMap<>();
        SeatWind discarderSeat = this.seatOf(discarderId);
        List<String> flags = this.discardWinFlags(claimedTile, true);
        for (SeatWind wind : GbRoundSupport.orderedAfter(discarderSeat)) {
            UUID playerId = this.playerAt(wind);
            if (playerId == null || playerId.equals(discarderId) || this.isSettledInSichuan(playerId)) {
                continue;
            }
            GbFanResponse response = this.evaluateFanResponse(playerId, claimedTile, "DISCARD", discarderSeat, flags);
            boolean canRon = this.canWinResponse(response) && this.exceedsSichuanPassedWin(playerId, response);
            if (canRon) {
                options.put(playerId, new ReactionOptions(true, false, false, List.of()));
            }
        }
        return options.isEmpty()
            ? null
            : new GbReactionResolver.PendingReactionWindow(discarderId, claimedTile, options, new HashMap<>(), flags, true, upgradeMeldIndex);
    }

    private void finishAddedKong(UUID playerId, MahjongTile target, Integer meldIndex) {
        if (meldIndex == null) {
            return;
        }
        GbRoundSupport.removeTiles(this.hands.get(playerId), target, 1);
        this.hasDrawnTile.put(playerId, false);
        this.sortHand(playerId);
        GbMeldState meld = this.melds.get(playerId).get(meldIndex);
        this.melds.get(playerId).set(meldIndex, meld.toAddedKong(target));
        this.kanCount++;
        this.applySichuanKanSettlement(playerId, this.sichuanActiveOpponents(playerId), SICHUAN_ADDED_KAN_UNIT);
        this.drawReplacementTileOrFinish(playerId);
        this.refreshAllTing();
    }

    private List<String> selfDrawFlags(UUID playerId, MahjongTile winningTile) {
        List<String> flags = new ArrayList<>();
        if (this.wall.isEmpty()) {
            flags.add("LAST_TILE");
        }
        if (Objects.equals(this.afterKanTsumoPlayer, playerId)) {
            flags.add("AFTER_KONG");
        }
        if (this.visibleTileCount(winningTile) >= 3) {
            flags.add("LAST_OF_KIND");
        }
        return List.copyOf(flags);
    }

    private List<String> discardWinFlags(MahjongTile winningTile, boolean robbingKong) {
        List<String> flags = new ArrayList<>();
        if (this.wall.isEmpty()) {
            flags.add("LAST_TILE");
        }
        if (robbingKong) {
            flags.add("ROBBING_KONG");
        }
        int visibleTilesRequired = robbingKong ? 3 : 4;
        if (this.visibleTileCount(winningTile) >= visibleTilesRequired) {
            flags.add("LAST_OF_KIND");
        }
        return List.copyOf(flags);
    }

    private int visibleTileCount(MahjongTile target) {
        int count = 0;
        for (List<MahjongTile> playerDiscards : this.discards.values()) {
            count += GbRoundSupport.countMatchingTiles(playerDiscards, target);
        }
        for (List<GbMeldState> playerMelds : this.melds.values()) {
            for (GbMeldState meld : playerMelds) {
                if (!meld.open()) {
                    continue;
                }
                count += GbRoundSupport.countMatchingTiles(meld.tiles(), target);
            }
        }
        return count;
    }

    private MahjongTile drawnTile(UUID playerId) {
        List<MahjongTile> hand = this.hands.get(playerId);
        return hand == null || hand.isEmpty() || !this.hasDrawnTile(playerId) ? null : hand.get(hand.size() - 1);
    }

    private boolean hasDrawnTile(UUID playerId) {
        return Boolean.TRUE.equals(this.hasDrawnTile.get(playerId));
    }

    private void sortHand(UUID playerId) {
        List<MahjongTile> hand = this.hands.get(playerId);
        if (hand == null || hand.size() < 2) {
            return;
        }
        if (!this.hasDrawnTile(playerId)) {
            hand.sort((left, right) -> Integer.compare(handTileSort(left), handTileSort(right)));
            return;
        }
        MahjongTile drawn = hand.remove(hand.size() - 1);
        hand.sort((left, right) -> Integer.compare(handTileSort(left), handTileSort(right)));
        hand.add(drawn);
    }

    private static int handTileSort(MahjongTile tile) {
        return tile.ordinal();
    }

    private void consumeClaimedDiscard(UUID discarderId, MahjongTile claimedTile) {
        List<MahjongTile> river = this.discards.get(discarderId);
        if (river == null || river.isEmpty() || claimedTile == null) {
            return;
        }
        for (int i = river.size() - 1; i >= 0; i--) {
            if (GbRoundSupport.sameKind(river.get(i), claimedTile)) {
                river.remove(i);
                return;
            }
        }
    }

    private void refreshAllTing() {
        for (UUID playerId : this.seats.values()) {
            if (playerId == null) {
                continue;
            }
            this.invalidateTing(playerId);
        }
    }

    private void invalidateTing(UUID playerId) {
        if (playerId != null) {
            this.dirtyTingPlayers.add(playerId);
        }
    }

    private void cacheTing(UUID playerId, GbTingResponse response) {
        if (playerId == null || response == null) {
            return;
        }
        this.tingCache.put(playerId, response);
        this.dirtyTingPlayers.remove(playerId);
    }

    private void refreshTingIfDirty(UUID playerId) {
        if (playerId == null || !this.dirtyTingPlayers.remove(playerId)) {
            return;
        }
        if (this.isSettledInSichuan(playerId)) {
            this.tingCache.put(playerId, new GbTingResponse(false, List.of(), "Player has already won this hand."));
            return;
        }
        if (this.isSichuanPreparationPhase()) {
            this.tingCache.put(playerId, new GbTingResponse(false, List.of(), "Complete Sichuan opening actions first."));
            return;
        }
        this.tingCache.put(playerId, this.evaluateTing(playerId, this.currentConcealedHand(playerId), this.melds.getOrDefault(playerId, List.of())));
    }

    private GbTingResponse evaluateTing(UUID playerId, List<MahjongTile> concealedHand, List<GbMeldState> meldStates) {
        if (this.isSettledInSichuan(playerId)) {
            return new GbTingResponse(false, List.of(), "Player has already won this hand.");
        }
        if (this.isSichuanPreparationPhase()) {
            return new GbTingResponse(false, List.of(), "Complete Sichuan opening actions first.");
        }
        if (!this.ruleProfile.useSichuanHuEvaluator()
            && concealedHand != null
            && concealedHand.stream().anyMatch(MahjongTile::isFlower)) {
            return new GbTingResponse(false, List.of(), "Expose or discard retained flowers before declaring a ready hand.");
        }
        if (this.ruleProfile.useSichuanHuEvaluator()) {
            if (playerId == null) {
                return new GbTingResponse(false, List.of(), "Player is unavailable.");
            }
            List<MahjongTile> waits = this.sichuanRulesEngine.waitingTiles(concealedHand, meldStates.size());
            List<GbTingCandidate> candidates = waits.stream()
                .filter(tile -> this.canPhysicallyWaitOnSichuanTile(tile, concealedHand, meldStates))
                .filter(tile -> this.isMissingChosenSuit(playerId, this.tilesForSichuanWin(playerId, concealedHand, meldStates, tile)))
                .map(tile -> {
                    SichuanRulesEngine.FanResult result = this.sichuanFanResult(
                        playerId, concealedHand, meldStates, tile, "DISCARD", List.of()
                    );
                    int totalFan = result.valid() ? Math.max(0, result.totalFan()) : 0;
                    List<GbFanEntry> fans = result.valid() ? result.fans() : List.of();
                    return new GbTingCandidate(GbTileEncoding.encode(tile), totalFan, fans);
                })
                .toList();
            return new GbTingResponse(!candidates.isEmpty(), candidates, candidates.isEmpty() ? "No valid Sichuan waits." : null);
        }
        return this.nativeGateway.evaluateTing(this.buildTingRequest(playerId, concealedHand, meldStates));
    }

    private boolean hasUnexposedFlowerForWin(UUID playerId, MahjongTile winningTile, String winType) {
        return winningTile == null
            || winningTile.isFlower()
            || this.concealedHandForWin(playerId, winType).stream().anyMatch(MahjongTile::isFlower);
    }

    private boolean canPhysicallyWaitOnSichuanTile(
        MahjongTile candidate,
        List<MahjongTile> concealedHand,
        List<GbMeldState> meldStates
    ) {
        int ownedCopies = GbRoundSupport.countMatchingTiles(concealedHand, candidate);
        for (GbMeldState meld : meldStates == null ? List.<GbMeldState>of() : meldStates) {
            ownedCopies += GbRoundSupport.countMatchingTiles(meld.tiles(), candidate);
        }
        return ownedCopies < 4;
    }

    private List<MahjongTile> currentConcealedHand(UUID playerId) {
        List<MahjongTile> hand = this.hands.getOrDefault(playerId, List.of());
        if (!this.hasDrawnTile(playerId) || hand.isEmpty()) {
            return List.copyOf(hand);
        }
        return List.copyOf(hand.subList(0, hand.size() - 1));
    }

    private SeatWind roundWindSeat() {
        return switch (this.round.getWind()) {
            case EAST -> SeatWind.EAST;
            case SOUTH -> SeatWind.SOUTH;
            case WEST -> SeatWind.WEST;
            case NORTH -> SeatWind.NORTH;
        };
    }

    private SeatWind seatOf(UUID playerId) {
        return playerId == null ? null : this.seatByPlayerId.get(playerId);
    }

    private boolean isSeatedPlayer(UUID playerId) {
        return playerId != null && this.hands.containsKey(playerId);
    }

    private SeatWind logicalSeatOf(UUID playerId) {
        return this.logicalSeat(this.seatOf(playerId));
    }

    private SeatWind logicalSeat(SeatWind physicalSeat) {
        if (physicalSeat == null) {
            return null;
        }
        return SeatWind.fromIndex(Math.floorMod(physicalSeat.index() - this.dealerSeat().index(), SeatWind.values().length));
    }

    private UUID currentPlayerId() {
        return this.playerAt(SeatWind.fromIndex(this.currentPlayerIndex));
    }
}
