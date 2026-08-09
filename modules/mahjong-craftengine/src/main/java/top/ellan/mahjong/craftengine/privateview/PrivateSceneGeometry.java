package top.ellan.mahjong.craftengine.privateview;

import org.bukkit.Location;
import top.ellan.mahjong.presentation.SceneTransform;

/** Converts the shared table-local coordinate system into one Paper world location. */
final class PrivateSceneGeometry {
    private PrivateSceneGeometry() {}

    static Location localToWorld(Location anchor, SceneTransform transform) {
        double yaw = Math.toRadians(anchor.getYaw());
        double x = transform.x() * Math.cos(yaw) - transform.z() * Math.sin(yaw);
        double z = transform.x() * Math.sin(yaw) + transform.z() * Math.cos(yaw);
        Location result = anchor.clone().add(x, transform.y(), z);
        result.setYaw((float) (anchor.getYaw() + transform.yawDegrees()));
        result.setPitch((float) transform.pitchDegrees());
        return result;
    }
}
