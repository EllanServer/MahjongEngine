package top.ellan.mahjong.table.action;

import top.ellan.mahjong.model.MahjongVariant;
import top.ellan.mahjong.riichi.ReactionOptions;
import top.ellan.mahjong.table.core.TableSessionContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import kotlin.Pair;
import net.kyori.adventure.text.format.NamedTextColor;

public final class PlayerActionSnapshotFactory {
    private final TableSessionContext session;

    public PlayerActionSnapshotFactory(TableSessionContext session) {
        this.session = session;
    }

    public PlayerActionSnapshot capture(UUID viewerId) {
        if (viewerId == null || this.session.isSpectator(viewerId) || !this.session.hasRoundController() || !this.session.isStarted()) {
            return new PlayerActionSnapshot(PlayerActionPhase.NONE, "", 0, 0, false, List.of());
        }
        String menuState = this.session.viewerActionMenuState(viewerId);
        ReactionOptions options = this.session.availableReactions(viewerId);
        if (options != null && this.session.hasPendingReaction()) {
            return new PlayerActionSnapshot(PlayerActionPhase.REACTION, menuState, 0, 0, false, this.reactionActions(options, menuState));
        }
        if (this.session.canChooseSichuanMissingSuit(viewerId)) {
            return new PlayerActionSnapshot(PlayerActionPhase.SICHUAN_DING_QUE, menuState, 0, 0, false, this.sichuanMissingSuitActions());
        }
        if (this.session.isSichuanExchangePhase(viewerId)) {
            int selectedCount = this.session.selectedHandTileIndices(viewerId).size();
            return new PlayerActionSnapshot(
                PlayerActionPhase.SICHUAN_EXCHANGE,
                menuState,
                selectedCount,
                3,
                selectedCount >= 3,
                List.of()
            );
        }
        if (!this.session.isCurrentPlayer(viewerId)) {
            return new PlayerActionSnapshot(PlayerActionPhase.WAITING, menuState, 0, 0, false, List.of());
        }
        return new PlayerActionSnapshot(PlayerActionPhase.TURN, menuState, 0, 0, false, this.turnActions(viewerId, menuState));
    }

    private List<PlayerActionEntry> reactionActions(ReactionOptions options, String menuState) {
        List<PlayerActionEntry> actions = new ArrayList<>(6);
        if ("react-chii".equals(menuState)) {
            for (Pair<top.ellan.mahjong.riichi.model.MahjongTile, top.ellan.mahjong.riichi.model.MahjongTile> pair : options.getChiiPairs()) {
                String first = pair.getFirst().name().toLowerCase(Locale.ROOT);
                String second = pair.getSecond().name().toLowerCase(Locale.ROOT);
                actions.add(new PlayerActionEntry(
                    PlayerActionId.CHII,
                    "react:chii:" + first + ":" + second,
                    "table.action.chii",
                    NamedTextColor.GREEN,
                    false,
                    List.of(pair.getFirst().name(), pair.getSecond().name())
                ));
            }
            actions.add(new PlayerActionEntry(PlayerActionId.MENU_BACK, "menu:back", "table.action.back", NamedTextColor.GRAY, true, List.of()));
            return List.copyOf(actions);
        }
        if (options.getCanRon()) {
            actions.add(new PlayerActionEntry(PlayerActionId.RON, "react:ron", "table.action.ron", NamedTextColor.RED, false, List.of()));
        }
        if (options.getCanPon()) {
            actions.add(new PlayerActionEntry(PlayerActionId.PON, "react:pon", "table.action.pon", NamedTextColor.YELLOW, false, List.of()));
        }
        if (options.getCanMinkan()) {
            actions.add(new PlayerActionEntry(PlayerActionId.MINKAN, "react:minkan", "table.action.minkan", NamedTextColor.DARK_AQUA, false, List.of()));
        }
        if (options.getChiiPairs().size() == 1) {
            Pair<top.ellan.mahjong.riichi.model.MahjongTile, top.ellan.mahjong.riichi.model.MahjongTile> pair = options.getChiiPairs().get(0);
            String first = pair.getFirst().name().toLowerCase(Locale.ROOT);
            String second = pair.getSecond().name().toLowerCase(Locale.ROOT);
            actions.add(new PlayerActionEntry(
                PlayerActionId.CHII,
                "react:chii:" + first + ":" + second,
                "table.action.chii",
                NamedTextColor.GREEN,
                false,
                List.of(pair.getFirst().name(), pair.getSecond().name())
            ));
        } else if (!options.getChiiPairs().isEmpty()) {
            actions.add(new PlayerActionEntry(PlayerActionId.MENU_REACT_CHII, "menu:react-chii", "table.action.chii", NamedTextColor.GREEN, true, List.of()));
        }
        actions.add(new PlayerActionEntry(PlayerActionId.SKIP, "react:skip", "table.action.skip", NamedTextColor.GRAY, false, List.of()));
        return List.copyOf(actions);
    }

