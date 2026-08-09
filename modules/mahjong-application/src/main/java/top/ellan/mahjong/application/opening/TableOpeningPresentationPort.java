package top.ellan.mahjong.application.opening;

/**
 * Non-blocking opening-animation boundary. Implementations may enqueue scene work but must not
 * mutate platform entities, wait, or perform I/O on the table actor thread.
 */
@FunctionalInterface
public interface TableOpeningPresentationPort {
    TableOpeningPresentationPort NONE = batch -> {};

    void present(TableOpeningBatch batch);
}
