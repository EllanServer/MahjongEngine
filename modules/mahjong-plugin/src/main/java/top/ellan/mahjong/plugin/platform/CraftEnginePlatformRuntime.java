package top.ellan.mahjong.plugin.platform;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import top.ellan.mahjong.application.concurrent.BoundedDeadlineScheduler;
import top.ellan.mahjong.application.interaction.InteractionRouter;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.application.table.TableActorRegistry;
import top.ellan.mahjong.application.lobby.port.SeatInteractionPort;
import top.ellan.mahjong.craftengine.scene.CraftEngineBackendConfig;
import top.ellan.mahjong.craftengine.bundle.CraftEngineBundleInstaller;
import top.ellan.mahjong.craftengine.interaction.CraftEngineInteractionListener;
import top.ellan.mahjong.craftengine.bundle.CraftEngineReloadListener;
import top.ellan.mahjong.craftengine.scene.CraftEngineSceneBackend;
import top.ellan.mahjong.craftengine.bundle.CraftEngineVersion;
import top.ellan.mahjong.craftengine.scene.DirectCraftEngineMutationGateway;
import top.ellan.mahjong.craftengine.privateview.SparrowPrivateProjectionGateway;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.platform.paper.BoundedPlatformExecutors;
import top.ellan.mahjong.platform.paper.PaperRegionScheduler;
import top.ellan.mahjong.platform.paper.PaperTableAnchorRegistry;
import top.ellan.mahjong.platform.paper.PaperTableAnchorService;
import top.ellan.mahjong.plugin.MahjongPaperPlugin;
import top.ellan.mahjong.plugin.PluginConfiguration;
import top.ellan.mahjong.plugin.runtime.FailureSupport;
import top.ellan.mahjong.presentation.projection.DefaultTableSceneMapper;
import top.ellan.mahjong.presentation.projection.LatestSceneProjector;
import top.ellan.mahjong.presentation.scene.SceneGraphDiffer;
import top.ellan.mahjong.presentation.layout.TableGeometry;
import top.ellan.mahjong.presentation.asset.TableSceneAssets;
import top.ellan.mahjong.presentation.layout.UniversalTableLayout;

