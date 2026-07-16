package top.ellan.mahjong.render.display;

import top.ellan.mahjong.compat.PaperCompatibility;
import top.ellan.mahjong.compat.CraftEngineService;
import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.model.MahjongVariant;
import top.ellan.mahjong.runtime.ServerScheduler;
import net.kyori.adventure.text.Component;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

public final class DisplayEntities {
    private static final String ITEM_MODEL_NAMESPACE = "mahjongcraft";
    private static final String MANAGED_ENTITY_KEY = "managed_entity";
    private static final float TILE_SCALE = 1.0F;
    private static final float LABEL_VIEW_RANGE = 48.0F;
    private static final Map<String, ItemStack> TILE_ITEM_CACHE = new ConcurrentHashMap<>();
    private static final Map<Plugin, NamespacedKey> MANAGED_ENTITY_KEYS = new ConcurrentHashMap<>();
    /**
     * Last immutable built-in spec applied to a managed entity. Entries use the server entity ID
     * for lock-free lookup but retain the entity only through a queued weak reference; UUID checks
     * prevent a recycled entity ID from inheriting an older display's state. Snapshot values contain
     * no Bukkit Entity, World, Location, Plugin, or session reference.
     */
    private static final ConcurrentMap<Integer, AppliedBuiltInSpec> APPLIED_BUILT_IN_SPECS = new ConcurrentHashMap<>();
    private static final ReferenceQueue<Entity> APPLIED_BUILT_IN_SPEC_ENTITY_QUEUE = new ReferenceQueue<>();
    /**
     * Per-Material BlockData cache. Material.createBlockData allocates a fresh
     * CraftBlockData on every call; for the ~5 distinct Materials used by
     * table structure rendering (DARK_OAK_WOOD, SMOOTH_STONE, STRIPPED_OAK_WOOD,
     * GREEN_WOOL, etc.) the result is identical across spawns and safe to
     * share — BlockDisplay.setBlock copies the state internally and never
     * mutates the input. This eliminates one allocation per BlockDisplay spawn
     * in the render hot path; a single render pass that spawns ~16 block
     * displays (table structure) saves 16 CraftBlockData allocations.
     */
    private static final Map<Material, BlockData> BLOCK_DATA_CACHE = new ConcurrentHashMap<>();

    private DisplayEntities() {
    }

    /**
     * Clears static caches keyed by mutable inputs. Called on plugin reload
     * (see MahjongPaperPlugin.reloadMahjongConfiguration) so that resource
     * pack updates or CraftEngine custom-item config changes are picked up
     * by tile displays spawned after the reload. Without this, the cached
     * ItemStack from the OLD resourcepack/CraftEngine config is returned for
     * every spawn until the JVM is restarted, leaving stale visuals on
     * tables that re-render post-reload.
     *
     * Caches keyed by Plugin (MANAGED_ENTITY_KEYS) are NOT cleared here:
     * the plugin instance is stable across reload (only its config changes),
     * so the NamespacedKey remains valid. They are cleared by onDisable via
     * the per-plugin remove path.
     */
    public static void clearCaches() {
        TILE_ITEM_CACHE.clear();
        BLOCK_DATA_CACHE.clear();
        clearAppliedBuiltInSpecs();
    }

    static void clearAppliedBuiltInSpecs() {
        APPLIED_BUILT_IN_SPECS.clear();
        drainCollectedAppliedSpecEntities();
    }

    /**
     * Returns a shared BlockData instance for the given Material. CraftBukkit's
     * Material.createBlockData allocates a fresh CraftBlockData each call;
     * since BlockDisplay.setBlock copies the state internally and never
     * mutates the input BlockData, we can safely share a single instance per
     * Material across all spawns. The cache is bounded by the (small, fixed)
     * set of Materials used in rendering — typically 5-8 — so no eviction
     * policy is needed. Cleared on plugin reload via clearCaches() so a
     * server resourcepack update that changes block state mappings is
     * picked up.
     */
    private static BlockData blockDataFor(Material material) {
        if (material == null) {
            return null;
        }
        return BLOCK_DATA_CACHE.computeIfAbsent(material, Material::createBlockData);
    }

    private record BukkitDisplayEntityRuntime(Plugin bukkitPlugin, List<Player> onlinePlayers) implements DisplayEntityRuntime {
        private BukkitDisplayEntityRuntime(Plugin bukkitPlugin) {
            this(bukkitPlugin, List.copyOf(Bukkit.getOnlinePlayers()));
        }
    }

    /**
     * Takes the online-player snapshot only if a visibility change actually needs it. Most
     * reconciles update metadata while retaining the same private-viewer set, so eagerly copying
     * Bukkit's full player collection here amplified both allocation and packet-side work.
     */
    private static final class SnapshotDisplayEntityRuntime implements DisplayEntityRuntime {
        private final DisplayEntityRuntime delegate;
        private List<Player> onlinePlayers;

        private SnapshotDisplayEntityRuntime(DisplayEntityRuntime delegate) {
            this.delegate = delegate;
        }

        @Override
        public Plugin bukkitPlugin() {
            return this.delegate.bukkitPlugin();
        }

        @Override
        public ServerScheduler scheduler() {
            return this.delegate.scheduler();
        }

        @Override
        public Supplier<CraftEngineService> craftEngineSupplier() {
            return this.delegate.craftEngineSupplier();
        }

        @Override
        public void teleport(Entity entity, Location location) {
            this.delegate.teleport(entity, location);
        }

        @Override
        public void runForViewer(Player player, Runnable runnable) {
            this.delegate.runForViewer(player, runnable);
        }

        @Override
        public void registerCullableEntity(Entity entity) {
            this.delegate.registerCullableEntity(entity);
        }

        @Override
        public boolean requiresVisibilityResync() {
            return this.delegate.requiresVisibilityResync();
        }

        @Override
        public ItemStack resolveTileItem(MahjongVariant variant, MahjongTile tile, boolean faceDown) {
            return this.delegate.resolveTileItem(variant, tile, faceDown);
        }

        @Override
        public Entity placeFurniture(Location location, String furnitureItemId, DisplayClickAction clickAction) {
            return this.delegate.placeFurniture(location, furnitureItemId, clickAction);
        }

        @Override
        public boolean reconcileFurniture(Entity entity, Location location, String furnitureItemId, DisplayClickAction clickAction) {
            return this.delegate.reconcileFurniture(entity, location, furnitureItemId, clickAction);
        }

        @Override
        public Collection<? extends Player> onlinePlayers() {
            if (this.onlinePlayers == null) {
                this.onlinePlayers = List.copyOf(this.delegate.onlinePlayers());
            }
            return this.onlinePlayers;
        }
    }

    public static List<Entity> spawnAll(Plugin plugin, List<EntitySpec> specs) {
        return spawnAll(new BukkitDisplayEntityRuntime(plugin), specs);
    }

    public static List<Entity> spawnAll(DisplayEntityRuntime runtime, List<EntitySpec> specs) {
        if (runtime == null || runtime.bukkitPlugin() == null || specs == null || specs.isEmpty()) {
            return List.of();
        }
        DisplayEntityRuntime scopedRuntime = visibilitySnapshotRuntime(runtime);
        if (specs.size() == 1) {
            Entity entity = specs.get(0).spawn(scopedRuntime);
            return entity == null ? List.of() : List.of(entity);
        }
        List<Entity> spawned = new java.util.ArrayList<>(specs.size());
        for (EntitySpec spec : specs) {
            Entity entity = spec.spawn(scopedRuntime);
            if (entity != null) {
                spawned.add(entity);
            }
        }
        return List.copyOf(spawned);
    }

