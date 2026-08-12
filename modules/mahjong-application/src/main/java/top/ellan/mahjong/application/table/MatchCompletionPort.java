package top.ellan.mahjong.application.table;

/** Receives a match completion only after its terminal persistence boundary has drained. */
@FunctionalInterface
public interface MatchCompletionPort {
    MatchCompletionPort NONE = completion -> {};

    void completed(MatchCompletion completion);
}
