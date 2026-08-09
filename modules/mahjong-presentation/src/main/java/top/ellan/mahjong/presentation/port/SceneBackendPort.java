package top.ellan.mahjong.presentation.port;

import top.ellan.mahjong.presentation.scene.SceneDiff;

/** Non-blocking scene backend; concrete CE mutations are region-thread work. */
@FunctionalInterface
public interface SceneBackendPort {
    void submit(SceneDiff diff);
}
