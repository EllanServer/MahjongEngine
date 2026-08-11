package top.ellan.mahjong.plugin.dialog;

import java.util.List;
import java.util.Objects;
import top.ellan.mahjong.application.projection.SceneProjectionPort;
import top.ellan.mahjong.application.projection.TableProjection;

/** Non-blocking fan-out used to feed both the CE scene and native-dialog observer. */
public final class CompositeSceneProjectionPort implements SceneProjectionPort {
    private final List<SceneProjectionPort> delegates;

    public CompositeSceneProjectionPort(SceneProjectionPort... delegates) {
        this.delegates = List.of(delegates);
        this.delegates.forEach(delegate -> Objects.requireNonNull(delegate, "delegate"));
    }

    @Override
    public void publish(TableProjection projection) {
        for (SceneProjectionPort delegate : delegates) {
            delegate.publish(projection);
        }
    }
}
