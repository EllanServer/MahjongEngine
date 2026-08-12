package top.ellan.mahjong.spi;

/** Whether a transition was accepted and whether it reached a lifecycle boundary. */
public enum TransitionDisposition {
    /** Action was rejected and state remains unchanged. */
    REJECTED,
    /** Action was accepted without ending a hand or match. */
    ACCEPTED,
    /** Action was accepted and ended the current hand. */
    ROUND_ENDED,
    /** Action was accepted and ended the complete match. */
    MATCH_ENDED
}
