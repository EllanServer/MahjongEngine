package top.ellan.mahjong.render.scene;

import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.model.MahjongVariant;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.display.DisplayEntities;
import top.ellan.mahjong.render.layout.DiscardLayout;
import top.ellan.mahjong.render.layout.TableRenderLayout;
import top.ellan.mahjong.render.layout.WallLayout;
import top.ellan.mahjong.render.TableRenderSubject;
import top.ellan.mahjong.riichi.model.ScoringStick;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.Location;

/**
 * Shared geometry helpers, records and tile-spawn utilities used by the table
 * scene renderers. All members are {@code public static} so the region
 * renderers can call them directly.
 */
public final class TableGeometry {
    private TableGeometry() {
    }

    /** Horizontal-only offset (x/z) applied to table locations. */
    public static record Offset(double x, double z) {
    }

    public static Location add(Location location, Offset offset) {
        return location.clone().add(offset.x(), 0.0D, offset.z());
    }

    public static Offset add(Offset first, Offset second) {
        return new Offset(first.x() + second.x(), first.z() + second.z());
    }

    public static Offset multiply(Offset offset, double factor) {
        return new Offset(offset.x() * factor, offset.z() * factor);
    }

    public static Location displayCenter(TableRenderSubject session) {
        return session.center().add(0.0D, TableRenderConstants.DISPLAY_CENTER_Y_OFFSET, 0.0D);
    }

    public static Location toLocation(TableRenderSubject session, TableRenderLayout.Point point) {
        Location origin = session.center();
        return new Location(origin.getWorld(), point.x(), point.y(), point.z());
    }

    public static float seatYaw(SeatWind wind) {
        return DiscardLayout.seatYaw(wind);
    }

    public static SeatWind displayDirection(SeatWind wind) {
        return switch (wind) {
            case EAST -> SeatWind.EAST;
            case SOUTH -> SeatWind.NORTH;
            case WEST -> SeatWind.WEST;
            case NORTH -> SeatWind.SOUTH;
        };
    }

    public static Location handDirectionBase(Location center, SeatWind wind) {
        return switch (displayDirection(wind)) {
            case EAST -> center.clone().add(TableRenderConstants.HAND_DIRECTION_OFFSET, 0.0D, 0.0D);
            case SOUTH -> center.clone().add(0.0D, 0.0D, TableRenderConstants.HAND_DIRECTION_OFFSET);
            case WEST -> center.clone().add(-TableRenderConstants.HAND_DIRECTION_OFFSET, 0.0D, 0.0D);
            case NORTH -> center.clone().add(0.0D, 0.0D, -TableRenderConstants.HAND_DIRECTION_OFFSET);
        };
    }

    public static Location meldStart(Location center, SeatWind wind) {
        double halfHeight = TableRenderConstants.TILE_HEIGHT / 2.0D;
        return switch (displayDirection(wind)) {
            case EAST -> center.clone().add(TableRenderConstants.HALF_TABLE_LENGTH_NO_BORDER - halfHeight, 0.0D, -TableRenderConstants.HALF_TABLE_LENGTH_NO_BORDER);
            case SOUTH -> center.clone().add(TableRenderConstants.HALF_TABLE_LENGTH_NO_BORDER, 0.0D, TableRenderConstants.HALF_TABLE_LENGTH_NO_BORDER - halfHeight);
            case WEST -> center.clone().add(-TableRenderConstants.HALF_TABLE_LENGTH_NO_BORDER + halfHeight, 0.0D, TableRenderConstants.HALF_TABLE_LENGTH_NO_BORDER);
            case NORTH -> center.clone().add(-TableRenderConstants.HALF_TABLE_LENGTH_NO_BORDER, 0.0D, -TableRenderConstants.HALF_TABLE_LENGTH_NO_BORDER + halfHeight);
        };
    }

