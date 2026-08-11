package top.ellan.mahjong.plugin.bootstrap.rules;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import top.ellan.mahjong.runtime.security.EmbeddedRuleTrustRoot;
import top.ellan.mahjong.runtime.install.HttpArtifactDownloader;
import top.ellan.mahjong.runtime.registry.HttpRegistrySource;
import top.ellan.mahjong.runtime.security.OfficialTrustRoot;
import top.ellan.mahjong.runtime.activation.RuleActivationStore;
import top.ellan.mahjong.runtime.admin.RulePackAdminService;
import top.ellan.mahjong.runtime.common.RulePackException;
import top.ellan.mahjong.runtime.admin.RulePackGarbageCollector;
import top.ellan.mahjong.runtime.install.RulePackInstaller;
import top.ellan.mahjong.runtime.admin.RulePackInventoryReader;
import top.ellan.mahjong.runtime.loading.RulePackLoader;
import top.ellan.mahjong.runtime.storage.RulePackPaths;
import top.ellan.mahjong.runtime.storage.RulePackReferenceIndex;
import top.ellan.mahjong.runtime.lifecycle.RulePackRuntime;
import top.ellan.mahjong.runtime.resources.RuleResourcePackResolver;

/** Starts installed providers and builds the signed official-package administration boundary. */
public final class RulePackBootstrap {
    private final Path pluginDataFolder;
    private final ClassLoader pluginClassLoader;
    private final String coreVersion;
    private final String registryUrl;
    private final Executor ioExecutor;
    private final RulePackReferenceIndex references;
    private final Clock clock;
    private final Logger logger;

    public RulePackBootstrap(
            Path pluginDataFolder,
            ClassLoader pluginClassLoader,
            String coreVersion,
            String registryUrl,
            Executor ioExecutor,
            RulePackReferenceIndex references,
            Clock clock,
            Logger logger) {
        this.pluginDataFolder = Objects.requireNonNull(pluginDataFolder, "pluginDataFolder");
        this.pluginClassLoader = Objects.requireNonNull(pluginClassLoader, "pluginClassLoader");
        this.coreVersion = Objects.requireNonNull(coreVersion, "coreVersion");
        this.registryUrl = Objects.requireNonNull(registryUrl, "registryUrl");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.references = Objects.requireNonNull(references, "references");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public RulePackRuntimeServices initialize() {
        RulePackPaths paths = new RulePackPaths(pluginDataFolder.resolve("rules"));
        RulePackLoader loader = new RulePackLoader(coreVersion);
        RuleActivationStore activation = new RuleActivationStore(paths.activationState());
        RulePackInventoryReader inventory = new RulePackInventoryReader(paths, activation);
        Optional<RulePackRuntime> running = startRuntime(paths, loader, activation);
        Optional<OfficialTrustRoot> trustRoot = loadTrustRoot();
        Optional<RuleResourcePackResolver> resources = trustRoot.map(
                trust -> new RuleResourcePackResolver(paths, activation, trust));
        Optional<RulePackAdminService> admin = trustRoot.flatMap(
                trust -> createAdmin(paths, loader, activation, running, trust));
        return new RulePackRuntimeServices(running, admin, inventory, resources);
    }

    private Optional<RulePackRuntime> startRuntime(
            RulePackPaths paths,
            RulePackLoader loader,
            RuleActivationStore activation) {
        RulePackRuntime runtime = new RulePackRuntime(paths, loader, activation);
        try {
            runtime.start();
            return Optional.of(runtime);
        } catch (IOException | RulePackException failure) {
            new RulePackRuntimeServices(
                            Optional.of(runtime),
                            Optional.empty(),
                            new RulePackInventoryReader(paths, activation),
                            Optional.empty())
                    .close();
            logger.log(Level.SEVERE, "Rule-pack runtime failed closed", failure);
            return Optional.empty();
        }
    }

    private Optional<RulePackAdminService> createAdmin(
            RulePackPaths paths,
            RulePackLoader loader,
            RuleActivationStore activation,
            Optional<RulePackRuntime> running,
            OfficialTrustRoot trustRoot) {
        try {
            URI registryUri = URI.create(registryUrl);
            if (!"https".equalsIgnoreCase(registryUri.getScheme())) {
                throw new IllegalArgumentException("rules.registry-url must use HTTPS");
            }
            HttpClient client =
                    HttpClient.newBuilder()
                            .executor(ioExecutor)
                            .connectTimeout(Duration.ofSeconds(10))
                            // GitHub Release assets redirect to GitHub object storage. NORMAL
                            // follows that HTTPS redirect but refuses an HTTPS-to-HTTP downgrade;
                            // the registry signature and artifact SHA-256 still authenticate bytes.
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build();
            HttpRegistrySource registry = new HttpRegistrySource(client, registryUri);
            RulePackInstaller installer =
                    new RulePackInstaller(
                            paths,
                            registry,
                            new HttpArtifactDownloader(client),
                            trustRoot,
                            loader,
                            clock);
            RulePackGarbageCollector garbageCollector =
                    new RulePackGarbageCollector(
                            paths,
                            references,
                            activation,
                            // A still-open classloader keeps its JAR handle open, so quarantining
                            // that directory would fail on Windows. Ask the runtime every time.
                            () -> running
                                    .map(RulePackRuntime::loadedReferences)
                                    .map(refs -> (Iterable<top.ellan.mahjong.spi.RulePackRef>) refs)
                                    .orElse(java.util.List.of()),
                            clock);
            return Optional.of(
                    new RulePackAdminService(
                            paths,
                            registry,
                            trustRoot,
                            loader,
                            installer,
                            activation,
                            garbageCollector));
        } catch (IllegalArgumentException failure) {
            logger.log(Level.WARNING, "Rule-pack administration disabled", failure);
            return Optional.empty();
        }
    }

    private Optional<OfficialTrustRoot> loadTrustRoot() {
        if (registryUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(EmbeddedRuleTrustRoot.load(pluginClassLoader));
        } catch (IOException | RulePackException failure) {
            logger.log(Level.WARNING, "Rule-pack trust root is unavailable", failure);
            return Optional.empty();
        }
    }
}
