package top.ellan.mahjong.craftengine.interaction;

/** Allocation-free oriented-plane and oriented-box intersection math. */
final class InteractionRayMath {
    private static final double EPSILON = 1.0E-7D;

    private InteractionRayMath() {}

    static Hit intersect(
            Target target,
            double originX,
            double originY,
            double originZ,
            double rayX,
            double rayY,
            double rayZ,
            double maxDistance) {
        double normalX = -target.acrossZ();
        double normalZ = target.acrossX();
        double relativeX = originX - target.centerX();
        double relativeY = originY - target.centerY();
        double relativeZ = originZ - target.centerZ();
        double localOriginAcross = relativeX * target.acrossX() + relativeZ * target.acrossZ();
        double localOriginDepth = relativeX * normalX + relativeZ * normalZ;
        double localRayAcross = rayX * target.acrossX() + rayZ * target.acrossZ();
        double localRayDepth = rayX * normalX + rayZ * normalZ;
        if (target.depth() <= EPSILON) {
            if (Math.abs(localRayDepth) <= EPSILON) {
                return null;
            }
            double distance = -localOriginDepth / localRayDepth;
            if (distance <= 0.0D || distance > maxDistance) {
                return null;
            }
            double hitAcross = localOriginAcross + localRayAcross * distance;
            double hitHeight = relativeY + rayY * distance;
            if (Math.abs(hitAcross) > target.width() / 2.0D
                    || Math.abs(hitHeight) > target.height() / 2.0D) {
                return null;
            }
            return new Hit(
                    distance,
                    centerScore(hitAcross, hitHeight, target.width(), target.height()));
        }

        Range range = new Range(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        range = clip(localOriginAcross, localRayAcross, target.width() / 2.0D, range);
        range = clip(relativeY, rayY, target.height() / 2.0D, range);
        range = clip(localOriginDepth, localRayDepth, target.depth() / 2.0D, range);
        if (range == null) {
            return null;
        }
        double distance = range.near() > EPSILON ? range.near() : range.far();
        if (distance <= 0.0D || distance > maxDistance) {
            return null;
        }
        double hitAcross = localOriginAcross + localRayAcross * distance;
        double hitHeight = relativeY + rayY * distance;
        return new Hit(distance, centerScore(hitAcross, hitHeight, target.width(), target.height()));
    }

    private static Range clip(
            double origin, double direction, double halfExtent, Range current) {
        if (current == null) {
            return null;
        }
        if (Math.abs(direction) <= EPSILON) {
            return Math.abs(origin) <= halfExtent ? current : null;
        }
        double first = (-halfExtent - origin) / direction;
        double second = (halfExtent - origin) / direction;
        double near = Math.max(current.near(), Math.min(first, second));
        double far = Math.min(current.far(), Math.max(first, second));
        return near <= far ? new Range(near, far) : null;
    }

    private static double centerScore(
            double across, double height, double width, double totalHeight) {
        double normalizedAcross = across / (width / 2.0D);
        double normalizedHeight = height / (totalHeight / 2.0D);
        return normalizedAcross * normalizedAcross + normalizedHeight * normalizedHeight;
    }

    record Target(
            double centerX,
            double centerY,
            double centerZ,
            double acrossX,
            double acrossZ,
            double width,
            double height,
            double depth) {}

    record Hit(double distance, double centerScore) {}

    private record Range(double near, double far) {}
}
