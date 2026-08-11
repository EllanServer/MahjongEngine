package top.ellan.mahjong.application.table.actor;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.application.table.TableActorSnapshot;

/** Publishes the immutable actor state consumed outside the actor dispatcher. */
final class TableActorSnapshotPublisher {
    private final AtomicReference<TableActorSnapshot> published = new AtomicReference<>();

    TableActorSnapshot current() {
        return published.get();
    }

    TableActionResult result(TableActionCode code, String reason) {
        TableActorSnapshot snapshot = published.get();
        return new TableActionResult(
                code,
                snapshot == null ? 0 : snapshot.revision(),
                reason == null ? "" : reason);
    }

    void publish(TableActorStateMachine stateMachine, int mailboxSize, boolean ruleInFlight) {
        TableActorSnapshot next = stateMachine.snapshot(mailboxSize, ruleInFlight);
        TableActorSnapshot current = published.get();
        if (current == null
                || next.revision() != current.revision()
                || next.lifecycle() != current.lifecycle()
                || !Objects.equals(next.failureCode(), current.failureCode())) {
            published.set(next);
        }
    }
}