    private List<PlayerActionEntry> turnActions(UUID viewerId, String menuState) {
        List<PlayerActionEntry> actions = new ArrayList<>(12);
        if (this.session.canDeclareTsumo(viewerId)) {
            actions.add(new PlayerActionEntry(PlayerActionId.TSUMO, "turn:tsumo", "table.action.tsumo", NamedTextColor.GOLD, false, List.of()));
        }

        List<PlayerActionEntry> flowerActions = new ArrayList<>();
        if (this.session.canDeclareFlower(viewerId)) {
            List<top.ellan.mahjong.model.MahjongTile> hand = this.session.hand(viewerId);
            for (Integer tileIndex : this.session.suggestedFlowerIndices(viewerId)) {
                if (tileIndex == null || tileIndex < 0 || tileIndex >= hand.size()) {
                    continue;
                }
                flowerActions.add(new PlayerActionEntry(
                    PlayerActionId.FLOWER,
                    "turn:flower:" + tileIndex,
                    "table.action.flower",
                    NamedTextColor.GREEN,
                    false,
                    List.of(hand.get(tileIndex).name(), String.valueOf(tileIndex))
                ));
            }
        }
        if ("turn-flower".equals(menuState)) {
            actions.addAll(flowerActions);
            actions.add(new PlayerActionEntry(PlayerActionId.MENU_BACK, "menu:back", "table.action.back", NamedTextColor.GRAY, true, List.of()));
            return List.copyOf(actions);
        } else if (flowerActions.size() == 1) {
            actions.add(flowerActions.get(0));
        } else if (!flowerActions.isEmpty()) {
            actions.add(new PlayerActionEntry(PlayerActionId.MENU_TURN_FLOWER, "menu:turn-flower", "table.action.flower", NamedTextColor.GREEN, true, List.of()));
        }

        List<PlayerActionEntry> kanActions = new ArrayList<>();
        if (this.session.canDeclareConcealedKan(viewerId)) {
            for (String tileName : this.session.suggestedConcealedKanTiles(viewerId)) {
                kanActions.add(new PlayerActionEntry(PlayerActionId.ANKAN, "turn:kan:" + tileName.toLowerCase(Locale.ROOT), "table.action.ankan", NamedTextColor.DARK_AQUA, false, List.of(tileName.toUpperCase(Locale.ROOT))));
            }
        }
        if (this.session.canDeclareAddedKan(viewerId)) {
            for (String tileName : this.session.suggestedAddedKanTiles(viewerId)) {
                kanActions.add(new PlayerActionEntry(PlayerActionId.KAKAN, "turn:kan:" + tileName.toLowerCase(Locale.ROOT), "table.action.kakan", NamedTextColor.DARK_AQUA, false, List.of(tileName.toUpperCase(Locale.ROOT))));
            }
        }
        if ("turn-kan".equals(menuState)) {
            actions.addAll(kanActions);
            actions.add(new PlayerActionEntry(PlayerActionId.MENU_BACK, "menu:back", "table.action.back", NamedTextColor.GRAY, true, List.of()));
        } else if (kanActions.size() == 1) {
            actions.add(kanActions.get(0));
        } else if (!kanActions.isEmpty()) {
            actions.add(new PlayerActionEntry(PlayerActionId.MENU_TURN_KAN, "menu:turn-kan", "table.action.kan", NamedTextColor.DARK_AQUA, true, List.of()));
        }

        List<PlayerActionEntry> riichiActions = new ArrayList<>();
        if (this.session.canDeclareRiichi(viewerId)) {
            List<top.ellan.mahjong.model.MahjongTile> hand = this.session.hand(viewerId);
            for (Integer discardIndex : this.session.suggestedRiichiIndices(viewerId)) {
                if (discardIndex == null || discardIndex < 0 || discardIndex >= hand.size()) {
                    continue;
                }
                riichiActions.add(new PlayerActionEntry(
                    PlayerActionId.RIICHI,
                    "turn:riichi:" + discardIndex,
                    "table.action.riichi",
                    NamedTextColor.LIGHT_PURPLE,
                    false,
                    List.of(hand.get(discardIndex).name(), String.valueOf(discardIndex))
                ));
            }
        }
        if ("turn-riichi".equals(menuState)) {
            actions.addAll(riichiActions);
            actions.add(new PlayerActionEntry(PlayerActionId.MENU_BACK, "menu:back", "table.action.back", NamedTextColor.GRAY, true, List.of()));
        } else if (riichiActions.size() == 1) {
            actions.add(riichiActions.get(0));
        } else if (!riichiActions.isEmpty()) {
            actions.add(new PlayerActionEntry(PlayerActionId.MENU_TURN_RIICHI, "menu:turn-riichi", "table.action.riichi", NamedTextColor.LIGHT_PURPLE, true, List.of()));
        }

        if (this.session.canDeclareKyuushu(viewerId)) {
            actions.add(new PlayerActionEntry(PlayerActionId.KYUUSHU, "turn:kyuushu", "table.action.kyuushu", NamedTextColor.RED, false, List.of()));
        }
        return List.copyOf(actions);
    }

    private List<PlayerActionEntry> sichuanMissingSuitActions() {
        return List.of(
            new PlayerActionEntry(PlayerActionId.DINGQUE_WAN, "turn:dingque:wan", "table.action.dingque_wan", NamedTextColor.RED, false, List.of()),
            new PlayerActionEntry(PlayerActionId.DINGQUE_TONG, "turn:dingque:tong", "table.action.dingque_tong", NamedTextColor.GOLD, false, List.of()),
            new PlayerActionEntry(PlayerActionId.DINGQUE_SUO, "turn:dingque:suo", "table.action.dingque_suo", NamedTextColor.GREEN, false, List.of())
        );
    }

}
