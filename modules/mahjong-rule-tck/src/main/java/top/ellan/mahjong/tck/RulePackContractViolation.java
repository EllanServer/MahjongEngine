package top.ellan.mahjong.tck;

/** A provider violated a deterministic or immutable SPI requirement. */
public final class RulePackContractViolation extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a provider-contract violation with a diagnostic message.
     *
     * @param message description of the violated deterministic or immutable contract
     */
    public RulePackContractViolation(String message) {
        super(message);
    }
}
