package top.ellan.mahjong.compat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.display.DisplayClickAction;
import top.ellan.mahjong.render.display.DisplayInteractionRayRegistry;
import top.ellan.mahjong.render.display.TableDisplayRegistry;
import top.ellan.mahjong.table.core.MahjongTableManager;
import top.ellan.mahjong.table.core.TableOverheadViews;

final class CraftEngineInteractionBridgeTest {
    private static final UUID VIEWER_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000402");
    private static final int FURNITURE_ENTITY_ID = 41;

    @AfterEach
    void clearRegistries() {
        DisplayInteractionRayRegistry.clear();
        TableDisplayRegistry.clear();
    }

    @Test
    void managedFurnitureWithoutDirectActionUsesTheExactRayAction() {
        MahjongTableManager manager = mock(MahjongTableManager.class);
        CraftEngineFurnitureBridge furnitureBridge = mock(CraftEngineFurnitureBridge.class);
        CraftEngineInteractionBridge bridge = bridge(furnitureBridge);
        Player player = playerLookingSouth();
        Entity furnitureEntity = mock(Entity.class);
        Cancellable event = mock(Cancellable.class);
        DisplayClickAction action = DisplayClickAction.playerCommand("table-a", VIEWER_ID, "view:river");
        DisplayInteractionRayRegistry.replace(VIEWER_ID, "table-a", List.of(interaction(action)));
        when(furnitureBridge.isManagedFurnitureEntity(furnitureEntity)).thenReturn(true);
        when(manager.handleDisplayAction(player, action)).thenReturn(true);

        bridge.handleFurnitureInteraction(
            manager,
            player,
            furnitureEntity,
            FURNITURE_ENTITY_ID,
            point(player, 4.0D),
            event
        );

        verify(event).setCancelled(true);
        verify(manager).handleDisplayAction(player, action);
        verify(player).swingMainHand();
    }

    @Test
    void managedTableFurnitureWakesAPublicJoinRayForANonMemberWithoutAProxyPacket() {
        MahjongTableManager manager = mock(MahjongTableManager.class);
        CraftEngineFurnitureBridge furnitureBridge = mock(CraftEngineFurnitureBridge.class);
        CraftEngineInteractionBridge bridge = bridge(furnitureBridge);
        Player player = playerLookingSouth();
        Entity furnitureEntity = mock(Entity.class);
        Cancellable event = mock(Cancellable.class);
        DisplayClickAction join = DisplayClickAction.joinSeat("table-a", SeatWind.NORTH);
        DisplayInteractionRayRegistry.replacePublicJoinRegion(
            "table-a",
            "seat-label:NORTH",
            List.of(interaction(join))
        );
        when(furnitureBridge.isManagedFurnitureEntity(furnitureEntity)).thenReturn(true);
        when(manager.handleDisplayAction(player, join)).thenReturn(true);

        bridge.handleFurnitureInteraction(
            manager,
            player,
            furnitureEntity,
            FURNITURE_ENTITY_ID,
            point(player, 4.0D),
            event
        );

        verify(event).setCancelled(true);
        verify(manager).handleDisplayAction(player, join);
        verify(player).swingMainHand();
    }

    @Test
    void managedFurnitureRayMissLeavesTheCraftEngineEventUntouched() {
        MahjongTableManager manager = mock(MahjongTableManager.class);
        CraftEngineFurnitureBridge furnitureBridge = mock(CraftEngineFurnitureBridge.class);
        CraftEngineInteractionBridge bridge = bridge(furnitureBridge);
        Player player = playerLookingSouth();
        Entity furnitureEntity = mock(Entity.class);
        Cancellable event = mock(Cancellable.class);
        DisplayClickAction action = DisplayClickAction.playerCommand("table-a", VIEWER_ID, "view:river");
        when(furnitureBridge.isManagedFurnitureEntity(furnitureEntity)).thenReturn(true);

        bridge.handleFurnitureInteraction(
            manager,
            player,
            furnitureEntity,
            FURNITURE_ENTITY_ID,
            point(player, 4.0D),
            event
        );

        verify(event, never()).setCancelled(true);
        verify(manager, never()).handleDisplayAction(player, action);
        verify(player, never()).swingMainHand();
    }

