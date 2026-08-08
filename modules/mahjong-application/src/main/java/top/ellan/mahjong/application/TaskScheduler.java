package top.ellan.mahjong.application;

import java.time.Duration;

/** Bounded scheduling port used for deadlines, retries and delayed batching. */
@FunctionalInterface
public interface TaskScheduler {
    Cancellable schedule(Runnable task, Duration delay);
}
