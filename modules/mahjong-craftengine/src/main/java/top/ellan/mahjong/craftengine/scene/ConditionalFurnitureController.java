package top.ellan.mahjong.craftengine.scene;

import static top.ellan.mahjong.craftengine.scene.ConditionalFurniturePresentation.asset;
import static top.ellan.mahjong.craftengine.scene.ConditionalFurniturePresentation.setVariant;
import static top.ellan.mahjong.craftengine.scene.ConditionalFurniturePresentation.singleViewer;
import static top.ellan.mahjong.craftengine.scene.ConditionalFurniturePresentation.transform;
import static top.ellan.mahjong.craftengine.scene.ManagedFurnitureIdentity.Channel.CONDITIONAL;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurnitureManager;
import net.momirealms.craftengine.core.entity.furniture.FurnitureDefinition;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import top.ellan.mahjong.craftengine.privateview.PrivateFurnitureVisibility;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.platform.paper.anchor.TableAnchorLookup;
import top.ellan.mahjong.presentation.node.ActionFurnitureNode;
import top.ellan.mahjong.presentation.node.PrivateFurnitureNode;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.TileInstanceId;

/** Owns desired private nodes while CE behavior callbacks own their physical lifecycle. */
final class ConditionalFurnitureController implements AutoCloseable {
    private static final CompletionStage<Void> COMPLETED = CompletableFuture.completedStage(null);
    private final Plugin plugin;
    private final Logger logger;
    private final TableAnchorLookup anchors;
    private final PrivateFurnitureVisibility visibility;
    private final CraftEngineManagedFurnitureRegistry registry;
    private final BukkitFurnitureManager furnitureManager;
    private final LongSupplier definitionEpoch;
    private final ConcurrentHashMap<ConditionalFurnitureKey, SceneNode> desired =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ConditionalFurnitureKey, ManagedConditionalFurniture> loaded =
            new ConcurrentHashMap<>();
    private final PrivateTileSelectionIndex selections = new PrivateTileSelectionIndex();

