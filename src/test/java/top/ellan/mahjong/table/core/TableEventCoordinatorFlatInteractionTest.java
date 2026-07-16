package top.ellan.mahjong.table.core;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.destroystokyo.paper.event.player.PlayerUseUnknownEntityEvent;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.display.DisplayClickAction;
import top.ellan.mahjong.render.display.ClientInteractionProxyRegistry;
import top.ellan.mahjong.render.display.DisplayInteractionRayRegistry;

final class TableEventCoordinatorFlatInteractionTest {
    private static final UUID VIEWER_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final UUID WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000203");

    @AfterEach
    void clearRegistry() {
        DisplayInteractionRayRegistry.clear();
        ClientInteractionProxyRegistry.clear();
    }

    @Test
    void mainHandRightClickOnFlatControlUsesTheExistingValidatedActionPath() {
        MahjongTableManager manager = mock(MahjongTableManager.class);
        Player player = playerLookingSouth();
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        DisplayClickAction clickAction = DisplayClickAction.playerCommand(
            "table-a",
            VIEWER_ID,
            "turn:dingque:wan"
        );
        DisplayInteractionRayRegistry.replace(
            VIEWER_ID,
            "table-a",
            List.of(interaction(clickAction))
        );
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(event.getPlayer()).thenReturn(player);
        when(manager.handleDisplayAction(player, clickAction)).thenReturn(true);

        new TableEventCoordinator(manager).onFlatDisplayInteract(event);

        verify(event).setCancelled(true);
        verify(manager).handleDisplayAction(player, clickAction);
    }

    @Test
    void nonMemberCanRightClickAPublicJoinRayWithoutReceivingAClientProxy() {
        MahjongTableManager manager = mock(MahjongTableManager.class);
        Player player = playerLookingSouth();
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        DisplayClickAction join = DisplayClickAction.joinSeat("table-a", SeatWind.EAST);
        DisplayInteractionRayRegistry.replacePublicJoinRegion(
            "table-a",
            "seat-label:EAST",
            List.of(interaction(join))
        );
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(event.getPlayer()).thenReturn(player);
        when(manager.handleDisplayAction(player, join)).thenReturn(true);

        new TableEventCoordinator(manager).onFlatDisplayInteract(event);

        verify(event).setCancelled(true);
        verify(manager).handleDisplayAction(player, join);
    }

    @Test
    void publicRegistryCannotLeakAPrivateDecisionToANonMember() {
        MahjongTableManager manager = mock(MahjongTableManager.class);
        Player player = playerLookingSouth();
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        DisplayClickAction privateDecision = DisplayClickAction.playerCommand(
            "table-a",
            VIEWER_ID,
            "turn:dingque:wan"
        );
        DisplayInteractionRayRegistry.replacePublicJoinRegion(
            "table-a",
            "viewer-actions",
            List.of(interaction(privateDecision))
        );
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(event.getPlayer()).thenReturn(player);

        new TableEventCoordinator(manager).onFlatDisplayInteract(event);

        verify(event, never()).setCancelled(true);
        verify(manager, never()).handleDisplayAction(player, privateDecision);
    }