    public static boolean reconcile(Plugin plugin, List<Entity> entities, List<EntitySpec> specs) {
        return reconcile(new BukkitDisplayEntityRuntime(plugin), entities, specs);
    }

    public static boolean reconcile(DisplayEntityRuntime runtime, List<Entity> entities, List<EntitySpec> specs) {
        if (runtime == null || runtime.bukkitPlugin() == null || entities == null || specs == null || entities.size() != specs.size()) {
            return false;
        }
        DisplayEntityRuntime scopedRuntime = visibilitySnapshotRuntime(runtime);
        for (int i = 0; i < specs.size(); i++) {
            Entity entity = entities.get(i);
            EntitySpec spec = specs.get(i);
            if (entity == null || spec == null || !spec.canReuse(scopedRuntime, entity)) {
                return false;
            }
            if (requiresPrivateTileRespawn(entity, spec)) {
                return false;
            }
            if (!spec.managesOwnReuse() && !isManagedEntity(scopedRuntime.bukkitPlugin(), entity)) {
                return false;
            }
        }
        for (int i = 0; i < specs.size(); i++) {
            Entity entity = entities.get(i);
            EntitySpec spec = specs.get(i);
            BuiltInSpecSnapshot snapshot = builtInSpecSnapshot(spec);
            if (snapshot == null) {
                // Custom and CraftEngine furniture specs retain their original apply-on-every-
                // reconcile semantics. They may mutate state not represented by the built-in
                // snapshots, so a stale built-in cache entry must not survive this transition.
                forgetAppliedBuiltInSpec(entity);
                spec.apply(scopedRuntime, entity);
                continue;
            }
            AppliedBuiltInSpec applied = appliedBuiltInSpec(entity);
            if (applied != null && applied.snapshot().equals(snapshot)) {
                continue;
            }
            applyBuiltInSpec(
                scopedRuntime,
                entity,
                spec,
                applied == null ? null : applied.snapshot(),
                snapshot
            );
            rememberAppliedBuiltInSpec(entity, snapshot);
        }
        return true;
    }

    /**
     * A private tile cannot safely change owners in place. Viewer visibility changes may be
     * scheduled on another entity thread, while ItemDisplay metadata is written immediately. A
     * respawn guarantees the old owner receives removal before the new owner's tile identity is
     * installed on a fresh, hidden-by-default entity.
     */
    private static boolean requiresPrivateTileRespawn(Entity entity, EntitySpec spec) {
        if (!(spec instanceof TileDisplaySpec)) {
            return false;
        }
        BuiltInSpecSnapshot current = builtInSpecSnapshot(spec);
        if (!(current instanceof TileDisplaySpecSnapshot currentTile)) {
            return false;
        }
        AppliedBuiltInSpec applied = appliedBuiltInSpec(entity);
        if (applied == null || !(applied.snapshot() instanceof TileDisplaySpecSnapshot previous)) {
            return currentTile.privateViewers() != null;
        }
        return (previous.privateViewers() != null || currentTile.privateViewers() != null)
            && !sameViewerIds(previous.privateViewers(), currentTile.privateViewers());
    }

    public static TileDisplayBuilder tileDisplay(Location location, float yaw, MahjongTile tile, TileRenderPose pose) {
        return new TileDisplayBuilder(location, yaw, null, tile, pose);
    }

    public static TileDisplayBuilder tileDisplay(Location location, float yaw, MahjongVariant variant, MahjongTile tile, TileRenderPose pose) {
        return new TileDisplayBuilder(location, yaw, variant, tile, pose);
    }

    public static LabelSpec labelSpec(Plugin plugin, Location location, Component text, Color color) {
        return new LabelSpec(location, text, color, null, Display.Billboard.CENTER, 0.0F, 0.0F, true);
    }

    public static LabelSpec labelSpec(Location location, Component text, Color color) {
        return new LabelSpec(location, text, color, null, Display.Billboard.CENTER, 0.0F, 0.0F, true);
    }

    public static LabelSpec labelSpec(
        Location location,
        Component text,
        Color color,
        Collection<UUID> privateViewers,
        Display.Billboard billboard,
        float yaw,
        float pitch,
        boolean shadowed
    ) {
        return new LabelSpec(location, text, color, privateViewers, billboard, yaw, pitch, shadowed);
    }

    public static InteractionSpec interactionSpec(
        Location location,
        float width,
        float height,
        DisplayClickAction clickAction,
        Collection<UUID> privateViewers
    ) {
        return new InteractionSpec(location, width, height, clickAction, privateViewers);
    }

    public interface EntitySpec {
        Entity spawn(DisplayEntityRuntime runtime);

        boolean canReuse(DisplayEntityRuntime runtime, Entity entity);

        void apply(DisplayEntityRuntime runtime, Entity entity);

        default boolean managesOwnReuse() {
            return false;
        }
    }

    public static final class TileDisplayBuilder {
        private final Location location;
        private final float yaw;
        private final MahjongTile tile;
        private final TileRenderPose pose;
        private MahjongVariant variant;
        private DisplayClickAction clickAction;
        private boolean visibleByDefault = true;
        private Collection<UUID> privateViewers;
        private Collection<UUID> hiddenViewers;
        private float scale = TILE_SCALE;
        private Color glowColor;
        private Display.Billboard billboard;
        private boolean smoothMovement = true;

        private TileDisplayBuilder(Location location, float yaw, MahjongVariant variant, MahjongTile tile, TileRenderPose pose) {
            this.location = location;
            this.yaw = yaw;
            this.variant = variant;
            this.tile = tile;
            this.pose = pose;
        }

        public TileDisplayBuilder variant(MahjongVariant variant) {
            this.variant = variant;
            return this;
        }

        public TileDisplayBuilder clickAction(DisplayClickAction clickAction) {
            this.clickAction = clickAction;
            return this;
        }

        public TileDisplayBuilder visibleByDefault(boolean visibleByDefault) {
            this.visibleByDefault = visibleByDefault;
            return this;
        }

        public TileDisplayBuilder privateViewers(Collection<UUID> privateViewers) {
            this.privateViewers = privateViewers;
            return this;
        }

        public TileDisplayBuilder hiddenViewers(Collection<UUID> hiddenViewers) {
            this.hiddenViewers = hiddenViewers;
            return this;
        }

        public TileDisplayBuilder scale(float scale) {
            this.scale = scale;
            return this;
        }

        public TileDisplayBuilder glowColor(Color glowColor) {
            this.glowColor = glowColor;
            return this;
        }

        public TileDisplayBuilder billboard(Display.Billboard billboard) {
            this.billboard = billboard;
            return this;
        }

        public TileDisplayBuilder smoothMovement(boolean smoothMovement) {
            this.smoothMovement = smoothMovement;
            return this;
        }

        public TileDisplaySpec spec() {
            return new TileDisplaySpec(
                this.location,
                this.yaw,
                this.variant,
                this.tile,
                this.pose,
                this.clickAction,
                this.visibleByDefault,
                this.privateViewers,
                this.hiddenViewers,
                this.scale,
                this.glowColor,
                this.billboard,
                this.smoothMovement
            );
        }

        public ItemDisplay spawn(Plugin plugin) {
            return spawnTileDisplay(plugin, this.spec());
        }
    }

