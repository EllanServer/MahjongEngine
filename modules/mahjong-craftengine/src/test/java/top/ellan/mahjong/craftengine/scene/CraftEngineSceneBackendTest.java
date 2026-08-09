package top.ellan.mahjong.craftengine.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.application.interaction.InteractionHandle;
import top.ellan.mahjong.application.interaction.InteractionRouter;
import top.ellan.mahjong.application.table.TableActorRegistry;
import top.ellan.mahjong.craftengine.port.CraftEngineMutationGateway;
import top.ellan.mahjong.craftengine.port.RegionKey;
import top.ellan.mahjong.craftengine.port.RegionSchedulerPort;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.presentation.scene.SceneInteractionBinding;
import top.ellan.mahjong.presentation.node.FurnitureNode;
import top.ellan.mahjong.presentation.scene.SceneDiff;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.presentation.node.SceneTransform;
import top.ellan.mahjong.presentation.node.SceneVisibility;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.PlayerId;

class CraftEngineSceneBackendTest {
    private static final RegionKey REGION = new RegionKey("world", 0, 0);

    @Test
    void appliesAtMostSixteenMutationsPerTablePerTick() {
        ManualRegionScheduler scheduler = new ManualRegionScheduler();
        RecordingGateway gateway = new RecordingGateway();
        CraftEngineSceneBackend backend = backend(gateway, scheduler, ignored -> {});
        TableId table = TableId.random();
        backend.submit(diff(table, 20));
        backend.onCraftEngineReloaded();

        scheduler.runOneTick(REGION);
        assertEquals(16, gateway.upserted.getOrDefault(table, 0));
        assertTrue(scheduler.hasTasks(REGION));

        scheduler.runOneTick(REGION);
        assertEquals(20, gateway.upserted.getOrDefault(table, 0));
    }

    @Test
    void oneFailingTableDoesNotBlockAnotherTableInTheSameRegion() {
        ManualRegionScheduler scheduler = new ManualRegionScheduler();
        RecordingGateway gateway = new RecordingGateway();
        List<CraftEngineTableFailure> failures = new ArrayList<>();
        CraftEngineSceneBackend backend = backend(gateway, scheduler, failures::add);
        TableId broken = TableId.random();
        TableId healthy = TableId.random();
        gateway.broken = broken;
        backend.submit(diff(broken, 4));
        backend.submit(diff(healthy, 4));
        backend.onCraftEngineReloaded();

        scheduler.runOneTick(REGION);

        assertFalse(failures.isEmpty());
        assertEquals(4, gateway.upserted.getOrDefault(healthy, 0));
        assertEquals(0, gateway.upserted.getOrDefault(broken, 0));

        scheduler.runOneTick(REGION);
        assertEquals(1, failures.size());
    }

    @Test
    void eightTablesInOneRegionAdvanceFairlyWithinPerTableTickCaps() {
        ManualRegionScheduler scheduler = new ManualRegionScheduler();
        RecordingGateway gateway = new RecordingGateway();
        CraftEngineSceneBackend backend = backend(gateway, scheduler, ignored -> {});
        List<TableId> tables = java.util.stream.IntStream.range(0, 8)
                .mapToObj(ignored -> TableId.random())
                .toList();
        tables.forEach(table -> backend.submit(diff(table, 20)));
        backend.onCraftEngineReloaded();

        scheduler.runOneTick(REGION);

        tables.forEach(table -> assertEquals(16, gateway.upserted.getOrDefault(table, 0)));
        assertTrue(scheduler.hasTasks(REGION));

        scheduler.runOneTick(REGION);
        tables.forEach(table -> assertEquals(20, gateway.upserted.getOrDefault(table, 0)));
    }

    @Test
    void queuedRegionWorkCannotMutateFurnitureDuringCraftEngineReload() {
        ManualRegionScheduler scheduler = new ManualRegionScheduler();
        RecordingGateway gateway = new RecordingGateway();
        CraftEngineSceneBackend backend = backend(gateway, scheduler, ignored -> {});
        TableId table = TableId.random();
        backend.submit(diff(table, 3));
        backend.onCraftEngineReloaded();
        backend.onCraftEngineReloadStarted();

        scheduler.runOneTick(REGION);
        assertFalse(backend.ready());
        assertEquals(0, gateway.upserted.getOrDefault(table, 0));

        backend.onCraftEngineReloaded();
        scheduler.runUntilIdle(REGION, 8);
        assertTrue(backend.ready());
        assertEquals(3, gateway.upserted.getOrDefault(table, 0));
    }

