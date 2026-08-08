package top.ellan.mahjong.application;

/** Explicit actor limits; no queue silently grows with traffic. */
public record TableActorConfig(
        int mailboxCapacity,
        int maxMessagesPerRun,
        int maxLegalActionsPerPlayer,
        int maxEventsPerAction) {
    public static final TableActorConfig DEFAULT = new TableActorConfig(128, 32, 64, 64);

    public TableActorConfig(
            int mailboxCapacity, int maxMessagesPerRun, int maxLegalActionsPerPlayer) {
        this(mailboxCapacity, maxMessagesPerRun, maxLegalActionsPerPlayer, 64);
    }

    public TableActorConfig {
        if (mailboxCapacity < 8
                || maxMessagesPerRun < 1
                || maxLegalActionsPerPlayer < 1
                || maxEventsPerAction < 1) {
            throw new IllegalArgumentException("Invalid TableActor limits");
        }
    }
}
