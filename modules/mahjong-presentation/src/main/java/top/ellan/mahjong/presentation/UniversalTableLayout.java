package top.ellan.mahjong.presentation;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import top.ellan.mahjong.spi.ActionPlacement;
import top.ellan.mahjong.spi.RuleTablePresentation;
import top.ellan.mahjong.spi.RuleTilePresentation;
import top.ellan.mahjong.spi.RuleViewTile;
import top.ellan.mahjong.spi.RuleWallDirection;
import top.ellan.mahjong.spi.RuleWallPresentation;
import top.ellan.mahjong.spi.SeatId;

/**
 * One physical layout engine for every rule mode.
 *
 * <p>Rules declare wall stacks and semantic tile zones. A bounded shared cache compiles
 * those declarations once; projection frames then perform only array lookups.</p>
 */
public final class UniversalTableLayout implements TableLayout {
    private static final int PLAN_CACHE_SIZE = 8;

    private final TableGeometry geometry;
    private final AtomicReferenceArray<CacheEntry> plans =
            new AtomicReferenceArray<>(PLAN_CACHE_SIZE);
    private final AtomicInteger nextPlanSlot = new AtomicInteger();

    public UniversalTableLayout(TableGeometry geometry) {
        this.geometry = Objects.requireNonNull(geometry, "geometry");
    }

    @Override
    public ResolvedTableLayout resolve(RuleTablePresentation tablePresentation) {
        Objects.requireNonNull(tablePresentation, "tablePresentation");
        LayoutKey key = new LayoutKey(
                tablePresentation.seatCount(),
                tablePresentation.wall().stackCountsBySide(),
                tablePresentation.discardsPerRow());
        for (int slot = 0; slot < PLAN_CACHE_SIZE; slot++) {
            CacheEntry cached = plans.get(slot);
            if (cached != null && cached.key().equals(key)) {
                return resolved(cached.plan(), tablePresentation.wall());
            }
        }
        Plan compiled = new Plan(geometry, key);
        int cacheSlot = Math.floorMod(nextPlanSlot.getAndIncrement(), PLAN_CACHE_SIZE);
        plans.set(cacheSlot, new CacheEntry(key, compiled));
        return resolved(compiled, tablePresentation.wall());
    }

    private static ResolvedTableLayout resolved(
            Plan plan, RuleWallPresentation wall) {
        return new ResolvedPlan(plan, wall.drawStartStack(), wall.direction());
    }

    private record LayoutKey(
            int seatCount, List<Integer> wallStacks, int discardsPerRow) {
        private LayoutKey {
            wallStacks = List.copyOf(wallStacks);
        }
    }

    private record CacheEntry(LayoutKey key, Plan plan) {}

    private record ResolvedPlan(
            Plan plan, int drawStartStack, RuleWallDirection direction)
            implements ResolvedTableLayout {
        private ResolvedPlan {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(direction, "direction");
        }

        @Override
        public SceneTransform tile(RuleViewTile tile, int groupSize) {
            if (tile.zone() == top.ellan.mahjong.spi.RuleViewZone.WALL
                    || tile.zone() == top.ellan.mahjong.spi.RuleViewZone.INDICATOR) {
                return plan.wall(
                        tile.presentation(), drawStartStack, direction);
            }
            return plan.tile(tile, groupSize);
        }

        @Override
        public SceneTransform privateTile(RuleViewTile tile, int groupSize) {
            return plan.privateTile(tile, groupSize);
        }

        @Override
        public SceneTransform action(SeatId seat, ActionPlacement placement, int index) {
            return plan.action(seat, placement, index);
        }

        @Override
        public SceneTransform viewControl(SeatId seat) {
            return plan.viewControl(seat);
        }

        @Override
        public SceneTransform overheadCamera(SeatId seat, double height) {
            return plan.overheadCamera(seat, height);
        }
    }