    public static Location meldStartByDisplayDirection(Location center, SeatWind direction) {
        for (SeatWind wind : SeatWind.values()) {
            if (displayDirection(wind) == direction) {
                return meldStart(center, wind);
            }
        }
        throw new IllegalStateException("Missing meld start for display direction: " + direction);
    }

    public static Offset offsetAcrossSeat(SeatWind wind, double amount) {
        return switch (displayDirection(wind)) {
            case EAST -> new Offset(0.0D, amount);
            case SOUTH -> new Offset(-amount, 0.0D);
            case WEST -> new Offset(0.0D, -amount);
            case NORTH -> new Offset(amount, 0.0D);
        };
    }

    public static Offset offsetTowardSeatFront(SeatWind wind, double amount) {
        return switch (displayDirection(wind)) {
            case EAST -> new Offset(amount, 0.0D);
            case SOUTH -> new Offset(0.0D, amount);
            case WEST -> new Offset(-amount, 0.0D);
            case NORTH -> new Offset(0.0D, -amount);
        };
    }

    public static Offset offsetTowardTableCenter(SeatWind wind, double amount) {
        return offsetTowardSeatFront(wind, -amount);
    }

    public static double centeredOffset(int size, int index, double step) {
        return (index - (size - 1) / 2.0D) * step;
    }

    public static Location centeredCuboid(Location center, double width, double height, double depth) {
        return center.clone().add(-width / 2.0D, 0.0D, -depth / 2.0D);
    }

    public static TableBounds tableBoundsFromTiles(Location center) {
        Location eastMeldStart = meldStartByDisplayDirection(center, SeatWind.EAST);
        Location southMeldStart = meldStartByDisplayDirection(center, SeatWind.SOUTH);
        Location westMeldStart = meldStartByDisplayDirection(center, SeatWind.WEST);
        Location northMeldStart = meldStartByDisplayDirection(center, SeatWind.NORTH);
        double halfTileHeight = TableRenderConstants.TILE_HEIGHT / 2.0D;
        double minX = northMeldStart.getX();
        double maxX = southMeldStart.getX();
        double minZ = eastMeldStart.getZ();
        double maxZ = westMeldStart.getZ();
        double centerX = (westMeldStart.getX() - halfTileHeight + maxX) / 2.0D;
        double centerZ = (northMeldStart.getZ() - halfTileHeight + maxZ) / 2.0D;
        return new TableBounds(centerX, centerZ, minX, maxX, minZ, maxZ);
    }

    public static int wallBreakTileIndex(TableRenderSubject session) {
        int seatCount = SeatWind.values().length;
        int dicePoints = session.dicePoints();
        int breakDice = session.breakDicePoints();
        int openDoorIndex = Math.floorMod(session.dealerSeat().index() + dicePoints - 1, seatCount);
        int openingStackCount = usesDeadWall(session) ? breakDice : dicePoints + breakDice;
        return Math.floorMod(
            openDoorIndex * wallTilesPerSide(session) + openingStackCount * 2,
            wallCapacity(session)
        );
    }

    public static boolean usesDeadWall(TableRenderSubject session) {
        return variant(session) == MahjongVariant.RIICHI;
    }

    public static int wallCapacity(TableRenderSubject session) {
        return switch (variant(session)) {
            case RIICHI -> 136;
            case GB -> 144;
            case SICHUAN -> 108;
        };
    }

    public static int wallTilesPerSide(TableRenderSubject session) {
        return wallCapacity(session) / SeatWind.values().length;
    }

    public static int deadWallAnchorSlot(TableRenderSubject session) {
        return Math.floorMod(wallBreakTileIndex(session) - TableRenderConstants.DEAD_WALL_SIZE, TableRenderConstants.TOTAL_WALL_TILES);
    }

    public static int doraIndicatorDeadWallIndex(int kanCount, int indicatorIndex) {
        return (4 - indicatorIndex) * 2 + kanCount;
    }