    @Test
    void removalDuringReloadStillDeletesThePreviouslyAppliedFurniture() {
        ManualRegionScheduler scheduler = new ManualRegionScheduler();
        RecordingGateway gateway = new RecordingGateway();
        CraftEngineSceneBackend backend = backend(gateway, scheduler, ignored -> {});
        TableId table = TableId.random();
        SceneNodeId node = new SceneNodeId("tile/0");
        backend.submit(diff(table, 1));
        backend.onCraftEngineReloaded();
        scheduler.runUntilIdle(REGION, 4);
        assertTrue(gateway.live.getOrDefault(table, Map.of()).containsKey(node));

        backend.onCraftEngineReloadStarted();
        backend.submit(new SceneDiff(table, 1, 2, List.of(node), List.of(), List.of()));
        backend.onCraftEngineReloaded();
        scheduler.runUntilIdle(REGION, 4);

        assertFalse(gateway.live.getOrDefault(table, Map.of()).containsKey(node));
    }

    @Test
    void interactionTokensOpenOnlyAfterTheirSceneRevisionIsApplied() {
        ManualRegionScheduler scheduler = new ManualRegionScheduler();
        RecordingGateway gateway = new RecordingGateway();
        InteractionRouter interactions = new InteractionRouter(new TableActorRegistry());
        CraftEngineSceneBackend backend =
                backend(gateway, scheduler, interactions, ignored -> {});
        TableId table = TableId.random();
        PlayerId player = new PlayerId(UUID.randomUUID());
        InteractionHandle handle = new InteractionHandle(UUID.randomUUID());
        SceneDiff initial = withBinding(diff(table, 1), handle, player, 1);

        backend.submit(initial);
        assertEquals(0, interactions.routeCount());
        backend.onCraftEngineReloaded();
        assertEquals(0, interactions.routeCount());

        scheduler.runUntilIdle(REGION, 4);
        assertEquals(1, interactions.routeCount());

        SceneNode changed =
                new FurnitureNode(
                        new SceneNodeId("tile/0"),
                        SceneVisibility.publicToAll(),
                        "mahjong:tile/back",
                        new SceneTransform(2, 0, 0, 0, 0, 0, 1));
        SceneDiff next =
                new SceneDiff(
                        table,
                        1,
                        2,
                        List.of(),
                        List.of(changed),
                        List.of(
                                new SceneInteractionBinding(
                                        handle,
                                        player,
                                        new ActionToken(UUID.randomUUID(), player, 2))));

        backend.submit(next);
        assertEquals(0, interactions.routeCount());

        scheduler.runUntilIdle(REGION, 4);
        assertEquals(1, interactions.routeCount());
    }

    @Test
    void newerSceneSubmittedDuringMutationCannotBeClearedByTheOlderResult() {
        ManualRegionScheduler scheduler = new ManualRegionScheduler();
        RecordingGateway gateway = new RecordingGateway();
        CraftEngineSceneBackend backend = backend(gateway, scheduler, ignored -> {});
        TableId table = TableId.random();
        SceneNodeId nodeId = new SceneNodeId("tile/0");
        SceneNode newest =
                new FurnitureNode(
                        nodeId,
                        SceneVisibility.publicToAll(),
                        "mahjong:tile/back",
                        new SceneTransform(3, 0, 0, 0, 0, 0, 1));
        gateway.duringFirstUpsert =
                () ->
                        backend.submit(
                                new SceneDiff(
                                        table,
                                        1,
                                        2,
                                        List.of(),
                                        List.of(newest),
                                        List.of()));

        backend.submit(diff(table, 1));
        backend.onCraftEngineReloaded();
        scheduler.runUntilIdle(REGION, 4);

        assertEquals(2, gateway.upserted.getOrDefault(table, 0));
        assertEquals(newest, gateway.live.getOrDefault(table, Map.of()).get(nodeId));
    }