    private static final class Plan {
        private final TableGeometry geometry;
        private final LayoutKey key;
        private final SceneTransform[][][] hands;
        private final SceneTransform[][] discards;
        private final SceneTransform[][] melds;
        private final SceneTransform[][] flowers;
        private final SceneTransform[][] pointSticks;
        private final SceneTransform[][] auxiliary;
        private final SceneTransform[][] winClaims;
        private final SceneTransform[][] actions;
        private final SceneTransform[][] secondaryActions;
        private final SceneTransform[] viewControls;
        private final SceneTransform[] wall;
        private final int totalWallStacks;

        private Plan(TableGeometry geometry, LayoutKey key) {
            this.geometry = geometry;
            this.key = key;
            totalWallStacks = key.wallStacks().stream().mapToInt(Integer::intValue).sum();
            hands = precomputeHands();
            discards = precomputeDiscards();
            melds = precomputeMelds();
            flowers = precomputeFlowers();
            pointSticks = precomputePointSticks();
            auxiliary = precomputeCenterRows(0.34D, geometry.maxAuxiliaryTiles());
            winClaims = precomputeCenterRows(-0.34D, geometry.maxAuxiliaryTiles());
            actions = precomputeActions(0.0D);
            secondaryActions = precomputeActions(geometry.secondaryActionOffset());
            viewControls = precomputeViewControls();
            wall = precomputeWall();
        }

        private SceneTransform tile(RuleViewTile tile, int groupSize) {
            Objects.requireNonNull(tile, "tile");
            RuleTilePresentation presentation = tile.presentation();
            SceneTransform base = switch (tile.zone()) {
                case HAND -> hand(tile, groupSize);
                case DISCARD -> seatValue(discards, tile, geometry.maxDiscards());
                case MELD -> seatValue(melds, tile, geometry.maxMeldTiles());
                case FLOWER -> seatValue(flowers, tile, geometry.maxAuxiliaryTiles());
                case WIN_CLAIM -> centerValue(
                        winClaims, groupSize, presentation.layoutIndex(), "win claim");
                case WALL, INDICATOR -> throw new IllegalArgumentException(
                        "Wall tiles require the resolved draw origin");
                case POINT_STICK ->
                        seatValue(pointSticks, tile, geometry.maxPointSticks());
                case AUXILIARY -> centerValue(
                        auxiliary, groupSize, presentation.layoutIndex(), "auxiliary");
            };
            return decorate(base, presentation);
        }

        private SceneTransform privateTile(RuleViewTile tile, int groupSize) {
            SceneTransform base = tile(tile, groupSize);
            if (tile.zone() != top.ellan.mahjong.spi.RuleViewZone.HAND) {
                return base;
            }
            Axis axis = axis(owner(tile));
            double offset = Math.max(0.0005D, geometry.tileGap() * 0.5D);
            return new SceneTransform(
                    base.x() + axis.outX() * offset,
                    base.y(),
                    base.z() + axis.outZ() * offset,
                    base.yawDegrees(),
                    base.pitchDegrees(),
                    base.rollDegrees(),
                    base.scale());
        }

        private SceneTransform action(SeatId seat, ActionPlacement placement, int index) {
            int seatIndex = seatIndex(seat);
            if (index < 0 || index >= geometry.maxActions()) {
                throw new IllegalArgumentException("Action index exceeds the layout capacity");
            }
            return switch (Objects.requireNonNull(placement, "placement")) {
                case ACTION_ROW -> actions[seatIndex][index];
                case SECONDARY_ROW -> secondaryActions[seatIndex][index];
                case HAND_TILE -> throw new IllegalArgumentException(
                        "Hand-tile actions use the target tile transform");
            };
        }

        private SceneTransform viewControl(SeatId seat) {
            return viewControls[seatIndex(seat)];
        }

        private SceneTransform overheadCamera(SeatId seat, double height) {
            int seatIndex = seatIndex(seat);
            if (!Double.isFinite(height) || height <= geometry.surfaceHeight()) {
                throw new IllegalArgumentException("Overhead camera height must clear the table");
            }
            double facingCenterYaw = 180.0D - 360.0D * seatIndex / key.seatCount();
            return new SceneTransform(0, height, 0, facingCenterYaw, 90, 0, 1);
        }