    @Test
    void publicJoinRayStillObeysBlockOcclusionAndMaximumReach() {
        MahjongTableManager manager = mock(MahjongTableManager.class);
        Player player = playerLookingSouth();
        DisplayClickAction blockedJoin = DisplayClickAction.joinSeat("table-a", SeatWind.SOUTH);
        DisplayInteractionRayRegistry.replacePublicJoinRegion(
            "table-a",
            "seat-label:SOUTH",
            List.of(interaction(blockedJoin))
        );
        PlayerInteractEvent blocked = blockEvent(player, 2.0D);

        new TableEventCoordinator(manager).onFlatDisplayInteract(blocked);

        verify(blocked, never()).setCancelled(true);
        verify(manager, never()).handleDisplayAction(player, blockedJoin);

        DisplayInteractionRayRegistry.clear();
        DisplayClickAction distantJoin = DisplayClickAction.joinSeat("table-b", SeatWind.WEST);
        DisplayInteractionRayRegistry.replacePublicJoinRegion(
            "table-b",
            "seat-label:WEST",
            List.of(interactionAt(7.0D, distantJoin))
        );
        PlayerInteractEvent distant = mock(PlayerInteractEvent.class);
        when(distant.getHand()).thenReturn(EquipmentSlot.HAND);
        when(distant.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(distant.getPlayer()).thenReturn(player);

        new TableEventCoordinator(manager).onFlatDisplayInteract(distant);

        verify(distant, never()).setCancelled(true);
        verify(manager, never()).handleDisplayAction(player, distantJoin);
    }

    @Test
    void armSwingOnFlatControlExecutesAndCancelsTheAnimation() {
        MahjongTableManager manager = mock(MahjongTableManager.class);
        Player player = playerLookingSouth();
        PlayerAnimationEvent event = mock(PlayerAnimationEvent.class);
        DisplayClickAction clickAction = DisplayClickAction.playerCommand(
            "table-a",
            VIEWER_ID,
            "turn:discard:0"
        );
        DisplayInteractionRayRegistry.replace(
            VIEWER_ID,
            "table-a",
            List.of(interaction(clickAction))
        );
        when(event.getAnimationType()).thenReturn(PlayerAnimationType.ARM_SWING);
        when(event.getPlayer()).thenReturn(player);
        when(manager.handleDisplayAction(player, clickAction)).thenReturn(true);

        new TableEventCoordinator(manager).onFlatDisplayAnimation(event);

        verify(event).setCancelled(true);
        verify(manager).handleDisplayAction(player, clickAction);
    }

    @Test
    void armSwingMissDoesNotCancelOrExecuteAnything() {
        MahjongTableManager manager = mock(MahjongTableManager.class);
        Player player = playerLookingSouth();
        PlayerAnimationEvent event = mock(PlayerAnimationEvent.class);
        DisplayClickAction clickAction = DisplayClickAction.playerCommand(
            "table-a",
            VIEWER_ID,
            "turn:discard:0"
        );
        when(event.getAnimationType()).thenReturn(PlayerAnimationType.ARM_SWING);
        when(event.getPlayer()).thenReturn(player);

        new TableEventCoordinator(manager).onFlatDisplayAnimation(event);

        verify(event, never()).setCancelled(true);
        verify(manager, never()).handleDisplayAction(player, clickAction);
    }

    @Test
    void offHandEventCannotTriggerTheSameControlTwice() {
        MahjongTableManager manager = mock(MahjongTableManager.class);
        Player player = playerLookingSouth();
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        DisplayClickAction clickAction = DisplayClickAction.playerCommand("table-a", VIEWER_ID, "view:river");
        DisplayInteractionRayRegistry.replace(
            VIEWER_ID,
            "table-a",
            List.of(interaction(clickAction))
        );
        when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(event.getPlayer()).thenReturn(player);

        new TableEventCoordinator(manager).onFlatDisplayInteract(event);

        verify(event, never()).setCancelled(true);
        verify(manager, never()).handleDisplayAction(player, clickAction);
    }

    @Test
    void aCloserBlockOccludesTheControlButAControlInFrontOfTheBlockStillWorks() {
        MahjongTableManager manager = mock(MahjongTableManager.class);
        Player player = playerLookingSouth();
        DisplayClickAction clickAction = DisplayClickAction.playerCommand("table-a", VIEWER_ID, "view:river");
        DisplayInteractionRayRegistry.replace(VIEWER_ID, "table-a", List.of(interaction(clickAction)));
        PlayerInteractEvent blocked = blockEvent(player, 2.0D);

        new TableEventCoordinator(manager).onFlatDisplayInteract(blocked);

        verify(blocked, never()).setCancelled(true);
        verify(manager, never()).handleDisplayAction(player, clickAction);

        PlayerInteractEvent visible = blockEvent(player, 4.0D);
        when(manager.handleDisplayAction(player, clickAction)).thenReturn(true);
        new TableEventCoordinator(manager).onFlatDisplayInteract(visible);

        verify(visible).setCancelled(true);
        verify(manager).handleDisplayAction(player, clickAction);
    }

    @Test
    void staleSeatRayControlsCannotFireWhileTheOverheadCameraIsActive() {
        MahjongTableManager manager = mock(MahjongTableManager.class);
        TableOverheadViews overheadViews = mock(TableOverheadViews.class);
        Player player = playerLookingSouth();
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        DisplayClickAction clickAction = DisplayClickAction.playerCommand("table-a", VIEWER_ID, "turn:discard:0");
        DisplayInteractionRayRegistry.replace(VIEWER_ID, "table-a", List.of(interaction(clickAction)));
        when(manager.overheadViews()).thenReturn(overheadViews);
        when(overheadViews.isActive(VIEWER_ID)).thenReturn(true);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(event.getPlayer()).thenReturn(player);

        new TableEventCoordinator(manager).onFlatDisplayInteract(event);

        verify(event, never()).setCancelled(true);
        verify(manager, never()).handleDisplayAction(player, clickAction);
    }

    @Test
    void armSwingCannotFireStaleSeatRayControlsWhileTheOverheadCameraIsActive() {
        MahjongTableManager manager = mock(MahjongTableManager.class);
        TableOverheadViews overheadViews = mock(TableOverheadViews.class);
        Player player = playerLookingSouth();
        PlayerAnimationEvent event = mock(PlayerAnimationEvent.class);
        DisplayClickAction clickAction = DisplayClickAction.playerCommand(
            "table-a",
            VIEWER_ID,
            "turn:discard:0"
        );
        DisplayInteractionRayRegistry.replace(
            VIEWER_ID,
            "table-a",
            List.of(interaction(clickAction))
        );
        when(manager.overheadViews()).thenReturn(overheadViews);
        when(overheadViews.isActive(VIEWER_ID)).thenReturn(true);
        when(event.getAnimationType()).thenReturn(PlayerAnimationType.ARM_SWING);
        when(event.getPlayer()).thenReturn(player);

        new TableEventCoordinator(manager).onFlatDisplayAnimation(event);

        verify(event, never()).setCancelled(true);
        verify(manager, never()).handleDisplayAction(player, clickAction);
    }

    @Test
    void rightClickAndArmSwingBothCancelButExecuteTheSameActionOnlyOnce() {
        MahjongTableManager manager = mock(MahjongTableManager.class);
        Player player = playerLookingSouth();
        PlayerInteractEvent interactEvent = mock(PlayerInteractEvent.class);
        PlayerAnimationEvent animationEvent = mock(PlayerAnimationEvent.class);
        DisplayClickAction clickAction = DisplayClickAction.playerCommand(
            "table-a",
            VIEWER_ID,
            "view:river"
        );
        DisplayInteractionRayRegistry.replace(
            VIEWER_ID,
            "table-a",
            List.of(interaction(clickAction))
        );
        when(interactEvent.getHand()).thenReturn(EquipmentSlot.HAND);
        when(interactEvent.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(interactEvent.getPlayer()).thenReturn(player);
        when(animationEvent.getAnimationType()).thenReturn(PlayerAnimationType.ARM_SWING);
        when(animationEvent.getPlayer()).thenReturn(player);
        when(manager.handleDisplayAction(player, clickAction)).thenReturn(true);
        TableEventCoordinator coordinator = new TableEventCoordinator(manager);

        coordinator.onFlatDisplayInteract(interactEvent);
        coordinator.onFlatDisplayAnimation(animationEvent);

        verify(interactEvent).setCancelled(true);
        verify(animationEvent).setCancelled(true);
        verify(manager, times(1)).handleDisplayAction(player, clickAction);
    }

    @Test
    void leftClickBlockAlsoCancelsMiningWhenTheFlatControlIsInFront() {
        MahjongTableManager manager = mock(MahjongTableManager.class);
        Player player = playerLookingSouth();
        PlayerInteractEvent event = blockEvent(player, Action.LEFT_CLICK_BLOCK, 4.0D);
        DisplayClickAction clickAction = DisplayClickAction.playerCommand(
            "table-a",
            VIEWER_ID,
            "turn:dingque:wan"
        );
        DisplayInteractionRayRegistry.replace(
            VIEWER_ID,
            "table-a",
            List.of(interaction(clickAction))
        );
        when(manager.handleDisplayAction(player, clickAction)).thenReturn(true);

        new TableEventCoordinator(manager).onFlatDisplayInteract(event);

        verify(event).setCancelled(true);
        verify(manager).handleDisplayAction(player, clickAction);
    }

    @Test
    void ownedClientProxyUsesTheSameExactRayActionAndSwingsForRightClick() {
        MahjongTableManager manager = mock(MahjongTableManager.class);
        Player player = playerLookingSouth();
        PlayerUseUnknownEntityEvent event = mock(PlayerUseUnknownEntityEvent.class);
        DisplayClickAction clickAction = DisplayClickAction.playerCommand(
            "table-a",
            VIEWER_ID,
            "turn:discard:0"
        );
        DisplayInteractionRayRegistry.replace(
            VIEWER_ID,
            "table-a",
            List.of(interaction(clickAction))
        );
        ClientInteractionProxyRegistry.register(901, VIEWER_ID, "table-a");
        when(event.getPlayer()).thenReturn(player);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getEntityId()).thenReturn(901);
        when(event.isAttack()).thenReturn(false);
        when(manager.handleDisplayAction(player, clickAction)).thenReturn(true);

        new TableEventCoordinator(manager).onClientInteractionProxy(event);

        verify(manager).handleDisplayAction(player, clickAction);
        verify(player).swingMainHand();
    }

    @Test
    void foreignUnknownEntityIdIsIgnored() {
        MahjongTableManager manager = mock(MahjongTableManager.class);
        Player player = playerLookingSouth();
        PlayerUseUnknownEntityEvent event = mock(PlayerUseUnknownEntityEvent.class);
        DisplayClickAction clickAction = DisplayClickAction.playerCommand(
            "table-a",
            VIEWER_ID,
            "turn:discard:0"
        );
        DisplayInteractionRayRegistry.replace(
            VIEWER_ID,
            "table-a",
            List.of(interaction(clickAction))
        );
        when(event.getPlayer()).thenReturn(player);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getEntityId()).thenReturn(902);

        new TableEventCoordinator(manager).onClientInteractionProxy(event);

        verify(manager, never()).handleDisplayAction(player, clickAction);
        verify(player, never()).swingMainHand();
    }

