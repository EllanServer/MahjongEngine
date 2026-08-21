package top.ellan.mahjong.craftengine.interaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import top.ellan.mahjong.application.interaction.InteractionHandle;
import top.ellan.mahjong.application.interaction.InteractionRouteBinding;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.platform.paper.anchor.TableAnchorLookup;
import top.ellan.mahjong.presentation.node.InteractionBounds;
import top.ellan.mahjong.presentation.node.InteractionNode;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.presentation.node.SceneTransform;
import top.ellan.mahjong.spi.PlayerId;

/**
 * Bounded per-viewer interaction planes. CraftEngine entities only wake the client input packet;
 * this registry performs the authoritative 1.5.0-style closest-ray selection.
 */
public final class InteractionRayRegistry {
    private static final double EPSILON = 1.0E-7D;

    private final TableAnchorLookup anchors;
    private final Object updateLock = new Object();
    private final ConcurrentHashMap<UUID, Map<TableId, List<LocalTarget>>> targetsByPlayer =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TableId, Set<UUID>> playersByTable =
            new ConcurrentHashMap<>();

    public InteractionRayRegistry(TableAnchorLookup anchors) {
        this.anchors = Objects.requireNonNull(anchors, "anchors");
    }

    /** Atomically replaces every route for one fully-applied table revision. */
    public void replace(
            TableId tableId,
            Map<SceneNodeId, SceneNode> nodes,
            List<InteractionRouteBinding> bindings) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(bindings, "bindings");

        Map<InteractionHandle, List<InteractionNode>> interactions = new LinkedHashMap<>();
        for (SceneNodeId nodeId : nodes.keySet().stream().sorted().toList()) {
            SceneNode node = nodes.get(nodeId);
            if (node instanceof InteractionNode interaction) {
                interactions.computeIfAbsent(interaction.handle(), ignored -> new ArrayList<>())
                        .add(interaction);
            }
        }
        Map<UUID, List<LocalTarget>> next = new LinkedHashMap<>();
        for (InteractionRouteBinding binding : bindings) {
            List<InteractionNode> interactionNodes = interactions.get(binding.handle());
            if (interactionNodes == null) {
                // Production SceneGraph validation rejects missing nodes. Synthetic backend tests
                // may omit them, leaving the corresponding route deliberately non-clickable.
                continue;
            }
            List<LocalTarget> playerTargets = next.computeIfAbsent(
                    binding.playerId().value(), ignored -> new ArrayList<>());
            for (InteractionNode node : interactionNodes) {
                playerTargets.add(new LocalTarget(node.handle(), node.bounds(), node.transform()));
            }
        }
        next.replaceAll((ignored, targets) -> List.copyOf(targets));