    public static Location wallSlotLocation(Location center, int wallSlot) {
        return wallSlotLocation(center, wallSlot, TableRenderConstants.WALL_TILES_PER_SIDE);
    }

    public static Location wallSlotLocation(Location center, int wallSlot, int tilesPerSide) {
        SeatWind wind = WallLayout.wallSeat(wallSlot, tilesPerSide);
        int stackIndex = WallLayout.wallColumn(wallSlot, tilesPerSide);
        double stackWidth = stackIndex * TableRenderConstants.WALL_TILE_STEP;
        int stackCount = (tilesPerSide + 1) / 2;
        double startingPos = (stackCount * TableRenderConstants.TILE_WIDTH) / 2.0D - TableRenderConstants.TILE_HEIGHT;
        double yOffset = TableRenderConstants.FLAT_TILE_Y + wallLayerYOffset(WallLayout.wallLayer(wallSlot, tilesPerSide));
        return switch (displayDirection(wind)) {
            case EAST -> center.clone().add(TableRenderConstants.WALL_DIRECTION_OFFSET, yOffset, -startingPos + stackWidth);
            case SOUTH -> center.clone().add(startingPos - stackWidth, yOffset, TableRenderConstants.WALL_DIRECTION_OFFSET);
            case WEST -> center.clone().add(-TableRenderConstants.WALL_DIRECTION_OFFSET, yOffset, startingPos - stackWidth);
            case NORTH -> center.clone().add(-startingPos + stackWidth, yOffset, -TableRenderConstants.WALL_DIRECTION_OFFSET);
        };
    }

    private static MahjongVariant variant(TableRenderSubject session) {
        MahjongVariant variant = session.currentVariant();
        return variant == null ? MahjongVariant.RIICHI : variant;
    }

    public static double wallLayerYOffset(int layer) {
        return layer * TableRenderConstants.TILE_DEPTH + (layer == 1 ? TableRenderConstants.TILE_PADDING : 0.0D);
    }

    public static Offset deadWallGapOffset(SeatWind wind) {
        return switch (displayDirection(wind)) {
            case EAST -> new Offset(0.0D, TableRenderConstants.DEAD_WALL_GAP);
            case SOUTH -> new Offset(-TableRenderConstants.DEAD_WALL_GAP, 0.0D);
            case WEST -> new Offset(0.0D, -TableRenderConstants.DEAD_WALL_GAP);
            case NORTH -> new Offset(TableRenderConstants.DEAD_WALL_GAP, 0.0D);
        };
    }

    public static Offset deadWallLineOffset(SeatWind wind) {
        double amount = TableRenderConstants.WALL_TILE_STEP;
        return switch (displayDirection(wind)) {
            case EAST -> new Offset(0.0D, -amount);
            case SOUTH -> new Offset(amount, 0.0D);
            case WEST -> new Offset(0.0D, amount);
            case NORTH -> new Offset(-amount, 0.0D);
        };
    }

    public static Offset deadWallCornerShift(SeatWind wind) {
        return switch (displayDirection(wind)) {
            case EAST -> new Offset(0.0D, TableRenderConstants.TILE_WIDTH);
            case SOUTH -> new Offset(-TableRenderConstants.TILE_WIDTH, 0.0D);
            case WEST -> new Offset(0.0D, -TableRenderConstants.TILE_WIDTH);
            case NORTH -> new Offset(TableRenderConstants.TILE_WIDTH, 0.0D);
        };
    }

    public static Offset verticalTileOffset(SeatWind wind) {
        double amount = TableRenderConstants.TILE_WIDTH + TableRenderConstants.TILE_PADDING;
        return switch (displayDirection(wind)) {
            case EAST -> new Offset(0.0D, amount);
            case SOUTH -> new Offset(-amount, 0.0D);
            case WEST -> new Offset(0.0D, -amount);
            case NORTH -> new Offset(amount, 0.0D);
        };
    }

