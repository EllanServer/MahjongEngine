package top.ellan.mahjong.runtime.resources;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import top.ellan.mahjong.runtime.activation.RuleActivationState;
import top.ellan.mahjong.runtime.activation.RuleActivationStore;
import top.ellan.mahjong.runtime.common.RulePackException;
import top.ellan.mahjong.runtime.registry.RulePackRegistry;
import top.ellan.mahjong.runtime.registry.RulePackRegistryEntry;
import top.ellan.mahjong.runtime.registry.SignedRegistryCodec;
import top.ellan.mahjong.runtime.security.OfficialTrustRoot;
import top.ellan.mahjong.runtime.storage.RulePackPaths;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackRef;

/** Resolves installed resources only through the cached, signed official registry. */
public final class RuleResourcePackResolver {
    private final RulePackPaths paths;
    private final RuleActivationStore activationStore;
    private final OfficialTrustRoot trustRoot;
    private final RuleResourcePackInspector inspector;

    public RuleResourcePackResolver(
            RulePackPaths paths,
            RuleActivationStore activationStore,
            OfficialTrustRoot trustRoot) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.activationStore = Objects.requireNonNull(activationStore, "activationStore");
        this.trustRoot = Objects.requireNonNull(trustRoot, "trustRoot");
        this.inspector = new RuleResourcePackInspector();
    }

    public List<InspectedRuleResourcePack> resolveActive()
            throws IOException, RulePackException {
        RuleActivationState activation = activationStore.read();
        if (activation.active().isEmpty()) {
            return List.of();
        }
        RulePackRegistry registry = readCachedRegistry();
        List<InspectedRuleResourcePack> resources = new ArrayList<>();
        for (RulePackRef reference : activation.active().values()) {
            resolve(reference, registry).ifPresent(resources::add);
        }
        return List.copyOf(resources);
    }

    public Optional<InspectedRuleResourcePack> resolve(RuleId ruleId, String version)
            throws IOException, RulePackException {
        RulePackRegistryEntry entry = readCachedRegistry()
                .find(ruleId, Optional.of(version))
                .orElseThrow(() -> new RulePackException(
                        "Requested rule-pack version is absent from cached signed registry"));
        if (entry.resources().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(inspector.inspect(paths.installedResources(ruleId, version), entry));
    }

    /** Resolves resources for an exact running generation, including recovered matches. */
    public Optional<InspectedRuleResourcePack> resolve(RulePackRef reference)
            throws IOException, RulePackException {
        Objects.requireNonNull(reference, "reference");
        return resolve(reference, readCachedRegistry());
    }

    private Optional<InspectedRuleResourcePack> resolve(
            RulePackRef reference, RulePackRegistry registry) throws IOException, RulePackException {
        RulePackRegistryEntry entry = registry.find(
                        reference.ruleId(), Optional.of(reference.version()))
                .orElseThrow(() -> new RulePackException(
                        "Active rule coordinate is absent from cached signed registry"));
        if (!entry.sha256().equals(reference.jarSha256())) {
            throw new RulePackException("Active rule provenance differs from cached registry");
        }
        if (entry.resources().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(inspector.inspect(
                paths.installedResources(reference.ruleId(), reference.version()), entry));
    }

    private RulePackRegistry readCachedRegistry() throws IOException, RulePackException {
        if (!Files.isRegularFile(paths.registryCache())) {
            throw new RulePackException("No cached signed registry is available for rule resources");
        }
        return SignedRegistryCodec.decode(Files.readAllBytes(paths.registryCache()), trustRoot)
                .registry();
    }
}