    ConditionalFurnitureController(
            Plugin plugin,
            Logger logger,
            TableAnchorLookup anchors,
            PrivateFurnitureVisibility visibility,
            CraftEngineManagedFurnitureRegistry registry,
            BukkitFurnitureManager furnitureManager,
            LongSupplier definitionEpoch) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.anchors = Objects.requireNonNull(anchors, "anchors");
        this.visibility = Objects.requireNonNull(visibility, "visibility");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.furnitureManager = Objects.requireNonNull(furnitureManager, "furnitureManager");
        this.definitionEpoch = Objects.requireNonNull(definitionEpoch, "definitionEpoch");
    }

    boolean supports(SceneNode node) {
        return node instanceof PrivateFurnitureNode || node instanceof ActionFurnitureNode;
    }

    CompletionStage<Void> upsert(TableId tableId, SceneNode node) {
        ConditionalFurnitureKey key = new ConditionalFurnitureKey(tableId, node.id());
        PlayerId viewer = singleViewer(node);
        String asset = asset(node);
        String variant = variant(tableId, viewer, node);
        desired.put(key, node);
        if (!resolve(key, node)) {
            return COMPLETED;
        }
        ManagedConditionalFurniture existing = loaded.get(key);
        if (usable(existing, asset)) {
            BukkitFurniture furniture = existing.furniture();
            ManagedConditionalFurniture updated = new ManagedConditionalFurniture(
                    furniture, asset, definitionEpoch.getAsLong(), node);
            selections.unindex(key, existing.node());
            loaded.put(key, updated);
            selections.index(key, node, viewer);
            visibility.authorize(furniture, node.visibility());
            setVariant(furniture, variant);
            return move(key, updated);
        }
        removeLoaded(key);
        Location location = target(key.tableId(), node);
        if (!chunkLoaded(location)) {
            return COMPLETED;
        }
        FurnitureDefinition definition = furnitureManager
                .furnitureById(Key.of(asset))
                .orElseThrow(() -> new IllegalStateException("Unknown CraftEngine furniture " + asset));
        ManagedFurnitureIdentity identity = identity(key);
        BukkitFurniture furniture = furnitureManager.place(
                location, definition, identity.persistentData(variant), false, null);
        if (furniture == null || furniture.bukkitEntity() == null) {
            throw new IllegalStateException(
                    "CraftEngine could not place conditional furniture asset " + asset);
        }
        loaded.put(
                key,
                new ManagedConditionalFurniture(
                        furniture, asset, definitionEpoch.getAsLong(), node));
        selections.index(key, node, viewer);
        visibility.authorize(furniture, node.visibility());
        return COMPLETED;
    }

    void remove(TableId tableId, SceneNodeId nodeId) {
        ConditionalFurnitureKey key = new ConditionalFurnitureKey(tableId, nodeId);
        SceneNode removed = desired.remove(key);
        selections.unindex(key, removed);
        removeLoaded(key);
    }

    boolean desires(TableId tableId, SceneNodeId nodeId) {
        return desired.containsKey(new ConditionalFurnitureKey(tableId, nodeId));
    }

    void showSelection(
            TableId tableId,
            PlayerId playerId,
            Optional<TileInstanceId> selectedTile) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(selectedTile, "selectedTile");
        selections.showSelection(tableId, playerId, selectedTile, this::refreshSelectionVariant);
    }

    void definitionsReloaded() {
        loaded.values().forEach(entry -> CraftEngineFurnitureRemoval.runOnOwner(
                plugin, entry.furniture(), () -> visibility.revoke(entry.furniture())));
    }

    boolean adopt(ManagedFurnitureIdentity identity, BukkitFurniture furniture, long epoch) {
        if (identity.channel() != CONDITIONAL) {
            return false;
        }
        ConditionalFurnitureKey key =
                new ConditionalFurnitureKey(identity.tableId(), identity.nodeId());
        SceneNode wanted = desired.get(key);
        if (wanted == null || !matches(wanted, furniture, key.tableId())) {
            return false;
        }
        ManagedConditionalFurniture candidate =
                new ManagedConditionalFurniture(furniture, furniture.id().asString(), epoch, wanted);
        ManagedConditionalFurniture winner = loaded.compute(
                key,
                (ignored, current) -> {
                    if (!replaceable(current, furniture)) {
                        return current;
                    }
                    if (current != null) {
                        visibility.forget(current.furniture());
                        selections.unindex(key, current.node());
                    }
                    return candidate;
                });
        if (winner != candidate) {
            return false;
        }
        PlayerId viewer = singleViewer(wanted);
        selections.index(key, wanted, viewer);
        visibility.authorize(furniture, wanted.visibility());
        setVariant(furniture, variant(key.tableId(), viewer, wanted));
        return true;
    }

    void forget(ManagedFurnitureIdentity identity, BukkitFurniture furniture) {
        if (identity.channel() != CONDITIONAL) {
            return;
        }
        ConditionalFurnitureKey key =
                new ConditionalFurnitureKey(identity.tableId(), identity.nodeId());
        visibility.forget(furniture);
        loaded.computeIfPresent(
                key,
                (ignored, current) -> {
                    if (current.furniture() != furniture) {
                        return current;
                    }
                    selections.unindex(key, current.node());
                    return null;
                });
    }

    @Override
    public void close() {
        desired.clear();
        loaded.clear();
        selections.clear();
        visibility.close();
    }

    private boolean resolve(ConditionalFurnitureKey key, SceneNode node) {
        ManagedFurnitureIdentity identity = identity(key);
        ManagedConditionalFurniture current = loaded.get(key);
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
            visibility.forget(current.furniture());
            selections.unindex(key, current.node());
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

    private boolean usable(ManagedConditionalFurniture existing, String asset) {
        if (existing == null
                || existing.epoch() != definitionEpoch.getAsLong()
                || !existing.assetId().equals(asset)) {
            return false;
        }
        Entity entity = existing.furniture().bukkitEntity();
        return entity != null
                && entity.isValid()
                && Bukkit.isOwnedByCurrentRegion(entity)
                && furnitureManager.loadedFurnitureByMetaEntityId(entity.getEntityId())
                        == existing.furniture();
    }

    private CompletionStage<Void> move(
            ConditionalFurnitureKey key, ManagedConditionalFurniture expected) {
        if (loaded.get(key) != expected || expected.node() == null) {
            return COMPLETED;
        }
        Location target = target(key.tableId(), expected.node());
        Entity entity = expected.furniture().bukkitEntity();
        if (entity == null
                || !entity.isValid()
                || CraftEngineFurnitureGeometry.sameLocation(entity.getLocation(), target)) {
            return COMPLETED;
        }
        return expected.furniture()
                .moveTo(CraftEngineFurnitureGeometry.position(target), true)
                .thenAccept(moved -> {
                    if (!Boolean.TRUE.equals(moved)) {
                        throw new IllegalStateException(
                                "CraftEngine refused conditional furniture move");
                    }
                });
    }

    private void removeLoaded(ConditionalFurnitureKey key) {
        HashSet<BukkitFurniture> physical = new HashSet<>(registry.furniture(identity(key)));
        ManagedConditionalFurniture existing = loaded.remove(key);
        if (existing != null) {
            visibility.forget(existing.furniture());
            selections.unindex(key, existing.node());
            physical.add(existing.furniture());
        }
        physical.forEach(furniture -> removePhysical(key, furniture, "managed"));
    }

    private void removePhysical(
            ConditionalFurnitureKey key, BukkitFurniture furniture, String reason) {
        CraftEngineFurnitureRemoval.runOnOwner(plugin, furniture, () -> {
            visibility.revoke(furniture);
            if (CraftEngineFurnitureRemoval.remove(furniture)) {
                logger.warning("CraftEngine lost " + reason + " conditional furniture "
                        + key.tableId() + '/' + key.nodeId());
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
        return CraftEngineFurnitureGeometry.localToWorld(anchor(tableId), transform(node));
    }

    private static boolean chunkLoaded(Location location) {
        return location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private ManagedFurnitureIdentity identity(ConditionalFurnitureKey key) {
        return new ManagedFurnitureIdentity(
                key.tableId(), key.nodeId(), CONDITIONAL, Optional.empty());
    }

    private void refreshSelectionVariant(ConditionalFurnitureKey key) {
        ManagedConditionalFurniture current = loaded.get(key);
        if (current == null || !(current.node() instanceof PrivateFurnitureNode tile)) {
            return;
        }
        PlayerId viewer = singleViewer(tile);
        setVariant(current.furniture(), selections.variant(key.tableId(), viewer, tile));
    }

    private Location anchor(TableId tableId) {
        return anchors.location(tableId)
                .orElseThrow(() -> new IllegalStateException("No anchor for table " + tableId));
    }

    private String variant(TableId tableId, PlayerId viewer, SceneNode node) {
        return node instanceof PrivateFurnitureNode tile
                ? selections.variant(tableId, viewer, tile)
                : ((ActionFurnitureNode) node).variant();
    }

    private static boolean replaceable(
            ManagedConditionalFurniture current, BukkitFurniture candidate) {
        return current == null
                || current.furniture().bukkitEntity() == null
                || !current.furniture().bukkitEntity().isValid()
                || current.furniture() == candidate;
    }
}