    public static Offset halfVerticalTileOffset(SeatWind wind) {
        return multiply(verticalTileOffset(wind), 0.5D);
    }

    public static Offset horizontalTileOffset(SeatWind wind) {
        double amount = TableRenderConstants.TILE_HEIGHT + TableRenderConstants.TILE_PADDING;
        return switch (displayDirection(wind)) {
            case EAST -> new Offset(0.0D, amount);
            case SOUTH -> new Offset(-amount, 0.0D);
            case WEST -> new Offset(0.0D, -amount);
            case NORTH -> new Offset(amount, 0.0D);
        };
    }

    public static Offset halfHorizontalTileOffset(SeatWind wind) {
        return multiply(horizontalTileOffset(wind), 0.5D);
    }

    public static Offset horizontalTileGravityOffset(SeatWind wind) {
        double amount = (TableRenderConstants.TILE_HEIGHT - TableRenderConstants.TILE_WIDTH) / 2.0D;
        return switch (displayDirection(wind)) {
            case EAST -> new Offset(amount, 0.0D);
            case SOUTH -> new Offset(0.0D, amount);
            case WEST -> new Offset(-amount, 0.0D);
            case NORTH -> new Offset(0.0D, -amount);
        };
    }

    public static Offset cornerStickMeldOffset(SeatWind wind, int firstStackCount) {
        double amount = firstStackCount * TableRenderConstants.STICK_DEPTH + Math.max(0, firstStackCount - 1) * TableRenderConstants.TILE_PADDING;
        return switch (displayDirection(wind)) {
            case EAST -> new Offset(0.0D, amount);
            case SOUTH -> new Offset(-amount, 0.0D);
            case WEST -> new Offset(0.0D, -amount);
            case NORTH -> new Offset(amount, 0.0D);
        };
    }

    public static Location riichiStickCenter(Location center, SeatWind wind) {
        double halfWidthOfSixTiles = TableRenderConstants.TILE_WIDTH * TableRenderConstants.DISCARDS_PER_ROW / 2.0D;
        double paddingFromCenter = halfWidthOfSixTiles - TableRenderConstants.STICK_DEPTH / 2.0D;
        return switch (displayDirection(wind)) {
            case EAST -> center.clone().add(paddingFromCenter, TableRenderConstants.STICK_Y_OFFSET, 0.0D);
            case SOUTH -> center.clone().add(0.0D, TableRenderConstants.STICK_Y_OFFSET, paddingFromCenter);
            case WEST -> center.clone().add(-paddingFromCenter, TableRenderConstants.STICK_Y_OFFSET, 0.0D);
            case NORTH -> center.clone().add(0.0D, TableRenderConstants.STICK_Y_OFFSET, -paddingFromCenter);
        };
    }

    public static boolean riichiStickLongOnX(SeatWind wind) {
        SeatWind direction = displayDirection(wind);
        return direction == SeatWind.SOUTH || direction == SeatWind.NORTH;
    }

    public static Location cornerStickCenter(Location center, SeatWind wind, int index) {
        int stackIndex = index / TableRenderConstants.STICKS_PER_STACK;
        int stickIndex = index % TableRenderConstants.STICKS_PER_STACK;
        double halfWidthOfStick = TableRenderConstants.STICK_WIDTH / 2.0D;
        double halfDepthOfStick = TableRenderConstants.STICK_DEPTH / 2.0D;
        Location start = switch (displayDirection(wind)) {
            case EAST -> center.clone().add(TableRenderConstants.HALF_TABLE_LENGTH_NO_BORDER - halfWidthOfStick, 0.0D, -TableRenderConstants.HALF_TABLE_LENGTH_NO_BORDER + halfDepthOfStick);
            case SOUTH -> center.clone().add(TableRenderConstants.HALF_TABLE_LENGTH_NO_BORDER - halfDepthOfStick, 0.0D, TableRenderConstants.HALF_TABLE_LENGTH_NO_BORDER - halfWidthOfStick);
            case WEST -> center.clone().add(-TableRenderConstants.HALF_TABLE_LENGTH_NO_BORDER + halfWidthOfStick, 0.0D, TableRenderConstants.HALF_TABLE_LENGTH_NO_BORDER - halfDepthOfStick);
            case NORTH -> center.clone().add(-TableRenderConstants.HALF_TABLE_LENGTH_NO_BORDER + halfDepthOfStick, 0.0D, -TableRenderConstants.HALF_TABLE_LENGTH_NO_BORDER + halfWidthOfStick);
        };
        return add(start, multiply(cornerStickOffset(wind), stickIndex)).add(0.0D, TableRenderConstants.STICK_Y_OFFSET + stackIndex * (TableRenderConstants.STICK_HEIGHT + TableRenderConstants.TILE_PADDING), 0.0D);
    }

