package top.ellan.mahjong.spi;

/** Whether a transition was accepted and whether it reached a lifecycle boundary. */
public enum TransitionDisposition {
    REJECTED,
    ACCEPTED,
    ROUND_ENDED,
    MATCH_ENDED
}
