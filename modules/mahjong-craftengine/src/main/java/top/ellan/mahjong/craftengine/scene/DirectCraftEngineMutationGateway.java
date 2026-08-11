package top.ellan.mahjong.craftengine.scene;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import top.ellan.mahjong.application.interaction.InteractionHandle;
import top.ellan.mahjong.craftengine.port.CraftEngineMutationGateway;
import top.ellan.mahjong.craftengine.port.PrivateProjectionGateway;
import top.ellan.mahjong.platform.paper.anchor.TableAnchorLookup;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.presentation.node.FurnitureNode;
import top.ellan.mahjong.presentation.node.InteractionNode;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.presentation.node.SceneTransform;

/** Direct CE 26.7 furniture adapter. Absence or API failure throws; there is no display-entity fallback. */
public final class DirectCraftEngineMutationGateway implements CraftEngineMutationGateway {
    public static final String MANAGED_KEY = "scene_managed";
    public static final String TABLE_KEY = "scene_table";
    public static final String NODE_KEY = "scene_node";
    public static final String INTERACTION_KEY = "scene_interaction";

    private final TableAnchorLookup anchors;
    private final PrivateProjectionGateway privateProjection;
    private final NamespacedKey managedKey;
    private final NamespacedKey tableKey;
    private final NamespacedKey nodeKey;
    private final NamespacedKey interactionKey;
    private final ConcurrentHashMap<NodeKey, WorldFurniture> worldEntities =
            new ConcurrentHashMap<>();

    public DirectCraftEngineMutationGateway(
            Plugin plugin,
            TableAnchorLookup anchors,
            PrivateProjectionGateway privateProjection) {
        Objects.requireNonNull(plugin, "plugin");
        this.anchors = Objects.requireNonNull(anchors, "anchors");
        this.privateProjection = Objects.requireNonNull(privateProjection, "privateProjection");
        managedKey = new NamespacedKey(plugin, MANAGED_KEY);
        tableKey = new NamespacedKey(plugin, TABLE_KEY);
        nodeKey = new NamespacedKey(plugin, NODE_KEY);
        interactionKey = new NamespacedKey(plugin, INTERACTION_KEY);
    }

    @Override
    public void upsert(TableId tableId, SceneNode node) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(node, "node");
        if (!node.worldBacked()) {
            removeWorldEntity(new NodeKey(tableId, node.id()));
            privateProjection.upsert(tableId, node);
            return;
        }
        NodeKey key = new NodeKey(tableId, node.id());
        if (node instanceof FurnitureNode furniture && updateVariant(key, furniture)) {
            privateProjection.remove(tableId, node.id());
            return;
        }
        removeWorldEntity(key);
        privateProjection.remove(tableId, node.id());
        Location anchor =
                anchors.location(tableId)
                        .orElseThrow(
                                () -> new IllegalStateException("No anchor for table " + tableId));
        String asset;
        SceneTransform transform;
        InteractionHandle handle = null;
        if (node instanceof FurnitureNode furniture) {
            asset = furniture.assetId();
            transform = furniture.transform();
        } else if (node instanceof InteractionNode interaction) {
            asset = interaction.assetId();
            transform = interaction.transform();
            handle = interaction.handle();
        } else {
            throw new IllegalArgumentException("Unsupported world-backed scene node: " + node.getClass());
        }
        Location location = localToWorld(anchor, transform);
        BukkitFurniture furniture = node instanceof FurnitureNode furnitureNode
                ? CraftEngineFurniture.place(
                        location, Key.of(asset), furnitureNode.variant(), false)
                : CraftEngineFurniture.place(location, Key.of(asset));
        if (furniture == null || furniture.bukkitEntity() == null) {
            throw new IllegalStateException("CraftEngine could not place furniture asset " + asset);
        }
        Entity entity = furniture.bukkitEntity();
        entity.setPersistent(false);
        entity.getPersistentDataContainer().set(managedKey, PersistentDataType.BYTE, (byte) 1);
        entity.getPersistentDataContainer().set(
                tableKey, PersistentDataType.STRING, tableId.toString());
        entity.getPersistentDataContainer().set(
                nodeKey, PersistentDataType.STRING, node.id().value());
        if (handle != null) {
            entity.getPersistentDataContainer().set(
                    interactionKey, PersistentDataType.STRING, handle.value().toString());
        }
        worldEntities.put(key, new WorldFurniture(entity, node));
    }

    @Override
    public void remove(TableId tableId, SceneNodeId nodeId) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(nodeId, "nodeId");
        removeWorldEntity(new NodeKey(tableId, nodeId));
        privateProjection.remove(tableId, nodeId);
    }

    public NamespacedKey managedKey() {
        return managedKey;
    }

    public NamespacedKey tableKey() {
        return tableKey;
    }

    public NamespacedKey nodeKey() {
        return nodeKey;
    }

    public NamespacedKey interactionKey() {
        return interactionKey;
    }

    private void removeWorldEntity(NodeKey key) {
        WorldFurniture existing = worldEntities.remove(key);
        if (existing == null) {
            return;
        }
        Entity entity = existing.entity();
        if (entity.isValid() && CraftEngineFurniture.isFurniture(entity)) {
            if (!CraftEngineFurniture.remove(entity, false, false)) {
                throw new IllegalStateException("CraftEngine refused to remove managed furniture");
            }
        } else if (entity.isValid()) {
            throw new IllegalStateException("Managed CE entity is no longer recognized as furniture");
        }
    }

    private boolean updateVariant(NodeKey key, FurnitureNode desired) {
        WorldFurniture existing = worldEntities.get(key);
        if (existing == null
                || !(existing.node() instanceof FurnitureNode previous)
                || !previous.assetId().equals(desired.assetId())
                || !previous.transform().equals(desired.transform())
                || !previous.visibility().equals(desired.visibility())
                || previous.variant().equals(desired.variant())) {
            return false;
        }
        Entity entity = existing.entity();
        if (!entity.isValid()) {
            return false;
        }
        BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
        if (furniture == null) {
            return false;
        }
        boolean changed = furniture.setVariant(desired.variant(), true);
        if (!changed && !furniture.currentVariant().name().equals(desired.variant())) {
            throw new IllegalStateException(
                    "CraftEngine refused furniture variant " + desired.variant());
        }
        worldEntities.put(key, new WorldFurniture(entity, desired));
        return true;
    }

    private static Location localToWorld(Location anchor, SceneTransform transform) {
        double yaw = Math.toRadians(anchor.getYaw());
        double x = transform.x() * Math.cos(yaw) - transform.z() * Math.sin(yaw);
        double z = transform.x() * Math.sin(yaw) + transform.z() * Math.cos(yaw);
        Location result = anchor.clone().add(x, transform.y(), z);
        result.setYaw((float) (anchor.getYaw() + transform.yawDegrees()));
        result.setPitch((float) transform.pitchDegrees());
        return result;
    }

    private record NodeKey(TableId tableId, SceneNodeId nodeId) {
        private NodeKey {
            Objects.requireNonNull(tableId, "tableId");
            Objects.requireNonNull(nodeId, "nodeId");
        }
    }

    private record WorldFurniture(Entity entity, SceneNode node) {
        private WorldFurniture {
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(node, "node");
        }
    }
}
