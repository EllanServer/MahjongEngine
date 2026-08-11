package top.ellan.mahjong.craftengine.opening;

import java.util.ArrayList;
import java.util.List;
import top.ellan.mahjong.presentation.node.FurnitureNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.presentation.node.SceneTransform;
import top.ellan.mahjong.presentation.node.SceneVisibility;
import top.ellan.mahjong.spi.RuleDiceRoll;
import top.ellan.mahjong.spi.RuleOpeningPresentation;

/** Maps declared rolls to four stable CE slot assets; CE variants own every spatial transform. */
final class OpeningDiceFrameFactory {
    private static final List<SceneNodeId> IDS = List.of(
            new SceneNodeId("opening/die/0"),
            new SceneNodeId("opening/die/1"),
            new SceneNodeId("opening/die/2"),
            new SceneNodeId("opening/die/3"));

    private final CraftEngineOpeningAnimationConfig config;

    OpeningDiceFrameFactory(CraftEngineOpeningAnimationConfig config) {
        this.config = config;
    }

    List<SceneNodeId> managedIds() {
        return IDS;
    }

    List<FurnitureNode> frame(
            RuleOpeningPresentation opening,
            int activeRoll,
            boolean revealActiveRoll) {
        if (activeRoll < 0 || activeRoll >= opening.rolls().size()) {
            throw new IllegalArgumentException("active roll is outside the opening");
        }
        int count = (activeRoll + 1) * 2;
        boolean doubleLayout = activeRoll > 0;
        ArrayList<FurnitureNode> nodes = new ArrayList<>(count);
        int flatIndex = 0;
        for (int rollIndex = 0; rollIndex <= activeRoll; rollIndex++) {
            RuleDiceRoll roll = opening.rolls().get(rollIndex);
            boolean revealed = rollIndex < activeRoll || revealActiveRoll;
            for (int dieIndex = 0; dieIndex < roll.points().size(); dieIndex++) {
                int finalPoint = roll.points().get(dieIndex);
                nodes.add(new FurnitureNode(
                        IDS.get(flatIndex),
                        SceneVisibility.publicToAll(),
                        config.asset(flatIndex),
                        revealed
                                ? config.variant(doubleLayout, finalPoint)
                                : config.rollingVariant(doubleLayout),
                        new SceneTransform(0, 0, 0, 0, 0, 0, 1)));
                flatIndex++;
            }
        }
        return List.copyOf(nodes);
    }
}
