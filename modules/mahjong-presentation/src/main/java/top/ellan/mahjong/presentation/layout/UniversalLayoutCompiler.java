package top.ellan.mahjong.presentation.layout;

import top.ellan.mahjong.presentation.node.SceneTransform;

/** Cold-path compiler that turns rule-declared table dimensions into immutable lookup arrays. */
final class UniversalLayoutCompiler {
    /** 1.5.0-aligned viewer overlay pulls the action row 0.42 blocks toward the table. */
    private static final double ACTION_ROW_OUTWARD_INSET = 0.42D;
    /** 1.5.0-aligned OVERLAY_ACTION_Y_OFFSET above the display center surface. */
    private static final double ACTION_ROW_Y_OFFSET = 0.36D;

    private final TableGeometry geometry;
    private final UniversalLayoutSpec spec;

    UniversalLayoutCompiler(TableGeometry geometry, UniversalLayoutSpec spec) {
        this.geometry = geometry;
        this.spec = spec;
    }

    UniversalLayoutPlan compile() {
        return new UniversalLayoutPlan(
                geometry,
                spec,
                precomputeHands(0.0D),
                precomputeHands(privateHandOffset()),
                precomputeDiscards(),
                precomputeMelds(),
                precomputeFlowers(),
                precomputePointSticks(),
                precomputeCenterRows(0.34D, geometry.maxAuxiliaryTiles()),
                precomputeCenterRows(-0.34D, geometry.maxAuxiliaryTiles()),
                precomputeActionRows(),
                precomputeSeatTangentX(),
                precomputeSeatTangentZ(),
                precomputeViewControls(),
                precomputeWall());
    }

    private SceneTransform[][][] precomputeHands(double outwardOffset) {
        int max = geometry.maxHandTiles();
        SceneTransform[][][] result = new SceneTransform[spec.seatCount()][max + 1][max];
        for (int seat = 0; seat < spec.seatCount(); seat++) {
            SeatAxis axis = axis(seat);
            for (int size = 1; size <= max; size++) {
                double start = (size - 1) * tileStep() / 2.0D;
                for (int index = 0; index < size; index++) {
                    double drawGap = index == size - 1 && size % 3 == 2
                            ? geometry.tileGap() * 15.0D
                            : 0.0D;
                    double tangent = start - index * tileStep() - drawGap;
                    result[seat][size][index] = transform(
                            axis.outX() * (geometry.handRadius() + outwardOffset)
                                    + axis.tangentX() * tangent,
                            uprightY(),
                            axis.outZ() * (geometry.handRadius() + outwardOffset)
                                    + axis.tangentZ() * tangent,
                            seat);
                }
            }
        }
        return result;
    }

    private double privateHandOffset() {
        return Math.max(0.0005D, geometry.tileGap() * 0.5D);
    }

