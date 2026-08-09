package top.ellan.mahjong.spi;

import java.util.Objects;

/**
 * Shared real-table placement for open melds.
 *
 * <p>A rule pack supplies only semantic facts: the owning seat, source seat, base meld width,
 * and whether the tile is ordinary, claimed, or added. This class turns those facts into the
 * left/middle/right source marker, sideways rotation, and added-kong stack used by every table
 * renderer. Keeping this arithmetic in the parent-loaded SPI prevents rule packs from growing
 * separate presentation engines.</p>
 */
public final class RuleMeldPresentation {
    private static final int SLOTS_PER_MELD = 4;

    private RuleMeldPresentation() {}

    /**
     * Resolves one open-meld tile without allocating intermediate collections.
     *
     * @param meldIndex zero-based meld group index for the owning seat
     * @param baseTileCount distinct horizontal positions, three for pung/chow/added kong and
     *     four for a directly declared kong
     * @param seatCount active seats around the table
     * @param owner seat that owns the meld
     * @param source seat whose discard formed the meld
     * @param role semantic role of this tile
     * @param ordinaryOrdinal zero-based order among ordinary tiles, or {@code -1} for claimed
     *     and added tiles
     */
    public static RuleTilePresentation tile(
            int meldIndex,
            int baseTileCount,
            int seatCount,
            SeatId owner,
            SeatId source,
            RuleMeldTileRole role,
            int ordinaryOrdinal) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(source, "source");
        return tile(
                meldIndex,
                baseTileCount,
                seatCount,
                owner.value(),
                source.value(),
                role,
                ordinaryOrdinal);
    }

    /** Primitive-seat overload for allocation-free frame projection hot paths. */
    public static RuleTilePresentation tile(
            int meldIndex,
            int baseTileCount,
            int seatCount,
            int ownerSeat,
            int sourceSeat,
            RuleMeldTileRole role,
            int ordinaryOrdinal) {
        if (meldIndex < 0) {
            throw new IllegalArgumentException("meldIndex must be non-negative");
        }
        if (baseTileCount != 3 && baseTileCount != 4) {
            throw new IllegalArgumentException("an open meld has three or four base positions");
        }
        if (seatCount < 2 || seatCount > 4) {
            throw new IllegalArgumentException("seatCount must be between two and four");
        }
        Objects.requireNonNull(role, "role");
        if (ownerSeat < 0 || ownerSeat >= seatCount || sourceSeat < 0 || sourceSeat >= seatCount) {
            throw new IllegalArgumentException("meld references an absent seat");
        }
        int sourceDelta = Math.floorMod(sourceSeat - ownerSeat, seatCount);
        if (sourceDelta == 0) {
            throw new IllegalArgumentException("a player cannot call their own tile");
        }
        int claimedSlot = sourceSlot(sourceDelta, seatCount, baseTileCount);
        int displaySlot;
        if (role == RuleMeldTileRole.ORDINARY) {
            if (ordinaryOrdinal < 0 || ordinaryOrdinal >= baseTileCount - 1) {
                throw new IllegalArgumentException("ordinary tile ordinal is outside the meld");
            }
            displaySlot = ordinaryOrdinal < claimedSlot
                    ? ordinaryOrdinal
                    : ordinaryOrdinal + 1;
        } else {
            if (ordinaryOrdinal != -1) {
                throw new IllegalArgumentException("claimed and added tiles have no ordinary ordinal");
            }
            displaySlot = claimedSlot;
        }
        int layoutIndex = Math.addExact(
                Math.multiplyExact(meldIndex, SLOTS_PER_MELD), displaySlot);
        return switch (role) {
            case ORDINARY -> RuleTilePresentation.natural(layoutIndex);
            case CLAIMED -> RuleTilePresentation.clockwise(layoutIndex);
            case ADDED -> RuleTilePresentation.stackedClockwise(layoutIndex);
        };
    }

    private static int sourceSlot(int sourceDelta, int seatCount, int baseTileCount) {
        if (sourceDelta == seatCount - 1) {
            return 0;
        }
        if (sourceDelta == 1) {
            return baseTileCount - 1;
        }
        return (baseTileCount - 1) / 2;
    }
}
