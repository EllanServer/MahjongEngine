package top.ellan.mahjong.application;

/** Handle for one event-driven deadline. */
@FunctionalInterface
public interface Cancellable {
    boolean cancel();
}
