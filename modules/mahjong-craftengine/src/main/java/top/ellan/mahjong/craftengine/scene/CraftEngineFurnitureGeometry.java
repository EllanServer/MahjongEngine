package top.ellan.mahjong.craftengine.scene;

import java.util.Objects;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.core.world.WorldPosition;
import org.bukkit.Location;
import top.ellan.mahjong.presentation.node.SceneTransform;

final class CraftEngineFurnitureGeometry {
    private CraftEngineFurnitureGeometry() {}

    static WorldPosition position(Location location) {
        org.bukkit.World world = Objects.requireNonNull(location.getWorld(), "target world");
        return new WorldPosition(
                BukkitAdaptor.adapt(world),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getPitch(),
                location.getYaw());
    }

    static boolean sameLocation(Location first, Location second) {
        return Objects.equals(first.getWorld(), second.getWorld())
                && Math.abs(first.getX() - second.getX()) < 1.0E-6D
                && Math.abs(first.getY() - second.getY()) < 1.0E-6D
                && Math.abs(first.getZ() - second.getZ()) < 1.0E-6D
                && Math.abs(first.getPitch() - second.getPitch()) < 1.0E-4F
                && Math.abs(Math.IEEEremainder(first.getYaw() - second.getYaw(), 360.0D))
                        < 1.0E-4D;
    }

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
