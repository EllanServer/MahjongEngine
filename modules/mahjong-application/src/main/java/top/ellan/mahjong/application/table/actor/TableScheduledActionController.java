package top.ellan.mahjong.application.table.actor;

import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import top.ellan.mahjong.application.concurrent.Cancellable;
import top.ellan.mahjong.application.concurrent.TaskScheduler;
import top.ellan.mahjong.spi.ScheduledRuleAction;

/** Owns exactly one revision-bound rule timer for one table. */
final class TableScheduledActionController {
    private final TaskScheduler scheduler;
    private final TableActorInbox inbox;
    private final Runnable wakeActor;
    private Optional<ScheduledRuleAction> current = Optional.empty();
    private Cancellable task = () -> false;

    TableScheduledActionController(
            TaskScheduler scheduler, TableActorInbox inbox, Runnable wakeActor) {
        this.scheduler = scheduler;
        this.inbox = inbox;
        this.wakeActor = wakeActor;
    }

    Optional<String> install(
            Optional<ScheduledRuleAction> action, long revision, boolean enabled) {
        current = action;
        return schedule(revision, enabled);
    }

    Optional<String> resume(long revision, boolean enabled) {
        return schedule(revision, enabled);
    }

    void pause() {
        task.cancel();
        task = () -> false;
    }

    void clear() {
        pause();
        current = Optional.empty();
        inbox.clearScheduledTriggers();
    }

    private Optional<String> schedule(long revision, boolean enabled) {
        pause();
        if (!enabled || current.isEmpty()) {
            return Optional.empty();
        }
        ScheduledRuleAction scheduled = current.orElseThrow();
        try {
            task = scheduler.schedule(
                    () -> {
                        inbox.offerScheduled(revision, scheduled);
                        wakeActor.run();
                    },
                    scheduled.delay());
            return Optional.empty();
        } catch (RejectedExecutionException failure) {
            return Optional.of("scheduled-deadline-capacity");
        }
    }
}
