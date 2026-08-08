package top.ellan.mahjong.compat;

import top.ellan.mahjong.config.PluginSettings;
import top.ellan.mahjong.debug.DebugService;
import top.ellan.mahjong.i18n.MessageService;
import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.render.display.DisplayClickAction;
import top.ellan.mahjong.runtime.AsyncService;
import top.ellan.mahjong.runtime.ServerScheduler;
import top.ellan.mahjong.table.core.MahjongTableManager;
import top.ellan.mahjong.model.MahjongVariant;
import java.io.InputStream;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public final class CraftEngineService {
    private final CraftEngineBridgeContext context;
    private final boolean exportBundleOnEnable;
    private final CraftEngineItemBridge itemBridge;
    private final CraftEngineFurnitureBridge furnitureBridge;
    private final CraftEngineInteractionBridge interactionBridge;
    private final CraftEngineCullingBridge cullingBridge;
    private final CraftEngineBundleExporter bundleExporter;

    public CraftEngineService(
        Plugin ownerPlugin,
        ServerScheduler scheduler,
        AsyncService async,
        DebugService debug,
        MessageService messages,
        Supplier<PluginSettings> settings,
        PluginSettings.CraftEngineSettings craftEngineSettings
    ) {
        this(
            runtimeServices(ownerPlugin, scheduler, async, debug, messages, settings),
            exportBundleOnEnable(craftEngineSettings),
            preferCustomItems(craftEngineSettings),
            preferFurnitureHitbox(craftEngineSettings),
            injectAntiCheatPacketEventsMappings(craftEngineSettings),
            bundleFolderName(craftEngineSettings)
        );
    }

    private CraftEngineService(
        CraftEngineBridgeContext.Services services,
        boolean exportBundleOnEnable,
        boolean preferCustomItems,
        boolean preferFurnitureHitbox,
        boolean injectAntiCheatPacketEventsMappings,
        String bundleFolderName
    ) {
        this.context = new CraftEngineBridgeContext(services);
        this.exportBundleOnEnable = exportBundleOnEnable;
        this.itemBridge = new CraftEngineItemBridge(this.context, preferCustomItems);
        this.furnitureBridge = new CraftEngineFurnitureBridge(this.context, preferFurnitureHitbox);
        this.interactionBridge = new CraftEngineInteractionBridge(this.context, this.furnitureBridge);
        this.cullingBridge = new CraftEngineCullingBridge(this.context);
        this.bundleExporter = new CraftEngineBundleExporter(this.context, bundleFolderName, injectAntiCheatPacketEventsMappings);
    }

    public void initializeAfterStartup() {
        Plugin craftEngine = this.context.craftEnginePlugin();
        if (craftEngine == null) {
            throw new IllegalStateException("CraftEngine 26.7+ is a required MahjongPaper dependency");
        }

        if (this.exportBundleOnEnable) {
            this.context.plugin().async().execute("export-craftengine-bundle", () -> this.bundleExporter.exportBundle(craftEngine));
        }
        this.bundleExporter.injectAntiCheatMappingsIfNeeded(craftEngine);
    }

    public int cleanupMahjongFurniture() {
        return this.furnitureBridge.cleanupMahjongFurniture();
    }

    public ItemStack resolveTileItem(MahjongVariant variant, MahjongTile tile, boolean faceDown) {
        return this.itemBridge.resolveTileItem(variant, tile, faceDown);
    }

    public ItemStack resolveTileItem(MahjongTile tile, boolean faceDown) {
        return this.resolveTileItem(MahjongVariant.RIICHI, tile, faceDown);
    }

    public Entity placeTableHitbox(Location location) {
        return this.furnitureBridge.placeTableHitbox(location);
    }

    public Entity placeHandTileHitbox(Location location, DisplayClickAction action) {
        return this.furnitureBridge.placeHandTileHitbox(location, action);
    }

    public Entity placeSeatHitbox(Location location, DisplayClickAction action) {
        return this.furnitureBridge.placeSeatHitbox(location, action);
    }

    public Entity placeSeatFurniture(Location location, String furnitureItemId, DisplayClickAction action) {
        return this.furnitureBridge.placeSeatFurniture(location, furnitureItemId, action);
    }

    public Entity placeFurniture(Location location, String furnitureItemId, DisplayClickAction action) {
        return this.furnitureBridge.placeFurniture(location, furnitureItemId, action);
    }

    public Entity placeFurniture(Location location, String furnitureItemId) {
        return this.furnitureBridge.placeFurniture(location, furnitureItemId);
    }

    public void enableFurnitureInteractionBridge(MahjongTableManager tableManager) {
        this.interactionBridge.enableFurnitureInteractionBridge(tableManager);
    }

    public void disableFurnitureInteractionBridge() {
        this.interactionBridge.disableFurnitureInteractionBridge();
    }

    public boolean removeFurniture(Entity entity) {
        return this.furnitureBridge.removeFurniture(entity);
    }

    public void registerCullableEntity(Entity entity) {
        this.cullingBridge.registerCullableEntity(entity);
    }

    public void unregisterCullableEntity(Entity entity) {
        this.cullingBridge.unregisterCullableEntity(entity);
    }

    public void syncTrackedEntitiesFor(Player player) {
        this.cullingBridge.syncTrackedEntitiesFor(player);
    }

    public void clearTrackedCullables() {
        this.cullingBridge.clearTrackedCullables();
    }

    public String customItemId(MahjongVariant variant, MahjongTile tile, boolean faceDown) {
        return this.itemBridge.customItemId(variant, tile, faceDown);
    }

    public String customItemId(MahjongTile tile, boolean faceDown) {
        return this.customItemId(MahjongVariant.RIICHI, tile, faceDown);
    }

    public boolean isFurnitureEntity(Entity entity) {
        return this.furnitureBridge.isFurnitureEntity(entity);
    }

    public boolean reconcileFurniture(Entity entity, Location location, String furnitureItemId, DisplayClickAction action) {
        return this.furnitureBridge.reconcileFurniture(entity, location, furnitureItemId, action);
    }

    public boolean isManagedFurnitureEntity(Entity entity) {
        return this.furnitureBridge.isManagedFurnitureEntity(entity);
    }

    public boolean isMahjongFurnitureEntity(Entity entity) {
        return this.furnitureBridge.isMahjongFurnitureEntity(entity);
    }

    public String furnitureItemId(Entity entity) {
        return this.furnitureBridge.furnitureItemId(entity);
    }

    public boolean isSeatEntity(Entity entity) {
        return this.furnitureBridge.isSeatEntity(entity);
    }

    public Entity furnitureEntityForSeat(Entity seatEntity) {
        return this.furnitureBridge.furnitureEntityForSeat(seatEntity);
    }

    public boolean canPlaceFurniture() {
        return this.furnitureBridge.canPlaceFurniture();
    }

    public boolean seatPlayerOnFurniture(Entity furnitureEntity, Player player) {
        return this.furnitureBridge.seatPlayerOnFurniture(furnitureEntity, player);
    }

    private static boolean exportBundleOnEnable(PluginSettings.CraftEngineSettings settings) {
        return safeSettings(settings).exportBundleOnEnable();
    }

    private static boolean preferCustomItems(PluginSettings.CraftEngineSettings settings) {
        PluginSettings.CraftEngineSettings safe = safeSettings(settings);
        return safe.items() == null || safe.items().preferCustomItems();
    }

    private static boolean preferFurnitureHitbox(PluginSettings.CraftEngineSettings settings) {
        PluginSettings.CraftEngineSettings safe = safeSettings(settings);
        return safe.furniture() == null || safe.furniture().preferHitboxInteraction();
    }

    private static boolean injectAntiCheatPacketEventsMappings(PluginSettings.CraftEngineSettings settings) {
        return safeSettings(settings).injectAntiCheatPacketEventsMappings();
    }

    private static String bundleFolderName(PluginSettings.CraftEngineSettings settings) {
        String configured = safeSettings(settings).bundleFolder();
        return configured == null || configured.isBlank() ? "mahjongpaper" : configured;
    }

    private static PluginSettings.CraftEngineSettings safeSettings(PluginSettings.CraftEngineSettings settings) {
        return settings == null
            ? new PluginSettings.CraftEngineSettings(
                true,
                "mahjongpaper",
                true,
                new PluginSettings.CraftEngineItemsSettings(true, "mahjongpaper:", "mahjongpaper:", "mahjongpaper:"),
                new PluginSettings.CraftEngineFurnitureSettings(true, "mahjongpaper:table_visual", "mahjongpaper:seat_chair")
            )
            : settings;
    }

    private static CraftEngineBridgeContext.Services runtimeServices(
        Plugin ownerPlugin,
        ServerScheduler scheduler,
        AsyncService async,
        DebugService debug,
        MessageService messages,
        Supplier<PluginSettings> settings
    ) {
        return new RuntimeServices(
            Objects.requireNonNull(ownerPlugin, "ownerPlugin"),
            Objects.requireNonNull(scheduler, "scheduler"),
            Objects.requireNonNull(async, "async"),
            Objects.requireNonNull(debug, "debug"),
            Objects.requireNonNull(messages, "messages"),
            Objects.requireNonNull(settings, "settings")
        );
    }

    private record RuntimeServices(
        Plugin bukkitPlugin,
        ServerScheduler scheduler,
        AsyncService async,
        DebugService debug,
        MessageService messages,
        Supplier<PluginSettings> settingsSupplier
    ) implements CraftEngineBridgeContext.Services {
        @Override
        public Server getServer() {
            return this.bukkitPlugin.getServer();
        }

        @Override
        public Logger getLogger() {
            return this.bukkitPlugin.getLogger();
        }

        @Override
        public InputStream getResource(String path) {
            return this.bukkitPlugin.getResource(path);
        }

        @Override
        public boolean isEnabled() {
            return this.bukkitPlugin.isEnabled();
        }

        @Override
        public PluginSettings settings() {
            return this.settingsSupplier.get();
        }
    }
}