    @Test
    void overheadViewIgnoresManagedFurnitureRayActions() {
        MahjongTableManager manager = mock(MahjongTableManager.class);
        TableOverheadViews overheadViews = mock(TableOverheadViews.class);
        CraftEngineFurnitureBridge furnitureBridge = mock(CraftEngineFurnitureBridge.class);
        CraftEngineInteractionBridge bridge = bridge(furnitureBridge);
        Player player = playerLookingSouth();
        Entity furnitureEntity = mock(Entity.class);
        Cancellable event = mock(Cancellable.class);
        DisplayClickAction action = DisplayClickAction.playerCommand("table-a", VIEWER_ID, "turn:discard:0");
        DisplayInteractionRayRegistry.replace(VIEWER_ID, "table-a", List.of(interaction(action)));
        when(furnitureBridge.isManagedFurnitureEntity(furnitureEntity)).thenReturn(true);
        when(manager.overheadViews()).thenReturn(overheadViews);
        when(overheadViews.isActive(VIEWER_ID)).thenReturn(true);

        bridge.handleFurnitureInteraction(
            manager,
            player,
            furnitureEntity,
            FURNITURE_ENTITY_ID,
            point(player, 4.0D),
            event
        );

        verify(event, never()).setCancelled(true);
        verify(manager, never()).handleDisplayAction(player, action);
        verify(player, never()).swingMainHand();
    }

    @Test
    void furnitureInteractionPointOccludesAControlBehindIt() {
        MahjongTableManager manager = mock(MahjongTableManager.class);
        CraftEngineFurnitureBridge furnitureBridge = mock(CraftEngineFurnitureBridge.class);
        CraftEngineInteractionBridge bridge = bridge(furnitureBridge);
        Player player = playerLookingSouth();
        Entity furnitureEntity = mock(Entity.class);
        Cancellable event = mock(Cancellable.class);
        DisplayClickAction action = DisplayClickAction.playerCommand("table-a", VIEWER_ID, "turn:discard:0");
        DisplayInteractionRayRegistry.replace(VIEWER_ID, "table-a", List.of(interaction(action)));
        when(furnitureBridge.isManagedFurnitureEntity(furnitureEntity)).thenReturn(true);

        bridge.handleFurnitureInteraction(
            manager,
            player,
            furnitureEntity,
            FURNITURE_ENTITY_ID,
            point(player, 2.0D),
            event
        );

        verify(event, never()).setCancelled(true);
        verify(manager, never()).handleDisplayAction(player, action);
        verify(player, never()).swingMainHand();
    }

    @Test
    void directFurnitureActionStillTakesPriorityWithoutManagedRayFallback() {
        MahjongTableManager manager = mock(MahjongTableManager.class);
        CraftEngineFurnitureBridge furnitureBridge = mock(CraftEngineFurnitureBridge.class);
        CraftEngineInteractionBridge bridge = bridge(furnitureBridge);
        Player player = playerLookingSouth();
        Entity furnitureEntity = mock(Entity.class);
        Cancellable event = mock(Cancellable.class);
        DisplayClickAction action = DisplayClickAction.playerCommand("table-a", VIEWER_ID, "lobby:ready");
        TableDisplayRegistry.register(FURNITURE_ENTITY_ID, action);
        when(manager.handleDisplayAction(player, action)).thenReturn(true);

        bridge.handleFurnitureInteraction(
            manager,
            player,
            furnitureEntity,
            FURNITURE_ENTITY_ID,
            null,
            event
        );

        verify(event).setCancelled(true);
        verify(manager).handleDisplayAction(player, action);
        verify(furnitureBridge, never()).isManagedFurnitureEntity(furnitureEntity);
    }

    private static CraftEngineInteractionBridge bridge(CraftEngineFurnitureBridge furnitureBridge) {
        return new CraftEngineInteractionBridge(mock(CraftEngineBridgeContext.class), furnitureBridge);
    }

    private static Player playerLookingSouth() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(WORLD_ID);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(VIEWER_ID);
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getEyeLocation()).thenReturn(new Location(
            world,
            0.0D,
            1.5D,
            0.0D,
            0.0F,
            0.0F
        ));
        return player;
    }

    private static Location point(Player player, double z) {
        return new Location(player.getEyeLocation().getWorld(), 0.0D, 1.5D, z);
    }

    private static DisplayInteractionRayRegistry.RayInteraction interaction(DisplayClickAction action) {
        return new DisplayInteractionRayRegistry.RayInteraction(
            WORLD_ID,
            0.0D,
            1.5D,
            3.0D,
            1.0D,
            0.0D,
            1.0F,
            0.8F,
            0.0F,
            action
        );
    }
}
