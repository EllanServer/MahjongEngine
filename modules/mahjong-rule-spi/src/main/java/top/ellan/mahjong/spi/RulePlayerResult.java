package top.ellan.mahjong.spi;

import java.util.Arrays;
import java.util.Objects;

/**
 * One immutable, provider-authored terminal result row.
 *
 * @param playerId player represented by this result row
 * @param seatId player's fixed seat in the completed match
 * @param placement one-based terminal placement
 * @param score rule-defined terminal score
 * @param rankingPointsMilli ranking points expressed in thousandths
 * @param canonicalPayload bounded provider-defined terminal payload
 */
public record RulePlayerResult(
        PlayerId playerId,
        SeatId seatId,
        int placement,
        long score,
        long rankingPointsMilli,
        byte[] canonicalPayload) {
    private static final int MAX_PAYLOAD_BYTES = 1_048_576;

    public RulePlayerResult {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(seatId, "seatId");
        if (placement < 1 || placement > 8) {
            throw new IllegalArgumentException("Placement must be between 1 and 8");
        }
        canonicalPayload = Objects.requireNonNull(canonicalPayload, "canonicalPayload");
        if (canonicalPayload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Player result payload exceeds 1 MiB");
        }
        canonicalPayload = canonicalPayload.clone();
    }

    @Override
    public byte[] canonicalPayload() {
        return canonicalPayload.clone();
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof RulePlayerResult result
                        && playerId.equals(result.playerId)
                        && seatId.equals(result.seatId)
                        && placement == result.placement
                        && score == result.score
                        && rankingPointsMilli == result.rankingPointsMilli
                        && Arrays.equals(canonicalPayload, result.canonicalPayload);
    }

    @Override
    public int hashCode() {
        int hash = Objects.hash(playerId, seatId, placement, score, rankingPointsMilli);
        return 31 * hash + Arrays.hashCode(canonicalPayload);
    }
}