/** Owns the Paper/CraftEngine presentation boundary and its restart-scoped resources. */
public final class CraftEnginePlatformRuntime implements AutoCloseable {
    private final MahjongPaperPlugin plugin;
    private final PluginConfiguration configuration;
    private final BoundedPlatformExecutors executors;
    private final Plugin craftEngine;
    private final PaperTableAnchorRegistry anchors = new PaperTableAnchorRegistry();
    private final PaperTableAnchorService anchorService;
    private final SparrowPrivateProjectionGateway privateProjection;
    private final InteractionRouter interactions;
    private final DirectCraftEngineMutationGateway mutations;
    private final CraftEngineSceneBackend sceneBackend;
    private final LatestSceneProjector sceneProjector;
    private final AtomicBoolean bundleInstalled = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public CraftEnginePlatformRuntime(
            MahjongPaperPlugin plugin,
            PluginConfiguration configuration,
            BoundedPlatformExecutors executors,
            BoundedDeadlineScheduler deadlines,
            TableActorRegistry actors) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.executors = Objects.requireNonNull(executors, "executors");
        Objects.requireNonNull(deadlines, "deadlines");
        Objects.requireNonNull(actors, "actors");
        craftEngine = requireCraftEngine();
        anchorService = new PaperTableAnchorService(plugin, anchors);
        privateProjection =
                new SparrowPrivateProjectionGateway(
                        plugin,
                        anchors,
                        configuration.layoutGeometry().emphasisRaise(),
                        configuration.viewSettings().transitionTicks());
        interactions = new InteractionRouter(actors, privateProjection, privateProjection);
        mutations = new DirectCraftEngineMutationGateway(plugin, anchors, privateProjection);
        sceneBackend =
                new CraftEngineSceneBackend(
                        mutations,
                        new PaperRegionScheduler(plugin),
                        anchors,
                        interactions,
                        CraftEngineBackendConfig.DEFAULT,
                        failure ->
                                plugin.getLogger()
                                        .warning(
                                                "CraftEngine table failure "
                                                        + failure.tableId()
                                                        + ": "
                                                        + failure.failureType()));
        sceneProjector =
                new LatestSceneProjector(
                        executors.render(),
                        new DefaultTableSceneMapper(
                                new UniversalTableLayout(
                                        tableGeometry(configuration.layoutGeometry())),
                                sceneAssets(configuration.craftEngineAssets()),
                                configuration.viewSettings().overheadHeight(),
                                configuration.viewSettings().overheadEnabled()),
                        sceneBackend,
                        new SceneGraphDiffer(),
                        deadlines);
    }

    /** Registers platform listeners and starts the immutable CE bundle installation once. */
    public void start(SeatInteractionPort seatInteractions) {
        Objects.requireNonNull(seatInteractions, "seatInteractions");
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("CraftEngine platform runtime already started");
        }
        registerListeners(seatInteractions);
        installBundle();
    }

    public LatestSceneProjector sceneProjector() {
        return sceneProjector;
    }

    public CraftEngineSceneBackend sceneBackend() {
        return sceneBackend;
    }

    public PaperTableAnchorService anchorService() {
        return anchorService;
    }

    public void registerAnchor(TableId tableId, Location location) {
        anchors.register(
                Objects.requireNonNull(tableId, "tableId"),
                Objects.requireNonNull(location, "location"));
    }

    public void removeTable(TableId tableId) {
        Objects.requireNonNull(tableId, "tableId");
        sceneProjector.remove(tableId);
        sceneBackend.removeTable(tableId);
        anchors.remove(tableId);
    }

    private void registerListeners(SeatInteractionPort seatInteractions) {
        plugin.getServer()
                .getPluginManager()
                .registerEvents(
                        new CraftEngineInteractionListener(
                                plugin,
                                interactions,
                                (player, result, failure) -> {
                                    Component message = interactionFeedback(result, failure);
                                    if (message == null) {
                                        return;
                                    }
                                    player.getScheduler()
                                            .run(
                                                    plugin,
                                                    ignored -> player.sendActionBar(message),
                                                    null);
                                },
                                seatInteractions,
                                mutations.managedKey(),
                                mutations.tableKey(),
                                mutations.nodeKey(),
                                mutations.interactionKey()),
                        plugin);
        plugin.getServer()
                .getPluginManager()
                .registerEvents(
                        new CraftEngineReloadListener(sceneBackend, bundleInstalled::get),
                        plugin);
        plugin.getServer().getPluginManager().registerEvents(privateProjection, plugin);
    }

    private static Component interactionFeedback(
            TableActionResult result,
            Throwable failure) {
        if (failure != null || result == null) {
            return Component.translatable("mahjongpaper.feedback.action_failed")
                    .color(NamedTextColor.RED);
        }
        return switch (result.code()) {
            case HAND_TILE_SELECTED,
                    HAND_TILE_SELECTION_CANCELLED,
                    DUPLICATE_INTERACTION,
                    ACCEPTED_MEMORY -> null;
            case OVERHEAD_VIEW_ENTERED ->
                    Component.translatable("mahjongpaper.feedback.overhead_entered")
                            .color(NamedTextColor.AQUA);
            case OVERHEAD_VIEW_EXITED ->
                    Component.translatable("mahjongpaper.feedback.overhead_exited")
                            .color(NamedTextColor.GREEN);
            case OVERHEAD_VIEW_UNAVAILABLE ->
                    Component.translatable("mahjongpaper.feedback.overhead_unavailable")
                            .color(NamedTextColor.RED);
            case OVERHEAD_VIEW_READ_ONLY ->
                    Component.translatable("mahjongpaper.feedback.overhead_read_only")
                            .color(NamedTextColor.YELLOW);
            default -> Component.text(result.reasonCode(), NamedTextColor.RED);
        };
    }

    private void installBundle() {
        CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return new CraftEngineBundleInstaller(
                                                plugin,
                                                configuration.craftEngineBundleFolder())
                                        .install(craftEngine);
                            } catch (IOException failure) {
                                throw new CompletionException(failure);
                            }
                        },
                        executors.io())
                .whenComplete(
                        (result, failure) -> {
                            if (failure == null) {
                                bundleInstalled.set(true);
                                if (result.changed()) {
                                    plugin.getLogger()
                                            .info(
                                                    "CraftEngine bundle updated; scenes remain closed until CraftEngineReloadEvent.");
                                } else {
                                    activateInstalledBundle();
                                }
                            } else {
                                plugin.getLogger()
                                        .log(
                                                Level.SEVERE,
                                                "CraftEngine bundle installation failed; scenes remain closed",
                                                FailureSupport.unwrap(failure));
                            }
                        });
    }

    private void activateInstalledBundle() {
        if (closed.get() || !plugin.isEnabled()) {
            return;
        }
        try {
            Bukkit.getGlobalRegionScheduler()
                    .execute(
                            plugin,
                            () -> {
                                try {
                                    if (CraftEngineItems.byId("mahjongpaper:table_visual") != null) {
                                        sceneBackend.onCraftEngineReloaded();
                                    } else {
                                        plugin.getLogger()
                                                .info(
                                                        "CraftEngine bundle is installed but not loaded; scenes await CraftEngineReloadEvent.");
                                    }
                                } catch (RuntimeException notLoadedYet) {
                                    plugin.getLogger().fine("CraftEngine assets are not loaded yet");
                                }
                            });
        } catch (RuntimeException shuttingDown) {
            plugin.getLogger().fine("CraftEngine bundle activation skipped during shutdown");
        }
    }

    private Plugin requireCraftEngine() {
        Plugin dependency = plugin.getServer().getPluginManager().getPlugin("CraftEngine");
        if (dependency == null || !dependency.isEnabled()) {
            throw new IllegalStateException("CraftEngine is a hard dependency");
        }
        CraftEngineVersion.requireSupported(dependency.getPluginMeta().getVersion());
        return dependency;
    }

    private static TableSceneAssets sceneAssets(
            PluginConfiguration.CraftEngineAssets configured) {
        return new TableSceneAssets(
                configured.table(),
                configured.standingBack(),
                configured.flatBack(),
                configured.handHitbox(),
                configured.actionHitbox());
    }

    private static TableGeometry tableGeometry(
            PluginConfiguration.LayoutGeometry configured) {
        return new TableGeometry(
                configured.tileWidth(),
                configured.tileHeight(),
                configured.tileDepth(),
                configured.tileGap(),
                configured.surfaceHeight(),
                configured.handRadius(),
                configured.wallRadius(),
                configured.tableHalfLength(),
                configured.emphasisRaise(),
                configured.actionColumnSpacing(),
                configured.actionRowSpacing(),
                configured.secondaryActionOffset(),
                configured.maxHandTiles(),
                configured.maxDiscards(),
                configured.maxMeldTiles(),
                configured.maxPointSticks(),
                configured.maxAuxiliaryTiles(),
                configured.maxActions());
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            privateProjection.close();
        }
    }
}