    @Test
    void failedCraftEngineRevisionNeverOpensItsInteractionTokens() {
        ManualRegionScheduler scheduler = new ManualRegionScheduler();
        RecordingGateway gateway = new RecordingGateway();
        InteractionRouter interactions = new InteractionRouter(new TableActorRegistry());
        CraftEngineSceneBackend backend =
                backend(gateway, scheduler, interactions, ignored -> {});
        TableId table = TableId.random();
        PlayerId player = new PlayerId(UUID.randomUUID());
        gateway.broken = table;

        backend.submit(
                withBinding(
                        diff(table, 1),
                        new InteractionHandle(UUID.randomUUID()),
                        player,
                        1));
        backend.onCraftEngineReloaded();
        scheduler.runUntilIdle(REGION, 4);

        assertEquals(0, interactions.routeCount());
    }

    private static CraftEngineSceneBackend backend(
            RecordingGateway gateway,
            ManualRegionScheduler scheduler,
            java.util.function.Consumer<CraftEngineTableFailure> failures) {
        return backend(
                gateway,
                scheduler,
                new InteractionRouter(new TableActorRegistry()),
                failures);
    }

    private static CraftEngineSceneBackend backend(
            RecordingGateway gateway,
            ManualRegionScheduler scheduler,
            InteractionRouter interactions,
            java.util.function.Consumer<CraftEngineTableFailure> failures) {
        return new CraftEngineSceneBackend(
                gateway,
                scheduler,
                ignored -> REGION,
                interactions,
                new CraftEngineBackendConfig(16, 1_000_000_000L, 1_024, 256),
                failures);
    }

    private static SceneDiff withBinding(
            SceneDiff diff, InteractionHandle handle, PlayerId player, long revision) {
        return new SceneDiff(
                diff.tableId(),
                diff.fromRevision(),
                diff.toRevision(),
                diff.removals(),
                diff.upserts(),
                List.of(
                        new SceneInteractionBinding(
                                handle,
                                player,
                                new ActionToken(UUID.randomUUID(), player, revision))));
    }

    private static SceneDiff diff(TableId table, int nodes) {
        List<SceneNode> upserts = new ArrayList<>();
        for (int index = 0; index < nodes; index++) {
            SceneNodeId id = new SceneNodeId("tile/" + index);
            upserts.add(
                    new FurnitureNode(
                            id,
                            SceneVisibility.publicToAll(),
                            "mahjong:tile/back",
                            new SceneTransform(index, 0, 0, 0, 0, 0, 1)));
        }
        return new SceneDiff(table, -1, 1, List.of(), upserts, List.of());
    }

    private static final class ManualRegionScheduler implements RegionSchedulerPort {
        private final Map<RegionKey, ArrayDeque<Runnable>> tasks = new HashMap<>();

        @Override
        public void nextTick(RegionKey region, Runnable task) {
            tasks.computeIfAbsent(region, ignored -> new ArrayDeque<>()).addLast(task);
        }

        void runOneTick(RegionKey region) {
            ArrayDeque<Runnable> queue = tasks.get(region);
            if (queue == null || queue.isEmpty()) {
                return;
            }
            int scheduledBeforeTick = queue.size();
            for (int index = 0; index < scheduledBeforeTick; index++) {
                queue.removeFirst().run();
            }
        }

        void runUntilIdle(RegionKey region, int maxTicks) {
            for (int tick = 0; tick < maxTicks && hasTasks(region); tick++) {
                runOneTick(region);
            }
        }

        boolean hasTasks(RegionKey region) {
            ArrayDeque<Runnable> queue = tasks.get(region);
            return queue != null && !queue.isEmpty();
        }
    }

    private static final class RecordingGateway implements CraftEngineMutationGateway {
        private final Map<TableId, Integer> upserted = new HashMap<>();
        private final Map<TableId, Map<SceneNodeId, SceneNode>> live = new HashMap<>();
        private TableId broken;
        private Runnable duringFirstUpsert;

        @Override
        public void upsert(TableId tableId, SceneNode node) {
            if (tableId.equals(broken)) {
                throw new IllegalStateException("isolated CE failure");
            }
            upserted.merge(tableId, 1, Integer::sum);
            Runnable callback = duringFirstUpsert;
            duringFirstUpsert = null;
            if (callback != null) {
                callback.run();
            }
            live.computeIfAbsent(tableId, ignored -> new HashMap<>()).put(node.id(), node);
        }

        @Override
        public void remove(TableId tableId, SceneNodeId nodeId) {
            live.computeIfAbsent(tableId, ignored -> new HashMap<>()).remove(nodeId);
        }
    }
}
