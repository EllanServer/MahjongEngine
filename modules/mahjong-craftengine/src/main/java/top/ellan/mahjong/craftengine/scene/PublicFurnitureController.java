package top.ellan.mahjong.craftengine.scene;

import static top.ellan.mahjong.craftengine.scene.ManagedFurnitureIdentity.Channel.PUBLIC;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurnitureManager;
import net.momirealms.craftengine.core.entity.furniture.FurnitureDefinition;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import top.ellan.mahjong.application.interaction.InteractionHandle;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.platform.paper.anchor.TableAnchorLookup;
import top.ellan.mahjong.presentation.node.FurnitureNode;
import top.ellan.mahjong.presentation.node.InteractionNode;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;

/** Owns desired public nodes while CE's behavior manager owns physical lifecycle discovery. */
final class PublicFurnitureController {
    private static final CompletionStage<Void> COMPLETED = CompletableFuture.completedStage(null);
    private final Plugin plugin;
    private final Logger logger;
    private final TableAnchorLookup anchors;
    private final CraftEngineManagedFurnitureRegistry registry;
    private final BukkitFurnitureManager furnitureManager;
    private final LongSupplier definitionEpoch;
    private final ConcurrentHashMap<FurnitureKey, SceneNode> desired = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<FurnitureKey, ManagedFurniture> loaded =
            new ConcurrentHashMap<>();