        synchronized (updateLock) {
            Set<UUID> previous = playersByTable.remove(tableId);
            if (previous != null) {
                for (UUID player : previous) {
                    targetsByPlayer.computeIfPresent(
                            player,
                            (ignored, tables) -> withoutTable(tables, tableId));
                }
            }
            if (next.isEmpty()) {
                return;
            }
            for (Map.Entry<UUID, List<LocalTarget>> entry : next.entrySet()) {
                targetsByPlayer.compute(
                        entry.getKey(),
                        (ignored, tables) -> withTable(tables, tableId, entry.getValue()));
            }
            playersByTable.put(tableId, Set.copyOf(next.keySet()));
        }
    }

    public void removeTable(TableId tableId) {
        TableId required = Objects.requireNonNull(tableId, "tableId");
        synchronized (updateLock) {
            Set<UUID> players = playersByTable.remove(required);
            if (players == null) {
                return;
            }
            for (UUID player : players) {
                targetsByPlayer.computeIfPresent(
                        player,
                        (ignored, tables) -> withoutTable(tables, required));
            }
        }
    }

    public void clear() {
        synchronized (updateLock) {
            playersByTable.clear();
            targetsByPlayer.clear();
        }
    }

    public Optional<InteractionHandle> resolve(Player player, double maxDistance) {
        return resolve(player, maxDistance, null);
    }

    public Optional<InteractionHandle> resolve(
            Player player, double maxDistance, TableId requiredTable) {
        Objects.requireNonNull(player, "player");
        Location eye = player.getEyeLocation();
        World world = eye.getWorld();
        if (world == null) {
            return Optional.empty();
        }
        Vector direction = eye.getDirection();
        return resolve(
                player.getUniqueId(),
                world.getUID(),
                eye.getX(),
                eye.getY(),
                eye.getZ(),
                direction.getX(),
                direction.getY(),
                direction.getZ(),
                maxDistance,
                requiredTable);
    }

    Optional<InteractionHandle> resolve(
            PlayerId player,
            UUID worldId,
            double originX,
            double originY,
            double originZ,
            double directionX,
            double directionY,
            double directionZ,
            double maxDistance) {
        return resolve(
                player.value(),
                worldId,
                originX,
                originY,
                originZ,
                directionX,
                directionY,
                directionZ,
                maxDistance,
                null);
    }

    private Optional<InteractionHandle> resolve(
            UUID player,
            UUID worldId,
            double originX,
            double originY,
            double originZ,
            double directionX,
            double directionY,
            double directionZ,
            double maxDistance,
            TableId requiredTable) {
        Map<TableId, List<LocalTarget>> tables = targetsByPlayer.get(player);
        if (tables == null
                || tables.isEmpty()
                || !Double.isFinite(maxDistance)
                || maxDistance <= 0.0D) {
            return Optional.empty();
        }
        double directionLength = Math.sqrt(
                directionX * directionX + directionY * directionY + directionZ * directionZ);
        if (!Double.isFinite(directionLength) || directionLength <= EPSILON) {
            return Optional.empty();
        }
        double rayX = directionX / directionLength;
        double rayY = directionY / directionLength;
        double rayZ = directionZ / directionLength;
        InteractionRayMath.Hit closest = null;
        InteractionHandle closestHandle = null;
        for (Map.Entry<TableId, List<LocalTarget>> entry : tables.entrySet()) {
            if (requiredTable != null && !requiredTable.equals(entry.getKey())) {
                continue;
            }
            Location anchor = anchors.location(entry.getKey()).orElse(null);
            if (anchor == null
                    || anchor.getWorld() == null
                    || !anchor.getWorld().getUID().equals(worldId)) {
                continue;
            }
            for (LocalTarget target : entry.getValue()) {
                InteractionRayMath.Target worldTarget = toWorld(anchor, target);
                InteractionRayMath.Hit hit = InteractionRayMath.intersect(
                        worldTarget,
                        originX,
                        originY,
                        originZ,
                        rayX,
                        rayY,
                        rayZ,
                        maxDistance);
                if (hit == null
                        || (closest != null
                                && hit.distance() > closest.distance() + EPSILON)) {
                    continue;
                }
                if (closest == null
                        || hit.distance() < closest.distance() - EPSILON
                        || hit.centerScore() < closest.centerScore()) {
                    closest = hit;
                    closestHandle = target.handle();
                }
            }
        }
        return Optional.ofNullable(closestHandle);
    }

    public boolean hasTargets(UUID playerId, TableId requiredTable) {
        Map<TableId, List<LocalTarget>> tables =
                targetsByPlayer.get(Objects.requireNonNull(playerId, "playerId"));
        if (tables == null || tables.isEmpty()) {
            return false;
        }
        return requiredTable == null || tables.containsKey(requiredTable);
    }

    int targetCount(PlayerId playerId) {
        Map<TableId, List<LocalTarget>> tables = targetsByPlayer.get(playerId.value());
        if (tables == null) {
            return 0;
        }
        int count = 0;
        for (List<LocalTarget> targets : tables.values()) {
            count += targets.size();
        }
        return count;
    }

    private static Map<TableId, List<LocalTarget>> withTable(
            Map<TableId, List<LocalTarget>> current,
            TableId tableId,
            List<LocalTarget> targets) {
        LinkedHashMap<TableId, List<LocalTarget>> updated =
                current == null ? new LinkedHashMap<>() : new LinkedHashMap<>(current);
        updated.put(tableId, targets);
        return Collections.unmodifiableMap(updated);
    }

    private static Map<TableId, List<LocalTarget>> withoutTable(
            Map<TableId, List<LocalTarget>> current, TableId tableId) {
        if (!current.containsKey(tableId)) {
            return current;
        }
        LinkedHashMap<TableId, List<LocalTarget>> updated = new LinkedHashMap<>(current);
        updated.remove(tableId);
        return updated.isEmpty() ? null : Map.copyOf(updated);
    }

    private static InteractionRayMath.Target toWorld(Location anchor, LocalTarget target) {
        SceneTransform transform = target.transform();
        double anchorYaw = Math.toRadians(anchor.getYaw());
        double cos = Math.cos(anchorYaw);
        double sin = Math.sin(anchorYaw);
        double x = anchor.getX() + transform.x() * cos - transform.z() * sin;
        double z = anchor.getZ() + transform.x() * sin + transform.z() * cos;
        double yaw = Math.toRadians(anchor.getYaw() + transform.yawDegrees());
        double scale = transform.scale();
        InteractionBounds bounds = target.bounds();
        return new InteractionRayMath.Target(
                x,
                anchor.getY() + transform.y() + bounds.centerYOffset() * scale,
                z,
                Math.cos(yaw),
                Math.sin(yaw),
                bounds.width() * scale,
                bounds.height() * scale,
                bounds.depth() * scale);
    }

    private record LocalTarget(
            InteractionHandle handle, InteractionBounds bounds, SceneTransform transform) {
        private LocalTarget {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(transform, "transform");
        }
    }

}