    @Test
    void ownedClientProxyIsIgnoredDuringOverheadView() {
        MahjongTableManager manager = mock(MahjongTableManager.class);
        TableOverheadViews overheadViews = mock(TableOverheadViews.class);
        Player player = playerLookingSouth();
        PlayerUseUnknownEntityEvent event = mock(PlayerUseUnknownEntityEvent.class);
        DisplayClickAction clickAction = DisplayClickAction.playerCommand(
            "table-a",
            VIEWER_ID,
            "turn:discard:0"
        );
        DisplayInteractionRayRegistry.replace(
            VIEWER_ID,
            "table-a",
            List.of(interaction(clickAction))
        );
        ClientInteractionProxyRegistry.register(903, VIEWER_ID, "table-a");
        when(manager.overheadViews()).thenReturn(overheadViews);
        when(overheadViews.isActive(VIEWER_ID)).thenReturn(true);
        when(event.getPlayer()).thenReturn(player);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getEntityId()).thenReturn(903);

        new TableEventCoordinator(manager).onClientInteractionProxy(event);

        verify(manager, never()).handleDisplayAction(player, clickAction);
        verify(player, never()).swingMainHand();
    }

    @Test
    void offhandClientProxyEventIsIgnored() {
        MahjongTableManager manager = mock(MahjongTableManager.class);
        Player player = playerLookingSouth();
        PlayerUseUnknownEntityEvent event = mock(PlayerUseUnknownEntityEvent.class);
        DisplayClickAction clickAction = DisplayClickAction.playerCommand(
            "table-a",
            VIEWER_ID,
            "turn:discard:0"
        );
        DisplayInteractionRayRegistry.replace(
            VIEWER_ID,
            "table-a",
            List.of(interaction(clickAction))
        );
        ClientInteractionProxyRegistry.register(904, VIEWER_ID, "table-a");
        when(event.getPlayer()).thenReturn(player);
        when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
        when(event.getEntityId()).thenReturn(904);

        new TableEventCoordinator(manager).onClientInteractionProxy(event);

        verify(manager, never()).handleDisplayAction(player, clickAction);
        verify(player, never()).swingMainHand();
    }

    private static Player playerLookingSouth() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(WORLD_ID);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(VIEWER_ID);
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getEyeLocation()).thenReturn(new Location(world, 0.0D, 1.5D, 0.0D, 0.0F, 0.0F));
        return player;
    }

    private static DisplayInteractionRayRegistry.RayInteraction interaction(DisplayClickAction clickAction) {
        return interactionAt(3.0D, clickAction);
    }

    private static DisplayInteractionRayRegistry.RayInteraction interactionAt(
        double centerZ,
        DisplayClickAction clickAction
    ) {
        return new DisplayInteractionRayRegistry.RayInteraction(
            WORLD_ID,
            0.0D,
            1.5D,
            centerZ,
            1.0D,
            0.0D,
            1.0F,
            0.8F,
            0.0F,
            clickAction
        );
    }

    private static PlayerInteractEvent blockEvent(Player player, double hitZ) {
        return blockEvent(player, Action.RIGHT_CLICK_BLOCK, hitZ);
    }

    private static PlayerInteractEvent blockEvent(Player player, Action action, double hitZ) {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        org.bukkit.World world = player.getEyeLocation().getWorld();
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getAction()).thenReturn(action);
        when(event.getPlayer()).thenReturn(player);
        when(event.getInteractionPoint()).thenReturn(new Location(
            world,
            0.0D,
            1.5D,
            hitZ
        ));
        return event;
    }
}
