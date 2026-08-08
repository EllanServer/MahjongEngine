package top.ellan.mahjong.presentation;

/** Closed node family lets backends enforce public-world/private-client separation exhaustively. */
public sealed interface SceneNode
        permits FurnitureNode, InteractionNode, PrivateItemNode, HudNode, CameraNode {
    SceneNodeId id();

    SceneVisibility visibility();

    boolean worldBacked();
}
