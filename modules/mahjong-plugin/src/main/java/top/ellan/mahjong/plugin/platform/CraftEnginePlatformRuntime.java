package top.ellan.mahjong.plugin.platform;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import top.ellan.mahjong.application.automation.PlayerPresencePort;
import top.ellan.mahjong.application.feedback.TablePresentationCuePort;
import top.ellan.mahjong.application.interaction.InteractionRouter;
import top.ellan.mahjong.application.opening.TableOpeningPresentationPort;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.application.table.TableActorRegistry;
import top.ellan.mahjong.application.lobby.port.SeatInteractionPort;
import top.ellan.mahjong.craftengine.scene.CraftEngineBackendConfig;
import top.ellan.mahjong.craftengine.bundle.CraftEngineBundleInstaller;
import top.ellan.mahjong.craftengine.interaction.CraftEngineInteractionListener;
import top.ellan.mahjong.craftengine.opening.CraftEngineOpeningAnimationConfig;
import top.ellan.mahjong.craftengine.opening.CraftEngineOpeningPresenter;
import top.ellan.mahjong.craftengine.bundle.CraftEngineReloadListener;
import top.ellan.mahjong.craftengine.scene.CraftEngineSceneBackend;
import top.ellan.mahjong.craftengine.bundle.CraftEngineVersion;
import top.ellan.mahjong.craftengine.scene.DirectCraftEngineMutationGateway;
import top.ellan.mahjong.craftengine.privateview.SparrowPrivateProjectionGateway;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.platform.paper.concurrent.BoundedPlatformExecutors;
import top.ellan.mahjong.platform.paper.feedback.PaperOpeningSoundGateway;
import top.ellan.mahjong.platform.paper.feedback.PaperRuleSoundCatalog;
import top.ellan.mahjong.platform.paper.feedback.PaperSoundDispatcher;
import top.ellan.mahjong.platform.paper.feedback.PaperSoundProfile;
import top.ellan.mahjong.platform.paper.feedback.PaperTableSoundGateway;
import top.ellan.mahjong.platform.paper.feedback.RuleSoundBinding;
import top.ellan.mahjong.platform.paper.feedback.RuleSoundProfiles;
import top.ellan.mahjong.platform.paper.region.PaperRegionScheduler;
import top.ellan.mahjong.platform.paper.anchor.PaperTableAnchorRegistry;
import top.ellan.mahjong.platform.paper.anchor.PaperTableAnchorService;
import top.ellan.mahjong.plugin.MahjongPaperPlugin;
import top.ellan.mahjong.plugin.config.PluginConfiguration;
import top.ellan.mahjong.plugin.runtime.FailureSupport;
import top.ellan.mahjong.presentation.projection.DefaultTableSceneMapper;
import top.ellan.mahjong.presentation.projection.LatestSceneProjector;
import top.ellan.mahjong.presentation.scene.SceneGraphDiffer;
import top.ellan.mahjong.presentation.layout.TableGeometry;
import top.ellan.mahjong.presentation.asset.TableSceneAssets;
import top.ellan.mahjong.presentation.layout.UniversalTableLayout;
import top.ellan.mahjong.runtime.resources.InspectedRuleResourcePack;
import top.ellan.mahjong.runtime.resources.RuleSoundCatalog;
import top.ellan.mahjong.runtime.resources.RuleSoundProfile;
import top.ellan.mahjong.spi.RulePackRef;
import top.ellan.mahjong.spi.RulePresentationCueType;

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
    private final TablePresentationCuePort presentationCues;
    private final CraftEngineOpeningPresenter openingPresentations;
    private final PaperRuleSoundCatalog soundCatalog = new PaperRuleSoundCatalog();
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
        PaperSoundDispatcher sounds = new PaperSoundDispatcher(plugin);
        presentationCues = new PaperTableSoundGateway(sounds, soundCatalog);
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
        PluginConfiguration.OpeningSettings opening = configuration.openingSettings();
        openingPresentations = new CraftEngineOpeningPresenter(
                deadlines,
                sceneBackend,
                new CraftEngineOpeningAnimationConfig(
                        configuration.craftEngineAssets().openingDieSlotPrefix(),
                        Duration.ofMillis(Math.multiplyExact(opening.rollTicks(), 50L)),
                        Duration.ofMillis(Math.multiplyExact(opening.revealTicks(), 50L))),
                new PaperOpeningSoundGateway(
                        sounds,
                        soundCatalog));
    }

    /** Registers platform listeners and starts the immutable CE bundle installation once. */
    public void start(
            SeatInteractionPort seatInteractions, PlayerPresencePort playerPresence) {
        Objects.requireNonNull(seatInteractions, "seatInteractions");
        Objects.requireNonNull(playerPresence, "playerPresence");
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("CraftEngine platform runtime already started");
        }
        registerListeners(seatInteractions, playerPresence);
        installBundle();
    }

    /** Installs and activates all resources belonging to the startup rule selection. */
    public void installRuleResources(List<InspectedRuleResourcePack> resources)
            throws IOException {
        ArrayList<RuleSoundBinding> profiles = new ArrayList<>();
        boolean changed = false;
        for (InspectedRuleResourcePack resource : List.copyOf(resources)) {
            CraftEngineBundleInstaller.InstallResult result =
                    CraftEngineBundleInstaller.fromArchive(
                                    resource.archive(), ruleBundleFolder(resource))
                            .install(craftEngine);
            changed |= result.changed();
            profiles.add(soundBinding(resource));
        }
        soundCatalog.replaceAll(profiles);
        if (changed) {
            plugin.getLogger()
                    .info(
                            "Rule resources updated; their sounds become available after CraftEngine reload.");
        }
    }

    /** Switches one rule's verified companion resources during an immediate hot swap. */
    public void activateRuleResource(
            RulePackRef reference, Optional<InspectedRuleResourcePack> resource) throws IOException {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(resource, "resource");
        if (resource.isEmpty()) {
            soundCatalog.remove(reference);
            return;
        }
        InspectedRuleResourcePack selected = resource.orElseThrow();
        if (!selected.belongsTo(reference)) {
            throw new IllegalArgumentException(
                    "Rule resources differ from the activation coordinate");
        }
        CraftEngineBundleInstaller.InstallResult result =
                CraftEngineBundleInstaller.fromArchive(
                                selected.archive(), ruleBundleFolder(selected))
                        .install(craftEngine);
        soundCatalog.put(soundBinding(selected));
        if (result.changed()) {
            plugin.getLogger()
                    .info(
                            "Rule resources updated for "
                                    + reference.ruleId()
                                    + ':'
                                    + reference.version()
                                    + "; CraftEngine reload is required.");
        }
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

    public TablePresentationCuePort presentationCues() {
        return presentationCues;
    }

    public TableOpeningPresentationPort openingPresentations() {
        return openingPresentations;
    }

    public void registerAnchor(TableId tableId, Location location) {
        anchors.register(
                Objects.requireNonNull(tableId, "tableId"),
                Objects.requireNonNull(location, "location"));
    }

    public void removeTable(TableId tableId) {
        Objects.requireNonNull(tableId, "tableId");
        openingPresentations.clear(tableId);
        sceneProjector.remove(tableId);
        sceneBackend.removeTable(tableId);
        anchors.remove(tableId);
    }

    private void registerListeners(
            SeatInteractionPort seatInteractions, PlayerPresencePort playerPresence) {
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
                                playerPresence,
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

    private static RuleSoundProfiles soundProfiles(RuleSoundCatalog configured) {
        EnumMap<RulePresentationCueType, PaperSoundProfile> profiles =
                new EnumMap<>(RulePresentationCueType.class);
        configured.cues().forEach(
                (type, profile) -> profiles.put(type, soundProfile(profile)));
        return new RuleSoundProfiles(
                Map.copyOf(profiles),
                soundProfile(configured.openingDice()),
                soundProfile(configured.openingWall()));
    }

    private static PaperSoundProfile soundProfile(RuleSoundProfile configured) {
        return new PaperSoundProfile(configured.key(), configured.volume(), configured.pitch());
    }

    private static RuleSoundBinding soundBinding(InspectedRuleResourcePack resource) {
        return new RuleSoundBinding(
                resource.ruleId(),
                resource.version(),
                resource.jarSha256(),
                soundProfiles(resource.sounds()));
    }

    private static String ruleBundleFolder(InspectedRuleResourcePack resource) {
        String version = resource.version().replaceAll("[^0-9A-Za-z._-]", "_");
        return "mahjongpaper-rule-"
                + resource.ruleId().value()
                + '-'
                + version
                + '-'
                + resource.jarSha256().substring(0, 12);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            openingPresentations.close();
            privateProjection.close();
        }
    }
}
