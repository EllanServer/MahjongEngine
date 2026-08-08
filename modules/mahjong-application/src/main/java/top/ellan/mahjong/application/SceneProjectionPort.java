package top.ellan.mahjong.application;

/** Latest-only rendering boundary. Implementations must not block the actor. */
@FunctionalInterface
public interface SceneProjectionPort {
    void publish(TableProjection projection);
}