        private SceneTransform wall(
                RuleTilePresentation presentation,
                int drawStartStack,
                RuleWallDirection direction) {
            int logicalTile = presentation.layoutIndex();
            if (logicalTile < 0 || logicalTile >= wall.length) {
                throw new IllegalArgumentException("wall layout index exceeds its capacity");
            }
            int logicalStack = logicalTile / 2;
            int physicalStack = direction == RuleWallDirection.CLOCKWISE
                    ? Math.floorMod(drawStartStack + logicalStack, totalWallStacks)
                    : Math.floorMod(drawStartStack - logicalStack, totalWallStacks);
            return decorate(wall[physicalStack * 2 + logicalTile % 2], presentation);
        }

        private SceneTransform hand(RuleViewTile tile, int groupSize) {
            int seat = owner(tile);
            int index = tile.presentation().layoutIndex();
            if (groupSize < 1
                    || groupSize > geometry.maxHandTiles()
                    || index < 0
                    || index >= groupSize) {
                throw new IllegalArgumentException("Hand layout exceeds the physical capacity");
            }
            return hands[seat][groupSize][index];
        }

        private SceneTransform seatValue(
                SceneTransform[][] values, RuleViewTile tile, int capacity) {
            int index = tile.presentation().layoutIndex();
            if (index < 0 || index >= capacity) {
                throw new IllegalArgumentException("Tile layout index exceeds its zone capacity");
            }
            return values[owner(tile)][index];
        }

        private SceneTransform decorate(
                SceneTransform base, RuleTilePresentation presentation) {
            if (presentation.rotation().quarterTurns() == 0
                    && presentation.stackLevel() == 0
                    && !presentation.emphasized()) {
                return base;
            }
            return new SceneTransform(
                    base.x(),
                    base.y()
                            + presentation.stackLevel() * stackRaise()
                            + (presentation.emphasized() ? geometry.emphasisRaise() : 0.0D),
                    base.z(),
                    base.yawDegrees() + presentation.rotation().quarterTurns() * 90.0D,
                    base.pitchDegrees(),
                    base.rollDegrees(),
                    base.scale());
        }

        private SceneTransform[][][] precomputeHands() {
            int max = geometry.maxHandTiles();
            SceneTransform[][][] result = new SceneTransform[key.seatCount()][max + 1][max];
            for (int seat = 0; seat < key.seatCount(); seat++) {
                Axis axis = axis(seat);
                for (int size = 1; size <= max; size++) {
                    double start = (size - 1) * tileStep() / 2.0D;
                    for (int index = 0; index < size; index++) {
                        double drawGap = index == size - 1 && size % 3 == 2
                                ? geometry.tileGap() * 15.0D
                                : 0.0D;
                        double tangent = index * tileStep() - start + drawGap;
                        result[seat][size][index] = transform(
                                axis.outX() * geometry.handRadius()
                                        + axis.tangentX() * tangent,
                                uprightY(),
                                axis.outZ() * geometry.handRadius()
                                        + axis.tangentZ() * tangent,
                                seat);
                    }
                }
            }
            return result;
        }

        private SceneTransform[][] precomputeDiscards() {
            SceneTransform[][] result =
                    new SceneTransform[key.seatCount()][geometry.maxDiscards()];
            int columns = key.discardsPerRow();
            double halfWidth = (columns * geometry.tileWidth()
                            + (columns - 1) * geometry.tileGap())
                    / 2.0D;
            double radial = halfWidth
                    + geometry.tileHeight() / 2.0D
                    + geometry.tileHeight() / 4.0D;
            double tangentStart = (columns - 1) * tileStep() / 2.0D;
            for (int seat = 0; seat < key.seatCount(); seat++) {
                Axis axis = axis(seat);
                for (int index = 0; index < geometry.maxDiscards(); index++) {
                    int row = index / columns;
                    int column = index % columns;
                    double outward = radial + row * (geometry.tileHeight() + geometry.tileGap());
                    double tangent = column * tileStep() - tangentStart;
                    result[seat][index] = transform(
                            axis.outX() * outward + axis.tangentX() * tangent,
                            flatY(),
                            axis.outZ() * outward + axis.tangentZ() * tangent,
                            seat);
                }
            }
            return result;
        }

