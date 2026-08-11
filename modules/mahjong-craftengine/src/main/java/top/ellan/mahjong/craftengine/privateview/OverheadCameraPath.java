package top.ellan.mahjong.craftengine.privateview;

import org.bukkit.Location;

/** Smooth two-endpoint camera path with exact endpoints and no per-frame allocation beyond Location. */
final class OverheadCameraPath {
    private static final int CLIENT_INTERPOLATION_TICKS = 10;

    private OverheadCameraPath() {}

    static Location frame(Location start, Location target, int frame, int frameCount) {
        if (start == null || target == null || start.getWorld() != target.getWorld()) {
            throw new IllegalArgumentException("Camera endpoints must share a world");
        }
        int frames = Math.max(1, frameCount);
        if (frame <= 0) {
            return start.clone();
        }
        if (frame >= frames) {
            return target.clone();
        }
        double progress = frame / (double) frames;
        Location result = start.clone();
        result.setX(smooth(start.getX(), target.getX(), progress));
        result.setY(smooth(start.getY(), target.getY(), progress));
        result.setZ(smooth(start.getZ(), target.getZ(), progress));
        double targetYaw = unwrap(start.getYaw(), target.getYaw());
        result.setYaw((float) smooth(start.getYaw(), targetYaw, progress));
        result.setPitch((float) smooth(start.getPitch(), target.getPitch(), progress));
        return result;
    }

    static int clientInterpolationTicks(int transitionTicks) {
        int ticks = Math.max(1, transitionTicks);
        return Math.min(CLIENT_INTERPOLATION_TICKS, ticks - 1);
    }

    static int serverKeyframeCount(
            int transitionTicks, boolean clientInterpolationConfigured) {
        int ticks = Math.max(1, transitionTicks);
        return clientInterpolationConfigured
                ? ticks - clientInterpolationTicks(ticks)
                : ticks;
    }

    static int targetArrivalTick(
            int transitionTicks, boolean clientInterpolationConfigured) {
        int clientTicks = clientInterpolationConfigured
                ? clientInterpolationTicks(transitionTicks)
                : 0;
        return serverKeyframeCount(transitionTicks, clientInterpolationConfigured) + clientTicks;
    }

    private static double smooth(double start, double end, double progress) {
        double curved = progress * progress * (3.0D - 2.0D * progress);
        return start + (end - start) * curved;
    }

    private static double unwrap(double reference, double angle) {
        double delta = (angle - reference) % 360.0D;
        if (delta > 180.0D) {
            delta -= 360.0D;
        } else if (delta < -180.0D) {
            delta += 360.0D;
        }
        return reference + delta;
    }
}
