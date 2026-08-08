package top.ellan.mahjong.application;

/** Highest sequence durably committed for a match. */
public record PersistAck(long committedSequence) {
    public PersistAck {
        if (committedSequence < 0) {
            throw new IllegalArgumentException("Committed sequence must be non-negative");
        }
    }
}