    public static boolean cornerStickLongOnX(SeatWind wind) {
        SeatWind direction = displayDirection(wind);
        return direction == SeatWind.EAST || direction == SeatWind.WEST;
    }

    public static Offset cornerStickOffset(SeatWind wind) {
        double amount = TableRenderConstants.STICK_DEPTH + TableRenderConstants.TILE_PADDING;
        return switch (displayDirection(wind)) {
            case EAST -> new Offset(0.0D, amount);
            case SOUTH -> new Offset(-amount, 0.0D);
            case WEST -> new Offset(0.0D, -amount);
            case NORTH -> new Offset(amount, 0.0D);
        };
    }

    public static DeadWallRenderState deadWallRenderState(Location center, TableRenderSubject session) {
        int kanCount = session.kanCount();
        boolean[] hiddenDoraSlots = new boolean[TableRenderConstants.DEAD_WALL_SIZE];
        for (int i = 0; i < session.doraIndicators().size(); i++) {
            int deadWallIndex = doraIndicatorDeadWallIndex(kanCount, i);
            if (deadWallIndex >= 0 && deadWallIndex < hiddenDoraSlots.length) {
                hiddenDoraSlots[deadWallIndex] = true;
            }
        }
        return new DeadWallRenderState(deadWallPlacements(center, session), hiddenDoraSlots, kanCount);
    }

    /** Computes the dead-wall render state from the session's display center. */
    public static DeadWallRenderState computeDeadWallRenderState(TableRenderSubject session) {
        return deadWallRenderState(displayCenter(session), session);
    }

    public static List<DeadWallPlacement> deadWallPlacements(Location center, TableRenderSubject session) {
        int breakTileIndex = wallBreakTileIndex(session);
        List<DeadWallPlacementMutable> placements = new ArrayList<>(TableRenderConstants.DEAD_WALL_SIZE);
        for (int i = 0; i < TableRenderConstants.DEAD_WALL_SIZE; i++) {
            int wallSlot = Math.floorMod(breakTileIndex - TableRenderConstants.DEAD_WALL_SIZE + i, TableRenderConstants.TOTAL_WALL_TILES);
            SeatWind face = WallLayout.wallSeat(wallSlot);
            placements.add(new DeadWallPlacementMutable(
                wallSlot,
                face,
                seatYaw(face),
                add(wallSlotLocation(center, wallSlot), deadWallGapOffset(face))
            ));
        }

        SeatWind direction = placements.get(placements.size() - 1).face();
        List<DeadWallPlacementMutable> reversed = new ArrayList<>(placements);
        Collections.reverse(reversed);
        for (int index = 0; index < reversed.size(); index++) {
            DeadWallPlacementMutable placement = reversed.get(index);
            if (placement.face() == direction) {
                continue;
            }

            placement.setYaw(seatYaw(direction));
            if (index % 2 == 0) {
                Offset shift = deadWallCornerShift(direction);
                for (DeadWallPlacementMutable other : reversed) {
                    other.shift(shift.x(), shift.z());
                }
            }

            Location base = reversed.get(0).location();
            double positionY = reversed.get(index % 2 == 0 ? 0 : 1).location().getY();
            double offset = TableRenderConstants.WALL_TILE_STEP * (index / 2);
            placement.setLocation(switch (displayDirection(direction)) {
                case EAST -> new Location(base.getWorld(), base.getX(), positionY, base.getZ() - offset);
                case SOUTH -> new Location(base.getWorld(), base.getX() + offset, positionY, base.getZ());
                case WEST -> new Location(base.getWorld(), base.getX(), positionY, base.getZ() + offset);
                case NORTH -> new Location(base.getWorld(), base.getX() - offset, positionY, base.getZ());
            });
        }

        List<DeadWallPlacement> result = new ArrayList<>(placements.size());
        for (DeadWallPlacementMutable placement : placements) {
            result.add(new DeadWallPlacement(placement.location(), placement.yaw()));
        }
        return result;
    }

