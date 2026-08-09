package top.ellan.mahjong.application.concurrent;

/** Handle for one event-driven deadline. */
@FunctionalInterface
public interface Cancellable {
    boolean cancel();
}
