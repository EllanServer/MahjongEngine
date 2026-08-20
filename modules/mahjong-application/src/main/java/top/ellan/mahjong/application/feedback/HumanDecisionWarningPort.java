package top.ellan.mahjong.application.feedback;

/**
 * Non-blocking boundary for "you are about to be played automatically" notices.
 *
 * <p>Implementations must enqueue platform work and return; they must never perform entity
 * mutations, I/O, or waits, because the warning fires on the shared deadline scheduler.
 */
@FunctionalInterface
public interface HumanDecisionWarningPort {
    HumanDecisionWarningPort NONE = warning -> {};

    void publish(HumanDecisionWarning warning);
}