    public record TileDisplaySpec(
        Location location,
        float yaw,
        MahjongVariant variant,
        MahjongTile tile,
        TileRenderPose pose,
        DisplayClickAction clickAction,
        boolean visibleByDefault,
        Collection<UUID> privateViewers,
        Collection<UUID> hiddenViewers,
        float scale,
        Color glowColor,
        Display.Billboard billboard,
        boolean smoothMovement
    ) implements EntitySpec {
        public TileDisplaySpec {
            privateViewers = privateViewers == null ? null : List.copyOf(privateViewers);
            hiddenViewers = hiddenViewers == null ? null : List.copyOf(hiddenViewers);
        }

        @Override
        public Entity spawn(DisplayEntityRuntime runtime) {
            Entity entity = spawnTileDisplayInternal(
                runtime,
                this.location,
                this.yaw,
                this.variant,
                this.tile,
                this.pose,
                this.clickAction,
                this.visibleByDefault,
                this.privateViewers,
                this.hiddenViewers,
                this.scale,
                this.glowColor,
                this.billboard,
                this.smoothMovement
            );
            rememberAppliedBuiltInSpec(entity, builtInSpecSnapshot(this));
            return entity;
        }

        @Override
        public boolean canReuse(DisplayEntityRuntime runtime, Entity entity) {
            return entity instanceof ItemDisplay;
        }

        @Override
        public void apply(DisplayEntityRuntime runtime, Entity entity) {
            applyTileDisplay(runtime, (ItemDisplay) entity, this, true);
        }
    }

    public record LabelSpec(
        Location location,
        Component text,
        Color color,
        Collection<UUID> privateViewers,
        Display.Billboard billboard,
        float yaw,
        float pitch,
        boolean shadowed
    ) implements EntitySpec {
        public LabelSpec {
            privateViewers = privateViewers == null ? null : List.copyOf(privateViewers);
        }

        @Override
        public Entity spawn(DisplayEntityRuntime runtime) {
            Entity entity = spawnLabel(runtime, this.location, this.text, this.color, this.privateViewers, this.billboard, this.yaw, this.pitch, this.shadowed);
            rememberAppliedBuiltInSpec(entity, builtInSpecSnapshot(this));
            return entity;
        }

        @Override
        public boolean canReuse(DisplayEntityRuntime runtime, Entity entity) {
            return entity instanceof TextDisplay;
        }

        @Override
        public void apply(DisplayEntityRuntime runtime, Entity entity) {
            applyLabel(runtime, (TextDisplay) entity, this, true);
        }
    }

    public record InteractionSpec(
        Location location,
        float width,
        float height,
        DisplayClickAction clickAction,
        Collection<UUID> privateViewers
    ) implements EntitySpec {
        public InteractionSpec {
            privateViewers = privateViewers == null ? null : List.copyOf(privateViewers);
        }

        @Override
        public Entity spawn(DisplayEntityRuntime runtime) {
            Entity entity = spawnInteraction(runtime, this.location, this.width, this.height, this.clickAction, this.privateViewers);
            rememberAppliedBuiltInSpec(entity, builtInSpecSnapshot(this));
            return entity;
        }

        @Override
        public boolean canReuse(DisplayEntityRuntime runtime, Entity entity) {
            return entity instanceof Interaction;
        }

        @Override
        public void apply(DisplayEntityRuntime runtime, Entity entity) {
            applyInteraction(runtime, (Interaction) entity, this, true);
        }
    }

    public static ItemDisplay spawnTileDisplay(Plugin plugin, TileDisplaySpec spec) {
        ItemDisplay display = spawnTileDisplayInternal(
            new BukkitDisplayEntityRuntime(plugin),
            spec.location(),
            spec.yaw(),
            spec.variant(),
            spec.tile(),
            spec.pose(),
            spec.clickAction(),
            spec.visibleByDefault(),
            spec.privateViewers(),
            spec.hiddenViewers(),
            spec.scale(),
            spec.glowColor(),
            spec.billboard(),
            spec.smoothMovement()
        );
        rememberAppliedBuiltInSpec(display, builtInSpecSnapshot(spec));
        return display;
    }

    private static ItemDisplay spawnTileDisplayInternal(
        DisplayEntityRuntime runtime,
        Location location,
        float yaw,
        MahjongVariant variant,
        MahjongTile tile,
        TileRenderPose pose,
        DisplayClickAction clickAction,
        boolean visibleByDefault,
        Collection<UUID> privateViewers,
        Collection<UUID> hiddenViewers,
        float scale,
        Color glowColor,
        Display.Billboard billboard,
        boolean smoothMovement
    ) {
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("Location world is null");
        }

