package top.ellan.mahjong.application.table.actor;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import top.ellan.mahjong.application.projection.SceneProjectionPort;
import top.ellan.mahjong.application.projection.TableProjection;
import top.ellan.mahjong.domain.TableLifecycle;

/** Publishes derived scene state without allowing a renderer failure to block the table. */
final class TableProjectionPublisher {
    private final SceneProjectionPort projector;
    private final AtomicReference<TableProjection> latest = new AtomicReference<>();

    TableProjectionPublisher(SceneProjectionPort projector) {
        this.projector = java.util.Objects.requireNonNull(projector, "projector");
    }

    Optional<TableProjection> latest() {
        return Optional.ofNullable(latest.get());
    }

    String install(TableProjection projection) {
        latest.set(projection);
        return publish(projection);
    }

    String republishLifecycle(TableLifecycle lifecycle) {
        TableProjection current = latest.get();
        if (current == null) {
            return "";
        }
        TableProjection updated =
                new TableProjection(
                        current.tableId(),
                        current.revision(),
                        lifecycle,
                        current.publicView(),
                        current.privateViews(),
                        current.authorizedActions());
        latest.set(updated);
        return publish(updated);
    }

    private String publish(TableProjection projection) {
        try {
            projector.publish(projection);
            return "";
        } catch (RuntimeException failure) {
            return "projection-" + failure.getClass().getSimpleName();
        }
    }
}