    public static org.bukkit.entity.Entity spawnPublicTile(
        TableRenderSubject session,
        Location location,
        float yaw,
        MahjongTile tile,
        DisplayEntities.TileRenderPose pose
    ) {
        // Public tiles still use CraftEngine custom items, but render through ItemDisplay so
        // they keep the exact sub-block positioning Mahjong tables need and don't introduce
        // furniture-sized interaction volumes over the table surface.
        return DisplayEntities.tileDisplay(
            location,
            yaw,
            session.currentVariant(),
            tile,
            pose
        ).spawn(session.bukkitPlugin());
    }

    public static org.bukkit.entity.Entity spawnPublicTile(TableRenderSubject session, TableRenderLayout.TilePlacement placement) {
        return spawnPublicTile(session, toLocation(session, placement.point()), placement.yaw(), placement.tile(), placement.pose());
    }

    public static DisplayEntities.TileDisplaySpec publicTileSpec(TableRenderSubject session, TableRenderLayout.TilePlacement placement) {
        return DisplayEntities.tileDisplay(
            toLocation(session, placement.point()),
            placement.yaw(),
            session.currentVariant(),
            placement.tile(),
            placement.pose()
        ).spec();
    }

    public record TableDiagnostics(
        Location displayCenter,
        Location tableCenter,
        Location visualAnchor,
        double borderSpanX,
        double borderSpanZ
    ) {
    }

    public record StickDiagnostics(
        SeatWind wind,
        int index,
        boolean riichi,
        boolean longOnX,
        ScoringStick stick,
        Location center,
        String furnitureId
    ) {
    }

    public record TableBounds(double centerX, double centerZ, double minX, double maxX, double minZ, double maxZ) {
        public double width() {
            return this.maxX - this.minX;
        }

        public double depth() {
            return this.maxZ - this.minZ;
        }
    }

    public record DeadWallPlacement(Location location, float yaw) {
    }

    public record DeadWallRenderState(List<DeadWallPlacement> placements, boolean[] hiddenDoraSlots, int kanCount) {
    }

    public static final class DeadWallPlacementMutable {
        private final int wallSlot;
        private final SeatWind face;
        private float yaw;
        private Location location;

        public DeadWallPlacementMutable(int wallSlot, SeatWind face, float yaw, Location location) {
            this.wallSlot = wallSlot;
            this.face = face;
            this.yaw = yaw;
            this.location = location;
        }

        public int wallSlot() {
            return this.wallSlot;
        }

        public SeatWind face() {
            return this.face;
        }

        public float yaw() {
            return this.yaw;
        }

        public void setYaw(float yaw) {
            this.yaw = yaw;
        }

        public Location location() {
            return this.location;
        }

        public void setLocation(Location location) {
            this.location = location;
        }

        public void shift(double deltaX, double deltaZ) {
            this.location = this.location.clone().add(deltaX, 0.0D, deltaZ);
        }
    }
}
