package top.ellan.mahjong.spi;

import java.util.Objects;
import java.util.Optional;

/**
 * Typed table-wide geometry metadata shared without exposing a concrete rules implementation.
 *
 * @param seatCount number of seats present at the table
 * @param wall physical wall layout exposed by the rule pack
 * @param discardsPerRow maximum number of discards in one river row
 * @param dealerSeat current dealer, when the rule pack has assigned one
 * @param currentSeat seat whose turn is currently active, when applicable
 * @param lastDiscard most recently discarded tile, when one is available
 * @param opening physical wall-opening metadata, when the rule exposes it
 */
public record RuleTablePresentation(
        int seatCount,
        RuleWallPresentation wall,
        int discardsPerRow,
        Optional<SeatId> dealerSeat,
        Optional<SeatId> currentSeat,
        Optional<TileInstanceId> lastDiscard,
        Optional<RuleOpeningPresentation> opening) {
    /**
     * Creates table metadata without optional wall-opening presentation.
     *
     * @param seatCount number of seats present at the table
     * @param wall physical wall layout exposed by the rule pack
     * @param discardsPerRow maximum number of discards in one river row
     * @param dealerSeat current dealer, when the rule pack has assigned one
     * @param currentSeat seat whose turn is currently active, when applicable
     * @param lastDiscard most recently discarded tile, when one is available
     */
    public RuleTablePresentation(
            int seatCount,
            RuleWallPresentation wall,
            int discardsPerRow,
            Optional<SeatId> dealerSeat,
            Optional<SeatId> currentSeat,
            Optional<TileInstanceId> lastDiscard) {
        this(
                seatCount,
                wall,
                discardsPerRow,
                dealerSeat,
                currentSeat,
                lastDiscard,
                Optional.empty());
    }

    /**
     * Creates validated table-wide geometry metadata.
     *
     * @param seatCount number of seats present at the table
     * @param wall physical wall layout exposed by the rule pack
     * @param discardsPerRow maximum number of discards in one river row
     * @param dealerSeat current dealer, when the rule pack has assigned one
     * @param currentSeat seat whose turn is currently active, when applicable
     * @param lastDiscard most recently discarded tile, when one is available
     * @param opening physical wall-opening metadata, when the rule exposes it
     */
    public RuleTablePresentation {
        if (seatCount < 2 || seatCount > 4) {
            throw new IllegalArgumentException("seatCount must be between two and four");
        }
        Objects.requireNonNull(wall, "wall");
        if (wall.stackCountsBySide().size() != seatCount) {
            throw new IllegalArgumentException("Wall sides must match the table seat count");
        }
        if (discardsPerRow < 1 || discardsPerRow > 12) {
            throw new IllegalArgumentException("discardsPerRow must be between one and twelve");
        }
        dealerSeat = Objects.requireNonNull(dealerSeat, "dealerSeat");
        currentSeat = Objects.requireNonNull(currentSeat, "currentSeat");
        lastDiscard = Objects.requireNonNull(lastDiscard, "lastDiscard");
        opening = Objects.requireNonNull(opening, "opening");
        dealerSeat.ifPresent(seat -> requireSeat(seat, seatCount));
        currentSeat.ifPresent(seat -> requireSeat(seat, seatCount));
        opening.ifPresent(value -> {
            requireSeat(value.openDoorSeat(), seatCount);
            if (value.breakStackOffset() >= wall.totalStacks()) {
                throw new IllegalArgumentException(
                        "Opening wall break must be inside the declared physical wall");
            }
        });
    }

    private static void requireSeat(SeatId seat, int seatCount) {
        if (seat.value() >= seatCount) {
            throw new IllegalArgumentException("table presentation references an absent seat");
        }
    }
}
