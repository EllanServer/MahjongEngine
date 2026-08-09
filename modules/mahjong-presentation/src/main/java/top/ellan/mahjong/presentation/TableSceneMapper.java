package top.ellan.mahjong.presentation;

import top.ellan.mahjong.application.projection.TableProjection;

/** CPU-only conversion from generic rule views to desired scene state. */
@FunctionalInterface
public interface TableSceneMapper {
    SceneGraph map(TableProjection projection);
}
