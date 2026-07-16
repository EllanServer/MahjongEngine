package top.ellan.mahjong.render.layout;

import top.ellan.mahjong.model.SeatWind;

public final class WallLayout {
    private WallLayout() {
    }

    public static SeatWind wallSeat(int tileIndex) {
        return wallSeat(tileIndex, 34);
    }

    public static SeatWind wallSeat(int tileIndex, int tilesPerSide) {
        requireTilesPerSide(tilesPerSide);
        int wallCapacity = tilesPerSide * SeatWind.values().length;
        return SeatWind.fromIndex(Math.floorMod(tileIndex, wallCapacity) / tilesPerSide);
    }

    public static int wallColumn(int tileIndex) {
        return wallColumn(tileIndex, 34);
    }

    public static int wallColumn(int tileIndex, int tilesPerSide) {
        requireTilesPerSide(tilesPerSide);
        return Math.floorMod(tileIndex, tilesPerSide) / 2;
    }

    public static int wallLayer(int tileIndex) {
        return wallLayer(tileIndex, 34);
    }

    public static int wallLayer(int tileIndex, int tilesPerSide) {
        requireTilesPerSide(tilesPerSide);
        return 1 - Math.floorMod(tileIndex, tilesPerSide) % 2;
    }

    /** Returns the lower slot supporting an upper tile, or {@code -1} at an unpaired side end. */
    public static int supportingLowerSlot(int tileIndex, int tilesPerSide) {
        requireTilesPerSide(tilesPerSide);
        int wallCapacity = tilesPerSide * SeatWind.values().length;
        int normalized = Math.floorMod(tileIndex, wallCapacity);
        int sideOffset = normalized % tilesPerSide;
        if (wallLayer(normalized, tilesPerSide) != 1 || sideOffset + 1 >= tilesPerSide) {
            return -1;
        }
        return normalized + 1;
    }

    private static void requireTilesPerSide(int tilesPerSide) {
        if (tilesPerSide <= 0) {
            throw new IllegalArgumentException("tilesPerSide must be positive");
        }
    }
}
