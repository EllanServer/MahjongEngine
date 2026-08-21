package top.ellan.mahjong.presentation.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.spi.PlayerId;

class ActionLabelNodesTest {
    private static final PlayerId VIEWER = new PlayerId(UUID.randomUUID());
    private static final PlayerId OTHER = new PlayerId(UUID.randomUUID());
    private static final SceneNodeId ID = new SceneNodeId("label/action/test");
    private static final SceneTransform TRANSFORM =
            new SceneTransform(0, 1, 0, 0, 0, 0, 1);

    @Test
    void staticTextUsesViewerConditionalCeFurniture() {
        SceneNode node = ActionLabelNodes.create(
                ID,
                SceneVisibility.privateTo(VIEWER),
                "action.declare_tsumo",
                TRANSFORM,
                true);

        ActionFurnitureNode furniture = assertInstanceOf(ActionFurnitureNode.class, node);
        assertTrue(furniture.worldBacked());
        assertEquals("mahjongpaper:action_label_declare_tsumo", furniture.assetId());
        assertEquals("emphasized", furniture.variant());
    }

    @Test
    void semanticArgumentsRemainOnTheDynamicPacketBoundary() {
        SceneNode node = ActionLabelNodes.create(
                ID,
                SceneVisibility.privateTo(VIEWER),
                "action.chii:tile.m2:tile.m3",
                TRANSFORM,
                false);

        assertInstanceOf(ActionLabelNode.class, node);
    }

    @Test
    void undeclaredRuleLabelStaysOnTheSafeDynamicBoundary() {
        SceneNode node = ActionLabelNodes.create(
                ID,
                SceneVisibility.privateTo(VIEWER),
                "action.third_party_choice",
                TRANSFORM,
                false);

        assertInstanceOf(ActionLabelNode.class, node);
    }

    @Test
    void staticFurnitureCannotAcquireASecondViewer() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ActionLabelNodes.create(
                        ID,
                        SceneVisibility.privateTo(Set.of(VIEWER, OTHER)),
                        "action.draw",
                        TRANSFORM,
                        false));
    }
}
