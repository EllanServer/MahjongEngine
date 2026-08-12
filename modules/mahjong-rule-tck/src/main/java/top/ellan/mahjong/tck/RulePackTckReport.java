package top.ellan.mahjong.tck;

/**
 * Successful provider contract verification summary.
 *
 * @param playersVerified number of player projections verified
 * @param legalActionsVerified number of legal actions verified
 * @param rejectedActionsVerified number of guaranteed-illegal actions verified
 * @param snapshotsVerified number of deterministic snapshots verified
 */
public record RulePackTckReport(
        int playersVerified,
        int legalActionsVerified,
        int rejectedActionsVerified,
        int snapshotsVerified) {
    /**
     * Creates a complete successful verification summary.
     *
     * @param playersVerified number of player projections verified
     * @param legalActionsVerified number of legal actions verified
     * @param rejectedActionsVerified number of guaranteed-illegal actions verified
     * @param snapshotsVerified number of deterministic snapshots verified
     */
    public RulePackTckReport {
        if (playersVerified < 2
                || legalActionsVerified < 1
                || rejectedActionsVerified < 1
                || snapshotsVerified < 1) {
            throw new IllegalArgumentException("Incomplete TCK report");
        }
    }
}