    PublicFurnitureController(
            Plugin plugin,
            Logger logger,
            TableAnchorLookup anchors,
            CraftEngineManagedFurnitureRegistry registry,
            BukkitFurnitureManager furnitureManager,
            LongSupplier definitionEpoch) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.anchors = Objects.requireNonNull(anchors, "anchors");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.furnitureManager = Objects.requireNonNull(furnitureManager, "furnitureManager");
        this.definitionEpoch = Objects.requireNonNull(definitionEpoch, "definitionEpoch");
    }

    CompletionStage<Void> upsert(TableId tableId, SceneNode node) {
        FurnitureKey key = new FurnitureKey(tableId, node.id());
        desired.put(key, node);
        if (!resolve(key, node)) {
            return COMPLETED;
        }
        if (node instanceof FurnitureNode furniture) {
            CompletionStage<Void> update = update(key, furniture);
            if (update != null) {
                return update;
            }
        }
        removeLoaded(key);
        Location anchor = anchor(tableId);
        String asset;
        String variant = null;
        Location location;
        Optional<InteractionHandle> interaction = Optional.empty();
        ManagedKind kind;
        if (node instanceof FurnitureNode furniture) {
            asset = furniture.assetId();
            variant = furniture.variant();
            location = CraftEngineFurnitureGeometry.localToWorld(anchor, furniture.transform());
            kind = ManagedKind.FURNITURE;
        } else if (node instanceof InteractionNode interactionNode) {
            asset = interactionNode.assetId();
            location = CraftEngineFurnitureGeometry.localToWorld(anchor, interactionNode.transform());
            interaction = Optional.of(interactionNode.handle());
            kind = ManagedKind.INTERACTION;
        } else {
            throw new IllegalArgumentException("Unsupported world node: " + node.getClass());
        }
        if (!chunkLoaded(location)) {
            return COMPLETED;
        }
        FurnitureDefinition definition = furnitureManager
                .furnitureById(Key.of(asset))
                .orElseThrow(() -> new IllegalStateException("Unknown CraftEngine furniture " + asset));
        ManagedFurnitureIdentity identity =
                new ManagedFurnitureIdentity(tableId, node.id(), PUBLIC, interaction);
        BukkitFurniture placed = furnitureManager.place(
                location, definition, identity.persistentData(variant), false, null);
        if (placed == null || placed.bukkitEntity() == null) {
            throw new IllegalStateException("CraftEngine could not place furniture asset " + asset);
        }
        loaded.put(key, new ManagedFurniture(placed, asset, kind, definitionEpoch.getAsLong()));
        return COMPLETED;
    }

    void remove(TableId tableId, SceneNodeId nodeId) {
        FurnitureKey key = new FurnitureKey(tableId, nodeId);
        desired.remove(key);
        removeLoaded(key);
    }

    boolean desires(TableId tableId, SceneNodeId nodeId) {
        return desired.containsKey(new FurnitureKey(tableId, nodeId));
    }

    boolean adopt(ManagedFurnitureIdentity identity, BukkitFurniture furniture, long epoch) {
        if (identity.channel() != PUBLIC) {
            return false;
        }
        FurnitureKey key = new FurnitureKey(identity.tableId(), identity.nodeId());
        SceneNode wanted = desired.get(key);
        if (wanted == null || !matches(wanted, furniture, key.tableId())) {
            return false;
        }
        ManagedKind kind = wanted instanceof InteractionNode
                ? ManagedKind.INTERACTION
                : ManagedKind.FURNITURE;
        ManagedFurniture candidate =
                new ManagedFurniture(furniture, furniture.id().asString(), kind, epoch);
        ManagedFurniture winner = loaded.compute(
                key, (ignored, current) -> replaceable(current, furniture) ? candidate : current);
        return winner == candidate;
    }

    void forget(ManagedFurnitureIdentity identity, BukkitFurniture furniture) {
        if (identity.channel() != PUBLIC) {
            return;
        }
        FurnitureKey key = new FurnitureKey(identity.tableId(), identity.nodeId());
        loaded.computeIfPresent(
                key, (ignored, current) -> current.furniture() == furniture ? null : current);
    }

    void clear() {
        desired.clear();
        loaded.clear();
    }

    private boolean resolve(FurnitureKey key, SceneNode node) {
        ManagedFurnitureIdentity identity = identity(key, node);
        ManagedFurniture current = loaded.get(key);
        Entity currentEntity = current == null ? null : current.furniture().bukkitEntity();
        List<BukkitFurniture> candidates = registry.furniture(identity);
        if (currentEntity != null
                && currentEntity.isValid()
                && Bukkit.isOwnedByCurrentRegion(currentEntity)
                && candidates.contains(current.furniture())) {
            return true;
        }
        if (current != null) {
            loaded.remove(key, current);
        }
        BukkitFurniture selected = null;
        for (BukkitFurniture candidate : candidates) {
            Entity entity = candidate.bukkitEntity();
            if (entity == null || !Bukkit.isOwnedByCurrentRegion(entity)) {
                removePhysical(key, candidate, "misplaced");
            } else if (selected == null && matches(node, candidate, key.tableId())) {
                selected = candidate;
            } else {
                removePhysical(key, candidate, "duplicate or misplaced");
            }
        }
        if (selected != null) {
            adopt(identity, selected, definitionEpoch.getAsLong());
            return true;
        }
        return chunkLoaded(target(key.tableId(), node));
    }

    private CompletionStage<Void> update(FurnitureKey key, FurnitureNode wanted) {
        ManagedFurniture existing = loaded.get(key);
        if (existing == null
                || existing.epoch() != definitionEpoch.getAsLong()
                || existing.kind() != ManagedKind.FURNITURE
                || !existing.assetId().equals(wanted.assetId())) {
            return null;
        }
        BukkitFurniture furniture = existing.furniture();
        Entity entity = furniture.bukkitEntity();
        if (entity == null
                || !entity.isValid()
                || !Bukkit.isOwnedByCurrentRegion(entity)
                || furnitureManager.loadedFurnitureByMetaEntityId(entity.getEntityId()) != furniture) {
            loaded.remove(key, existing);
            return null;
        }
        Location target = target(key.tableId(), wanted);
        boolean variantChanged = !furniture.currentVariant().name().equals(wanted.variant());
        boolean transformChanged = !CraftEngineFurnitureGeometry.sameLocation(entity.getLocation(), target);
        if (variantChanged) {
            boolean changed = furniture.setVariant(wanted.variant(), true);
            if (!changed && !furniture.currentVariant().name().equals(wanted.variant())) {
                throw new IllegalStateException("CraftEngine refused furniture variant " + wanted.variant());
            }
        }
        if (!transformChanged) {
            return COMPLETED;
        }
        return furniture.moveTo(CraftEngineFurnitureGeometry.position(target), true).thenAccept(moved -> {
            if (!Boolean.TRUE.equals(moved)) {
                throw new IllegalStateException("CraftEngine refused furniture move");
            }
        });
    }

    private void removeLoaded(FurnitureKey key) {
        SceneNode wanted = desired.get(key);
        ManagedFurnitureIdentity identity = identity(key, wanted);
        HashSet<BukkitFurniture> physical = new HashSet<>(registry.furniture(identity));
        ManagedFurniture existing = loaded.remove(key);
        if (existing != null) {
            physical.add(existing.furniture());
        }
        physical.forEach(furniture -> removePhysical(key, furniture, "managed"));
    }

    private void removePhysical(FurnitureKey key, BukkitFurniture furniture, String reason) {
        CraftEngineFurnitureRemoval.runOnOwner(plugin, furniture, () -> {
            if (CraftEngineFurnitureRemoval.remove(furniture)) {
                logger.warning("CraftEngine lost " + reason + " furniture " + key.tableId() + '/'
                        + key.nodeId().value());
            }
        });
    }

    private boolean matches(SceneNode node, BukkitFurniture furniture, TableId tableId) {
        Entity entity = furniture.bukkitEntity();
        return entity != null
                && entity.isValid()
                && furniture.id().asString().equals(asset(node))
                && CraftEngineFurnitureGeometry.sameLocation(entity.getLocation(), target(tableId, node));
    }

    private Location target(TableId tableId, SceneNode node) {
        return CraftEngineFurnitureGeometry.localToWorld(
                anchor(tableId),
                node instanceof FurnitureNode furniture
                        ? furniture.transform()
                        : ((InteractionNode) node).transform());
    }

    private ManagedFurnitureIdentity identity(FurnitureKey key, SceneNode node) {
        Optional<InteractionHandle> interaction = node instanceof InteractionNode interactionNode
                ? Optional.of(interactionNode.handle())
                : Optional.empty();
        return new ManagedFurnitureIdentity(key.tableId(), key.nodeId(), PUBLIC, interaction);
    }

    private static String asset(SceneNode node) {
        return node instanceof FurnitureNode furniture
                ? furniture.assetId()
                : ((InteractionNode) node).assetId();
    }

    private static boolean chunkLoaded(Location location) {
        return location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private Location anchor(TableId tableId) {
        return anchors.location(tableId)
                .orElseThrow(() -> new IllegalStateException("No anchor for table " + tableId));
    }

    private static boolean replaceable(ManagedFurniture current, BukkitFurniture candidate) {
        return current == null
                || current.furniture().bukkitEntity() == null
                || !current.furniture().bukkitEntity().isValid()
                || current.furniture() == candidate;
    }

    private record FurnitureKey(TableId tableId, SceneNodeId nodeId) {
        private FurnitureKey {
            Objects.requireNonNull(tableId, "tableId");
            Objects.requireNonNull(nodeId, "nodeId");
        }
    }

    private enum ManagedKind {
        FURNITURE,
        INTERACTION
    }

    private record ManagedFurniture(
            BukkitFurniture furniture, String assetId, ManagedKind kind, long epoch) {
        private ManagedFurniture {
            Objects.requireNonNull(furniture, "furniture");
            Objects.requireNonNull(assetId, "assetId");
            Objects.requireNonNull(kind, "kind");
        }
    }
}
