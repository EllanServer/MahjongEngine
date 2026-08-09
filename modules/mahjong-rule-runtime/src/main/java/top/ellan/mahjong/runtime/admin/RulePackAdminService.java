package top.ellan.mahjong.runtime.admin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import top.ellan.mahjong.runtime.activation.RuleActivationState;
import top.ellan.mahjong.runtime.activation.RuleActivationStore;
import top.ellan.mahjong.runtime.catalog.OfficialRuleIds;
import top.ellan.mahjong.runtime.common.RulePackException;
import top.ellan.mahjong.runtime.install.InstallationResult;
import top.ellan.mahjong.runtime.install.RulePackInstaller;
import top.ellan.mahjong.runtime.loading.LoadedRulePack;
import top.ellan.mahjong.runtime.loading.RulePackLoader;
import top.ellan.mahjong.runtime.registry.RegistrySource;
import top.ellan.mahjong.runtime.registry.RulePackRegistry;
import top.ellan.mahjong.runtime.registry.RulePackRegistryEntry;
import top.ellan.mahjong.runtime.registry.SignedRegistryCodec;
import top.ellan.mahjong.runtime.registry.VerifiedRegistryDocument;
import top.ellan.mahjong.runtime.security.OfficialTrustRoot;
import top.ellan.mahjong.runtime.storage.AtomicFiles;
import top.ellan.mahjong.runtime.storage.RulePackPaths;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackRef;

/**
 * Blocking administrative facade. Platform adapters must run these methods on their bounded I/O
 * executor; no Paper, CraftEngine, or actor thread should call it directly.
 */
public final class RulePackAdminService {
    private final RulePackPaths paths;
    private final RegistrySource registrySource;
    private final OfficialTrustRoot trustRoot;
    private final RulePackLoader loader;
    private final RulePackInstaller installer;
    private final RuleActivationStore activationStore;
    private final RulePackGarbageCollector garbageCollector;
    private final RulePackInventoryReader inventoryReader;

    public RulePackAdminService(
            RulePackPaths paths,
            RegistrySource registrySource,
            OfficialTrustRoot trustRoot,
            RulePackLoader loader,
            RulePackInstaller installer,
            RuleActivationStore activationStore,
            RulePackGarbageCollector garbageCollector) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.registrySource = Objects.requireNonNull(registrySource, "registrySource");
        this.trustRoot = Objects.requireNonNull(trustRoot, "trustRoot");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.installer = Objects.requireNonNull(installer, "installer");
        this.activationStore = Objects.requireNonNull(activationStore, "activationStore");
        this.garbageCollector = Objects.requireNonNull(garbageCollector, "garbageCollector");
        this.inventoryReader = new RulePackInventoryReader(paths, activationStore);
    }

    public InstallationResult install(RuleId ruleId, Optional<String> version)
            throws IOException, InterruptedException, RulePackException {
        return installer.install(requireOfficial(ruleId), Objects.requireNonNull(version, "version"));
    }

    public RulePackInventory list() throws IOException, RulePackException {
        return inventoryReader.read();
    }

    public List<RulePackVerification> verify(Optional<RuleId> requestedRuleId)
            throws IOException, InterruptedException, RulePackException {
        Objects.requireNonNull(requestedRuleId, "requestedRuleId");
        requestedRuleId.ifPresent(RulePackAdminService::requireOfficial);
        RulePackRegistry registry = refreshRegistry().registry();
        List<RulePackVerification> results = new ArrayList<>();
        for (InstalledRulePack installed : list().installed()) {
            if (requestedRuleId.isPresent()
                    && !requestedRuleId.orElseThrow().equals(installed.ruleId())) {
                continue;
            }
            Optional<RulePackRegistryEntry> expected = registry.find(
                    installed.ruleId(), Optional.of(installed.version()));
            if (expected.isEmpty()) {
                results.add(new RulePackVerification(
                        installed.ruleId(), installed.version(), false, null,
                        "coordinate-absent-from-signed-registry"));
                continue;
            }
            try (LoadedRulePack loaded = loader.load(installed.artifact(), expected.orElseThrow())) {
                results.add(new RulePackVerification(
                        installed.ruleId(), installed.version(), true, loaded.reference(), "verified"));
            } catch (RulePackException | IOException failure) {
                results.add(new RulePackVerification(
                        installed.ruleId(), installed.version(), false, null,
                        failure.getClass().getSimpleName() + ':' + safeMessage(failure)));
            }
        }
        return List.copyOf(results);
    }

    public RuleActivationState activate(RuleId ruleId, String version)
            throws IOException, InterruptedException, RulePackException {
        requireOfficial(ruleId);
        RulePackRegistryEntry expected = refreshRegistry().registry()
                .find(ruleId, Optional.of(version))
                .orElseThrow(() -> new RulePackException(
                        "Requested rule-pack version is absent from signed registry"));
        Path artifact = paths.installedJar(ruleId, version);
        RulePackRef reference;
        try (LoadedRulePack loaded = loader.load(artifact, expected)) {
            reference = loaded.reference();
        }
        return activationStore.requestActivation(reference);
    }

    public List<Path> collectGarbage() throws Exception {
        return garbageCollector.collect();
    }

    private VerifiedRegistryDocument refreshRegistry()
            throws IOException, InterruptedException, RulePackException {
        byte[] envelope = registrySource.fetch();
        VerifiedRegistryDocument verified = SignedRegistryCodec.decode(envelope, trustRoot);
        AtomicFiles.write(paths.registryCache(), verified.envelopeBytes());
        return verified;
    }

    private static RuleId requireOfficial(RuleId ruleId) {
        Objects.requireNonNull(ruleId, "ruleId");
        if (!OfficialRuleIds.ALL.contains(ruleId)) {
            throw new IllegalArgumentException("Only official rule ids are accepted");
        }
        return ruleId;
    }

    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? "verification-failed" : message;
    }
}
