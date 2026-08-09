package top.ellan.mahjong.runtime.common;

/** Fail-closed validation or lifecycle error scoped to one rule pack. */
public final class RulePackException extends Exception {
    private static final long serialVersionUID = 1L;

    public RulePackException(String message) {
        super(message);
    }

    public RulePackException(String message, Throwable cause) {
        super(message, cause);
    }
}