    private SceneTransform[][] precomputeDiscards() {
        SceneTransform[][] result =
                new SceneTransform[spec.seatCount()][geometry.maxDiscards()];
        int columns = spec.discardsPerRow();
        double halfWidth = (columns * geometry.tileWidth()
                        + (columns - 1) * geometry.tileGap())
                / 2.0D;
        double radial = halfWidth
                + geometry.tileHeight() / 2.0D
                + geometry.tileHeight() / 4.0D;
        double tangentStart = (columns - 1) * tileStep() / 2.0D;
        for (int seat = 0; seat < spec.seatCount(); seat++) {
            SeatAxis axis = axis(seat);
            for (int index = 0; index < geometry.maxDiscards(); index++) {
                int row = index / columns;
                int column = index % columns;
                double outward = radial + row * (geometry.tileHeight() + geometry.tileGap());
                double tangent = tangentStart - column * tileStep();
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
                new SceneTransform[spec.seatCount()][geometry.maxMeldTiles()];
        for (int seat = 0; seat < spec.seatCount(); seat++) {
            SeatAxis axis = axis(seat);
            double inset = geometry.tableHalfLength() - geometry.tileHeight() / 2.0D;
            double baseX = axis.outX() * inset + axis.tangentX() * geometry.tableHalfLength();
            double baseZ = axis.outZ() * inset + axis.tangentZ() * geometry.tableHalfLength();
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
                new SceneTransform[spec.seatCount()][geometry.maxAuxiliaryTiles()];
        for (int seat = 0; seat < spec.seatCount(); seat++) {
            SeatAxis axis = axis(seat);
            double inset = geometry.tableHalfLength()
                    - geometry.tileHeight() / 2.0D
                    - geometry.tileHeight()
                    - geometry.tileGap();
            double baseX = axis.outX() * inset + axis.tangentX() * geometry.tableHalfLength();
            double baseZ = axis.outZ() * inset + axis.tangentZ() * geometry.tableHalfLength();
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
                new SceneTransform[spec.seatCount()][geometry.maxPointSticks()];
        double radial = geometry.tableHalfLength() - 0.2D;
        for (int seat = 0; seat < spec.seatCount(); seat++) {
            SeatAxis axis = axis(seat);
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
                result[size][index] =
                        new SceneTransform(centered, flatY(), radialOffset, 0, 0, 0, 1);
            }
        }
        return result;
    }

    private SceneTransform[][] precomputeActionRows() {
        // Preserve the independent primary/secondary capacity while both placements share one rail.
        int rows = (geometry.maxActions() * 2 + 3) / 4;
        SceneTransform[][] result = new SceneTransform[spec.seatCount()][rows];
        for (int seat = 0; seat < spec.seatCount(); seat++) {
            SeatAxis axis = axis(seat);
            double outward = geometry.handRadius() - ACTION_ROW_OUTWARD_INSET;
            for (int row = 0; row < rows; row++) {
                result[seat][row] = transform(
                        axis.outX() * outward,
                        geometry.surfaceHeight() + ACTION_ROW_Y_OFFSET
                                - row * geometry.actionRowSpacing(),
                        axis.outZ() * outward,
                        seat);
            }
        }
        return result;
    }

    private double[] precomputeSeatTangentX() {
        double[] result = new double[spec.seatCount()];
        for (int seat = 0; seat < result.length; seat++) {
            result[seat] = axis(seat).tangentX();
        }
        return result;
    }

    private double[] precomputeSeatTangentZ() {
        double[] result = new double[spec.seatCount()];
        for (int seat = 0; seat < result.length; seat++) {
            result[seat] = axis(seat).tangentZ();
        }
        return result;
    }

    private SceneTransform[] precomputeViewControls() {
        SceneTransform[] result = new SceneTransform[spec.seatCount()];
        for (int seat = 0; seat < spec.seatCount(); seat++) {
            SeatAxis axis = axis(seat);
            double tangent = 2.55D * geometry.actionColumnSpacing();
            double outward = geometry.handRadius() - ACTION_ROW_OUTWARD_INSET;
            result[seat] = transform(
                    axis.outX() * outward + axis.tangentX() * tangent,
                    geometry.surfaceHeight() + ACTION_ROW_Y_OFFSET,
                    axis.outZ() * outward + axis.tangentZ() * tangent,
                    seat);
        }
        return result;
    }

    private SceneTransform[] precomputeWall() {
        SceneTransform[] result = new SceneTransform[spec.totalWallStacks() * 2];
        int[] sideStartStacks = new int[spec.seatCount() + 1];
        for (int side = 0; side < spec.seatCount(); side++) {
            sideStartStacks[side + 1] = sideStartStacks[side] + spec.wallStacks().get(side);
        }
        for (int physicalStack = 0; physicalStack < spec.totalWallStacks(); physicalStack++) {
            int side = sideOf(physicalStack, sideStartStacks);
            int stackWithinSide = physicalStack - sideStartStacks[side];
            int sideStacks = spec.wallStacks().get(side);
            double tangent = stackWithinSide * tileStep()
                    - (sideStacks - 1) * tileStep() / 2.0D;
            SeatAxis axis = axis(side);
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

    private SceneTransform transform(double x, double y, double z, int seat) {
        return new SceneTransform(x, y, z, seatYaw(seat), 0, 0, 1);
    }

    private SeatAxis axis(int seat) {
        double angle = Math.PI * 2.0D * seat / spec.seatCount();
        return new SeatAxis(
                Math.cos(angle), Math.sin(angle), -Math.sin(angle), Math.cos(angle));
    }

    private double seatYaw(int seat) {
        return -90.0D + 360.0D * seat / spec.seatCount();
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

    private record SeatAxis(double outX, double outZ, double tangentX, double tangentZ) {}
}
