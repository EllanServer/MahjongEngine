package top.ellan.mahjong.render.layout;

import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.display.DisplayEntities;
import top.ellan.mahjong.render.scene.MeldView;
import top.ellan.mahjong.riichi.model.ScoringStick;
import top.ellan.mahjong.render.snapshot.TableRenderSnapshot;
import top.ellan.mahjong.render.snapshot.TableSeatRenderSnapshot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;

public final class TableRenderLayout {
    private static final double ONE_SIXTEENTH = 1.0D / 16.0D;
    private static final double TILE_WIDTH = 0.1125D;
    private static final double TILE_HEIGHT = 0.15D;
    private static final double TILE_DEPTH = 0.075D;
    private static final double TILE_PADDING = 0.0025D;
    private static final double STICK_WIDTH = 0.4D;
    private static final double STICK_HEIGHT = 0.0125D;
    private static final double STICK_DEPTH = 0.0625D;
    private static final double STICK_Y_OFFSET = 0.515625D;
    private static final int STICKS_PER_STACK = 5;
    private static final double TABLE_TOP_SIZE_EXPANSION = ONE_SIXTEENTH;
    private static final double TABLE_BORDER_THICKNESS = ONE_SIXTEENTH;
    private static final double DISPLAY_CENTER_Y_OFFSET = 0.52D;
    private static final double TABLE_VISUAL_Y_OFFSET = 0.375D;
    private static final double FLOATING_TEXT_Y_OFFSET = 1.0D;
    private static final double WALL_DIRECTION_OFFSET = 1.0D;
    private static final double HAND_DIRECTION_OFFSET = WALL_DIRECTION_OFFSET + TILE_DEPTH + TILE_HEIGHT;
    private static final double HALF_TABLE_LENGTH_NO_BORDER = 0.5D + 15.0D / 16.0D;
    private static final double DEAD_WALL_GAP = TILE_PADDING * 20.0D;
    private static final double WALL_TILE_STEP = TILE_WIDTH + TILE_PADDING;
    private static final double UPRIGHT_TILE_Y = TILE_HEIGHT / 2.0D;
    private static final double FLAT_TILE_Y = TILE_DEPTH / 2.0D;
    private static final double SELECTED_HAND_TILE_Y_OFFSET = 0.06D;
    private static final int WALL_TILES_PER_SIDE = 34;
    private static final int TOTAL_WALL_TILES = 136;
    private static final int DEAD_WALL_SIZE = 14;
    private static final int LIVE_WALL_SIZE = TOTAL_WALL_TILES - DEAD_WALL_SIZE;
    private static final int DISCARDS_PER_ROW = 6;

    private TableRenderLayout() {
    }

    public static LayoutPlan precompute(TableRenderSnapshot snapshot) {
        Point displayCenter = new Point(snapshot.centerX(), snapshot.centerY() + DISPLAY_CENTER_Y_OFFSET, snapshot.centerZ());
        TableBounds bounds = tableBoundsFromTiles(displayCenter);
        Point tableCenter = new Point(bounds.centerX(), displayCenter.y(), bounds.centerZ());
        Point tableVisualAnchor = new Point(tableCenter.x(), tableCenter.y() + TABLE_VISUAL_Y_OFFSET, tableCenter.z());
        double borderSpanX = bounds.width() + TABLE_TOP_SIZE_EXPANSION + TABLE_BORDER_THICKNESS;
        double borderSpanZ = bounds.depth() + TABLE_TOP_SIZE_EXPANSION + TABLE_BORDER_THICKNESS;

        EnumMap<SeatWind, SeatLayoutPlan> seats = new EnumMap<>(SeatWind.class);
        for (SeatWind wind : SeatWind.values()) {
            TableSeatRenderSnapshot seat = snapshot.seat(wind);
            seats.put(wind, precomputeSeat(displayCenter, snapshot, seat));
        }

        return new LayoutPlan(
            displayCenter,
            tableCenter,
            tableVisualAnchor,
            borderSpanX,
            borderSpanZ,
            seats,
            precomputeWall(displayCenter, snapshot),
            precomputeDora(displayCenter, snapshot)
        );
    }

    public static SeatLayoutPlan precomputeSeatOnly(TableRenderSnapshot snapshot, SeatWind wind) {
        if (snapshot == null || wind == null) {
            throw new IllegalArgumentException("snapshot and wind are required");
        }
        Point displayCenter = new Point(snapshot.centerX(), snapshot.centerY() + DISPLAY_CENTER_Y_OFFSET, snapshot.centerZ());
        return precomputeSeat(displayCenter, snapshot, snapshot.seat(wind));
    }

    public static SeatLayoutPlan precomputePrivateHandOnly(double centerX, double centerY, double centerZ, TableSeatRenderSnapshot seat) {
        if (seat == null) {
            throw new IllegalArgumentException("seat is required");
        }
        Point displayCenter = new Point(centerX, centerY + DISPLAY_CENTER_Y_OFFSET, centerZ);
        return precomputePrivateHandSeat(displayCenter, seat);
    }

