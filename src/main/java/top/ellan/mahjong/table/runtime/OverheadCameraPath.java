package top.ellan.mahjong.table.runtime;

import org.bukkit.Location;

public final class OverheadCameraPath {
    /** Matches TypeWriter's display-camera interpolation window. */
    static final int CLIENT_INTERPOLATION_TICKS = 10;

    private OverheadCameraPath() {
    }

    public static Location interpolate(Location start, Location target, int frame, int frameCount) {
        if (start == null || target == null) {
            throw new IllegalArgumentException("Camera path endpoints are required");
        }
        if (start.getWorld() != target.getWorld()) {
            throw new IllegalArgumentException("Camera path endpoints must be in the same world");
        }
        int safeFrameCount = Math.max(1, frameCount);
        if (frame <= 0) {
            return start.clone();
        }
        if (frame >= safeFrameCount) {
            return target.clone();
        }
        double progress = Math.max(0.0D, Math.min(1.0D, frame / (double) safeFrameCount));
        Location result = start.clone();
        // TypeWriter samples cinematic paths with Catmull-Rom interpolation. An overhead
        // transition currently has two authored endpoints, so duplicate the outer control
        // points just as its path sampler does at a path boundary. This keeps both endpoints
        // exact while avoiding the constant-velocity look of a linear teleport sequence.
        result.setX(catmullRom(start.getX(), start.getX(), target.getX(), target.getX(), progress));
        result.setY(catmullRom(start.getY(), start.getY(), target.getY(), target.getY(), progress));
        result.setZ(catmullRom(start.getZ(), start.getZ(), target.getZ(), target.getZ(), progress));

        double startYaw = start.getYaw();
        double targetYaw = unwrapAngle(startYaw, target.getYaw());
        result.setYaw((float) catmullRom(startYaw, startYaw, targetYaw, targetYaw, progress));
        result.setPitch((float) catmullRom(
            start.getPitch(),
            start.getPitch(),
            target.getPitch(),
            target.getPitch(),
            progress
        ));
        return result;
    }

    static int clientInterpolationTicks(int transitionTicks) {
        int safeTransitionTicks = Math.max(1, transitionTicks);
        return Math.min(CLIENT_INTERPOLATION_TICKS, safeTransitionTicks - 1);
    }

    static int serverKeyframeCount(int transitionTicks, boolean clientInterpolationConfigured) {
        int safeTransitionTicks = Math.max(1, transitionTicks);
        if (!clientInterpolationConfigured) {
            return safeTransitionTicks;
        }
        return safeTransitionTicks - clientInterpolationTicks(safeTransitionTicks);
    }

    /** Tick at which the final client interpolation reaches the target. */
    static int targetArrivalTick(int transitionTicks, boolean clientInterpolationConfigured) {
        int interpolationTicks = clientInterpolationConfigured ? clientInterpolationTicks(transitionTicks) : 0;
        // Frame one is scheduled at tick one, therefore the final server keyframe
        // is sent at tick serverKeyframeCount rather than one tick earlier.
        return serverKeyframeCount(transitionTicks, clientInterpolationConfigured) + interpolationTicks;
    }

    static double catmullRom(double previous, double current, double next, double nextNext, double progress) {
        double square = progress * progress;
        double cube = square * progress;
        double currentTangent = (next - previous) * 0.5D;
        double nextTangent = (nextNext - current) * 0.5D;
        double currentWeight = (2.0D * cube) - (3.0D * square) + 1.0D;
        double currentTangentWeight = cube - (2.0D * square) + progress;
        double nextWeight = (-2.0D * cube) + (3.0D * square);
        double nextTangentWeight = cube - square;
        return (currentWeight * current)
            + (currentTangentWeight * currentTangent)
            + (nextWeight * next)
            + (nextTangentWeight * nextTangent);
    }

    private static double unwrapAngle(double reference, double angle) {
        double delta = (angle - reference) % 360.0D;
        if (delta > 180.0D) {
            delta -= 360.0D;
        } else if (delta < -180.0D) {
            delta += 360.0D;
        }
        return reference + delta;
    }
}
