package top.ellan.mahjong.craftengine.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.application.interaction.InteractionHandle;
import top.ellan.mahjong.application.interaction.InteractionRouteBinding;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.presentation.node.InteractionBounds;
import top.ellan.mahjong.presentation.node.InteractionNode;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.presentation.node.SceneTransform;
import top.ellan.mahjong.presentation.node.SceneVisibility;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.PlayerId;

class InteractionRayRegistryTest {
    private static final UUID WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000100");
    private static final PlayerId PLAYER =
            new PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000200"));

    @Test
    void resolvesAFlatOrientedLabelPlaneAndRejectsItsOutsideEdge() {
        TableId table = TableId.random();
        InteractionHandle handle = handle("ready");
        InteractionRayRegistry registry = registry(table);
        registry.replace(
                table,
                Map.of(nodeId("ready"), plane("ready", handle, -0.6D)),
                List.of(binding(handle, 1)));

        assertEquals(1, registry.targetCount(PLAYER));
        assertEquals(
                Optional.of(handle),
                registry.resolve(PLAYER, WORLD_ID, 0, 1.0D, 0, -0.6D, 0, 3, 6));
        assertTrue(registry.resolve(PLAYER, WORLD_ID, 0, 1.0D, 0, 1.6D, 0, 3, 6).isEmpty());
    }

    @Test
    void sideBySideLabelsSelectTheCrosshairTargetInsteadOfTheProxyEntity() {
        TableId table = TableId.random();
        InteractionHandle ready = handle("ready");
        InteractionHandle leave = handle("leave");
        InteractionRayRegistry registry = registry(table);
        registry.replace(
                table,
                Map.of(
                        nodeId("ready"), plane("ready", ready, -0.6D),
                        nodeId("leave"), plane("leave", leave, 0.6D)),
                List.of(binding(ready, 2), binding(leave, 2)));

        assertEquals(
                Optional.of(ready),
                registry.resolve(PLAYER, WORLD_ID, 0, 1.0D, 0, -0.6D, 0, 3, 6));
        assertEquals(
                Optional.of(leave),
                registry.resolve(PLAYER, WORLD_ID, 0, 1.0D, 0, 0.6D, 0, 3, 6));

        registry.removeTable(table);
        assertEquals(0, registry.targetCount(PLAYER));
    }

    @Test
    void oneLogicalHandleMayUseMultipleCraftEngineWakeUpProxies() {
        TableId table = TableId.random();
        InteractionHandle handle = handle("shared");
        InteractionRayRegistry registry = registry(table);
        registry.replace(
                table,
                Map.of(
                        nodeId("shared-left"), plane("shared-left", handle, -0.6D),
                        nodeId("shared-right"), plane("shared-right", handle, 0.6D)),
                List.of(binding(handle, 3)));

        assertEquals(2, registry.targetCount(PLAYER));
        assertEquals(
                Optional.of(handle),
                registry.resolve(PLAYER, WORLD_ID, 0, 1.0D, 0, 0.6D, 0, 3, 6));
    }

    @Test
    void rotatesTargetsWithTheTableAnchorYaw() {
        TableId table = TableId.random();
        InteractionHandle handle = handle("rotated");
        InteractionRayRegistry registry = registry(table, 90.0F);
        InteractionNode node = new InteractionNode(
                nodeId("rotated"),
                SceneVisibility.publicToAll(),
                handle,
                "mahjongpaper:action_button_hitbox",
                InteractionBounds.plane(1.0D, 0.4D),
                new SceneTransform(0, 1.0D, 3.0D, 0, 0, 0, 1));
        registry.replace(table, Map.of(node.id(), node), List.of(binding(handle, 3)));

        assertEquals(
                Optional.of(handle),
                registry.resolve(PLAYER, WORLD_ID, 0, 1.0D, 0, -3, 0, 0, 6));
        assertTrue(registry.hasTargets(PLAYER.value(), table));
        assertFalse(registry.hasTargets(PLAYER.value(), TableId.random()));
    }

    @Test
    void clipsOrientedTileBoxesAtTheirRealDepthAndMaximumDistance() {
        TableId table = TableId.random();
        InteractionHandle handle = handle("tile");
        InteractionRayRegistry registry = registry(table);
        InteractionNode node = new InteractionNode(
                nodeId("tile"),
                SceneVisibility.publicToAll(),
                handle,
                "mahjongpaper:hand_tile_hitbox",
                new InteractionBounds(1.0D, 0.4D, 0.2D),
                new SceneTransform(0, 1.0D, 3.0D, 0, 0, 0, 1));
        registry.replace(table, Map.of(node.id(), node), List.of(binding(handle, 4)));

        assertTrue(registry.resolve(PLAYER, WORLD_ID, 0, 1.0D, 0, 0, 0, 1, 2.89D).isEmpty());
        assertEquals(
                Optional.of(handle),
                registry.resolve(PLAYER, WORLD_ID, 0, 1.0D, 0, 0, 0, 1, 2.91D));
    }

    private static InteractionRayRegistry registry(TableId table) {
        return registry(table, 0.0F);
    }

    private static InteractionRayRegistry registry(TableId table, float yaw) {
        World world = world();
        return new InteractionRayRegistry(candidate -> candidate.equals(table)
                ? Optional.of(new Location(world, 0, 0, 0, yaw, 0.0F))
                : Optional.empty());
    }

    private static InteractionNode plane(
            String name, InteractionHandle handle, double tangent) {
        SceneNodeId id = nodeId(name);
        return new InteractionNode(
                id,
                SceneVisibility.publicToAll(),
                handle,
                "mahjongpaper:action_button_hitbox",
                InteractionBounds.plane(1.0D, 0.4D),
                new SceneTransform(tangent, 1.0D, 3.0D, 0, 0, 0, 1));
    }

    private static InteractionRouteBinding binding(InteractionHandle handle, long revision) {
        return new InteractionRouteBinding(
                handle,
                PLAYER,
                new ActionToken(UUID.randomUUID(), PLAYER, revision));
    }

    private static SceneNodeId nodeId(String value) {
        return new SceneNodeId("interaction/action/" + value);
    }

    private static InteractionHandle handle(String value) {
        return new InteractionHandle(UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static World world() {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[] {World.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUID" -> WORLD_ID;
                    case "getName" -> "world";
                    case "isChunkLoaded" -> true;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0F;
        }
        return 0.0D;
    }
}
