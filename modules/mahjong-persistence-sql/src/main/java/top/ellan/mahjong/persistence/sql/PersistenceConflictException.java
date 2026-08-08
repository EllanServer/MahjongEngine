package top.ellan.mahjong.persistence.sql;

/** Indicates an idempotency or hash mismatch, not transient database unavailability. */
public final class PersistenceConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PersistenceConflictException(String message) {
        super(message);
    }
}