        private SceneTransform[][] precomputeMelds() {
            SceneTransform[][] result =
                    new SceneTransform[key.seatCount()][geometry.maxMeldTiles()];
            for (int seat = 0; seat < key.seatCount(); seat++) {
                Axis axis = axis(seat);
                double inset = geometry.tableHalfLength() - geometry.tileHeight() / 2.0D;
                double baseX = axis.outX() * inset
                        + axis.tangentX() * geometry.tableHalfLength();
                double baseZ = axis.outZ() * inset
                        + axis.tangentZ() * geometry.tableHalfLength();
                for (int index = 0; index < geometry.maxMeldTiles(); index++) {
                    int group = index / 4;
                    int slot = index % 4;
                    int railIndex = group * 4 + (3 - slot);
                    double distance = (railIndex + 0.5D) * tileStep();
                    result[seat][index] = transform(
                            baseX - axis.tangentX() * distance,
                            flatY(),
                            baseZ - axis.tangentZ() * distance,
                            seat);
                }
            }
            return result;
        }

        private SceneTransform[][] precomputeFlowers() {
            SceneTransform[][] result =
                    new SceneTransform[key.seatCount()][geometry.maxAuxiliaryTiles()];
            for (int seat = 0; seat < key.seatCount(); seat++) {
                Axis axis = axis(seat);
                double inset = geometry.tableHalfLength()
                        - geometry.tileHeight() / 2.0D
                        - geometry.tileHeight()
                        - geometry.tileGap();
                double baseX = axis.outX() * inset
                        + axis.tangentX() * geometry.tableHalfLength();
                double baseZ = axis.outZ() * inset
                        + axis.tangentZ() * geometry.tableHalfLength();
                for (int index = 0; index < geometry.maxAuxiliaryTiles(); index++) {
                    double distance = (index + 0.5D) * tileStep();
                    result[seat][index] = transform(
                            baseX - axis.tangentX() * distance,
                            flatY(),
                            baseZ - axis.tangentZ() * distance,
                            seat);
                }
            }
            return result;
        }

        private SceneTransform[][] precomputePointSticks() {
            SceneTransform[][] result =
                    new SceneTransform[key.seatCount()][geometry.maxPointSticks()];
            double radial = geometry.tableHalfLength() - 0.2D;
            for (int seat = 0; seat < key.seatCount(); seat++) {
                Axis axis = axis(seat);
                for (int index = 0; index < geometry.maxPointSticks(); index++) {
                    int stack = index / 5;
                    int within = index % 5;
                    double tangent = -0.16D + within * (0.0625D + geometry.tileGap());
                    result[seat][index] = transform(
                            axis.outX() * radial + axis.tangentX() * tangent,
                            geometry.surfaceHeight() + 0.0125D + stack * 0.015D,
                            axis.outZ() * radial + axis.tangentZ() * tangent,
                            seat);
                }
            }
            return result;
        }

        private SceneTransform[][] precomputeCenterRows(double radialOffset, int capacity) {
            SceneTransform[][] result = new SceneTransform[capacity + 1][capacity];
            for (int size = 1; size <= capacity; size++) {
                for (int index = 0; index < size; index++) {
                    double centered = (index - (size - 1) / 2.0D) * tileStep();
                    result[size][index] = new SceneTransform(
                            centered, flatY(), radialOffset, 0, 0, 0, 1);
                }
            }
            return result;
        }

        private SceneTransform[][] precomputeActions(double extraOutward) {
            SceneTransform[][] result =
                    new SceneTransform[key.seatCount()][geometry.maxActions()];
            for (int seat = 0; seat < key.seatCount(); seat++) {
                Axis axis = axis(seat);
                for (int index = 0; index < geometry.maxActions(); index++) {
                    int row = index / 4;
                    int column = index % 4;
                    double tangent = (column - 1.5D) * geometry.actionColumnSpacing();
                    double outward = geometry.handRadius()
                            - 0.42D
                            + extraOutward
                            + row * geometry.actionRowSpacing();
                    result[seat][index] = transform(
                            axis.outX() * outward + axis.tangentX() * tangent,
                            geometry.surfaceHeight() + 0.36D,
                            axis.outZ() * outward + axis.tangentZ() * tangent,
                            seat);
                }
            }
            return result;
        }

