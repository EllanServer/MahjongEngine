package top.ellan.mahjong.application.feedback;

/**
 * Non-blocking transient-feedback boundary. Implementations must enqueue platform work and return;
 * they must never perform entity mutations, I/O, or waits on the table actor thread.
 */
@FunctionalInterface
public interface TablePresentationCuePort {
    TablePresentationCuePort NONE = batch -> {};

    void publish(TableCueBatch batch);
}
