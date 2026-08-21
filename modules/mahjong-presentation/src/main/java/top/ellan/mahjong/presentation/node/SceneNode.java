package top.ellan.mahjong.presentation.node;

/** Closed node family lets backends handle public and viewer-conditional CE state exhaustively. */
public sealed interface SceneNode
        permits FurnitureNode,
                InteractionNode,
                PrivateFurnitureNode,
                HudNode,
                CameraNode,
                ActionLabelNode,
                ActionFurnitureNode {
    SceneNodeId id();

    SceneVisibility visibility();

    boolean worldBacked();
}
