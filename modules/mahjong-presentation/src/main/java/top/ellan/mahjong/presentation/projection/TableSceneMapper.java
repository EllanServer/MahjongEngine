package top.ellan.mahjong.presentation.projection;

import top.ellan.mahjong.application.projection.TableProjection;
import top.ellan.mahjong.presentation.scene.SceneGraph;

/** CPU-only conversion from generic rule views to desired scene state. */
@FunctionalInterface
public interface TableSceneMapper {
    SceneGraph map(TableProjection projection);
}
