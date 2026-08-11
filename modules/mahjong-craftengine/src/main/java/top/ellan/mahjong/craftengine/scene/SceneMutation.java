package top.ellan.mahjong.craftengine.scene;

import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;

/** One revision-epoch-bound CraftEngine node mutation. */
record SceneMutation(SceneNodeId id, SceneNode desired, long applyEpoch) {}