        private SceneTransform[] precomputeViewControls() {
            SceneTransform[] result = new SceneTransform[key.seatCount()];
            for (int seat = 0; seat < key.seatCount(); seat++) {
                Axis axis = axis(seat);
                double tangent = 2.55D * geometry.actionColumnSpacing();
                double outward = geometry.handRadius() - 0.42D;
                result[seat] = transform(
                        axis.outX() * outward + axis.tangentX() * tangent,
                        geometry.surfaceHeight() + 0.36D,
                        axis.outZ() * outward + axis.tangentZ() * tangent,
                        seat);
            }
            return result;
        }

        private SceneTransform[] precomputeWall() {
            SceneTransform[] result = new SceneTransform[totalWallStacks * 2];
            int[] sideStartStacks = new int[key.seatCount() + 1];
            for (int side = 0; side < key.seatCount(); side++) {
                sideStartStacks[side + 1] = sideStartStacks[side]
                        + key.wallStacks().get(side);
            }
            for (int physicalStack = 0; physicalStack < totalWallStacks; physicalStack++) {
                int side = sideOf(physicalStack, sideStartStacks);
                int stackWithinSide = physicalStack - sideStartStacks[side];
                int sideStacks = key.wallStacks().get(side);
                double tangent = stackWithinSide * tileStep()
                        - (sideStacks - 1) * tileStep() / 2.0D;
                Axis axis = axis(side);
                for (int tileInStack = 0; tileInStack < 2; tileInStack++) {
                    int layer = 1 - tileInStack;
                    result[physicalStack * 2 + tileInStack] = transform(
                            axis.outX() * geometry.wallRadius() + axis.tangentX() * tangent,
                            flatY() + layer * stackRaise(),
                            axis.outZ() * geometry.wallRadius() + axis.tangentZ() * tangent,
                            side);
                }
            }
            return result;
        }

        private static int sideOf(int stack, int[] sideStartStacks) {
            for (int side = 0; side < sideStartStacks.length - 1; side++) {
                if (stack < sideStartStacks[side + 1]) {
                    return side;
                }
            }
            throw new IllegalArgumentException("Wall stack is outside every side");
        }

        private SceneTransform centerValue(
                SceneTransform[][] values, int groupSize, int index, String zone) {
            if (groupSize < 1
                    || groupSize >= values.length
                    || index < 0
                    || index >= groupSize) {
                throw new IllegalArgumentException(zone + " layout exceeds its capacity");
            }
            return values[groupSize][index];
        }

        private SceneTransform transform(double x, double y, double z, int seat) {
            return new SceneTransform(x, y, z, seatYaw(seat), 0, 0, 1);
        }

        private int owner(RuleViewTile tile) {
            return seatIndex(tile.owner().orElseThrow(
                    () -> new IllegalArgumentException(tile.zone() + " tile has no owner")));
        }

        private int seatIndex(SeatId seat) {
            int value = Objects.requireNonNull(seat, "seat").value();
            if (value < 0 || value >= key.seatCount()) {
                throw new IllegalArgumentException("Tile references an absent table seat");
            }
            return value;
        }

        private Axis axis(int seat) {
            double angle = Math.PI * 2.0D * seat / key.seatCount();
            return new Axis(
                    Math.sin(angle),
                    Math.cos(angle),
                    Math.cos(angle),
                    -Math.sin(angle));
        }

        private double seatYaw(int seat) {
            return 360.0D * seat / key.seatCount();
        }

        private double tileStep() {
            return geometry.tileWidth() + geometry.tileGap();
        }

        private double uprightY() {
            return geometry.surfaceHeight() + geometry.tileHeight() / 2.0D;
        }

        private double flatY() {
            return geometry.surfaceHeight() + geometry.tileDepth() / 2.0D;
        }

        private double stackRaise() {
            return geometry.tileDepth() + geometry.tileGap();
        }

        private record Axis(double outX, double outZ, double tangentX, double tangentZ) {}
    }
}
