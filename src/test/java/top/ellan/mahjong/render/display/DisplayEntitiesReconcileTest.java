package top.ellan.mahjong.render.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.model.MahjongVariant;

final class DisplayEntitiesReconcileTest {
    private static final UUID WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");

    private Plugin plugin;
    private World world;
    private CountingRuntime runtime;

    @BeforeEach
    void setUp() {
        DisplayEntities.clearCaches();
        DisplayVisibilityRegistry.clear();
        TableDisplayRegistry.clear();
        this.plugin = mock(Plugin.class);
        when(this.plugin.getName()).thenReturn("MahjongPaper");
        this.world = mock(World.class);
        when(this.world.getUID()).thenReturn(WORLD_ID);
        when(this.world.getName()).thenReturn("world");
        this.runtime = new CountingRuntime(this.plugin);
    }

    @AfterEach
    void tearDown() {
        DisplayEntities.clearCaches();
        DisplayVisibilityRegistry.clear();
        TableDisplayRegistry.clear();
    }

    @Test
    void identicalTileSpecSkipsTeleportItemMetadataAndVisibilityWork() {
        ItemDisplay display = managedEntity(ItemDisplay.class, 101, uuid(101));
        DisplayEntities.TileDisplaySpec spec = tileSpec(location(1.0D, 2.0D, 3.0D));

        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(display), List.of(spec)));
        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(display), List.of(spec)));

        assertEquals(1, this.runtime.teleports);
        assertEquals(1, this.runtime.itemResolutions);
        assertEquals(0, this.runtime.onlinePlayerReads);
        verify(display, times(1)).setItemStack(same(this.runtime.tileItem));
        verify(display, times(1)).setTransformation(any(Transformation.class));
        verify(display, times(1)).setVisibleByDefault(true);
    }

    @Test
    void privateTileOwnerChangeRequiresRespawnBeforeNewMetadata() {
        UUID firstOwner = uuid(801);
        UUID secondOwner = uuid(802);
        ItemDisplay display = managedEntity(ItemDisplay.class, 180, uuid(180));
        when(this.world.spawn(any(Location.class), eq(ItemDisplay.class))).thenReturn(display);
        DisplayEntities.TileDisplaySpec first = privateTileSpec(location(1.0D, 2.0D, 3.0D), firstOwner);
        DisplayEntities.TileDisplaySpec second = privateTileSpec(location(1.0D, 2.0D, 3.0D), secondOwner);

        assertEquals(List.of(display), DisplayEntities.spawnAll(this.runtime, List.of(first)));
        assertFalse(DisplayEntities.reconcile(this.runtime, List.of(display), List.of(second)));

        verify(display, times(1)).setItemStack(same(this.runtime.tileItem));
        verify(display, never()).setVisibleByDefault(true);
    }

    @Test
    void identicalLabelAndInteractionSpecsSkipTheirSetters() {
        TextDisplay label = managedEntity(TextDisplay.class, 102, uuid(102));
        Interaction interaction = managedEntity(Interaction.class, 103, uuid(103));
        when(interaction.getYaw()).thenReturn(0.0F);
        when(interaction.getPitch()).thenReturn(0.0F);
        Component text = Component.text("ready");
        DisplayEntities.LabelSpec labelSpec = new DisplayEntities.LabelSpec(
            location(2.0D, 3.0D, 4.0D),
            text,
            Color.BLACK,
            null,
            Display.Billboard.CENTER,
            0.0F,
            0.0F,
            true
        );
        DisplayEntities.InteractionSpec interactionSpec = new DisplayEntities.InteractionSpec(
            location(4.0D, 5.0D, 6.0D),
            1.25F,
            0.75F,
            null,
            null
        );

        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(label), List.of(labelSpec)));
        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(label), List.of(labelSpec)));
        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(interaction), List.of(interactionSpec)));
        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(interaction), List.of(interactionSpec)));

        assertEquals(2, this.runtime.teleports);
        verify(label, times(1)).text(text);
        verify(label, times(1)).setVisibleByDefault(true);
        verify(interaction, times(1)).setInteractionWidth(1.25F);
        verify(interaction, times(1)).setInteractionHeight(0.75F);
        verify(interaction, times(1)).setVisibleByDefault(true);
    }

    @Test
    void labelTextChangeReappliesMetadataWithoutTeleporting() {
        TextDisplay label = managedEntity(TextDisplay.class, 108, uuid(108));
        Location labelLocation = location(2.0D, 3.0D, 4.0D);
        Component initialText = Component.text("waiting");
        Component updatedText = Component.text("ready");
        DisplayEntities.LabelSpec initial = labelSpec(labelLocation, initialText);
        DisplayEntities.LabelSpec updated = labelSpec(labelLocation, updatedText);

        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(label), List.of(initial)));
        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(label), List.of(updated)));

        assertEquals(1, this.runtime.teleports);
        verify(label, times(1)).text(initialText);
        verify(label, times(1)).text(updatedText);
        verify(label, times(1)).setSeeThrough(false);
        verify(label, times(1)).setShadowed(true);
        verify(label, times(1)).setDefaultBackground(true);
        verify(label, times(1)).setBillboard(Display.Billboard.CENTER);
        verify(label, times(1)).setLineWidth(160);
        verify(label, times(1)).setViewRange(48.0F);
        verify(label, times(1)).setVisibleByDefault(true);
    }

    @Test
    void labelBackgroundPresenceUsesTheNonDeprecatedDefaultBackgroundToggle() {
        TextDisplay label = managedEntity(TextDisplay.class, 119, uuid(119));
        Location labelLocation = location(2.0D, 3.0D, 4.0D);
        Component text = Component.text("ready");
        DisplayEntities.LabelSpec withBackground = labelSpec(labelLocation, text, Color.BLACK);
        DisplayEntities.LabelSpec transparent = labelSpec(labelLocation, text, null);

        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(label), List.of(withBackground)));
        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(label), List.of(transparent)));

        assertEquals(1, this.runtime.teleports);
        verify(label, times(1)).text(text);
        verify(label, times(1)).setDefaultBackground(true);
        verify(label, times(1)).setDefaultBackground(false);
        verify(label, times(1)).setShadowed(true);
        verify(label, times(1)).setBillboard(Display.Billboard.CENTER);
        verify(label, times(1)).setVisibleByDefault(true);
    }

    @Test
    void unsupportedCustomBackgroundTintChangeDoesNotReplayUnrelatedMetadata() {
        TextDisplay label = managedEntity(TextDisplay.class, 120, uuid(120));
        Location labelLocation = location(2.0D, 3.0D, 4.0D);
        Component text = Component.text("ready");
        DisplayEntities.LabelSpec red = labelSpec(labelLocation, text, Color.RED);
        DisplayEntities.LabelSpec blue = labelSpec(labelLocation, text, Color.BLUE);

        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(label), List.of(red)));
        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(label), List.of(blue)));

        assertEquals(1, this.runtime.teleports);
        verify(label, times(1)).text(text);
        verify(label, times(1)).setDefaultBackground(true);
        verify(label, times(1)).setShadowed(true);
        verify(label, times(1)).setBillboard(Display.Billboard.CENTER);
        verify(label, times(1)).setVisibleByDefault(true);
    }

    @Test
    void tileGlowChangeReappliesMetadataWithoutTeleporting() {
        ItemDisplay display = managedEntity(ItemDisplay.class, 109, uuid(109));
        Location tileLocation = location(5.0D, 6.0D, 7.0D);
        DisplayEntities.TileDisplaySpec initial = tileSpec(tileLocation, null);
        DisplayEntities.TileDisplaySpec glowing = tileSpec(tileLocation, Color.RED);

        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(display), List.of(initial)));
        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(display), List.of(glowing)));

        assertEquals(1, this.runtime.teleports);
        assertEquals(1, this.runtime.itemResolutions);
        verify(display, times(1)).setGlowing(false);
        verify(display, times(1)).setGlowing(true);
        verify(display, times(1)).setItemStack(same(this.runtime.tileItem));
        verify(display, times(1)).setTransformation(any(Transformation.class));
        verify(display, times(1)).setBillboard(Display.Billboard.FIXED);
        verify(display, times(1)).setVisibleByDefault(true);
    }

    @Test
    void nonNullTileGlowColorChangeOnlyUpdatesTheColorOverride() {
        ItemDisplay display = managedEntity(ItemDisplay.class, 113, uuid(113));
        Location tileLocation = location(5.0D, 6.0D, 7.0D);
        DisplayEntities.TileDisplaySpec red = tileSpec(tileLocation, Color.RED);
        DisplayEntities.TileDisplaySpec blue = tileSpec(tileLocation, Color.BLUE);

        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(display), List.of(red)));
        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(display), List.of(blue)));

        assertEquals(1, this.runtime.teleports);
        assertEquals(1, this.runtime.itemResolutions);
        verify(display, times(1)).setGlowing(true);
        verify(display, times(1)).setGlowColorOverride(Color.RED);
        verify(display, times(1)).setGlowColorOverride(Color.BLUE);
        verify(display, times(1)).setBrightness(any(Display.Brightness.class));
        verify(display, times(1)).setItemStack(same(this.runtime.tileItem));
        verify(display, times(1)).setTransformation(any(Transformation.class));
    }

    @Test
    void tileScaleChangeOnlyUpdatesScaleDependentMetadata() {
        ItemDisplay display = managedEntity(ItemDisplay.class, 114, uuid(114));
        Location tileLocation = location(5.0D, 6.0D, 7.0D);
        DisplayEntities.TileDisplaySpec initial = tileSpec(tileLocation, 1.0F);
        DisplayEntities.TileDisplaySpec scaled = tileSpec(tileLocation, 1.5F);

        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(display), List.of(initial)));
        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(display), List.of(scaled)));

        assertEquals(1, this.runtime.teleports);
        assertEquals(1, this.runtime.itemResolutions);
        verify(display, times(1)).setDisplayWidth(0.4F);
        verify(display, times(1)).setDisplayWidth(0.6F);
        verify(display, times(1)).setDisplayHeight(0.6F);
        verify(display, times(1)).setDisplayHeight(0.6F * 1.5F);
        verify(display, times(2)).setTransformation(any(Transformation.class));
        verify(display, times(1)).setItemStack(same(this.runtime.tileItem));
        verify(display, times(1)).setBillboard(Display.Billboard.FIXED);
        verify(display, times(1)).setVisibleByDefault(true);
    }

    @Test
    void tilePoseRotationWithTheSameFaceDoesNotResolveOrSetTheItemAgain() {
        ItemDisplay display = managedEntity(ItemDisplay.class, 115, uuid(115));
        Location tileLocation = location(5.0D, 6.0D, 7.0D);
        DisplayEntities.TileDisplaySpec standing = tileSpecWithPose(tileLocation, DisplayEntities.TileRenderPose.STANDING);
        DisplayEntities.TileDisplaySpec flat = tileSpecWithPose(tileLocation, DisplayEntities.TileRenderPose.FLAT_FACE_UP);

        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(display), List.of(standing)));
        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(display), List.of(flat)));

        assertEquals(1, this.runtime.teleports);
        assertEquals(1, this.runtime.itemResolutions);
        verify(display, times(2)).setTransformation(any(Transformation.class));
        verify(display, times(1)).setItemStack(same(this.runtime.tileItem));
        verify(display, times(1)).setDisplayWidth(0.4F);
        verify(display, times(1)).setDisplayHeight(0.6F);
    }

    @Test
    void interactionWidthChangeDoesNotReplayHeightResponsiveOrVisibilityMetadata() {
        Interaction interaction = managedEntity(Interaction.class, 116, uuid(116));
        when(interaction.getYaw()).thenReturn(0.0F);
        when(interaction.getPitch()).thenReturn(0.0F);
        Location interactionLocation = location(4.0D, 5.0D, 6.0D);
        DisplayEntities.InteractionSpec initial = interactionSpec(interactionLocation, 1.25F, 0.75F);
        DisplayEntities.InteractionSpec widened = interactionSpec(interactionLocation, 1.75F, 0.75F);

        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(interaction), List.of(initial)));
        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(interaction), List.of(widened)));

        assertEquals(1, this.runtime.teleports);
        verify(interaction, times(1)).setResponsive(true);
        verify(interaction, times(1)).setInteractionWidth(1.25F);
        verify(interaction, times(1)).setInteractionWidth(1.75F);
        verify(interaction, times(1)).setInteractionHeight(0.75F);
        verify(interaction, times(1)).setVisibleByDefault(true);
    }

    @Test
    void privateViewerChangeDoesNotRewriteAnUnchangedDefaultVisibilityFlag() {
        Interaction interaction = managedEntity(Interaction.class, 118, uuid(118));
        when(interaction.getYaw()).thenReturn(0.0F);
        when(interaction.getPitch()).thenReturn(0.0F);
        Location interactionLocation = location(4.0D, 5.0D, 6.0D);
        DisplayEntities.InteractionSpec initial = interactionSpec(
            interactionLocation,
            1.25F,
            0.75F,
            List.of(uuid(501))
        );
        DisplayEntities.InteractionSpec updated = interactionSpec(
            interactionLocation,
            1.25F,
            0.75F,
            List.of(uuid(502))
        );

        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(interaction), List.of(initial)));
        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(interaction), List.of(updated)));

        assertEquals(1, this.runtime.teleports);
        verify(interaction, times(1)).setResponsive(true);
        verify(interaction, times(1)).setInteractionWidth(1.25F);
        verify(interaction, times(1)).setInteractionHeight(0.75F);
        verify(interaction, times(1)).setVisibleByDefault(false);
    }

    @Test
    void directBuiltInApplyRetainsFullApplyOnEveryCallSemantics() {
        ItemDisplay display = managedEntity(ItemDisplay.class, 117, uuid(117));
        DisplayEntities.TileDisplaySpec spec = tileSpec(location(1.0D, 2.0D, 3.0D));

        spec.apply(this.runtime, display);
        spec.apply(this.runtime, display);

        assertEquals(2, this.runtime.teleports);
        assertEquals(2, this.runtime.itemResolutions);
        verify(display, times(2)).setItemStack(same(this.runtime.tileItem));
        verify(display, times(2)).setTransformation(any(Transformation.class));
        verify(display, times(2)).setVisibleByDefault(true);
    }

    @Test
    void locationChangeTeleportsExactlyOnceDuringUpdatedApply() {
        TextDisplay label = managedEntity(TextDisplay.class, 110, uuid(110));
        Component initialText = Component.text("waiting");
        Component updatedText = Component.text("ready");
        DisplayEntities.LabelSpec initial = labelSpec(location(1.0D, 2.0D, 3.0D), initialText);
        DisplayEntities.LabelSpec metadataOnly = labelSpec(location(1.0D, 2.0D, 3.0D), updatedText);
        DisplayEntities.LabelSpec moved = labelSpec(location(8.0D, 2.0D, 3.0D), updatedText);

        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(label), List.of(initial)));
        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(label), List.of(metadataOnly)));
        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(label), List.of(moved)));

        assertEquals(2, this.runtime.teleports);
        assertEquals(List.of(1.0D, 8.0D), this.runtime.teleportedX);
    }

    @Test
    void tileYawChangeTeleportsWithoutRequiringPositionChange() {
        ItemDisplay display = managedEntity(ItemDisplay.class, 111, uuid(111));
        Location tileLocation = location(5.0D, 6.0D, 7.0D);
        DisplayEntities.TileDisplaySpec initial = tileSpec(tileLocation, null, 15.0F);
        DisplayEntities.TileDisplaySpec rotated = tileSpec(tileLocation, null, 45.0F);

        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(display), List.of(initial)));
        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(display), List.of(rotated)));

        assertEquals(2, this.runtime.teleports);
        assertEquals(List.of(15.0F, 45.0F), this.runtime.teleportedYaw);
    }

    @Test
    void labelPitchChangeTeleportsWithoutRequiringPositionChange() {
        TextDisplay label = managedEntity(TextDisplay.class, 112, uuid(112));
        Location labelLocation = location(2.0D, 3.0D, 4.0D);
        Component text = Component.text("ready");
        DisplayEntities.LabelSpec initial = labelSpec(labelLocation, text, 10.0F, 5.0F);
        DisplayEntities.LabelSpec rotated = labelSpec(labelLocation, text, 10.0F, 25.0F);

        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(label), List.of(initial)));
        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(label), List.of(rotated)));

        assertEquals(2, this.runtime.teleports);
        assertEquals(List.of(5.0F, 25.0F), this.runtime.teleportedPitch);
    }

    @Test
    void cacheUsesAnImmutableLocationSnapshot() {
        ItemDisplay display = managedEntity(ItemDisplay.class, 104, uuid(104));
        Location mutableLocation = location(7.0D, 8.0D, 9.0D);
        DisplayEntities.TileDisplaySpec spec = tileSpec(mutableLocation);

        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(display), List.of(spec)));
        mutableLocation.add(2.0D, 0.0D, 0.0D);
        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(display), List.of(spec)));

        assertEquals(List.of(7.0D, 9.0D), this.runtime.teleportedX);
        assertEquals(1, this.runtime.itemResolutions);
        verify(display, times(1)).setItemStack(same(this.runtime.tileItem));
    }

    @Test
    void recycledEntityIdWithAnotherUuidCannotReuseCachedState() {
        ItemDisplay first = managedEntity(ItemDisplay.class, 105, uuid(105));
        ItemDisplay recycled = managedEntity(ItemDisplay.class, 105, uuid(205));
        DisplayEntities.TileDisplaySpec spec = tileSpec(location(1.0D, 1.0D, 1.0D));

        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(first), List.of(spec)));
        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(recycled), List.of(spec)));

        assertEquals(2, this.runtime.teleports);
        assertEquals(2, this.runtime.itemResolutions);
        verify(first, times(1)).setItemStack(same(this.runtime.tileItem));
        verify(recycled, times(1)).setItemStack(same(this.runtime.tileItem));
    }

    @Test
    void clearCachesForcesBuiltInStateToBeAppliedAgain() {
        ItemDisplay display = managedEntity(ItemDisplay.class, 106, uuid(106));
        DisplayEntities.TileDisplaySpec spec = tileSpec(location(3.0D, 3.0D, 3.0D));

        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(display), List.of(spec)));
        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(display), List.of(spec)));
        DisplayEntities.clearCaches();
        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(display), List.of(spec)));

        assertEquals(2, this.runtime.teleports);
        assertEquals(2, this.runtime.itemResolutions);
        verify(display, times(2)).setItemStack(same(this.runtime.tileItem));
    }

    @Test
    void entityRegistryRemovalDropsItsAppliedSpec() {
        ItemDisplay display = managedEntity(ItemDisplay.class, 107, uuid(107));
        DisplayEntities.TileDisplaySpec spec = tileSpec(location(4.0D, 4.0D, 4.0D));

        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(display), List.of(spec)));
        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(display), List.of(spec)));
        DisplayVisibilityRegistry.unregister(display.getEntityId());
        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(display), List.of(spec)));

        assertEquals(2, this.runtime.teleports);
        assertEquals(2, this.runtime.itemResolutions);
        verify(display, times(2)).setItemStack(same(this.runtime.tileItem));
    }

    @Test
    void customSpecsKeepApplyOnEveryReconcileSemantics() {
        Entity entity = mock(Entity.class);
        int[] applications = {0};
        DisplayEntities.EntitySpec custom = new DisplayEntities.EntitySpec() {
            @Override
            public Entity spawn(DisplayEntityRuntime runtime) {
                return entity;
            }

            @Override
            public boolean canReuse(DisplayEntityRuntime runtime, Entity candidate) {
                return candidate == entity;
            }

            @Override
            public void apply(DisplayEntityRuntime runtime, Entity candidate) {
                applications[0]++;
            }

            @Override
            public boolean managesOwnReuse() {
                return true;
            }
        };

        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(entity), List.of(custom)));
        assertTrue(DisplayEntities.reconcile(this.runtime, List.of(entity), List.of(custom)));

        assertEquals(2, applications[0]);
    }

    private DisplayEntities.TileDisplaySpec tileSpec(Location location) {
        return tileSpec(location, null);
    }

    private DisplayEntities.TileDisplaySpec tileSpec(Location location, Color glowColor) {
        return tileSpec(location, glowColor, 90.0F);
    }

    private DisplayEntities.TileDisplaySpec tileSpec(Location location, Color glowColor, float yaw) {
        return tileSpec(location, glowColor, yaw, 1.0F, DisplayEntities.TileRenderPose.STANDING);
    }

    private DisplayEntities.TileDisplaySpec tileSpec(Location location, float scale) {
        return tileSpec(location, null, 90.0F, scale, DisplayEntities.TileRenderPose.STANDING);
    }

    private DisplayEntities.TileDisplaySpec tileSpecWithPose(Location location, DisplayEntities.TileRenderPose pose) {
        return tileSpec(location, null, 90.0F, 1.0F, pose);
    }

    private DisplayEntities.TileDisplaySpec privateTileSpec(Location location, UUID ownerId) {
        return new DisplayEntities.TileDisplaySpec(
            location,
            90.0F,
            MahjongVariant.RIICHI,
            MahjongTile.M1,
            DisplayEntities.TileRenderPose.STANDING,
            null,
            true,
            List.of(ownerId),
            null,
            1.0F,
            null,
            Display.Billboard.FIXED,
            true
        );
    }

    private DisplayEntities.TileDisplaySpec tileSpec(
        Location location,
        Color glowColor,
        float yaw,
        float scale,
        DisplayEntities.TileRenderPose pose
    ) {
        return new DisplayEntities.TileDisplaySpec(
            location,
            yaw,
            MahjongVariant.RIICHI,
            MahjongTile.M1,
            pose,
            null,
            true,
            null,
            null,
            scale,
            glowColor,
            Display.Billboard.FIXED,
            true
        );
    }

    private DisplayEntities.InteractionSpec interactionSpec(Location location, float width, float height) {
        return new DisplayEntities.InteractionSpec(location, width, height, null, null);
    }

    private DisplayEntities.InteractionSpec interactionSpec(
        Location location,
        float width,
        float height,
        Collection<UUID> privateViewers
    ) {
        return new DisplayEntities.InteractionSpec(location, width, height, null, privateViewers);
    }

    private DisplayEntities.LabelSpec labelSpec(Location location, Component text) {
        return labelSpec(location, text, 0.0F, 0.0F);
    }

    private DisplayEntities.LabelSpec labelSpec(Location location, Component text, Color color) {
        return new DisplayEntities.LabelSpec(
            location,
            text,
            color,
            null,
            Display.Billboard.CENTER,
            0.0F,
            0.0F,
            true
        );
    }

    private DisplayEntities.LabelSpec labelSpec(Location location, Component text, float yaw, float pitch) {
        return new DisplayEntities.LabelSpec(
            location,
            text,
            Color.BLACK,
            null,
            Display.Billboard.CENTER,
            yaw,
            pitch,
            true
        );
    }

    private Location location(double x, double y, double z) {
        return new Location(this.world, x, y, z);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", suffix));
    }

    private static <T extends Entity> T managedEntity(Class<T> type, int entityId, UUID entityUuid) {
        T entity = mock(type);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(entity.getEntityId()).thenReturn(entityId);
        when(entity.getUniqueId()).thenReturn(entityUuid);
        when(entity.getPersistentDataContainer()).thenReturn(data);
        when(data.has(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenReturn(true);
        return entity;
    }

    private static final class CountingRuntime implements DisplayEntityRuntime {
        private final Plugin plugin;
        private final ItemStack tileItem = mock(ItemStack.class);
        private final List<Double> teleportedX = new ArrayList<>();
        private final List<Float> teleportedYaw = new ArrayList<>();
        private final List<Float> teleportedPitch = new ArrayList<>();
        private int teleports;
        private int itemResolutions;
        private int onlinePlayerReads;

        private CountingRuntime(Plugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public Plugin bukkitPlugin() {
            return this.plugin;
        }

        @Override
        public void teleport(Entity entity, Location location) {
            this.teleports++;
            this.teleportedX.add(location.getX());
            this.teleportedYaw.add(location.getYaw());
            this.teleportedPitch.add(location.getPitch());
        }

        @Override
        public ItemStack resolveTileItem(MahjongVariant variant, MahjongTile tile, boolean faceDown) {
            this.itemResolutions++;
            return this.tileItem;
        }

        @Override
        public Collection<? extends Player> onlinePlayers() {
            this.onlinePlayerReads++;
            return List.of();
        }
    }
}
