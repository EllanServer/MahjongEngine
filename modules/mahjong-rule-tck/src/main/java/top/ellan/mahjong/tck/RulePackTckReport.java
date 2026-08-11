package top.ellan.mahjong.tck;

/** Successful provider contract verification summary. */
public record RulePackTckReport(
        int playersVerified,
        int legalActionsVerified,
        int rejectedActionsVerified,
        int snapshotsVerified) {
    public RulePackTckReport {
        if (playersVerified < 2
                || legalActionsVerified < 1
                || rejectedActionsVerified < 1
                || snapshotsVerified < 1) {
            throw new IllegalArgumentException("Incomplete TCK report");
        }
    }
}
