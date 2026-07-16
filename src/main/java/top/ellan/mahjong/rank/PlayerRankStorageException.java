package top.ellan.mahjong.rank;

public final class PlayerRankStorageException extends Exception {
    public enum Reason {
        SYNC_PENDING,
        CORRUPT_REMOTE_DATA,
        DATABASE_FAILURE,
        UNAVAILABLE
    }

    private final Reason reason;

    public PlayerRankStorageException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public PlayerRankStorageException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return this.reason;
    }
}