        // World.spawn(Location, Class, Consumer) was source-compatible across 1.20.x but the
        // Consumer parameter type changed from org.bukkit.util.Consumer (forRemoval) to
        // java.util.function.Consumer in 1.21+. To keep one jar working on both we use the
        // pre-1.20 World.spawn(Location, Class) entry point, which has been stable since
        // Bukkit's earliest releases, and configure the entity immediately after spawning.
        ItemDisplay display = world.spawn(location, ItemDisplay.class);
        boolean restrictedVisibility = privateViewers != null;
        // Set visibility before any other property so the entity tracker that
        // wakes up on the next broadcast tick already sees the final state and
        // never publishes a one-frame window of default visibility to clients.
        display.setVisibleByDefault(!restrictedVisibility && visibleByDefault);
        display.setPersistent(false);
        markManagedEntity(runtime.bukkitPlugin(), display);
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.HEAD);
        display.setInterpolationDuration(smoothMovement ? 1 : 0);
        display.setInterpolationDelay(0);
        PaperCompatibility.setTeleportDuration(display, smoothMovement ? 1 : 0);
        display.setViewRange(32.0F);
        display.setShadowRadius(0.0F);
        display.setShadowStrength(0.0F);
        display.setDisplayWidth(0.4F * scale);
        display.setDisplayHeight(0.6F * scale);
        if (billboard != null) {
            display.setBillboard(billboard);
        }
        display.setRotation(yaw, 0.0F);
        if (glowColor != null) {
            display.setGlowing(true);
            display.setGlowColorOverride(glowColor);
            display.setBrightness(new Display.Brightness(15, 15));
        }
        display.setTransformation(new Transformation(
            new Vector3f(),
            new AxisAngle4f((float) Math.toRadians(pose.xRotationDegrees()), 1.0F, 0.0F, 0.0F),
            new Vector3f(scale, scale, scale),
            new AxisAngle4f()
        ));
        display.setItemStack(tileItem(runtime, variant, tile, pose.faceDown()));

        if (clickAction != null) {
            TableDisplayRegistry.register(display.getEntityId(), clickAction);
        }
        if (privateViewers != null) {
            if (privateViewers.isEmpty()) {
                DisplayVisibilityRegistry.registerHidden(display.getEntityId());
            } else {
                DisplayVisibilityRegistry.registerPrivate(display.getEntityId(), privateViewers);
            }
            syncPrivateVisibility(runtime, display, privateViewers);
        } else if (hiddenViewers != null && !hiddenViewers.isEmpty()) {
            DisplayVisibilityRegistry.registerExcluded(display.getEntityId(), hiddenViewers);
            syncExcludedVisibility(runtime, display, hiddenViewers, visibleByDefault);
        }
        registerForCraftEngineCulling(runtime, display);
        return display;
    }

    public static TextDisplay spawnLabel(Plugin plugin, Location location, Component text, Color color) {
        return spawnLabel(plugin, location, text, color, null);
    }

    public static TextDisplay spawnLabel(Plugin plugin, Location location, Component text, Color color, Collection<UUID> privateViewers) {
        return spawnLabel(plugin, location, text, color, privateViewers, Display.Billboard.CENTER, 0.0F, 0.0F);
    }

    public static TextDisplay spawnLabel(
        Plugin plugin,
        Location location,
        Component text,
        Color color,
        Collection<UUID> privateViewers,
        Display.Billboard billboard,
        float yaw,
        float pitch
    ) {
        return spawnLabel(plugin, location, text, color, privateViewers, billboard, yaw, pitch, true);
    }

    public static TextDisplay spawnLabel(
        Plugin plugin,
        Location location,
        Component text,
        Color color,
        Collection<UUID> privateViewers,
        Display.Billboard billboard,
        float yaw,
        float pitch,
        boolean shadowed
    ) {
        return spawnLabel(new BukkitDisplayEntityRuntime(plugin), location, text, color, privateViewers, billboard, yaw, pitch, shadowed);
    }

    private static TextDisplay spawnLabel(
        DisplayEntityRuntime runtime,
        Location location,
        Component text,
        Color color,
        Collection<UUID> privateViewers,
        Display.Billboard billboard,
        float yaw,
        float pitch,
        boolean shadowed
    ) {
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("Location world is null");
        }

        TextDisplay display = world.spawn(location, TextDisplay.class);
        boolean privateOnly = privateViewers != null && !privateViewers.isEmpty();
        // See ItemDisplay spawn comment above: ensure the visibility flag is
        // resolved before any tracker tick observes the entity.
        display.setVisibleByDefault(!privateOnly);
        display.setPersistent(false);
        markManagedEntity(runtime.bukkitPlugin(), display);
        display.text(text);
        display.setSeeThrough(false);
        display.setShadowed(shadowed);
        display.setDefaultBackground(color != null);
        display.setBillboard(billboard);
        display.setRotation(yaw, pitch);
        display.setLineWidth(160);
        display.setViewRange(LABEL_VIEW_RANGE);
        display.setBrightness(new Display.Brightness(15, 15));
        if (privateViewers != null && !privateViewers.isEmpty()) {
            DisplayVisibilityRegistry.registerPrivate(display.getEntityId(), privateViewers);
            syncPrivateVisibility(runtime, display, privateViewers);
        }
        registerForCraftEngineCulling(runtime, display);
        return display;
    }

    public static Interaction spawnInteraction(
        Plugin plugin,
        Location location,
        float width,
        float height
    ) {
        return spawnInteraction(plugin, location, width, height, null, null);
    }

    public static Interaction spawnInteraction(
        Plugin plugin,
        Location location,
        float width,
        float height,
        DisplayClickAction clickAction,
        Collection<UUID> privateViewers
    ) {
        return spawnInteraction(new BukkitDisplayEntityRuntime(plugin), location, width, height, clickAction, privateViewers);
    }

    private static Interaction spawnInteraction(
        DisplayEntityRuntime runtime,
        Location location,
        float width,
        float height,
        DisplayClickAction clickAction,
        Collection<UUID> privateViewers
    ) {
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("Location world is null");
        }

        Interaction interaction = world.spawn(location, Interaction.class);
        boolean interactionPrivateOnly = privateViewers != null && !privateViewers.isEmpty();
        // See ItemDisplay spawn comment above.
        interaction.setVisibleByDefault(!interactionPrivateOnly);
        interaction.setPersistent(false);
        markManagedEntity(runtime.bukkitPlugin(), interaction);
        interaction.setResponsive(true);
        interaction.setInteractionWidth(width);
        interaction.setInteractionHeight(height);
        if (clickAction != null) {
            TableDisplayRegistry.register(interaction.getEntityId(), clickAction);
        }
        if (privateViewers != null && !privateViewers.isEmpty()) {
            DisplayVisibilityRegistry.registerPrivate(interaction.getEntityId(), privateViewers);
            syncPrivateVisibility(runtime, interaction, privateViewers);
        }
        registerForCraftEngineCulling(runtime, interaction);
        return interaction;
    }

    public static BlockDisplay spawnBlockDisplay(Plugin plugin, Location location, Material material, float scaleX, float scaleY, float scaleZ) {
        return spawnBlockDisplay(plugin, location, material, scaleX, scaleY, scaleZ, true, null, null);
    }

    public static BlockDisplay spawnBlockDisplay(
        Plugin plugin,
        Location location,
        Material material,
        float scaleX,
        float scaleY,
        float scaleZ,
        boolean visibleByDefault,
        Collection<UUID> privateViewers
    ) {
        return spawnBlockDisplay(plugin, location, material, scaleX, scaleY, scaleZ, visibleByDefault, privateViewers, null);
    }

    public static BlockDisplay spawnBlockDisplay(
        Plugin plugin,
        Location location,
        Material material,
        float scaleX,
        float scaleY,
        float scaleZ,
        boolean visibleByDefault,
        Collection<UUID> privateViewers,
        DisplayClickAction clickAction
    ) {
        DisplayEntityRuntime runtime = new BukkitDisplayEntityRuntime(plugin);
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("Location world is null");
        }

        BlockDisplay display = world.spawn(location, BlockDisplay.class);
        boolean blockPrivateOnly = privateViewers != null && !privateViewers.isEmpty();
        // See ItemDisplay spawn comment above.
        display.setVisibleByDefault(!blockPrivateOnly && visibleByDefault);
        display.setPersistent(false);
        markManagedEntity(plugin, display);
        display.setInterpolationDuration(1);
        display.setInterpolationDelay(0);
        PaperCompatibility.setTeleportDuration(display, 1);
        display.setViewRange(48.0F);
        display.setShadowRadius(0.0F);
        display.setShadowStrength(0.0F);
        display.setRotation(0.0F, 0.0F);
        display.setBlock(blockDataFor(material));
        // Keep display hit volume aligned with visual scale so custom ray hit-testing is stable.
        display.setDisplayWidth(scaleX);
        display.setDisplayHeight(scaleY);
        display.setTransformation(new Transformation(
            new Vector3f(),
            new AxisAngle4f(),
            new Vector3f(scaleX, scaleY, scaleZ),
            new AxisAngle4f()
        ));
        if (clickAction != null) {
            TableDisplayRegistry.register(display.getEntityId(), clickAction);
        }
        if (privateViewers != null && !privateViewers.isEmpty()) {
            DisplayVisibilityRegistry.registerPrivate(display.getEntityId(), privateViewers);
            syncPrivateVisibility(runtime, display, privateViewers);
        }
        registerForCraftEngineCulling(runtime, display);
        return display;
    }

    private static void applyTileDisplay(DisplayEntityRuntime runtime, ItemDisplay display, TileDisplaySpec spec, boolean locationChanged) {
        if (locationChanged) {
            applyEntityLocation(runtime, display, spec.location(), spec.yaw(), 0.0F);
        }
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.HEAD);
        display.setInterpolationDuration(spec.smoothMovement() ? 1 : 0);
        display.setInterpolationDelay(0);
        PaperCompatibility.setTeleportDuration(display, spec.smoothMovement() ? 1 : 0);
        display.setViewRange(32.0F);
        display.setShadowRadius(0.0F);
        display.setShadowStrength(0.0F);
        display.setDisplayWidth(0.4F * spec.scale());
        display.setDisplayHeight(0.6F * spec.scale());
        display.setBillboard(spec.billboard() == null ? Display.Billboard.FIXED : spec.billboard());
        applyTileGlow(display, spec.glowColor());
        applyTileTransformation(display, spec.pose(), spec.scale());
        display.setItemStack(tileItem(runtime, spec.variant(), spec.tile(), spec.pose().faceDown()));
        applyClickAction(display.getEntityId(), spec.clickAction());
        applyTileVisibility(runtime, display, spec.privateViewers(), spec.hiddenViewers(), spec.visibleByDefault());
    }

    private static void reconcileTileDisplay(
        DisplayEntityRuntime runtime,
        ItemDisplay display,
        TileDisplaySpec spec,
        TileDisplaySpecSnapshot previous,
        TileDisplaySpecSnapshot current
    ) {
        if (builtInEntityLocationChanged(previous, current)) {
            applyEntityLocation(runtime, display, spec.location(), spec.yaw(), 0.0F);
        }
        if (previous.smoothMovement() != current.smoothMovement()) {
            int duration = spec.smoothMovement() ? 1 : 0;
            display.setInterpolationDuration(duration);
            PaperCompatibility.setTeleportDuration(display, duration);
        }
        if (Float.compare(previous.scale(), current.scale()) != 0) {
            display.setDisplayWidth(0.4F * spec.scale());
            display.setDisplayHeight(0.6F * spec.scale());
        }
        Display.Billboard previousBillboard = tileBillboard(previous.billboard());
        Display.Billboard currentBillboard = tileBillboard(current.billboard());
        if (previousBillboard != currentBillboard) {
            display.setBillboard(currentBillboard);
        }
        if (!Objects.equals(previous.glowColor(), current.glowColor())) {
            applyTileGlowDelta(display, previous.glowColor(), current.glowColor());
        }
        if (previous.pose() != current.pose()
            || Float.compare(previous.scale(), current.scale()) != 0) {
            applyTileTransformation(display, spec.pose(), spec.scale());
        }
        if (tileItemAppearanceChanged(previous, current)) {
            display.setItemStack(tileItem(runtime, spec.variant(), spec.tile(), spec.pose().faceDown()));
        }
        if (!Objects.equals(previous.clickAction(), current.clickAction())) {
            applyClickAction(display.getEntityId(), spec.clickAction());
        }
        if (previous.visibleByDefault() != current.visibleByDefault()
            || !sameViewerIds(previous.privateViewers(), current.privateViewers())
            || !sameViewerIds(previous.hiddenViewers(), current.hiddenViewers())) {
            applyTileVisibility(
                runtime,
                display,
                spec.privateViewers(),
                spec.hiddenViewers(),
                spec.visibleByDefault(),
                tileVisibleByDefault(previous) != tileVisibleByDefault(current)
            );
        }
    }

    private static Display.Billboard tileBillboard(Display.Billboard billboard) {
        return billboard == null ? Display.Billboard.FIXED : billboard;
    }

    private static boolean tileVisibleByDefault(TileDisplaySpecSnapshot snapshot) {
        return (snapshot.privateViewers() == null || snapshot.privateViewers().isEmpty())
            && snapshot.visibleByDefault();
    }

    private static void applyTileGlow(ItemDisplay display, Color glowColor) {
        if (glowColor != null) {
            display.setGlowing(true);
            display.setGlowColorOverride(glowColor);
            display.setBrightness(new Display.Brightness(15, 15));
            return;
        }
        display.setGlowing(false);
        display.setGlowColorOverride(null);
        display.setBrightness(null);
    }

    private static void applyTileGlowDelta(ItemDisplay display, Color previous, Color current) {
        if (previous == null || current == null) {
            applyTileGlow(display, current);
            return;
        }
        display.setGlowColorOverride(current);
    }

    private static void applyTileTransformation(ItemDisplay display, TileRenderPose pose, float scale) {
        display.setTransformation(new Transformation(
            new Vector3f(),
            new AxisAngle4f((float) Math.toRadians(pose.xRotationDegrees()), 1.0F, 0.0F, 0.0F),
            new Vector3f(scale, scale, scale),
            new AxisAngle4f()
        ));
    }

    private static boolean tileItemAppearanceChanged(
        TileDisplaySpecSnapshot previous,
        TileDisplaySpecSnapshot current
    ) {
        return previous.variant() != current.variant()
            || previous.tile() != current.tile()
            || previous.pose().faceDown() != current.pose().faceDown();
    }

    private static void applyLabel(DisplayEntityRuntime runtime, TextDisplay display, LabelSpec spec, boolean locationChanged) {
        if (locationChanged) {
            applyEntityLocation(runtime, display, spec.location(), spec.yaw(), spec.pitch());
        }
        display.text(spec.text());
        display.setSeeThrough(false);
        display.setShadowed(spec.shadowed());
        display.setDefaultBackground(spec.color() != null);
        display.setBillboard(spec.billboard());
        display.setLineWidth(160);
        display.setViewRange(LABEL_VIEW_RANGE);
        display.setBrightness(new Display.Brightness(15, 15));
        applyPrivateVisibility(runtime, display, spec.privateViewers(), true);
    }

    private static void reconcileLabel(
        DisplayEntityRuntime runtime,
        TextDisplay display,
        LabelSpec spec,
        LabelSpecSnapshot previous,
        LabelSpecSnapshot current
    ) {
        if (builtInEntityLocationChanged(previous, current)) {
            applyEntityLocation(runtime, display, spec.location(), spec.yaw(), spec.pitch());
        }
        if (!Objects.equals(previous.text(), current.text())) {
            display.text(spec.text());
        }
        if (previous.shadowed() != current.shadowed()) {
            display.setShadowed(spec.shadowed());
        }
        if (previous.billboard() != current.billboard()) {
            display.setBillboard(spec.billboard());
        }
        if ((previous.color() == null) != (current.color() == null)) {
            display.setDefaultBackground(spec.color() != null);
        }
        if (!sameViewerIds(previous.privateViewers(), current.privateViewers())) {
            applyPrivateVisibility(
                runtime,
                display,
                spec.privateViewers(),
                true,
                privateVisibleByDefault(previous.privateViewers(), true)
                    != privateVisibleByDefault(current.privateViewers(), true)
            );
        }
    }

    private static void applyInteraction(DisplayEntityRuntime runtime, Interaction interaction, InteractionSpec spec, boolean locationChanged) {
        if (locationChanged) {
            applyEntityLocation(runtime, interaction, spec.location(), interaction.getYaw(), interaction.getPitch());
        }
        interaction.setResponsive(true);
        interaction.setInteractionWidth(spec.width());
        interaction.setInteractionHeight(spec.height());
        applyClickAction(interaction.getEntityId(), spec.clickAction());
        applyPrivateVisibility(runtime, interaction, spec.privateViewers(), true);
    }

    private static void reconcileInteraction(
        DisplayEntityRuntime runtime,
        Interaction interaction,
        InteractionSpec spec,
        InteractionSpecSnapshot previous,
        InteractionSpecSnapshot current
    ) {
        if (builtInEntityLocationChanged(previous, current)) {
            applyEntityLocation(runtime, interaction, spec.location(), interaction.getYaw(), interaction.getPitch());
        }
        if (Float.compare(previous.width(), current.width()) != 0) {
            interaction.setInteractionWidth(spec.width());
        }
        if (Float.compare(previous.height(), current.height()) != 0) {
            interaction.setInteractionHeight(spec.height());
        }
        if (!Objects.equals(previous.clickAction(), current.clickAction())) {
            applyClickAction(interaction.getEntityId(), spec.clickAction());
        }
        if (!sameViewerIds(previous.privateViewers(), current.privateViewers())) {
            applyPrivateVisibility(
                runtime,
                interaction,
                spec.privateViewers(),
                true,
                privateVisibleByDefault(previous.privateViewers(), true)
                    != privateVisibleByDefault(current.privateViewers(), true)
            );
        }
    }

    private static void applyEntityLocation(DisplayEntityRuntime runtime, Entity entity, Location location, float yaw, float pitch) {
        Location target = location.clone();
        target.setYaw(yaw);
        target.setPitch(pitch);
        runtime.teleport(entity, target);
    }

    private static void applyClickAction(int entityId, DisplayClickAction clickAction) {
        if (clickAction == null) {
            TableDisplayRegistry.unregister(entityId);
            return;
        }
        TableDisplayRegistry.register(entityId, clickAction);
    }

    private static void applyTileVisibility(
        DisplayEntityRuntime runtime,
        Entity entity,
        Collection<UUID> privateViewers,
        Collection<UUID> hiddenViewers,
        boolean visibleByDefault
    ) {
        applyTileVisibility(runtime, entity, privateViewers, hiddenViewers, visibleByDefault, true);
    }

    private static void applyTileVisibility(
        DisplayEntityRuntime runtime,
        Entity entity,
        Collection<UUID> privateViewers,
        Collection<UUID> hiddenViewers,
        boolean visibleByDefault,
        boolean updateVisibleByDefault
    ) {
        if (privateViewers != null && hiddenViewers != null && !hiddenViewers.isEmpty()) {
            throw new IllegalArgumentException("Tile visibility cannot define both private viewers and hidden viewers");
        }
        boolean privateOnly = privateViewers != null && !privateViewers.isEmpty();
        boolean hiddenSpecific = hiddenViewers != null && !hiddenViewers.isEmpty();
        if (updateVisibleByDefault) {
            entity.setVisibleByDefault(!privateOnly && visibleByDefault);
        }
        if (privateViewers != null) {
            Set<UUID> previousPrivateViewers = DisplayVisibilityRegistry.privateViewers(entity.getEntityId());
            boolean hadPrivateVisibility = previousPrivateViewers != null
                && DisplayVisibilityRegistry.excludedViewers(entity.getEntityId()) == null
                && !DisplayVisibilityRegistry.isHidden(entity.getEntityId());
            if (privateViewers.isEmpty()) {
                if (!requiresVisibilityResync(runtime) && DisplayVisibilityRegistry.isHidden(entity.getEntityId())) {
                    return;
                }
                DisplayVisibilityRegistry.registerHidden(entity.getEntityId());
                syncPrivateVisibility(runtime, entity, privateViewers);
                return;
            }
            if (!requiresVisibilityResync(runtime) && DisplayVisibilityRegistry.matchesPrivate(entity.getEntityId(), privateViewers)) {
                return;
            }
            DisplayVisibilityRegistry.registerPrivate(entity.getEntityId(), privateViewers);
            if (!requiresVisibilityResync(runtime)
                && hadPrivateVisibility
                && previousPrivateViewers != null) {
                syncPrivateVisibilityDelta(runtime, entity, previousPrivateViewers, Set.copyOf(privateViewers));
            } else {
                syncPrivateVisibility(runtime, entity, privateViewers);
            }
            return;
        }
        if (hiddenSpecific) {
            Set<UUID> previousExcludedViewers = DisplayVisibilityRegistry.excludedViewers(entity.getEntityId());
            boolean hadExcludedVisibility = previousExcludedViewers != null
                && DisplayVisibilityRegistry.privateViewers(entity.getEntityId()) == null
                && !DisplayVisibilityRegistry.isHidden(entity.getEntityId());
            if (!requiresVisibilityResync(runtime) && DisplayVisibilityRegistry.matchesExcluded(entity.getEntityId(), hiddenViewers)) {
                return;
            }
            DisplayVisibilityRegistry.registerExcluded(entity.getEntityId(), hiddenViewers);
            if (!requiresVisibilityResync(runtime)
                && hadExcludedVisibility
                && previousExcludedViewers != null) {
                syncExcludedVisibilityDelta(runtime, entity, previousExcludedViewers, Set.copyOf(hiddenViewers), visibleByDefault);
            } else {
                syncExcludedVisibility(runtime, entity, hiddenViewers, visibleByDefault);
            }
            return;
        }
        if (!requiresVisibilityResync(runtime) && !DisplayVisibilityRegistry.hasCustomVisibility(entity.getEntityId())) {
            return;
        }
        DisplayVisibilityRegistry.unregister(entity.getEntityId());
        syncPublicVisibility(runtime, entity);
    }

    private static void applyPrivateVisibility(DisplayEntityRuntime runtime, Entity entity, Collection<UUID> privateViewers, boolean visibleByDefault) {
        applyPrivateVisibility(runtime, entity, privateViewers, visibleByDefault, true);
    }

    private static void applyPrivateVisibility(
        DisplayEntityRuntime runtime,
        Entity entity,
        Collection<UUID> privateViewers,
        boolean visibleByDefault,
        boolean updateVisibleByDefault
    ) {
        boolean privateOnly = privateViewers != null && !privateViewers.isEmpty();
        if (updateVisibleByDefault) {
            entity.setVisibleByDefault(!privateOnly && visibleByDefault);
        }
        Set<UUID> previousPrivateViewers = DisplayVisibilityRegistry.privateViewers(entity.getEntityId());
        boolean hadPrivateVisibility = previousPrivateViewers != null
            && DisplayVisibilityRegistry.excludedViewers(entity.getEntityId()) == null
            && !DisplayVisibilityRegistry.isHidden(entity.getEntityId());
        if (privateViewers != null && privateViewers.isEmpty()) {
            if (!requiresVisibilityResync(runtime) && DisplayVisibilityRegistry.isHidden(entity.getEntityId())) {
                return;
            }
            DisplayVisibilityRegistry.registerHidden(entity.getEntityId());
            syncPrivateVisibility(runtime, entity, privateViewers);
            return;
        }
        if (!requiresVisibilityResync(runtime) && DisplayVisibilityRegistry.matchesPrivate(entity.getEntityId(), privateViewers)) {
            return;
        }
        if (privateViewers == null) {
            if (!requiresVisibilityResync(runtime) && !DisplayVisibilityRegistry.hasCustomVisibility(entity.getEntityId())) {
                return;
            }
            DisplayVisibilityRegistry.unregister(entity.getEntityId());
            syncPublicVisibility(runtime, entity);
            return;
        }
        DisplayVisibilityRegistry.registerPrivate(entity.getEntityId(), privateViewers);
        if (!requiresVisibilityResync(runtime)
            && hadPrivateVisibility
            && previousPrivateViewers != null) {
            syncPrivateVisibilityDelta(runtime, entity, previousPrivateViewers, Set.copyOf(privateViewers));
        } else {
            syncPrivateVisibility(runtime, entity, privateViewers);
        }
    }

    private static boolean privateVisibleByDefault(Collection<UUID> privateViewers, boolean visibleByDefault) {
        return (privateViewers == null || privateViewers.isEmpty()) && visibleByDefault;
    }

    private static void syncExcludedVisibility(DisplayEntityRuntime runtime, Entity entity, Collection<UUID> hiddenViewers, boolean visibleByDefault) {
        for (Player player : runtime.onlinePlayers()) {
            runForViewer(runtime, entity, player, () -> {
                if (hiddenViewers.contains(player.getUniqueId())) {
                    player.hideEntity(runtime.bukkitPlugin(), entity);
                } else if (visibleByDefault) {
                    player.showEntity(runtime.bukkitPlugin(), entity);
                } else {
                    player.hideEntity(runtime.bukkitPlugin(), entity);
                }
            });
        }
    }

    private static void syncPrivateVisibility(DisplayEntityRuntime runtime, Entity entity, Collection<UUID> privateViewers) {
        for (Player player : runtime.onlinePlayers()) {
            runForViewer(runtime, entity, player, () -> {
                if (!privateViewers.isEmpty() && privateViewers.contains(player.getUniqueId())) {
                    player.showEntity(runtime.bukkitPlugin(), entity);
                } else {
                    player.hideEntity(runtime.bukkitPlugin(), entity);
                }
            });
        }
    }

    private static void syncPrivateVisibilityDelta(DisplayEntityRuntime runtime, Entity entity, Set<UUID> previousViewers, Set<UUID> currentViewers) {
        for (Player player : runtime.onlinePlayers()) {
            UUID viewerId = player.getUniqueId();
            boolean wasVisible = previousViewers.contains(viewerId);
            boolean isVisible = currentViewers.contains(viewerId);
            if (wasVisible == isVisible) {
                continue;
            }
            runForViewer(runtime, entity, player, () -> {
                if (isVisible) {
                    player.showEntity(runtime.bukkitPlugin(), entity);
                } else {
                    player.hideEntity(runtime.bukkitPlugin(), entity);
                }
            });
        }
    }

    private static void syncExcludedVisibilityDelta(
        DisplayEntityRuntime runtime,
        Entity entity,
        Set<UUID> previousHiddenViewers,
        Set<UUID> currentHiddenViewers,
        boolean visibleByDefault
    ) {
        for (Player player : runtime.onlinePlayers()) {
            UUID viewerId = player.getUniqueId();
            boolean wasVisible = visibleByDefault && !previousHiddenViewers.contains(viewerId);
            boolean isVisible = visibleByDefault && !currentHiddenViewers.contains(viewerId);
            if (wasVisible == isVisible) {
                continue;
            }
            runForViewer(runtime, entity, player, () -> {
                if (isVisible) {
                    player.showEntity(runtime.bukkitPlugin(), entity);
                } else {
                    player.hideEntity(runtime.bukkitPlugin(), entity);
                }
            });
        }
    }

    private static void syncPublicVisibility(DisplayEntityRuntime runtime, Entity entity) {
        for (Player player : runtime.onlinePlayers()) {
            runForViewer(runtime, entity, player, () -> player.showEntity(runtime.bukkitPlugin(), entity));
        }
    }

    private static void runForViewer(DisplayEntityRuntime runtime, Entity entity, org.bukkit.entity.Player player, Runnable runnable) {
        runtime.runForViewer(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            // Folia region safety: show/hide touches both viewer and target entity internals.
            if (!PaperCompatibility.isOwnedByCurrentRegion(player) || !PaperCompatibility.isOwnedByCurrentRegion(entity)) {
                return;
            }
            if (!entity.isValid() || entity.isDead()) {
                return;
            }
            runnable.run();
        });
    }

    private static void registerForCraftEngineCulling(DisplayEntityRuntime runtime, org.bukkit.entity.Entity entity) {
        runtime.registerCullableEntity(entity);
    }

    private static boolean requiresVisibilityResync(DisplayEntityRuntime runtime) {
        return runtime.requiresVisibilityResync();
    }

    private static DisplayEntityRuntime visibilitySnapshotRuntime(DisplayEntityRuntime runtime) {
        if (runtime == null) {
            return null;
        }
        return new SnapshotDisplayEntityRuntime(runtime);
    }

    private static BuiltInSpecSnapshot builtInSpecSnapshot(EntitySpec spec) {
        if (spec instanceof TileDisplaySpec tile) {
            return new TileDisplaySpecSnapshot(
                locationSnapshot(tile.location()),
                tile.yaw(),
                tile.variant(),
                tile.tile(),
                tile.pose(),
                tile.clickAction(),
                tile.visibleByDefault(),
                immutableViewerIds(tile.privateViewers()),
                immutableViewerIds(tile.hiddenViewers()),
                tile.scale(),
                tile.glowColor(),
                tile.billboard(),
                tile.smoothMovement()
            );
        }
        if (spec instanceof LabelSpec label) {
            return new LabelSpecSnapshot(
                locationSnapshot(label.location()),
                label.text(),
                label.color(),
                immutableViewerIds(label.privateViewers()),
                label.billboard(),
                label.yaw(),
                label.pitch(),
                label.shadowed()
            );
        }
        if (spec instanceof InteractionSpec interaction) {
            return new InteractionSpecSnapshot(
                locationSnapshot(interaction.location()),
                interaction.width(),
                interaction.height(),
                interaction.clickAction(),
                immutableViewerIds(interaction.privateViewers())
            );
        }
        return null;
    }

    private static LocationSnapshot locationSnapshot(Location location) {
        if (location == null) {
            return null;
        }
        World world = location.getWorld();
        return new LocationSnapshot(
            world == null ? null : world.getUID(),
            world == null ? "" : Objects.toString(world.getName(), ""),
            location.getX(),
            location.getY(),
            location.getZ(),
            location.getYaw(),
            location.getPitch()
        );
    }

    private static List<UUID> immutableViewerIds(Collection<UUID> viewers) {
        return viewers == null ? null : List.copyOf(viewers);
    }

    private static boolean sameViewerIds(List<UUID> previous, List<UUID> current) {
        if (previous == current) {
            return true;
        }
        return previous != null
            && current != null
            && Set.copyOf(previous).equals(Set.copyOf(current));
    }

    private static void applyBuiltInSpec(
        DisplayEntityRuntime runtime,
        Entity entity,
        EntitySpec spec,
        BuiltInSpecSnapshot previous,
        BuiltInSpecSnapshot current
    ) {
        if (spec instanceof TileDisplaySpec tile) {
            if (previous instanceof TileDisplaySpecSnapshot previousTile
                && current instanceof TileDisplaySpecSnapshot currentTile) {
                reconcileTileDisplay(runtime, (ItemDisplay) entity, tile, previousTile, currentTile);
            } else {
                applyTileDisplay(runtime, (ItemDisplay) entity, tile, true);
            }
            return;
        }
        if (spec instanceof LabelSpec label) {
            if (previous instanceof LabelSpecSnapshot previousLabel
                && current instanceof LabelSpecSnapshot currentLabel) {
                reconcileLabel(runtime, (TextDisplay) entity, label, previousLabel, currentLabel);
            } else {
                applyLabel(runtime, (TextDisplay) entity, label, true);
            }
            return;
        }
        if (spec instanceof InteractionSpec interaction) {
            if (previous instanceof InteractionSpecSnapshot previousInteraction
                && current instanceof InteractionSpecSnapshot currentInteraction) {
                reconcileInteraction(
                    runtime,
                    (Interaction) entity,
                    interaction,
                    previousInteraction,
                    currentInteraction
                );
            } else {
                applyInteraction(runtime, (Interaction) entity, interaction, true);
            }
            return;
        }

        // builtInSpecSnapshot currently recognizes exactly the three types above. Keep a safe
        // fallback if another built-in snapshot is introduced without updating this dispatcher.
        spec.apply(runtime, entity);
    }

    private static boolean builtInEntityLocationChanged(
        BuiltInSpecSnapshot previous,
        BuiltInSpecSnapshot current
    ) {
        if (previous == null || current == null || previous.getClass() != current.getClass()) {
            return true;
        }
        if (!sameEntityPosition(previous.location(), current.location())) {
            return true;
        }
        if (previous instanceof TileDisplaySpecSnapshot previousTile
            && current instanceof TileDisplaySpecSnapshot currentTile) {
            return Float.compare(previousTile.yaw(), currentTile.yaw()) != 0;
        }
        if (previous instanceof LabelSpecSnapshot previousLabel
            && current instanceof LabelSpecSnapshot currentLabel) {
            return Float.compare(previousLabel.yaw(), currentLabel.yaw()) != 0
                || Float.compare(previousLabel.pitch(), currentLabel.pitch()) != 0;
        }
        return false;
    }

    private static boolean sameEntityPosition(LocationSnapshot previous, LocationSnapshot current) {
        if (previous == current) {
            return true;
        }
        if (previous == null || current == null) {
            return false;
        }
        return Objects.equals(previous.worldUuid(), current.worldUuid())
            && Objects.equals(previous.worldName(), current.worldName())
            && Double.compare(previous.x(), current.x()) == 0
            && Double.compare(previous.y(), current.y()) == 0
            && Double.compare(previous.z(), current.z()) == 0;
    }

    private static AppliedBuiltInSpec appliedBuiltInSpec(Entity entity) {
        if (entity == null) {
            return null;
        }
        drainCollectedAppliedSpecEntities();
        int entityId = entity.getEntityId();
        AppliedBuiltInSpec applied = APPLIED_BUILT_IN_SPECS.get(entityId);
        if (applied == null) {
            return null;
        }
        if (!sameCachedEntity(applied, entity)) {
            APPLIED_BUILT_IN_SPECS.remove(entityId, applied);
            return null;
        }
        return applied;
    }

    private static void rememberAppliedBuiltInSpec(Entity entity, BuiltInSpecSnapshot snapshot) {
        if (entity == null || snapshot == null) {
            return;
        }
        drainCollectedAppliedSpecEntities();
        int entityId = entity.getEntityId();
        APPLIED_BUILT_IN_SPECS.put(
            entityId,
            new AppliedBuiltInSpec(
                entity.getUniqueId(),
                System.identityHashCode(entity),
                new AppliedSpecEntityReference(entity, entityId, APPLIED_BUILT_IN_SPEC_ENTITY_QUEUE),
                snapshot
            )
        );
    }

    private static void forgetAppliedBuiltInSpec(Entity entity) {
        if (entity == null) {
            return;
        }
        drainCollectedAppliedSpecEntities();
        int entityId = entity.getEntityId();
        AppliedBuiltInSpec applied = APPLIED_BUILT_IN_SPECS.get(entityId);
        if (applied != null && sameCachedEntity(applied, entity)) {
            APPLIED_BUILT_IN_SPECS.remove(entityId, applied);
        }
    }

    static void forgetAppliedBuiltInSpec(int entityId) {
        drainCollectedAppliedSpecEntities();
        APPLIED_BUILT_IN_SPECS.remove(entityId);
    }

    private static boolean sameCachedEntity(AppliedBuiltInSpec applied, Entity entity) {
        UUID entityUuid = entity.getUniqueId();
        if (entityUuid != null || applied.entityUuid() != null) {
            return Objects.equals(applied.entityUuid(), entityUuid);
        }
        Entity cachedEntity = applied.entityReference().get();
        return cachedEntity == entity && applied.identityHash() == System.identityHashCode(entity);
    }

    private static void drainCollectedAppliedSpecEntities() {
        AppliedSpecEntityReference collected;
        while ((collected = (AppliedSpecEntityReference) APPLIED_BUILT_IN_SPEC_ENTITY_QUEUE.poll()) != null) {
            AppliedBuiltInSpec applied = APPLIED_BUILT_IN_SPECS.get(collected.entityId());
            if (applied != null && applied.entityReference() == collected) {
                APPLIED_BUILT_IN_SPECS.remove(collected.entityId(), applied);
            }
        }
    }

    private interface BuiltInSpecSnapshot {
        LocationSnapshot location();
    }

    private record AppliedBuiltInSpec(
        UUID entityUuid,
        int identityHash,
        AppliedSpecEntityReference entityReference,
        BuiltInSpecSnapshot snapshot
    ) {
    }

    private static final class AppliedSpecEntityReference extends WeakReference<Entity> {
        private final int entityId;

        private AppliedSpecEntityReference(Entity entity, int entityId, ReferenceQueue<Entity> queue) {
            super(entity, queue);
            this.entityId = entityId;
        }

        private int entityId() {
            return this.entityId;
        }
    }

    private record LocationSnapshot(
        UUID worldUuid,
        String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
    ) {
    }

    private record TileDisplaySpecSnapshot(
        LocationSnapshot location,
        float yaw,
        MahjongVariant variant,
        MahjongTile tile,
        TileRenderPose pose,
        DisplayClickAction clickAction,
        boolean visibleByDefault,
        List<UUID> privateViewers,
        List<UUID> hiddenViewers,
        float scale,
        Color glowColor,
        Display.Billboard billboard,
        boolean smoothMovement
    ) implements BuiltInSpecSnapshot {
    }

    private record LabelSpecSnapshot(
        LocationSnapshot location,
        Component text,
        Color color,
        List<UUID> privateViewers,
        Display.Billboard billboard,
        float yaw,
        float pitch,
        boolean shadowed
    ) implements BuiltInSpecSnapshot {
    }

    private record InteractionSpecSnapshot(
        LocationSnapshot location,
        float width,
        float height,
        DisplayClickAction clickAction,
        List<UUID> privateViewers
    ) implements BuiltInSpecSnapshot {
    }

    public static boolean isManagedEntity(Plugin plugin, Entity entity) {
        if (plugin == null || entity == null) {
            return false;
        }
        return entity.getPersistentDataContainer().has(managedEntityKey(plugin), PersistentDataType.BYTE);
    }

    private static void markManagedEntity(Plugin plugin, Entity entity) {
        entity.getPersistentDataContainer().set(managedEntityKey(plugin), PersistentDataType.BYTE, (byte) 1);
    }

    private static NamespacedKey managedEntityKey(Plugin plugin) {
        return MANAGED_ENTITY_KEYS.computeIfAbsent(plugin, key -> new NamespacedKey(key, MANAGED_ENTITY_KEY));
    }

    private static ItemStack tileItem(DisplayEntityRuntime runtime, MahjongVariant variant, MahjongTile tile, boolean faceDown) {
        ItemStack customItem = runtime.resolveTileItem(variant, tile, faceDown);
        if (customItem != null) {
            return customItem;
        }
        String path = faceDown ? "mahjong_tile/back" : tile.itemModelPath();
        return TILE_ITEM_CACHE.computeIfAbsent(path, key -> createTileItem(tile, key)).clone();
    }

    private static ItemStack createTileItem(MahjongTile tile, String path) {
        ItemStack itemStack = new ItemStack(Material.PAPER);
        ItemMeta meta = itemStack.getItemMeta();
        PaperCompatibility.applyItemModel(meta, new NamespacedKey(ITEM_MODEL_NAMESPACE, path));
        meta.displayName(Component.text(tile.name()));
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    public enum TileRenderPose {
        STANDING(false, 0.0F),
        STANDING_FACE_DOWN(true, 0.0F),
        FLAT_FACE_UP(false, -90.0F),
        FLAT_FACE_DOWN(true, 90.0F);

        private final boolean faceDown;
        private final float xRotationDegrees;

        TileRenderPose(boolean faceDown, float xRotationDegrees) {
            this.faceDown = faceDown;
            this.xRotationDegrees = xRotationDegrees;
        }

        public boolean faceDown() {
            return this.faceDown;
        }

        public float xRotationDegrees() {
            return this.xRotationDegrees;
        }
    }
}
