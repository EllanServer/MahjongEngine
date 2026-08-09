package top.ellan.mahjong.craftengine.opening;

import java.util.ArrayList;
import java.util.List;
import top.ellan.mahjong.presentation.node.FurnitureNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.presentation.node.SceneTransform;
import top.ellan.mahjong.presentation.node.SceneVisibility;
import top.ellan.mahjong.spi.RuleDiceRoll;
import top.ellan.mahjong.spi.RuleOpeningPresentation;

/** Allocation-bounded mapping from declared rule rolls to four stable CE furniture nodes. */
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
            int previewFrame,
            boolean revealActiveRoll) {
        if (activeRoll < 0 || activeRoll >= opening.rolls().size()) {
            throw new IllegalArgumentException("active roll is outside the opening");
        }
        int count = (activeRoll + 1) * 2;
        ArrayList<FurnitureNode> nodes = new ArrayList<>(count);
        double start = -(count - 1) * config.diceSpacing() / 2.0D;
        int flatIndex = 0;
        for (int rollIndex = 0; rollIndex <= activeRoll; rollIndex++) {
            RuleDiceRoll roll = opening.rolls().get(rollIndex);
            boolean revealed = rollIndex < activeRoll || revealActiveRoll;
            for (int dieIndex = 0; dieIndex < roll.points().size(); dieIndex++) {
                int finalPoint = roll.points().get(dieIndex);
                int point = revealed
                        ? finalPoint
                        : previewPoint(finalPoint, flatIndex, previewFrame);
                double yaw = revealed
                        ? (flatIndex % 2 == 0 ? -8.0D : 8.0D)
                        : Math.floorMod(previewFrame * 97 + flatIndex * 53, 360);
                nodes.add(new FurnitureNode(
                        IDS.get(flatIndex),
                        SceneVisibility.publicToAll(),
                        config.asset(point),
                        new SceneTransform(
                                start + flatIndex * config.diceSpacing(),
                                config.tableHeight(),
                                0,
                                yaw,
                                0,
                                0,
                                1)));
                flatIndex++;
            }
        }
        return List.copyOf(nodes);
    }

    private static int previewPoint(int finalPoint, int dieIndex, int previewFrame) {
        int candidate = 1 + Math.floorMod(finalPoint + dieIndex + previewFrame * 2, 6);
        return candidate == finalPoint ? 1 + candidate % 6 : candidate;
    }
}
