package top.ellan.mahjong.table.core.round;

import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.model.SeatWind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class SichuanPreparationFlow {
    private final Map<UUID, LinkedHashSet<Integer>> selectedExchangeTileIndices;
    private final Set<UUID> confirmedExchangePlayers;
    private final Map<UUID, SichuanSuit> chosenMissingSuits;
    private final Supplier<GbTableRoundController.SichuanPreparationPhase> phaseSupplier;
    private final Consumer<GbTableRoundController.SichuanPreparationPhase> phaseUpdater;
    private SichuanExchangeDirection exchangeDirection = SichuanExchangeDirection.CLOCKWISE;

    SichuanPreparationFlow(
        Map<UUID, LinkedHashSet<Integer>> selectedExchangeTileIndices,
        Set<UUID> confirmedExchangePlayers,
        Map<UUID, SichuanSuit> chosenMissingSuits,
        Supplier<GbTableRoundController.SichuanPreparationPhase> phaseSupplier,
        Consumer<GbTableRoundController.SichuanPreparationPhase> phaseUpdater
    ) {
        this.selectedExchangeTileIndices = selectedExchangeTileIndices;
        this.confirmedExchangePlayers = confirmedExchangePlayers;
        this.chosenMissingSuits = chosenMissingSuits;
        this.phaseSupplier = phaseSupplier;
        this.phaseUpdater = phaseUpdater;
    }

    void reset(boolean enabled, int dicePoints) {
        this.selectedExchangeTileIndices.clear();
        this.confirmedExchangePlayers.clear();
        this.chosenMissingSuits.clear();
        this.exchangeDirection = SichuanExchangeDirection.fromDicePoints(dicePoints);
        this.setPhase(enabled ? GbTableRoundController.SichuanPreparationPhase.DING_QUE : GbTableRoundController.SichuanPreparationPhase.NONE);
    }

    boolean isPreparationPhase() {
        return this.isExchangePhase() || this.isDingQuePhase();
    }

    boolean isExchangePhase() {
        return this.phase() == GbTableRoundController.SichuanPreparationPhase.EXCHANGE;
    }

    boolean isDingQuePhase() {
        return this.phase() == GbTableRoundController.SichuanPreparationPhase.DING_QUE;
    }

    boolean isActionPending(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        if (this.isExchangePhase()) {
            return !this.confirmedExchangePlayers.contains(playerId);
        }
        if (this.isDingQuePhase()) {
            return !this.chosenMissingSuits.containsKey(playerId);
        }
        return false;
    }

    boolean isExchangeAvailable(UUID playerId, boolean activePlayer) {
        return activePlayer && this.isExchangePhase() && !this.confirmedExchangePlayers.contains(playerId);
    }

    boolean canChooseMissingSuit(UUID playerId, boolean activePlayer) {
        return activePlayer && this.isDingQuePhase() && !this.chosenMissingSuits.containsKey(playerId);
    }

    boolean chooseMissingSuit(UUID playerId, String suitToken, boolean activePlayer, int activeSeatCount) {
        if (!this.canChooseMissingSuit(playerId, activePlayer)) {
            return false;
        }
        SichuanSuit suit = SichuanSuit.fromKey(suitToken);
        if (suit == null) {
            return false;
        }
        this.chosenMissingSuits.put(playerId, suit);
        if (this.chosenMissingSuits.size() == activeSeatCount) {
            this.setPhase(GbTableRoundController.SichuanPreparationPhase.ACTIVE);
        }
        return true;
    }

    boolean canSelectExchangeTile(UUID playerId, int tileIndex, boolean activePlayer, List<MahjongTile> hand) {
        if (!this.isExchangeAvailable(playerId, activePlayer) || hand == null || tileIndex < 0 || tileIndex >= hand.size()) {
            return false;
        }
        MahjongTile tile = hand.get(tileIndex);
        SichuanSuit tileSuit = SichuanSuit.fromTile(tile);
        if (tileSuit == null) {
            return false;
        }
        LinkedHashSet<Integer> selected = this.selectedExchangeTileIndices.get(playerId);
        if (selected == null || selected.isEmpty() || selected.contains(tileIndex)) {
            return true;
        }
        if (selected.size() >= 3) {
            return false;
        }
        Integer firstIndex = selected.iterator().next();
        if (firstIndex == null || firstIndex < 0 || firstIndex >= hand.size()) {
            return true;
        }
        return tileSuit == SichuanSuit.fromTile(hand.get(firstIndex));
    }

    boolean handleHandTileClick(UUID playerId, int tileIndex, boolean activePlayer, List<MahjongTile> hand, int activeSeatCount) {
        if (!this.canSelectExchangeTile(playerId, tileIndex, activePlayer, hand)) {
            return false;
        }
        LinkedHashSet<Integer> selected = this.selectedExchangeTileIndices.computeIfAbsent(playerId, ignored -> new LinkedHashSet<>());
        if (selected.contains(tileIndex)) {
            selected.remove(tileIndex);
            if (selected.isEmpty()) {
                this.selectedExchangeTileIndices.remove(playerId);
            }
            return true;
        }
        if (selected.size() >= 3) {
            return false;
        }
        MahjongTile tile = hand.get(tileIndex);
        if (!selected.isEmpty()) {
            Integer firstIndex = selected.iterator().next();
            MahjongTile firstTile = hand.get(firstIndex);
            if (SichuanSuit.fromTile(firstTile) != SichuanSuit.fromTile(tile)) {
                return false;
            }
        }
        selected.add(tileIndex);
        return selected.size() != 3 || this.confirmExchangeSelection(playerId, selected, activeSeatCount);
    }

    boolean submitExchangeSelection(UUID playerId, List<Integer> tileIndices, boolean activePlayer, List<MahjongTile> hand, int activeSeatCount) {
        if (!this.isExchangeAvailable(playerId, activePlayer) || tileIndices == null || tileIndices.size() != 3 || hand == null || hand.isEmpty()) {
            return false;
        }
        LinkedHashSet<Integer> normalized = new LinkedHashSet<>(tileIndices);
        if (normalized.size() != 3) {
            return false;
        }
        LinkedHashSet<Integer> ordered = new LinkedHashSet<>(normalized.stream().sorted().toList());
        SichuanSuit selectedSuit = null;
        for (Integer index : ordered) {
            if (index == null || index < 0 || index >= hand.size()) {
                return false;
            }
            SichuanSuit tileSuit = SichuanSuit.fromTile(hand.get(index));
            if (tileSuit == null) {
                return false;
            }
            if (selectedSuit == null) {
                selectedSuit = tileSuit;
            } else if (selectedSuit != tileSuit) {
                return false;
            }
        }
        this.selectedExchangeTileIndices.put(playerId, ordered);
        return this.confirmExchangeSelection(playerId, ordered, activeSeatCount);
    }

    boolean exchangeReadyToApply(int activeSeatCount) {
        return this.isExchangePhase() && this.confirmedExchangePlayers.size() == activeSeatCount;
    }

    List<Integer> selectedHandTileIndices(UUID playerId) {
        if (!this.isExchangePhase() || playerId == null) {
            return List.of();
        }
        LinkedHashSet<Integer> selected = this.selectedExchangeTileIndices.get(playerId);
        if (selected == null || selected.isEmpty()) {
            return List.of();
        }
        List<Integer> indices = new ArrayList<>(selected);
        Collections.sort(indices);
        return List.copyOf(indices);
    }

    boolean applyExchange(EnumMap<SeatWind, UUID> seats, Map<UUID, List<MahjongTile>> hands, ExchangeHandRebuilder rebuilder) {
        EnumMap<SeatWind, List<Integer>> indicesBySeat = new EnumMap<>(SeatWind.class);
        EnumMap<SeatWind, List<MahjongTile>> outgoingBySeat = new EnumMap<>(SeatWind.class);
        for (SeatWind wind : SeatWind.values()) {
            UUID playerId = seats.get(wind);
            if (playerId == null) {
                continue;
            }
            List<Integer> indices = this.selectedHandTileIndices(playerId);
            if (indices.size() != 3) {
                return false;
            }
            indicesBySeat.put(wind, indices);
            List<MahjongTile> hand = hands.getOrDefault(playerId, List.of());
            List<MahjongTile> outgoing = new ArrayList<>(indices.size());
            for (Integer index : indices) {
                outgoing.add(hand.get(index));
            }
            outgoingBySeat.put(wind, List.copyOf(outgoing));
        }
        EnumMap<SeatWind, List<MahjongTile>> incomingBySeat = new EnumMap<>(SeatWind.class);
        for (SeatWind source : SeatWind.values()) {
            List<MahjongTile> outgoing = outgoingBySeat.get(source);
            if (outgoing != null) {
                incomingBySeat.put(this.exchangeDirection.targetOf(source), outgoing);
            }
        }
        for (SeatWind wind : SeatWind.values()) {
            UUID playerId = seats.get(wind);
            if (playerId != null) {
                rebuilder.rebuild(playerId, indicesBySeat.getOrDefault(wind, List.of()), incomingBySeat.getOrDefault(wind, List.of()));
            }
        }
        this.selectedExchangeTileIndices.clear();
        this.confirmedExchangePlayers.clear();
        this.setPhase(GbTableRoundController.SichuanPreparationPhase.DING_QUE);
        return true;
    }

    private boolean confirmExchangeSelection(UUID playerId, LinkedHashSet<Integer> selected, int activeSeatCount) {
        if (playerId == null || selected == null || selected.size() != 3) {
            return false;
        }
        this.selectedExchangeTileIndices.put(playerId, new LinkedHashSet<>(selected));
        this.confirmedExchangePlayers.add(playerId);
        return this.confirmedExchangePlayers.size() <= activeSeatCount;
    }

    private GbTableRoundController.SichuanPreparationPhase phase() {
        return this.phaseSupplier.get();
    }

    private void setPhase(GbTableRoundController.SichuanPreparationPhase phase) {
        this.phaseUpdater.accept(phase);
    }

    @FunctionalInterface
    interface ExchangeHandRebuilder {
        void rebuild(UUID playerId, List<Integer> removedIndices, List<MahjongTile> incomingTiles);
    }
}