    private static SeatLayoutPlan precomputeSeat(
        Point displayCenter,
        TableRenderSnapshot snapshot,
        TableSeatRenderSnapshot seat
    ) {
        SeatWind wind = seat.wind();
        Point handBase = handDirectionBase(displayCenter, wind);
        Point statusLabelLocation = handBase.add(0.0D, 0.45D + FLOATING_TEXT_Y_OFFSET, 0.0D);
        Point playerNameLocation = handBase.add(0.0D, 0.26D + FLOATING_TEXT_Y_OFFSET, 0.0D);
        Point interactionLocation = handBase.add(0.0D, 0.18D + FLOATING_TEXT_Y_OFFSET, 0.0D);
        float yaw = seatYaw(wind);
        if (seat.playerId() == null) {
            return new SeatLayoutPlan(
                wind,
                handBase,
                statusLabelLocation,
                playerNameLocation,
                interactionLocation,
                yaw,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
            );
        }

        int handSize = seat.hand().size();
        int meldCount = seat.melds().size();
        int stickCount = seat.stickLayoutCount();
        double fuuroOffset = meldCount < 3 ? 0.0D : (meldCount - 2.0D) * TILE_WIDTH;
        double sticksOffset = stickCount < 3 ? 0.0D : (stickCount - 2.0D) * STICK_DEPTH;
        double startingPos = (handSize * TILE_WIDTH + Math.max(0, handSize - 1) * TILE_PADDING) / 2.0D + fuuroOffset + sticksOffset;
        SeatWind displayDirection = displayDirection(wind);

        List<Point> publicHandPoints = new ArrayList<>(handSize);
        List<Point> privateHandPoints = new ArrayList<>(handSize);
        List<Integer> selectedHandTileIndices = seat.selectedHandTileIndices();
        double hbx = handBase.x();
        double hby = handBase.y();
        double hbz = handBase.z();
        double publicBaseY = hby + UPRIGHT_TILE_Y;
        for (int tileIndex = 0; tileIndex < handSize; tileIndex++) {
            double drawGap = tileIndex == handSize - 1 && handSize % 3 == 2 ? TILE_PADDING * 15.0D : 0.0D;
            double stackOffset = tileIndex * (TILE_WIDTH + TILE_PADDING) + drawGap;
            boolean selected = selectedHandTileIndices.contains(tileIndex);
            Point publicPoint = switch (displayDirection) {
                case EAST -> new Point(hbx, publicBaseY, hbz + startingPos - stackOffset);
                case SOUTH -> new Point(hbx - startingPos + stackOffset, publicBaseY, hbz);
                case WEST -> new Point(hbx, publicBaseY, hbz - startingPos + stackOffset);
                case NORTH -> new Point(hbx + startingPos - stackOffset, publicBaseY, hbz);
            };
            double privateY = selected ? publicBaseY + SELECTED_HAND_TILE_Y_OFFSET : publicBaseY;
            Point privatePoint = switch (displayDirection) {
                case EAST -> new Point(hbx, privateY, hbz + startingPos - stackOffset);
                case SOUTH -> new Point(hbx - startingPos + stackOffset, privateY, hbz);
                case WEST -> new Point(hbx, privateY, hbz - startingPos + stackOffset);
                case NORTH -> new Point(hbx + startingPos - stackOffset, privateY, hbz);
            };
            publicHandPoints.add(publicPoint);
            privateHandPoints.add(privatePoint);
        }

        return new SeatLayoutPlan(
            wind,
            handBase,
            statusLabelLocation,
            playerNameLocation,
            interactionLocation,
            yaw,
            List.copyOf(publicHandPoints),
            List.copyOf(privateHandPoints),
            precomputeDiscards(displayCenter, seat, snapshot.openDoorSeat()),
            precomputeMelds(displayCenter, seat),
            precomputeSticks(displayCenter, seat)
        );
    }

