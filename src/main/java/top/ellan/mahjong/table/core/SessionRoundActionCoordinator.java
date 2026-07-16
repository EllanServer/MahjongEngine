package top.ellan.mahjong.table.core;

import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.scene.MeldView;
import top.ellan.mahjong.riichi.ReactionResponse;
import top.ellan.mahjong.riichi.ReactionResponses;
import top.ellan.mahjong.riichi.ReactionType;
import top.ellan.mahjong.riichi.RoundResolution;
import top.ellan.mahjong.riichi.model.YakuSettlement;
import top.ellan.mahjong.table.core.round.TableRoundController;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class SessionRoundActionCoordinator {
    private final TableSessionMutator session;

    SessionRoundActionCoordinator(TableSessionMutator session) {
        this.session = session;
    }

    boolean discard(UUID playerId, int tileIndex) {
        TableRoundController controller = this.session.roundControllerInternal();
        if (controller == null) {
            return false;
        }
        int discardedCountBefore = this.discardCount(controller, playerId);
        MahjongTile discardedTile = this.session.handTileAtInternal(playerId, tileIndex);
        boolean result = controller.discard(playerId, tileIndex);
        RoundActionResult actionResult = RoundActionResult.from(result)
            .clearSelectedHandTiles()
            .clearLastPublicAction();
        if (result && this.discardCount(controller, playerId) > discardedCountBefore) {
            actionResult.publicDiscard(playerId, discardedTile);
        }
        return this.completeRoundAction(actionResult);
    }

    boolean declareRiichi(UUID playerId, int tileIndex) {
        TableRoundController controller = this.session.roundControllerInternal();
        if (controller == null) {
            return false;
        }
        int discardedCountBefore = this.discardCount(controller, playerId);
        MahjongTile discardedTile = this.session.handTileAtInternal(playerId, tileIndex);
        boolean result = controller.declareRiichi(playerId, tileIndex);
        RoundActionResult actionResult = RoundActionResult.from(result).clearSelectedHandTiles();
        if (result && this.discardCount(controller, playerId) > discardedCountBefore) {
            actionResult
                .publicDiscard(playerId, discardedTile)
                .publicAction(playerId, "table.action.riichi")
                .riichiSound();
        }
        return this.completeRoundAction(actionResult);
    }

    boolean declareTsumo(UUID playerId) {
        TableRoundController controller = this.session.roundControllerInternal();
        if (controller == null) {
            return false;
        }
        boolean result = controller.declareTsumo(playerId);
        return this.completeRoundAction(RoundActionResult.from(result)
            .clearSelectedHandTiles()
            .publicAction(playerId, "table.action.tsumo"));
    }

    boolean declareKyuushuKyuuhai(UUID playerId) {
        TableRoundController controller = this.session.roundControllerInternal();
        if (controller == null) {
            return false;
        }
        boolean result = controller.declareKyuushuKyuuhai(playerId);
        return this.completeRoundAction(RoundActionResult.from(result)
            .clearSelectedHandTiles()
            .publicAction(playerId, "table.action.kyuushu"));
    }

    boolean react(UUID playerId, ReactionResponse response) {
        TableRoundController controller = this.session.roundControllerInternal();
        if (controller == null) {
            return false;
        }
        Map<UUID, List<MeldView>> meldsBefore = this.captureSeatMelds(controller);
        Map<UUID, Integer> pointsBefore = this.captureSeatPoints(controller);
        RoundResolution resolutionBefore = controller.lastResolution();
        boolean result = controller.react(playerId, response);
        // A submitted reaction is only a vote until every eligible seat has answered and priority
        // has been resolved. Do not announce or play it while the reaction window is still open.
        PublicAction publicAction = result && !controller.hasPendingReaction()
            ? this.resolvedReactionAction(controller, resolutionBefore, playerId, response, meldsBefore, pointsBefore)
            : PublicAction.none();
        return this.completeRoundAction(RoundActionResult.from(result)
            .clearSelectedHandTiles()
            .reactionSound(this.resolvedReactionSound(publicAction))
            .publicAction(publicAction.playerIds(), publicAction.actionKey()));
    }

    private ReactionResponse resolvedReactionSound(PublicAction action) {
        return switch (action.actionKey()) {
            case "table.action.chii" -> ReactionResponses.of(ReactionType.CHII);
            case "table.action.pon" -> ReactionResponses.PON;
            case "table.action.minkan" -> ReactionResponses.MINKAN;
            case "table.action.ron" -> ReactionResponses.RON;
            default -> null;
        };
    }

    boolean declareKan(UUID playerId, String tileName) {
        TableRoundController controller = this.session.roundControllerInternal();
        if (controller == null) {
            return false;
        }
        List<MeldView> meldsBefore = playerId == null ? List.of() : List.copyOf(controller.fuuro(playerId));
        boolean result = controller.declareKan(playerId, tileName);
        List<MeldView> meldsAfter = result && playerId != null ? List.copyOf(controller.fuuro(playerId)) : List.of();
        return this.completeRoundAction(RoundActionResult.from(result)
            .clearSelectedHandTiles()
            .publicAction(playerId, this.resolveKanActionKey(meldsBefore, meldsAfter)));
    }

    boolean declareFlower(UUID playerId, int tileIndex) {
        TableRoundController controller = this.session.roundControllerInternal();
        if (controller == null) {
            return false;
        }
        boolean result = controller.declareFlower(playerId, tileIndex);
        return this.completeRoundAction(RoundActionResult.from(result)
            .clearSelectedHandTiles()
            .publicAction(playerId, "table.action.flower"));
    }

    private PublicAction resolvedReactionAction(
        TableRoundController controller,
        RoundResolution resolutionBefore,
        UUID actorId,
        ReactionResponse response,
        Map<UUID, List<MeldView>> meldsBefore,
        Map<UUID, Integer> pointsBefore
    ) {
        List<UUID> resolvedWinners = this.resolvedWinnerIds(
            controller,
            resolutionBefore,
            controller.lastResolution(),
            pointsBefore
        );
        if (!resolvedWinners.isEmpty()) {
            return new PublicAction(resolvedWinners, "table.action.ron");
        }
        Map<UUID, List<MeldView>> meldsAfter = this.captureSeatMelds(controller);
        UUID claimPlayerId = this.findClaimPlayerId(meldsBefore, meldsAfter);
        if (claimPlayerId != null) {
            List<MeldView> before = meldsBefore.getOrDefault(claimPlayerId, List.of());
            List<MeldView> after = meldsAfter.getOrDefault(claimPlayerId, List.of());
            MeldView addedMeld = after.isEmpty() ? null : after.get(after.size() - 1);
            String actionKey = this.claimActionKey(addedMeld);
            if (!actionKey.isBlank()) {
                return new PublicAction(List.of(claimPlayerId), actionKey);
            }
            return PublicAction.none();
        }
        if (response != null && response.getType() == ReactionType.RON) {
            return new PublicAction(actorId == null ? List.of() : List.of(actorId), "table.action.ron");
        }
        return PublicAction.none();
    }

    private List<UUID> resolvedWinnerIds(
        TableRoundController controller,
        RoundResolution before,
        RoundResolution after,
        Map<UUID, Integer> pointsBefore
    ) {
        if (after == null || after == before || after.getYakuSettlements().isEmpty()) {
            return List.of();
        }
        LinkedHashSet<UUID> winnerIds = new LinkedHashSet<>();
        for (YakuSettlement settlement : after.getYakuSettlements()) {
            if (settlement == null || settlement.getUuid() == null || settlement.getUuid().isBlank()) {
                continue;
            }
            try {
                winnerIds.add(UUID.fromString(settlement.getUuid()));
            } catch (IllegalArgumentException ignored) {
                // A malformed settlement must not suppress feedback for the other winners.
            }
        }
        List<UUID> winnersWithNewIncome = winnerIds.stream()
            .filter(winnerId -> {
                Integer previous = pointsBefore.get(winnerId);
                return previous != null && controller.points(winnerId) > previous;
            })
            .toList();
        // Some controller test doubles and imported resolutions do not expose a
        // point transition. In that case the settlement remains authoritative.
        return winnersWithNewIncome.isEmpty() ? List.copyOf(winnerIds) : winnersWithNewIncome;
    }

    private Map<UUID, List<MeldView>> captureSeatMelds(TableRoundController controller) {
        Map<UUID, List<MeldView>> melds = new LinkedHashMap<>();
        for (SeatWind wind : SeatWind.values()) {
            UUID playerId = this.session.playerAt(wind);
            if (playerId == null) {
                continue;
            }
            melds.put(playerId, List.copyOf(controller.fuuro(playerId)));
        }
        return melds;
    }

    private Map<UUID, Integer> captureSeatPoints(TableRoundController controller) {
        Map<UUID, Integer> points = new LinkedHashMap<>();
        for (SeatWind wind : SeatWind.values()) {
            UUID playerId = this.session.playerAt(wind);
            if (playerId != null) {
                points.put(playerId, controller.points(playerId));
            }
        }
        return points;
    }

    private UUID findClaimPlayerId(Map<UUID, List<MeldView>> before, Map<UUID, List<MeldView>> after) {
        for (Map.Entry<UUID, List<MeldView>> entry : after.entrySet()) {
            UUID playerId = entry.getKey();
            int beforeCount = before.getOrDefault(playerId, List.of()).size();
            if (entry.getValue().size() > beforeCount) {
                return playerId;
            }
        }
        return null;
    }

    private String claimActionKey(MeldView meld) {
        if (meld == null || meld.tiles().isEmpty()) {
            return "";
        }
        if (meld.tiles().size() >= 4) {
            return "table.action.minkan";
        }
        return this.isUniformMeld(meld) ? "table.action.pon" : "table.action.chii";
    }

    private boolean isUniformMeld(MeldView meld) {
        MahjongTile first = meld.tiles().get(0);
        for (MahjongTile tile : meld.tiles()) {
            if (tile != first) {
                return false;
            }
        }
        return true;
    }

    private String resolveKanActionKey(List<MeldView> before, List<MeldView> after) {
        if (this.isAddedKanUpgrade(before, after)) {
            return "table.action.kakan";
        }
        if (after.size() > before.size()) {
            return "table.action.ankan";
        }
        return "";
    }

    private boolean isAddedKanUpgrade(List<MeldView> before, List<MeldView> after) {
        int compare = Math.min(before.size(), after.size());
        for (int i = 0; i < compare; i++) {
            MeldView previous = before.get(i);
            MeldView current = after.get(i);
            boolean previousAdded = previous != null && previous.hasAddedKanTile();
            boolean currentAdded = current != null && current.hasAddedKanTile();
            if (!previousAdded && currentAdded) {
                return true;
            }
            int previousSize = previous == null ? 0 : previous.tiles().size();
            int currentSize = current == null ? 0 : current.tiles().size();
            if (currentSize > previousSize) {
                return true;
            }
        }
        if (after.size() <= before.size()) {
            return false;
        }
        MeldView newest = after.get(after.size() - 1);
        return newest != null && newest.hasAddedKanTile();
    }

    private void renderAndFlushViewerPresentation() {
        this.session.render();
        this.session.flushViewerPresentationIfNeededInternal();
    }

    private int discardCount(TableRoundController controller, UUID playerId) {
        if (controller == null || playerId == null) {
            return 0;
        }
        List<MahjongTile> discards = controller.discards(playerId);
        return discards == null ? 0 : discards.size();
    }

    private boolean completeRoundAction(RoundActionResult result) {
        if (!result.changed()) {
            return false;
        }
        if (result.shouldClearSelectedHandTiles()) {
            this.session.clearSelectedHandTilesInternal();
        }
        if (result.shouldClearLastPublicAction()) {
            this.session.clearLastPublicActionInternal();
        }
        if (result.publicDiscardTile() != null) {
            this.session.rememberPublicDiscardInternal(result.publicDiscardPlayerId(), result.publicDiscardTile());
            this.session.playDiscardSoundInternal();
        }
        if (result.publicActionKey() != null && !result.publicActionKey().isBlank()) {
            if (result.publicActionPlayerIds().size() == 1) {
                this.session.rememberPublicActionInternal(
                    result.publicActionPlayerIds().get(0),
                    result.publicActionKey()
                );
            } else {
                this.session.rememberPublicActionsInternal(result.publicActionPlayerIds(), result.publicActionKey());
            }
        }
        if (result.reactionSound() != null) {
            this.session.playReactionSoundInternal(result.reactionSound());
        }
        if (result.shouldPlayRiichiSound()) {
            this.session.playRiichiSoundInternal();
        }
        this.renderAndFlushViewerPresentation();
        return true;
    }

    private record PublicAction(List<UUID> playerIds, String actionKey) {
        private PublicAction {
            playerIds = playerIds == null ? List.of() : List.copyOf(playerIds);
        }

        private static PublicAction none() {
            return new PublicAction(List.of(), "");
        }
    }

    private static final class RoundActionResult {
        private final boolean changed;
        private boolean clearSelectedHandTiles;
        private boolean clearLastPublicAction;
        private UUID publicDiscardPlayerId;
        private MahjongTile publicDiscardTile;
        private List<UUID> publicActionPlayerIds = List.of();
        private String publicActionKey;
        private ReactionResponse reactionSound;
        private boolean riichiSound;

        private RoundActionResult(boolean changed) {
            this.changed = changed;
        }

        static RoundActionResult from(boolean changed) {
            return new RoundActionResult(changed);
        }

        RoundActionResult clearSelectedHandTiles() {
            this.clearSelectedHandTiles = true;
            return this;
        }

        RoundActionResult clearLastPublicAction() {
            this.clearLastPublicAction = true;
            return this;
        }

        RoundActionResult publicDiscard(UUID playerId, MahjongTile tile) {
            this.publicDiscardPlayerId = playerId;
            this.publicDiscardTile = tile;
            return this;
        }

        RoundActionResult publicAction(UUID playerId, String actionKey) {
            return this.publicAction(playerId == null ? List.of() : List.of(playerId), actionKey);
        }

        RoundActionResult publicAction(List<UUID> playerIds, String actionKey) {
            this.publicActionPlayerIds = playerIds == null ? List.of() : List.copyOf(playerIds);
            this.publicActionKey = actionKey;
            return this;
        }

        RoundActionResult reactionSound(ReactionResponse response) {
            this.reactionSound = response;
            return this;
        }

        RoundActionResult riichiSound() {
            this.riichiSound = true;
            return this;
        }

        boolean changed() {
            return this.changed;
        }

        boolean shouldClearSelectedHandTiles() {
            return this.clearSelectedHandTiles;
        }

        boolean shouldClearLastPublicAction() {
            return this.clearLastPublicAction;
        }

        UUID publicDiscardPlayerId() {
            return this.publicDiscardPlayerId;
        }

        MahjongTile publicDiscardTile() {
            return this.publicDiscardTile;
        }

        List<UUID> publicActionPlayerIds() {
            return this.publicActionPlayerIds;
        }

        String publicActionKey() {
            return this.publicActionKey;
        }

        ReactionResponse reactionSound() {
            return this.reactionSound;
        }

        boolean shouldPlayRiichiSound() {
            return this.riichiSound;
        }
    }
}
