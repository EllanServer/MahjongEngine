package top.ellan.mahjong.table.core.round;

import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.scene.MeldView;
import top.ellan.mahjong.riichi.ReactionOptions;
import top.ellan.mahjong.riichi.ReactionResponse;
import top.ellan.mahjong.riichi.RiichiDiscardSuggestion;
import top.ellan.mahjong.riichi.RiichiPlayerState;
import top.ellan.mahjong.riichi.RiichiRoundEngine;
import top.ellan.mahjong.riichi.RoundResolution;
import top.ellan.mahjong.riichi.model.OpeningDiceRoll;
import top.ellan.mahjong.riichi.model.ScoringStick;
import top.ellan.mahjong.model.MahjongVariant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class RiichiTableRoundController implements TableRoundController {
    private final RiichiRoundEngine engine;

    public RiichiTableRoundController(RiichiRoundEngine engine) {
        this.engine = engine;
    }

    public RiichiRoundEngine roundEngine() {
        return this.engine;
    }

    @Override
    public <T> T accept(VariantVisitor<T> visitor) {
        return visitor.visitRiichi(this);
    }

    @Override
    public MahjongVariant variant() {
        return MahjongVariant.RIICHI;
    }

    @Override
    public top.ellan.mahjong.riichi.model.MahjongRule rule() {
        return this.engine.getRule();
    }

    @Override
    public boolean started() {
        return this.engine.getStarted();
    }

    @Override
    public boolean gameFinished() {
        return this.engine.getGameFinished();
    }

    @Override
    public void startRound() {
        this.engine.startRound();
    }

    @Override
    public void setPendingDiceRoll(OpeningDiceRoll diceRoll) {
        this.engine.setPendingDiceRoll(diceRoll);
    }

    @Override
    public boolean discard(UUID playerId, int tileIndex) {
        return playerId != null && this.engine.discard(playerId.toString(), tileIndex);
    }

    @Override
    public boolean declareRiichi(UUID playerId, int tileIndex) {
        return playerId != null && this.engine.declareRiichi(playerId.toString(), tileIndex);
    }

    @Override
    public boolean declareTsumo(UUID playerId) {
        return playerId != null && this.engine.tryTsumo(playerId.toString());
    }

    @Override
    public boolean declareKyuushuKyuuhai(UUID playerId) {
        return playerId != null && this.engine.declareKyuushuKyuuhai(playerId.toString());
    }

    @Override
    public boolean react(UUID playerId, ReactionResponse response) {
        return playerId != null
            && response != null
            && this.isReactionPending(playerId)
            && this.engine.react(playerId.toString(), response);
    }

    @Override
    public boolean declareKan(UUID playerId, String tileName) {
        if (playerId == null || tileName == null || tileName.isBlank()) {
            return false;
        }
        try {
            top.ellan.mahjong.riichi.model.MahjongTile tile =
                top.ellan.mahjong.riichi.model.MahjongTile.valueOf(GbRoundSupport.normalizeTileToken(tileName));
            return this.engine.tryAnkanOrKakan(playerId.toString(), tile);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    @Override
    public UUID playerAt(SeatWind wind) {
        if (wind == null || this.engine.getSeats().size() <= wind.index()) {
            return null;
        }
        return UUID.fromString(this.engine.getSeats().get(wind.index()).getUuid());
    }

    @Override
    public int points(UUID playerId) {
        RiichiPlayerState player = this.seatPlayer(playerId);
        return player == null ? 0 : player.getPoints();
    }

    @Override
    public boolean isRiichi(UUID playerId) {
        RiichiPlayerState player = this.seatPlayer(playerId);
        return player != null && (player.getRiichi() || player.getDoubleRiichi());
    }

    @Override
    public int dicePoints() {
        return this.engine.getDicePoints();
    }

    @Override
    public int kanCount() {
        return this.engine.getKanCount();
    }

    @Override
    public int roundIndex() {
        return this.engine.getRound().getRound();
    }

    @Override
    public int honbaCount() {
        return this.engine.getRound().getHonba();
    }

    @Override
    public SeatWind roundWind() {
        return switch (this.engine.getRound().getWind()) {
            case EAST -> SeatWind.EAST;
            case SOUTH -> SeatWind.SOUTH;
            case WEST -> SeatWind.WEST;
            case NORTH -> SeatWind.NORTH;
        };
    }

    @Override
    public SeatWind dealerSeat() {
        return SeatWind.fromIndex(this.engine.getRound().getRound());
    }

    @Override
    public SeatWind currentSeat() {
        return SeatWind.fromIndex(this.engine.getCurrentPlayerIndex());
    }

    @Override
    public String currentPlayerDisplayName() {
        return this.engine.getCurrentPlayer().getDisplayName();
    }

    @Override
    public List<top.ellan.mahjong.model.MahjongTile> hand(UUID playerId) {
        RiichiPlayerState player = this.seatPlayer(playerId);
        if (player == null) {
            return List.of();
        }
        List<top.ellan.mahjong.model.MahjongTile> tiles = new ArrayList<>(player.getHands().size());
        player.getHands().forEach(tile -> tiles.add(top.ellan.mahjong.model.MahjongTile.valueOf(tile.getMahjongTile().name())));
        return List.copyOf(tiles);
    }

    @Override
    public List<top.ellan.mahjong.model.MahjongTile> discards(UUID playerId) {
        RiichiPlayerState player = this.seatPlayer(playerId);
        if (player == null) {
            return List.of();
        }
        List<top.ellan.mahjong.model.MahjongTile> tiles = new ArrayList<>(player.getDiscardedTilesForDisplay().size());
        player.getDiscardedTilesForDisplay().forEach(tile -> tiles.add(top.ellan.mahjong.model.MahjongTile.valueOf(tile.getMahjongTile().name())));
        return List.copyOf(tiles);
    }

    @Override
    public List<top.ellan.mahjong.model.MahjongTile> remainingWall() {
        List<top.ellan.mahjong.model.MahjongTile> tiles = new ArrayList<>(this.engine.getWall().size());
        this.engine.getWall().forEach(tile -> tiles.add(top.ellan.mahjong.model.MahjongTile.UNKNOWN));
        return List.copyOf(tiles);
    }

    @Override
    public int remainingWallCount() {
        return this.engine.getWall().size();
    }

    @Override
    public List<MeldView> fuuro(UUID playerId) {
        RiichiPlayerState player = this.seatPlayer(playerId);
        if (player == null) {
            return List.of();
        }
        List<MeldView> melds = new ArrayList<>(player.getFuuroList().size());
        player.getFuuroList().forEach(fuuro -> {
            List<top.ellan.mahjong.riichi.model.TileInstance> orderedInstances = new ArrayList<>(fuuro.getTileInstances());
            top.ellan.mahjong.riichi.model.TileInstance addedKanTile = null;
            if ("KAKAN".equals(fuuro.getType().name()) && orderedInstances.size() > 3) {
                // KAKAN should render as 3 base tiles + 1 stacked tile; strip one tile into addedKanTile.
                addedKanTile = orderedInstances.remove(orderedInstances.size() - 1);
            } else if (!"KAKAN".equals(fuuro.getType().name())) {
                orderedInstances.sort((left, right) -> Integer.compare(right.getMahjongTile().getSortOrder(), left.getMahjongTile().getSortOrder()));
            }
            top.ellan.mahjong.riichi.model.TileInstance claimTile = fuuro.getClaimTile();
            orderedInstances.remove(claimTile);
            int claimTileIndex = switch (fuuro.getClaimTarget().name()) {
                case "RIGHT" -> 0;
                case "ACROSS" -> 1;
                case "LEFT" -> orderedInstances.size();
                default -> -1;
            };
            if (claimTileIndex >= 0) {
                orderedInstances.add(Math.min(claimTileIndex, orderedInstances.size()), claimTile);
            }
            List<top.ellan.mahjong.model.MahjongTile> tiles = new ArrayList<>(orderedInstances.size());
            List<Boolean> faceDownFlags = new ArrayList<>(orderedInstances.size());
            boolean concealedKan = fuuro.isKan() && !fuuro.isOpen();
            for (int i = 0; i < orderedInstances.size(); i++) {
                tiles.add(top.ellan.mahjong.model.MahjongTile.valueOf(orderedInstances.get(i).getMahjongTile().name()));
                faceDownFlags.add(concealedKan && (i == 0 || i == orderedInstances.size() - 1));
            }
            int claimYawOffset = switch (fuuro.getClaimTarget().name()) {
                case "RIGHT", "ACROSS" -> 90;
                case "LEFT" -> -90;
                default -> 0;
            };
            top.ellan.mahjong.model.MahjongTile addedKanView = addedKanTile == null
                ? null
                : top.ellan.mahjong.model.MahjongTile.valueOf(addedKanTile.getMahjongTile().name());
            melds.add(new MeldView(List.copyOf(tiles), List.copyOf(faceDownFlags), claimTileIndex, claimYawOffset, addedKanView));
        });
        return List.copyOf(melds);
    }

    @Override
    public List<ScoringStick> scoringSticks(UUID playerId) {
        RiichiPlayerState player = this.seatPlayer(playerId);
        return player == null ? List.of() : List.copyOf(player.getSticks());
    }

    @Override
    public List<top.ellan.mahjong.model.MahjongTile> doraIndicators() {
        List<top.ellan.mahjong.model.MahjongTile> tiles = new ArrayList<>(this.engine.getDoraIndicators().size());
        this.engine.getDoraIndicators().forEach(tile -> tiles.add(top.ellan.mahjong.model.MahjongTile.valueOf(tile.getMahjongTile().name())));
        return List.copyOf(tiles);
    }

    @Override
    public List<top.ellan.mahjong.model.MahjongTile> uraDoraIndicators() {
        List<top.ellan.mahjong.model.MahjongTile> tiles = new ArrayList<>(this.engine.getUraDoraIndicators().size());
        this.engine.getUraDoraIndicators().forEach(tile -> tiles.add(top.ellan.mahjong.model.MahjongTile.valueOf(tile.getMahjongTile().name())));
        return List.copyOf(tiles);
    }

    @Override
    public RoundResolution lastResolution() {
        return this.engine.getLastResolution();
    }

    @Override
    public ReactionOptions availableReactions(UUID playerId) {
        return this.isReactionPending(playerId) ? this.engine.availableReactions(playerId.toString()) : null;
    }

    @Override
    public boolean hasPendingReaction() {
        return this.engine.getPendingReaction() != null;
    }

    @Override
    public boolean isReactionPending(UUID playerId) {
        if (playerId == null || this.engine.getPendingReaction() == null) {
            return false;
        }
        String playerKey = playerId.toString();
        return this.engine.getPendingReaction().getOptions().containsKey(playerKey)
            && !this.engine.getPendingReaction().getResponses().containsKey(playerKey);
    }

    @Override
    public String pendingReactionFingerprint() {
        return String.valueOf(this.engine.getPendingReaction());
    }

    @Override
    public String pendingReactionTileKey() {
        return this.engine.getPendingReaction() == null ? "" : this.engine.getPendingReaction().getTile().getId().toString();
    }

    @Override
    public boolean isCurrentPlayer(UUID playerId) {
        return playerId != null && this.engine.getCurrentPlayer().getUuid().equals(playerId.toString());
    }

    @Override
    public boolean canSelectHandTile(UUID playerId, int tileIndex) {
        RiichiPlayerState player = this.seatPlayer(playerId);
        if (player == null || tileIndex < 0 || tileIndex >= player.getHands().size()) {
            return false;
        }
        if (!this.engine.getStarted() || this.engine.getPendingReaction() != null) {
            return false;
        }
        if (!this.isCurrentPlayer(playerId)) {
            return false;
        }
        if (player.getRiichi() || player.getDoubleRiichi()) {
            top.ellan.mahjong.riichi.model.TileInstance drawn = player.getLastDrawnTile();
            return drawn != null && player.getHands().get(tileIndex).getId().equals(drawn.getId());
        }
        return true;
    }

    @Override
    public boolean canDeclareRiichi(UUID playerId) {
        RiichiPlayerState player = this.seatPlayer(playerId);
        return player != null && this.isCurrentPlayer(playerId) && player.isRiichiable();
    }

    @Override
    public boolean canDeclareKan(UUID playerId) {
        return this.canDeclareConcealedKan(playerId) || this.canDeclareAddedKan(playerId);
    }

    @Override
    public boolean canDeclareConcealedKan(UUID playerId) {
        RiichiPlayerState player = this.seatPlayer(playerId);
        return player != null && this.isCurrentPlayer(playerId) && player.getCanAnkan();
    }

    @Override
    public boolean canDeclareAddedKan(UUID playerId) {
        RiichiPlayerState player = this.seatPlayer(playerId);
        return player != null && this.isCurrentPlayer(playerId) && player.getCanKakan();
    }

    @Override
    public boolean canDeclareKyuushu(UUID playerId) {
        return playerId != null && this.engine.canKyuushuKyuuhai(playerId.toString());
    }

    @Override
    public boolean canDeclareTsumo(UUID playerId) {
        return playerId != null && this.engine.canDeclareTsumo(playerId.toString());
    }

    @Override
    public List<Integer> suggestedRiichiIndices(UUID playerId) {
        RiichiPlayerState player = this.seatPlayer(playerId);
        if (player == null || !player.isRiichiable()) {
            return List.of();
        }
        java.util.Set<top.ellan.mahjong.riichi.model.MahjongTile> discardables = new java.util.LinkedHashSet<>();
        player.getTilePairsForRiichi().forEach(pair -> discardables.add(pair.getFirst()));
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < player.getHands().size(); i++) {
            if (discardables.contains(player.getHands().get(i).getMahjongTile())) {
                indices.add(i);
            }
        }
        return List.copyOf(indices);
    }

    @Override
    public List<String> suggestedKanTiles(UUID playerId) {
        RiichiPlayerState player = this.seatPlayer(playerId);
        if (player == null) {
            return List.of();
        }
        java.util.Set<String> suggestions = new java.util.LinkedHashSet<>();
        suggestions.addAll(this.suggestedConcealedKanTiles(playerId));
        suggestions.addAll(this.suggestedAddedKanTiles(playerId));
        return List.copyOf(suggestions);
    }

    @Override
    public List<String> suggestedConcealedKanTiles(UUID playerId) {
        RiichiPlayerState player = this.seatPlayer(playerId);
        if (player == null) {
            return List.of();
        }
        java.util.Set<String> suggestions = new java.util.LinkedHashSet<>();
        player.getTilesCanAnkan().forEach(tile -> suggestions.add(tile.getMahjongTile().name().toLowerCase(Locale.ROOT)));
        return List.copyOf(suggestions);
    }

    @Override
    public List<String> suggestedAddedKanTiles(UUID playerId) {
        RiichiPlayerState player = this.seatPlayer(playerId);
        if (player == null || !player.getCanKakan()) {
            return List.of();
        }
        java.util.Set<String> suggestions = new java.util.LinkedHashSet<>();
        player.getHands().forEach(tile -> {
            boolean matchesMeld = player.getFuuroList().stream()
                .anyMatch(fuuro -> fuuro.getTileInstances().stream().anyMatch(existing -> existing.getMahjongTile() == tile.getMahjongTile()));
            if (matchesMeld) {
                suggestions.add(tile.getMahjongTile().name().toLowerCase(Locale.ROOT));
            }
        });
        return List.copyOf(suggestions);
    }

    @Override
    public List<String> suggestedDiscardTiles(UUID playerId) {
        RiichiPlayerState player = this.seatPlayer(playerId);
        if (player == null || !this.isCurrentPlayer(playerId)) {
            return List.of();
        }
        List<String> suggestions = new ArrayList<>();
        this.suggestedDiscardSuggestions(playerId).forEach(suggestion -> {
            String tileName = suggestion.getTile().name().toLowerCase(Locale.ROOT);
            if (!suggestions.contains(tileName)) {
                suggestions.add(tileName);
            }
        });
        return List.copyOf(suggestions);
    }

    @Override
    public List<RiichiDiscardSuggestion> suggestedDiscardSuggestions(UUID playerId) {
        RiichiPlayerState player = this.seatPlayer(playerId);
        if (player == null || !this.isCurrentPlayer(playerId)) {
            return List.of();
        }
        return List.copyOf(player.discardSuggestions());
    }
    private RiichiPlayerState seatPlayer(UUID playerId) {
        return playerId == null ? null : this.engine.seatPlayer(playerId.toString());
    }
}
