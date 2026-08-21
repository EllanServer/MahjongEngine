package top.ellan.mahjong.craftengine.scene;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurnitureManager;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import top.ellan.mahjong.application.interaction.HandTileSelectionPort;
import top.ellan.mahjong.craftengine.port.CraftEngineMutationGateway;
import top.ellan.mahjong.craftengine.port.PrivateProjectionGateway;
import top.ellan.mahjong.craftengine.privateview.PrivateFurnitureVisibility;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.platform.paper.anchor.TableAnchorLookup;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.TileInstanceId;

/** Direct CE-manager mutation boundary; custom furniture behavior owns load/unload discovery. */
public final class DirectCraftEngineMutationGateway
        implements CraftEngineMutationGateway, HandTileSelectionPort, AutoCloseable {
    private final Plugin plugin;
    private final PrivateProjectionGateway privateProjection;
    private final CraftEngineManagedFurnitureRegistry registry;
    private final PublicFurnitureController publicFurniture;
    private final ConditionalFurnitureController conditionalFurniture;
    private final AtomicLong definitionEpoch = new AtomicLong();
    private final Set<TableId> knownTables = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Object reconciliationLock = new Object();
    private final AtomicBoolean reconciliationArmed = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public DirectCraftEngineMutationGateway(
            Plugin plugin,
            TableAnchorLookup anchors,
            PrivateProjectionGateway privateProjection,
            PrivateFurnitureVisibility privateVisibility) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.privateProjection = Objects.requireNonNull(privateProjection, "privateProjection");
        Objects.requireNonNull(anchors, "anchors");
        registry = new CraftEngineManagedFurnitureRegistry();
        BukkitFurnitureManager furnitureManager = BukkitCraftEngine.instance().furnitureManager();
        publicFurniture = new PublicFurnitureController(
                plugin, plugin.getLogger(), anchors, registry, furnitureManager, definitionEpoch::get);
        conditionalFurniture = new ConditionalFurnitureController(
                plugin,
                plugin.getLogger(),
                anchors,
                Objects.requireNonNull(privateVisibility, "privateVisibility"),
                registry,
                furnitureManager,
                definitionEpoch::get);
        registry.bindLifecycle(this::onLifecycle);
    }

    public void bindInteraction(
            Function<CraftEngineManagedFurnitureRegistry.Use, InteractionResult> listener) {
        registry.bindInteraction(listener);
    }

    @Override
    public CompletionStage<Void> upsert(TableId tableId, SceneNode node) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(node, "node");
        knownTables.add(tableId);
        if (conditionalFurniture.supports(node)) {
            publicFurniture.remove(tableId, node.id());
            privateProjection.remove(tableId, node.id());
            return conditionalFurniture.upsert(tableId, node);
        }
        conditionalFurniture.remove(tableId, node.id());
        if (node.worldBacked()) {
            return publicFurniture.upsert(tableId, node);
        }
        privateProjection.upsert(tableId, node);
        return CompletableFuture.completedStage(null);
    }

    @Override
    public CompletionStage<Void> remove(TableId tableId, SceneNodeId nodeId) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(nodeId, "nodeId");
        conditionalFurniture.remove(tableId, nodeId);
        publicFurniture.remove(tableId, nodeId);
        privateProjection.remove(tableId, nodeId);
        return CompletableFuture.completedStage(null);
    }

    @Override
    public void showSelection(
            TableId tableId, PlayerId playerId, Optional<TileInstanceId> selectedTile) {
        conditionalFurniture.showSelection(tableId, playerId, selectedTile);
    }

    @Override
    public void definitionsReloaded() {
        conditionalFurniture.definitionsReloaded();
        definitionEpoch.incrementAndGet();
    }

    public void reconcileKnownTables(Set<TableId> recoveredTables) {
        Set<TableId> recovered = Set.copyOf(Objects.requireNonNull(recoveredTables, "recoveredTables"));
        synchronized (reconciliationLock) {
            if (closed.get()) {
                throw new IllegalStateException("CraftEngine mutation gateway is closed");
            }
            reconciliationArmed.set(false);
            knownTables.clear();
            knownTables.addAll(recovered);
            reconciliationArmed.set(true);
        }
        new HashSet<>(registry.loadedFurniture()).forEach(this::scheduleReconciliation);
    }

    @Override
    public void tableClosed(TableId tableId) {
        knownTables.remove(Objects.requireNonNull(tableId, "tableId"));
    }

    @Override
    public void close() {
        synchronized (reconciliationLock) {
            closed.set(true);
            reconciliationArmed.set(false);
            knownTables.clear();
        }
        registry.close();
        conditionalFurniture.close();
        publicFurniture.clear();
    }

    private void onLifecycle(CraftEngineManagedFurnitureRegistry.Lifecycle lifecycle) {
        if (closed.get()) {
            return;
        }
        if (!lifecycle.loaded()) {
            conditionalFurniture.forget(lifecycle.identity(), lifecycle.furniture());
            publicFurniture.forget(lifecycle.identity(), lifecycle.furniture());
            return;
        }
        if (!reconciliationArmed.get()) {
            adopt(lifecycle.identity(), lifecycle.furniture());
            return;
        }
        reconcile(lifecycle.identity(), lifecycle.furniture());
    }

    private void scheduleReconciliation(BukkitFurniture furniture) {
        Entity entity = furniture.bukkitEntity();
        if (entity != null) {
            entity.getScheduler().run(plugin, ignored -> ManagedFurnitureIdentity.from(furniture)
                    .ifPresent(identity -> reconcile(identity, furniture)), null);
        }
    }

    private void reconcile(ManagedFurnitureIdentity identity, BukkitFurniture furniture) {
        if (!reconciliationArmed.get() || closed.get()) {
            return;
        }
        if (knownTables.contains(identity.tableId()) && desired(identity) && adopt(identity, furniture)) {
            return;
        }
        conditionalFurniture.forget(identity, furniture);
        publicFurniture.forget(identity, furniture);
        CraftEngineFurnitureRemoval.remove(furniture);
    }

    private boolean adopt(ManagedFurnitureIdentity identity, BukkitFurniture furniture) {
        return identity.channel() == ManagedFurnitureIdentity.Channel.CONDITIONAL
                ? conditionalFurniture.adopt(identity, furniture, definitionEpoch.get())
                : publicFurniture.adopt(identity, furniture, definitionEpoch.get());
    }

    private boolean desired(ManagedFurnitureIdentity identity) {
        return identity.channel() == ManagedFurnitureIdentity.Channel.CONDITIONAL
                ? conditionalFurniture.desires(identity.tableId(), identity.nodeId())
                : publicFurniture.desires(identity.tableId(), identity.nodeId());
    }
}
