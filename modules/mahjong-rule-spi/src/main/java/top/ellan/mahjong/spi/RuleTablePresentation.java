package top.ellan.mahjong.spi;

import java.util.Objects;
import java.util.Optional;

/** Typed table-wide geometry metadata shared without exposing a concrete rules implementation. */
public record RuleTablePresentation(
        int seatCount,
        RuleWallPresentation wall,
        int discardsPerRow,
        Optional<SeatId> dealerSeat,
        Optional<SeatId> currentSeat,
        Optional<TileInstanceId> lastDiscard,
        Optional<RuleOpeningPresentation> opening) {
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
