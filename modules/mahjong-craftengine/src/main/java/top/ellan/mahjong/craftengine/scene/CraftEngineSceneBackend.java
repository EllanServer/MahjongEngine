package top.ellan.mahjong.craftengine.scene;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import top.ellan.mahjong.application.interaction.InteractionRouteBinding;
import top.ellan.mahjong.application.interaction.InteractionRouter;
import top.ellan.mahjong.craftengine.port.CraftEngineMutationGateway;
import top.ellan.mahjong.platform.paper.region.RegionSchedulerPort;
import top.ellan.mahjong.platform.paper.region.TableRegionResolver;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.port.SceneBackendPort;
import top.ellan.mahjong.presentation.scene.SceneDiff;

/** Owns desired per-table scene state and delegates fair region work and CE mutations. */
public final class CraftEngineSceneBackend implements SceneBackendPort {
    private final ConcurrentHashMap<TableId, CraftEngineTableState> tables =
            new ConcurrentHashMap<>();
    private final InteractionRouter interactions;
    private final CraftEngineBackendConfig config;
    private final Consumer<CraftEngineTableFailure> failureSink;
    private final AtomicBoolean ready = new AtomicBoolean();
    private final SceneMutationProcessor mutations;
    private final SceneRegionDispatcher regions;

    public CraftEngineSceneBackend(
            CraftEngineMutationGateway gateway,
            RegionSchedulerPort scheduler,
            TableRegionResolver regionsByTable,
            InteractionRouter interactions,
            CraftEngineBackendConfig config,
            Consumer<CraftEngineTableFailure> failureSink) {
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.config = Objects.requireNonNull(config, "config");
        this.failureSink = Objects.requireNonNull(failureSink, "failureSink");
        mutations = new SceneMutationProcessor(
                Objects.requireNonNull(gateway, "gateway"),
                interactions,
                tables,
                ready::get,
                failureSink);
        regions = new SceneRegionDispatcher(
                Objects.requireNonNull(scheduler, "scheduler"),
                Objects.requireNonNull(regionsByTable, "regionsByTable"),
                tables,
                config,
                ready::get,
                failureSink,
                mutations);
    }

    @Override
    public void submit(SceneDiff diff) {
        Objects.requireNonNull(diff, "diff");
        CraftEngineTableState table =
                tables.computeIfAbsent(diff.tableId(), ignored -> new CraftEngineTableState());
        synchronized (table) {
            if (diff.toRevision() < table.desiredRevision) {
                return;
            }
            if (diff.toRevision() > table.desiredRevision) {
                table.failed = false;
            }
            table.bindingsInstalled = false;
            interactions.replaceBindings(diff.tableId(), List.of());
            diff.removals().forEach(
                    removal -> {
                        table.desired.remove(removal);
                        table.dirty.add(removal);
                    });
            for (SceneNode upsert : diff.upserts()) {
                table.desired.put(upsert.id(), upsert);
                table.dirty.add(upsert.id());
            }
            if (table.desired.size() > config.maxNodesPerTable()) {
                table.failed = true;
                table.desiredRevision = diff.toRevision();
                failureSink.accept(new CraftEngineTableFailure(
                        diff.tableId(), diff.toRevision(), "scene-node-capacity"));
                return;
            }
            table.desiredRevision = diff.toRevision();
            table.bindings = diff.interactionBindings().stream()
                    .map(binding -> new InteractionRouteBinding(
                            binding.handle(),
                            binding.playerId(),
                            binding.revision(),
                            binding.purpose(),
                            binding.actionToken(),
                            binding.targetTile()))
                    .toList();
        }
        regions.markReady(diff.tableId(), table);
        mutations.installBindingsIfReady(diff.tableId(), table);
    }

    /** Called only after CraftEngineReloadEvent confirms the atomically installed bundle is live. */
    public void onCraftEngineReloaded() {
        ready.set(true);
        tables.forEach(
                (tableId, table) -> {
                    synchronized (table) {
                        table.forced.addAll(table.actual.keySet());
                        table.forced.addAll(table.desired.keySet());
                        table.actual.clear();
                        table.dirty.addAll(table.forced);
                        table.failed = false;
                        table.applyEpoch++;
                        table.bindingsInstalled = false;
                        interactions.replaceBindings(tableId, List.of());
                    }
                    regions.markReady(tableId, table);
                });
    }

    public void onCraftEngineReloadStarted() {
        ready.set(false);
        regions.suspend();
        tables.forEach(
                (tableId, table) -> {
                    synchronized (table) {
                        table.applyEpoch++;
                        table.bindingsInstalled = false;
                        interactions.replaceBindings(tableId, List.of());
                    }
                });
    }

    public boolean ready() {
        return ready.get();
    }

    public void removeTable(TableId tableId) {
        CraftEngineTableState table = tables.get(Objects.requireNonNull(tableId, "tableId"));
        if (table == null) {
            return;
        }
        synchronized (table) {
            table.closed = true;
            table.failed = false;
            table.desired.clear();
            table.dirty.addAll(table.actual.keySet());
            table.bindingsInstalled = false;
            interactions.replaceBindings(tableId, List.of());
        }
        regions.markReady(tableId, table);
    }

    public int trackedTables() {
        return tables.size();
    }
}
