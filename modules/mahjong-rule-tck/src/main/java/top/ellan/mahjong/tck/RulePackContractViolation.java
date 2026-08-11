package top.ellan.mahjong.tck;

/** A provider violated a deterministic or immutable SPI requirement. */
public final class RulePackContractViolation extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public RulePackContractViolation(String message) {
        super(message);
    }
}