    private static SeatLayoutPlan precomputePrivateHandSeat(Point displayCenter, TableSeatRenderSnapshot seat) {
        SeatWind wind = seat.wind();
        Point handBase = handDirectionBase(displayCenter, wind);
        float yaw = seatYaw(wind);
        if (seat.playerId() == null) {
            return new SeatLayoutPlan(
                wind,
                handBase,
                handBase,
                handBase,
                handBase,
                yaw,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
            );
        }
        int handSize = seat.hand().size();
        int meldCount = seat.melds().size();
        int stickCount = seat.stickLayoutCount();
        double fuuroOffset = meldCount < 3 ? 0.0D : (meldCount - 2.0D) * TILE_WIDTH;
        double sticksOffset = stickCount < 3 ? 0.0D : (stickCount - 2.0D) * STICK_DEPTH;
        double startingPos = (handSize * TILE_WIDTH + Math.max(0, handSize - 1) * TILE_PADDING) / 2.0D + fuuroOffset + sticksOffset;
        SeatWind displayDirection = displayDirection(wind);
        double hbx = handBase.x();
        double hby = handBase.y();
        double hbz = handBase.z();
        double baseY = hby + UPRIGHT_TILE_Y;
        List<Point> privateHandPoints = new ArrayList<>(handSize);
        List<Integer> selectedHandTileIndices = seat.selectedHandTileIndices();
        for (int tileIndex = 0; tileIndex < handSize; tileIndex++) {
            double drawGap = tileIndex == handSize - 1 && handSize % 3 == 2 ? TILE_PADDING * 15.0D : 0.0D;
            double stackOffset = tileIndex * (TILE_WIDTH + TILE_PADDING) + drawGap;
            boolean selected = selectedHandTileIndices.contains(tileIndex);
            double py = selected ? baseY + SELECTED_HAND_TILE_Y_OFFSET : baseY;
            Point point = switch (displayDirection) {
                case EAST -> new Point(hbx, py, hbz + startingPos - stackOffset);
                case SOUTH -> new Point(hbx - startingPos + stackOffset, py, hbz);
                case WEST -> new Point(hbx, py, hbz - startingPos + stackOffset);
                case NORTH -> new Point(hbx + startingPos - stackOffset, py, hbz);
            };
            privateHandPoints.add(point);
        }
        return new SeatLayoutPlan(
            wind,
            handBase,
            handBase,
            handBase,
            handBase,
            yaw,
            List.of(),
            List.copyOf(privateHandPoints),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private static List<TilePlacement> precomputeWall(Point displayCenter, TableRenderSnapshot snapshot) {
        if (!snapshot.started()) {
            return List.of();
        }
        if (!snapshot.usesDeadWall()) {
            return precomputeLiveWallWithoutDeadWall(displayCenter, snapshot);
        }
        int liveWallCount = snapshot.remainingWallCount();
        int kanCount = snapshot.kanCount();
        int frontDrawCount = Math.max(0, LIVE_WALL_SIZE - liveWallCount - kanCount);
        boolean[] doraSlots = new boolean[DEAD_WALL_SIZE];
        for (int i = 0; i < snapshot.doraIndicators().size(); i++) {
            int deadWallIndex = doraIndicatorDeadWallIndex(kanCount, i);
            if (deadWallIndex >= 0 && deadWallIndex < doraSlots.length) {
                doraSlots[deadWallIndex] = true;
            }
        }

        List<DeadWallPlacement> deadWallPlacements = deadWallPlacements(displayCenter, snapshot);
        List<TilePlacement> placements = Arrays.asList(new TilePlacement[TOTAL_WALL_TILES]);
        boolean[] occupiedSlots = new boolean[TOTAL_WALL_TILES];
        int breakTileIndex = wallBreakTileIndex(snapshot);
        int firstLiveWallSlot = Math.floorMod(breakTileIndex + frontDrawCount, TOTAL_WALL_TILES);
        int wallSlot = firstLiveWallSlot;
        for (int i = 0; i < liveWallCount; i++) {
            occupiedSlots[wallSlot] = true;
            wallSlot = nextWallSlot(wallSlot, TOTAL_WALL_TILES);
        }
        for (DeadWallPlacement placement : deadWallPlacements) {
            occupiedSlots[placement.wallSlot()] = true;
        }

        wallSlot = firstLiveWallSlot;
        for (int i = 0; i < liveWallCount; i++) {
            SeatWind wind = WallLayout.wallSeat(wallSlot);
            Point point = wallSlotPoint(displayCenter, wallSlot, wind, WALL_TILES_PER_SIDE, occupiedSlots);
            placements.set(wallSlot, new TilePlacement(point, seatYaw(wind), MahjongTile.UNKNOWN, DisplayEntities.TileRenderPose.FLAT_FACE_DOWN));
            wallSlot = nextWallSlot(wallSlot, TOTAL_WALL_TILES);
        }

        for (int i = 0; i < DEAD_WALL_SIZE; i++) {
            DeadWallPlacement placement = deadWallPlacements.get(i);
            // A revealed dora is rendered in the separate dora region but still physically supports
            // the tile above it, so every dead-wall slot participates in the gravity calculation.
            if (doraSlots[i]) {
                continue;
            }
            Point point = settledWallPoint(placement.point(), placement.wallSlot(), occupiedSlots, WALL_TILES_PER_SIDE);
            placements.set(placement.wallSlot(), new TilePlacement(point, placement.yaw(), MahjongTile.UNKNOWN, DisplayEntities.TileRenderPose.FLAT_FACE_DOWN));
        }
        return Collections.unmodifiableList(placements);
    }

    private static List<TilePlacement> precomputeLiveWallWithoutDeadWall(Point displayCenter, TableRenderSnapshot snapshot) {
        int wallCapacity = snapshot.wallCapacity();
        int tilesPerSide = snapshot.wallTilesPerSide();
        int remainingWallCount = Math.max(0, Math.min(snapshot.remainingWallCount(), wallCapacity));
        int supplementDrawCount = Math.min(
            wallCapacity - remainingWallCount,
            snapshot.kanCount() + exposedFlowerCount(snapshot)
        );
        int frontDrawCount = wallCapacity - remainingWallCount - supplementDrawCount;
        int breakTileIndex = wallBreakTileIndex(snapshot);
        List<TilePlacement> placements = Arrays.asList(new TilePlacement[wallCapacity]);
        boolean[] occupiedSlots = new boolean[wallCapacity];
        int firstLiveWallSlot = Math.floorMod(breakTileIndex + frontDrawCount, wallCapacity);
        int wallSlot = firstLiveWallSlot;
        for (int i = 0; i < remainingWallCount; i++) {
            occupiedSlots[wallSlot] = true;
            wallSlot = nextWallSlot(wallSlot, wallCapacity);
        }

        wallSlot = firstLiveWallSlot;
        for (int i = 0; i < remainingWallCount; i++) {
            SeatWind wind = WallLayout.wallSeat(wallSlot, tilesPerSide);
            Point point = wallSlotPoint(displayCenter, wallSlot, wind, tilesPerSide, occupiedSlots);
            placements.set(wallSlot, new TilePlacement(point, seatYaw(wind), MahjongTile.UNKNOWN, DisplayEntities.TileRenderPose.FLAT_FACE_DOWN));
            wallSlot = nextWallSlot(wallSlot, wallCapacity);
        }
        return Collections.unmodifiableList(placements);
    }

    private static int exposedFlowerCount(TableRenderSnapshot snapshot) {
        int count = 0;
        for (SeatWind wind : SeatWind.values()) {
            TableSeatRenderSnapshot seat = snapshot.seat(wind);
            if (seat == null) {
                continue;
            }
            for (MeldView meld : seat.melds()) {
                for (MahjongTile tile : meld.tiles()) {
                    if (tile != null && tile.isFlower()) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static List<TilePlacement> precomputeDora(Point displayCenter, TableRenderSnapshot snapshot) {
        if (!snapshot.started() || !snapshot.usesDeadWall()) {
            return List.of();
        }
        List<DeadWallPlacement> deadWallPlacements = deadWallPlacements(displayCenter, snapshot);
        List<TilePlacement> placements = new ArrayList<>(snapshot.doraIndicators().size());
        for (int i = 0; i < snapshot.doraIndicators().size(); i++) {
            DeadWallPlacement placement = deadWallPlacements.get(doraIndicatorDeadWallIndex(snapshot.kanCount(), i));
            placements.add(new TilePlacement(placement.point(), placement.yaw(), snapshot.doraIndicators().get(i), DisplayEntities.TileRenderPose.FLAT_FACE_UP));
        }
        return List.copyOf(placements);
    }

    private static List<StickPlacement> precomputeSticks(Point displayCenter, TableSeatRenderSnapshot seat) {
        if (seat.playerId() == null) {
            return List.of();
        }
        SeatWind wind = seat.wind();
        List<ScoringStick> cornerSticks = seat.cornerSticks();
        List<StickPlacement> placements = new ArrayList<>(cornerSticks.size() + (seat.riichi() ? 1 : 0));
        boolean longOnX = cornerStickLongOnX(wind);
        for (int i = 0; i < cornerSticks.size(); i++) {
            placements.add(new StickPlacement(cornerStickCenter(displayCenter, wind, i), longOnX, cornerSticks.get(i)));
        }
        if (seat.riichi()) {
            placements.add(new StickPlacement(riichiStickCenter(displayCenter, wind), riichiStickLongOnX(wind), ScoringStick.P1000));
        }
        return List.copyOf(placements);
    }

    private static List<TilePlacement> precomputeDiscards(
        Point displayCenter,
        TableSeatRenderSnapshot seat,
        SeatWind openDoorSeat
    ) {
        if (seat.playerId() == null) {
            return List.of();
        }
        SeatWind wind = seat.wind();
        int discardCount = seat.discards().size();
        List<TilePlacement> placements = new ArrayList<>(discardCount);
        Point start = discardStart(displayCenter, wind);
        Point cursor = start;
        boolean openDoorSeatMatches = openDoorSeat == wind;
        Offset lo = lineOffset(wind);
        Offset to = tileOffset(wind);
        Offset rto = riichiTileOffset(wind);
        Offset sgo = smallGapOffset(wind);
        Offset negTo = negate(to);

        for (int discardIndex = 0; discardIndex < discardCount; discardIndex++) {
            int lineCount = discardIndex / DISCARDS_PER_ROW;
            int column = discardIndex % DISCARDS_PER_ROW;
            boolean firstTileInRow = column == 0;
            boolean riichiTile = discardIndex == seat.riichiDiscardIndex();
            boolean previousWasRiichi = discardIndex > 0 && discardIndex - 1 == seat.riichiDiscardIndex();

            if (lineCount > 0 && firstTileInRow && !(openDoorSeatMatches && discardIndex >= DISCARDS_PER_ROW * 3)) {
                cursor = new Point(start.x() + lo.x() * lineCount, start.y(), start.z() + lo.z() * lineCount);
            }

            if (firstTileInRow) {
                if (riichiTile) {
                    cursor = add(cursor, rto);
                    cursor = add(cursor, negTo);
                }
            } else {
                cursor = add(cursor, riichiTile || previousWasRiichi ? rto : to);
                cursor = add(cursor, sgo);
            }

            placements.add(new TilePlacement(
                cursor.add(0.0D, FLAT_TILE_Y, 0.0D),
                DiscardLayout.discardYaw(wind, riichiTile),
                seat.discards().get(discardIndex),
                DisplayEntities.TileRenderPose.FLAT_FACE_UP
            ));
        }
        return List.copyOf(placements);
    }

    private static List<TilePlacement> precomputeMelds(Point displayCenter, TableSeatRenderSnapshot seat) {
        if (seat.playerId() == null || seat.melds().isEmpty()) {
            return List.of();
        }

        SeatWind wind = seat.wind();
        float yaw = seatYaw(wind);
        List<TilePlacement> placements = new ArrayList<>();
        Point cursor = meldStart(displayCenter, wind);
        int stickCount = seat.stickLayoutCount();
        if (stickCount > 0) {
            cursor = add(cursor, cornerStickMeldOffset(wind, Math.min(stickCount, STICKS_PER_STACK)));
        }
        Offset halfVert = halfVerticalTileOffset(wind);
        Offset halfHoriz = halfHorizontalTileOffset(wind);
        Offset vert = verticalTileOffset(wind);
        Offset horiz = horizontalTileOffset(wind);
        Offset horizGrav = horizontalTileGravityOffset(wind);
        boolean lastTileWasHorizontal = false;
        int placedTileCount = 0;

        for (MeldView meld : seat.melds()) {
            Point kakanStackBase = null;
            float kakanStackYaw = yaw;
            Point firstTileBase = null;
            boolean concealedKan = meld.tiles().size() == 4 && meld.faceDownAt(0) && meld.faceDownAt(meld.tiles().size() - 1);
            if (concealedKan) {
                for (int i = 0; i < meld.tiles().size(); i++) {
                    if (placedTileCount == 0) {
                        cursor = add(cursor, halfVert);
                    } else if (lastTileWasHorizontal) {
                        cursor = add(cursor, add(halfHoriz, halfVert));
                    } else {
                        cursor = add(cursor, vert);
                    }
                    placements.add(new TilePlacement(
                        cursor.add(0.0D, FLAT_TILE_Y, 0.0D),
                        yaw,
                        meld.tiles().get(i),
                        meld.faceDownAt(i) ? DisplayEntities.TileRenderPose.FLAT_FACE_DOWN : DisplayEntities.TileRenderPose.FLAT_FACE_UP
                    ));
                    placedTileCount++;
                }
                lastTileWasHorizontal = false;
                continue;
            }

            for (int i = 0; i < meld.tiles().size(); i++) {
                boolean claimTile = meld.hasClaimTile() && i == meld.claimTileIndex();
                if (placedTileCount == 0) {
                    cursor = add(cursor, claimTile ? halfHoriz : halfVert);
                } else if (claimTile || lastTileWasHorizontal) {
                    cursor = claimTile && lastTileWasHorizontal
                        ? add(cursor, horiz)
                        : add(cursor, add(halfHoriz, halfVert));
                } else {
                    cursor = add(cursor, vert);
                }

                Point basePoint = claimTile ? add(cursor, horizGrav) : cursor;
                if (firstTileBase == null) {
                    firstTileBase = basePoint;
                }
                float tileYaw = claimTile ? yaw + meld.claimYawOffset() : yaw;
                placements.add(new TilePlacement(
                    basePoint.add(0.0D, FLAT_TILE_Y, 0.0D),
                    tileYaw,
                    meld.tiles().get(i),
                    meld.faceDownAt(i) ? DisplayEntities.TileRenderPose.FLAT_FACE_DOWN : DisplayEntities.TileRenderPose.FLAT_FACE_UP
                ));
                if (claimTile) {
                    kakanStackBase = basePoint;
                    kakanStackYaw = tileYaw;
                }
                lastTileWasHorizontal = claimTile;
                placedTileCount++;
            }

            if (meld.hasAddedKanTile() && kakanStackBase == null) {
                kakanStackBase = firstTileBase;
                kakanStackYaw = yaw;
            }
            if (meld.hasAddedKanTile() && kakanStackBase != null) {
                placements.add(new TilePlacement(
                    add(kakanStackBase, offsetTowardTableCenter(wind, TILE_WIDTH + TILE_PADDING)).add(0.0D, FLAT_TILE_Y, 0.0D),
                    kakanStackYaw,
                    meld.addedKanTile(),
                    DisplayEntities.TileRenderPose.FLAT_FACE_UP
                ));
                lastTileWasHorizontal = false;
            }
        }
        return List.copyOf(placements);
    }

    private static int wallBreakTileIndex(TableRenderSnapshot snapshot) {
        int seatCount = SeatWind.values().length;
        int dicePoints = snapshot.dicePoints();
        int breakDice = snapshot.breakDicePoints();
        int openDoorIndex = Math.floorMod(snapshot.dealerSeat().index() + dicePoints - 1, seatCount);
        int openingStackCount = snapshot.usesDeadWall() ? breakDice : dicePoints + breakDice;
        return Math.floorMod(
            openDoorIndex * snapshot.wallTilesPerSide() + openingStackCount * 2,
            snapshot.wallCapacity()
        );
    }

    private static int doraIndicatorDeadWallIndex(int kanCount, int indicatorIndex) {
        return (4 - indicatorIndex) * 2 + kanCount;
    }

    private static TableBounds tableBoundsFromTiles(Point center) {
        Point eastMeldStart = meldStartByDisplayDirection(center, SeatWind.EAST);
        Point southMeldStart = meldStartByDisplayDirection(center, SeatWind.SOUTH);
        Point westMeldStart = meldStartByDisplayDirection(center, SeatWind.WEST);
        Point northMeldStart = meldStartByDisplayDirection(center, SeatWind.NORTH);
        double halfTileHeight = TILE_HEIGHT / 2.0D;
        double minX = northMeldStart.x();
        double maxX = southMeldStart.x();
        double minZ = eastMeldStart.z();
        double maxZ = westMeldStart.z();
        double centerX = (westMeldStart.x() - halfTileHeight + maxX) / 2.0D;
        double centerZ = (northMeldStart.z() - halfTileHeight + maxZ) / 2.0D;
        return new TableBounds(centerX, centerZ, minX, maxX, minZ, maxZ);
    }

    private static Point meldStartByDisplayDirection(Point center, SeatWind direction) {
        SeatWind wind = switch (direction) {
            case EAST -> SeatWind.EAST;
            case SOUTH -> SeatWind.NORTH;
            case WEST -> SeatWind.WEST;
            case NORTH -> SeatWind.SOUTH;
        };
        return meldStart(center, wind);
    }

    private static Point wallSlotPoint(
        Point center,
        int wallSlot,
        SeatWind wind,
        int tilesPerSide,
        boolean[] occupiedSlots
    ) {
        int sideOffset = wallSlot % tilesPerSide;
        int stackIndex = sideOffset / 2;
        double stackWidth = stackIndex * WALL_TILE_STEP;
        int stackCount = (tilesPerSide + 1) / 2;
        double startingPos = (stackCount * TILE_WIDTH) / 2.0D - TILE_HEIGHT;
        int layer = settledWallLayer(wallSlot, sideOffset, occupiedSlots, tilesPerSide);
        double yOffset = FLAT_TILE_Y + wallLayerYOffset(layer);
        return switch (displayDirection(wind)) {
            case EAST -> center.add(WALL_DIRECTION_OFFSET, yOffset, -startingPos + stackWidth);
            case SOUTH -> center.add(startingPos - stackWidth, yOffset, WALL_DIRECTION_OFFSET);
            case WEST -> center.add(-WALL_DIRECTION_OFFSET, yOffset, startingPos - stackWidth);
            case NORTH -> center.add(-startingPos + stackWidth, yOffset, -WALL_DIRECTION_OFFSET);
        };
    }

    private static double wallLayerYOffset(int layer) {
        return layer * TILE_DEPTH + (layer == 1 ? TILE_PADDING : 0.0D);
    }

    private static Point settledWallPoint(
        Point point,
        int wallSlot,
        boolean[] occupiedSlots,
        int tilesPerSide
    ) {
        int sideOffset = wallSlot % tilesPerSide;
        if (settledWallLayer(wallSlot, sideOffset, occupiedSlots, tilesPerSide) == 1) {
            return point;
        }
        return (sideOffset & 1) == 0 ? point.add(0.0D, -wallLayerYOffset(1), 0.0D) : point;
    }

    private static int settledWallLayer(
        int wallSlot,
        int sideOffset,
        boolean[] occupiedSlots,
        int tilesPerSide
    ) {
        if ((sideOffset & 1) != 0) {
            return 0;
        }
        if (occupiedSlots == null) {
            return 1;
        }
        int supportingSlot = sideOffset + 1 < tilesPerSide ? wallSlot + 1 : -1;
        return supportingSlot >= 0 && occupiedSlots[supportingSlot] ? 1 : 0;
    }

    private static int nextWallSlot(int wallSlot, int wallCapacity) {
        int next = wallSlot + 1;
        return next == wallCapacity ? 0 : next;
    }

    private static List<DeadWallPlacement> deadWallPlacements(Point center, TableRenderSnapshot snapshot) {
        int breakTileIndex = wallBreakTileIndex(snapshot);
        List<DeadWallPlacementMutable> placements = new ArrayList<>(DEAD_WALL_SIZE);
        for (int i = 0; i < DEAD_WALL_SIZE; i++) {
            int wallSlot = Math.floorMod(breakTileIndex - DEAD_WALL_SIZE + i, TOTAL_WALL_TILES);
            SeatWind face = WallLayout.wallSeat(wallSlot);
            placements.add(new DeadWallPlacementMutable(
                wallSlot,
                face,
                seatYaw(face),
                add(wallSlotPoint(center, wallSlot, face, WALL_TILES_PER_SIDE, null), deadWallGapOffset(face))
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

            Point base = reversed.get(0).point();
            double positionY = reversed.get(index % 2 == 0 ? 0 : 1).point().y();
            double offset = WALL_TILE_STEP * (index / 2);
            placement.setPoint(switch (displayDirection(direction)) {
                case EAST -> new Point(base.x(), positionY, base.z() - offset);
                case SOUTH -> new Point(base.x() + offset, positionY, base.z());
                case WEST -> new Point(base.x(), positionY, base.z() + offset);
                case NORTH -> new Point(base.x() - offset, positionY, base.z());
            });
        }

        List<DeadWallPlacement> result = new ArrayList<>(placements.size());
        for (DeadWallPlacementMutable placement : placements) {
            result.add(new DeadWallPlacement(placement.wallSlot(), placement.point(), placement.yaw()));
        }
        return result;
    }

    private static float seatYaw(SeatWind wind) {
        return DiscardLayout.seatYaw(wind);
    }

    private static Point handDirectionBase(Point center, SeatWind wind) {
        return switch (displayDirection(wind)) {
            case EAST -> center.add(HAND_DIRECTION_OFFSET, 0.0D, 0.0D);
            case SOUTH -> center.add(0.0D, 0.0D, HAND_DIRECTION_OFFSET);
            case WEST -> center.add(-HAND_DIRECTION_OFFSET, 0.0D, 0.0D);
            case NORTH -> center.add(0.0D, 0.0D, -HAND_DIRECTION_OFFSET);
        };
    }

    private static Point discardStart(Point center, SeatWind wind) {
        double halfWidthOfSixTiles = TILE_WIDTH * DISCARDS_PER_ROW / 2.0D;
        double paddingFromCenter = halfWidthOfSixTiles + TILE_HEIGHT / 2.0D + TILE_HEIGHT / 4.0D;
        double basicOffset = halfWidthOfSixTiles - TILE_WIDTH / 2.0D;
        return switch (displayDirection(wind)) {
            case EAST -> center.add(paddingFromCenter, 0.0D, basicOffset);
            case SOUTH -> center.add(-basicOffset, 0.0D, paddingFromCenter);
            case WEST -> center.add(-paddingFromCenter, 0.0D, -basicOffset);
            case NORTH -> center.add(basicOffset, 0.0D, -paddingFromCenter);
        };
    }

    private static Point meldStart(Point center, SeatWind wind) {
        double halfHeight = TILE_HEIGHT / 2.0D;
        return switch (displayDirection(wind)) {
            case EAST -> center.add(HALF_TABLE_LENGTH_NO_BORDER - halfHeight, 0.0D, -HALF_TABLE_LENGTH_NO_BORDER);
            case SOUTH -> center.add(HALF_TABLE_LENGTH_NO_BORDER, 0.0D, HALF_TABLE_LENGTH_NO_BORDER - halfHeight);
            case WEST -> center.add(-HALF_TABLE_LENGTH_NO_BORDER + halfHeight, 0.0D, HALF_TABLE_LENGTH_NO_BORDER);
            case NORTH -> center.add(-HALF_TABLE_LENGTH_NO_BORDER, 0.0D, -HALF_TABLE_LENGTH_NO_BORDER + halfHeight);
        };
    }

    private static Point riichiStickCenter(Point center, SeatWind wind) {
        double halfWidthOfSixTiles = TILE_WIDTH * DISCARDS_PER_ROW / 2.0D;
        double paddingFromCenter = halfWidthOfSixTiles - STICK_DEPTH / 2.0D;
        return switch (displayDirection(wind)) {
            case EAST -> center.add(paddingFromCenter, STICK_Y_OFFSET, 0.0D);
            case SOUTH -> center.add(0.0D, STICK_Y_OFFSET, paddingFromCenter);
            case WEST -> center.add(-paddingFromCenter, STICK_Y_OFFSET, 0.0D);
            case NORTH -> center.add(0.0D, STICK_Y_OFFSET, -paddingFromCenter);
        };
    }

    private static boolean riichiStickLongOnX(SeatWind wind) {
        SeatWind direction = displayDirection(wind);
        return direction == SeatWind.SOUTH || direction == SeatWind.NORTH;
    }

    private static Point cornerStickCenter(Point center, SeatWind wind, int index) {
        int stackIndex = index / STICKS_PER_STACK;
        int stickIndex = index % STICKS_PER_STACK;
        double halfWidthOfStick = STICK_WIDTH / 2.0D;
        double halfDepthOfStick = STICK_DEPTH / 2.0D;
        Point start = switch (displayDirection(wind)) {
            case EAST -> center.add(HALF_TABLE_LENGTH_NO_BORDER - halfWidthOfStick, 0.0D, -HALF_TABLE_LENGTH_NO_BORDER + halfDepthOfStick);
            case SOUTH -> center.add(HALF_TABLE_LENGTH_NO_BORDER - halfDepthOfStick, 0.0D, HALF_TABLE_LENGTH_NO_BORDER - halfWidthOfStick);
            case WEST -> center.add(-HALF_TABLE_LENGTH_NO_BORDER + halfWidthOfStick, 0.0D, HALF_TABLE_LENGTH_NO_BORDER - halfDepthOfStick);
            case NORTH -> center.add(-HALF_TABLE_LENGTH_NO_BORDER + halfDepthOfStick, 0.0D, -HALF_TABLE_LENGTH_NO_BORDER + halfWidthOfStick);
        };
        return add(start, multiply(cornerStickOffset(wind), stickIndex)).add(0.0D, STICK_Y_OFFSET + stackIndex * (STICK_HEIGHT + TILE_PADDING), 0.0D);
    }

    private static boolean cornerStickLongOnX(SeatWind wind) {
        SeatWind direction = displayDirection(wind);
        return direction == SeatWind.EAST || direction == SeatWind.WEST;
    }

    private static Offset cornerStickOffset(SeatWind wind) {
        double amount = STICK_DEPTH + TILE_PADDING;
        return switch (displayDirection(wind)) {
            case EAST -> new Offset(0.0D, amount);
            case SOUTH -> new Offset(-amount, 0.0D);
            case WEST -> new Offset(0.0D, -amount);
            case NORTH -> new Offset(amount, 0.0D);
        };
    }

    private static Offset cornerStickMeldOffset(SeatWind wind, int firstStackCount) {
        double amount = firstStackCount * STICK_DEPTH + Math.max(0, firstStackCount - 1) * TILE_PADDING;
        return switch (displayDirection(wind)) {
            case EAST -> new Offset(0.0D, amount);
            case SOUTH -> new Offset(-amount, 0.0D);
            case WEST -> new Offset(0.0D, -amount);
            case NORTH -> new Offset(amount, 0.0D);
        };
    }

    private static Offset deadWallGapOffset(SeatWind wind) {
        return switch (displayDirection(wind)) {
            case EAST -> new Offset(0.0D, DEAD_WALL_GAP);
            case SOUTH -> new Offset(-DEAD_WALL_GAP, 0.0D);
            case WEST -> new Offset(0.0D, -DEAD_WALL_GAP);
            case NORTH -> new Offset(DEAD_WALL_GAP, 0.0D);
        };
    }

    private static Offset deadWallCornerShift(SeatWind wind) {
        return switch (displayDirection(wind)) {
            case EAST -> new Offset(0.0D, TILE_WIDTH);
            case SOUTH -> new Offset(-TILE_WIDTH, 0.0D);
            case WEST -> new Offset(0.0D, -TILE_WIDTH);
            case NORTH -> new Offset(TILE_WIDTH, 0.0D);
        };
    }

    private static Offset tileOffset(SeatWind wind) {
        return switch (displayDirection(wind)) {
            case EAST -> new Offset(0.0D, -TILE_WIDTH);
            case SOUTH -> new Offset(TILE_WIDTH, 0.0D);
            case WEST -> new Offset(0.0D, TILE_WIDTH);
            case NORTH -> new Offset(-TILE_WIDTH, 0.0D);
        };
    }

    private static Offset riichiTileOffset(SeatWind wind) {
        double amount = (TILE_HEIGHT + TILE_WIDTH) / 2.0D;
        return switch (displayDirection(wind)) {
            case EAST -> new Offset(0.0D, -amount);
            case SOUTH -> new Offset(amount, 0.0D);
            case WEST -> new Offset(0.0D, amount);
            case NORTH -> new Offset(-amount, 0.0D);
        };
    }

    private static Offset lineOffset(SeatWind wind) {
        double amount = TILE_HEIGHT + TILE_PADDING;
        return switch (displayDirection(wind)) {
            case EAST -> new Offset(amount, 0.0D);
            case SOUTH -> new Offset(0.0D, amount);
            case WEST -> new Offset(-amount, 0.0D);
            case NORTH -> new Offset(0.0D, -amount);
        };
    }

    private static Offset smallGapOffset(SeatWind wind) {
        return switch (displayDirection(wind)) {
            case EAST -> new Offset(0.0D, -TILE_PADDING);
            case SOUTH -> new Offset(TILE_PADDING, 0.0D);
            case WEST -> new Offset(0.0D, TILE_PADDING);
            case NORTH -> new Offset(-TILE_PADDING, 0.0D);
        };
    }

    private static Offset verticalTileOffset(SeatWind wind) {
        double amount = TILE_WIDTH + TILE_PADDING;
        return switch (displayDirection(wind)) {
            case EAST -> new Offset(0.0D, amount);
            case SOUTH -> new Offset(-amount, 0.0D);
            case WEST -> new Offset(0.0D, -amount);
            case NORTH -> new Offset(amount, 0.0D);
        };
    }

    private static Offset halfVerticalTileOffset(SeatWind wind) {
        return multiply(verticalTileOffset(wind), 0.5D);
    }

    private static Offset horizontalTileOffset(SeatWind wind) {
        double amount = TILE_HEIGHT + TILE_PADDING;
        return switch (displayDirection(wind)) {
            case EAST -> new Offset(0.0D, amount);
            case SOUTH -> new Offset(-amount, 0.0D);
            case WEST -> new Offset(0.0D, -amount);
            case NORTH -> new Offset(amount, 0.0D);
        };
    }

    private static Offset halfHorizontalTileOffset(SeatWind wind) {
        return multiply(horizontalTileOffset(wind), 0.5D);
    }

    private static Offset horizontalTileGravityOffset(SeatWind wind) {
        double amount = (TILE_HEIGHT - TILE_WIDTH) / 2.0D;
        return switch (displayDirection(wind)) {
            case EAST -> new Offset(amount, 0.0D);
            case SOUTH -> new Offset(0.0D, amount);
            case WEST -> new Offset(-amount, 0.0D);
            case NORTH -> new Offset(0.0D, -amount);
        };
    }

    private static Offset offsetTowardSeatFront(SeatWind wind, double amount) {
        return switch (displayDirection(wind)) {
            case EAST -> new Offset(amount, 0.0D);
            case SOUTH -> new Offset(0.0D, amount);
            case WEST -> new Offset(-amount, 0.0D);
            case NORTH -> new Offset(0.0D, -amount);
        };
    }

    private static Offset offsetTowardTableCenter(SeatWind wind, double amount) {
        return offsetTowardSeatFront(wind, -amount);
    }

    private static SeatWind displayDirection(SeatWind wind) {
        return switch (wind) {
            case EAST -> SeatWind.EAST;
            case SOUTH -> SeatWind.NORTH;
            case WEST -> SeatWind.WEST;
            case NORTH -> SeatWind.SOUTH;
        };
    }

    private static Point add(Point point, Offset offset) {
        return point.add(offset.x(), 0.0D, offset.z());
    }

    private static Offset add(Offset first, Offset second) {
        return new Offset(first.x() + second.x(), first.z() + second.z());
    }

    private static Offset multiply(Offset offset, double factor) {
        return new Offset(offset.x() * factor, offset.z() * factor);
    }

    private static Offset negate(Offset offset) {
        return multiply(offset, -1.0D);
    }

    public record LayoutPlan(
        Point displayCenter,
        Point tableCenter,
        Point tableVisualAnchor,
        double borderSpanX,
        double borderSpanZ,
        EnumMap<SeatWind, SeatLayoutPlan> seats,
        List<TilePlacement> wallTiles,
        List<TilePlacement> doraTiles
    ) {
        public SeatLayoutPlan seat(SeatWind wind) {
            return this.seats.get(wind);
        }
    }

    public record SeatLayoutPlan(
        SeatWind wind,
        Point handBase,
        Point statusLabelLocation,
        Point playerNameLocation,
        Point interactionLocation,
        float yaw,
        List<Point> publicHandPoints,
        List<Point> privateHandPoints,
        List<TilePlacement> discardPlacements,
        List<TilePlacement> meldPlacements,
        List<StickPlacement> stickPlacements
    ) {
    }

    public record Point(double x, double y, double z) {
        Point add(double deltaX, double deltaY, double deltaZ) {
            return new Point(this.x + deltaX, this.y + deltaY, this.z + deltaZ);
        }
    }

    public record TilePlacement(Point point, float yaw, MahjongTile tile, DisplayEntities.TileRenderPose pose) {
    }

    public record StickPlacement(Point center, boolean longOnX, ScoringStick stick) {
    }

    private record Offset(double x, double z) {
    }

    private record TableBounds(double centerX, double centerZ, double minX, double maxX, double minZ, double maxZ) {
        double width() {
            return this.maxX - this.minX;
        }

        double depth() {
            return this.maxZ - this.minZ;
        }
    }

    private static final class DeadWallPlacementMutable {
        private final int wallSlot;
        private final SeatWind face;
        private float yaw;
        private Point point;

        private DeadWallPlacementMutable(int wallSlot, SeatWind face, float yaw, Point point) {
            this.wallSlot = wallSlot;
            this.face = face;
            this.yaw = yaw;
            this.point = point;
        }

        private int wallSlot() {
            return this.wallSlot;
        }

        private SeatWind face() {
            return this.face;
        }

        private float yaw() {
            return this.yaw;
        }

        private void setYaw(float yaw) {
            this.yaw = yaw;
        }

        private Point point() {
            return this.point;
        }

        private void setPoint(Point point) {
            this.point = point;
        }

        private void shift(double deltaX, double deltaZ) {
            this.point = this.point.add(deltaX, 0.0D, deltaZ);
        }
    }

    private record DeadWallPlacement(int wallSlot, Point point, float yaw) {
    }
}
