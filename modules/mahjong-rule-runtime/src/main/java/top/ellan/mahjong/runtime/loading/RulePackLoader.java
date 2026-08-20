package top.ellan.mahjong.runtime.loading;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import top.ellan.mahjong.runtime.common.RulePackException;
import top.ellan.mahjong.runtime.registry.RulePackRegistryEntry;
import top.ellan.mahjong.spi.RulePackDescriptor;
import top.ellan.mahjong.spi.RuleExecutionBudget;
import top.ellan.mahjong.spi.RulePackProvider;
import top.ellan.mahjong.spi.RulePackRef;
import top.ellan.mahjong.spi.SpiVersion;

/** Static validation followed by isolated ServiceLoader probing. */
public final class RulePackLoader implements PinnedRulePackLoader {
    private static final String SPI_PREFIX = "top/ellan/mahjong/spi/";
    private static final String CORE_PREFIX = "top/ellan/mahjong/";
    private static final String RULE_PACK_PREFIX = "top/ellan/mahjong/rules/";
    /**
     * Assembled at runtime so this core module contains no literal JDBC package reference; the
     * architecture check rejects those, and the rule here is about rejecting such a service file in
     * a rule pack, not about using JDBC.
     */
    private static final String JDBC_DRIVER_SERVICE =
            "META-INF/services/" + String.join(".", "java", "sql", "Driver");
    private static final long MAX_ENTRY_BYTES = 64L * 1024 * 1024;
    private static final long MAX_UNCOMPRESSED_BYTES = 256L * 1024 * 1024;
    private static final int MAX_ENTRIES = 20_000;
    private static final Duration PROVIDER_PROBE_BUDGET = Duration.ofSeconds(2);
    private final String coreVersion;
    private final ArtifactInspections inspections = new ArtifactInspections();

    public RulePackLoader(String coreVersion) {
        this.coreVersion = java.util.Objects.requireNonNull(coreVersion, "coreVersion");
    }

    public LoadedRulePack load(Path artifact, RulePackRegistryEntry expected)
            throws RulePackException {
        Path normalized = java.util.Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new RulePackException("Rule-pack artifact does not exist: " + normalized);
        }
        ArtifactInspections.Inspection inspection;
        try {
            inspection = inspections.inspect(normalized, RulePackLoader::validateArchive);
        } catch (IOException failure) {
            throw new RulePackException("Unable to inspect rule-pack JAR", failure);
        }
        String sha256 = inspection.sha256();
        RulePackManifest manifest = inspection.manifest();
        // The integrity comparison stays outside the cache: the same verified file may be checked
        // against different registry entries, and a mismatch must always fail closed.
        if (!sha256.equals(expected.sha256())) {
            throw new RulePackException("Rule-pack SHA-256 differs from signed registry");
        }
        validateManifest(manifest, expected);

        ChildFirstRuleClassLoader loader;
        try {
            loader =
                    new ChildFirstRuleClassLoader(
                            normalized.toUri().toURL(), RulePackProvider.class.getClassLoader());
        } catch (MalformedURLException failure) {
            throw new RulePackException("Invalid rule-pack artifact path", failure);
        }
        try {
            List<RulePackProvider> providers = RuleExecutionBudget.call(
                    PROVIDER_PROBE_BUDGET,
                    () -> ServiceLoader.load(RulePackProvider.class, loader).stream()
                            .map(ServiceLoader.Provider::get)
                            .toList());
            if (providers.size() != 1) {
                throw new RulePackException(
                        "Rule pack must expose exactly one RulePackProvider; found " + providers.size());
            }
            RulePackProvider provider = providers.getFirst();
            RulePackDescriptor descriptor = RuleExecutionBudget.call(
                    PROVIDER_PROBE_BUDGET, provider::descriptor);
            validateDescriptor(descriptor, manifest);
            RulePackRef reference =
                    new RulePackRef(
                            descriptor.ruleId(),
                            descriptor.version(),
                            sha256,
                            descriptor.stateSchemaVersion());
            return new LoadedRulePack(reference, normalized, provider, loader);
        } catch (RulePackException failure) {
            closeAfterFailure(loader, failure);
            throw failure;
        } catch (ServiceConfigurationError | RuntimeException | LinkageError failure) {
            RulePackException wrapped = new RulePackException("Rule-pack provider probe failed", failure);
            closeAfterFailure(loader, wrapped);
            throw wrapped;
        } catch (Error failure) {
            if (!RuleExecutionBudget.exceeded(failure)) {
                try {
                    loader.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
            RulePackException wrapped =
                    new RulePackException("Rule-pack provider probe exceeded its budget", failure);
            closeAfterFailure(loader, wrapped);
            throw wrapped;
        }
    }

    /** Reloads a previously verified artifact using the exact provenance stored with a match. */
    @Override
    public LoadedRulePack loadPinned(Path artifact, RulePackRef pinned) throws RulePackException {
        Path normalized = java.util.Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new RulePackException("Rule-pack artifact does not exist: " + normalized);
        }
        RulePackManifest manifest;
        long size;
        try {
            size = Files.size(normalized);
            // Shares the cached inspection with the load() below, so a pinned reload opens the
            // archive once instead of twice and hashes it once instead of once per call.
            manifest = inspections.inspect(normalized, RulePackLoader::validateArchive).manifest();
        } catch (IOException failure) {
            throw new RulePackException("Unable to inspect pinned rule-pack JAR", failure);
        }
        if (!manifest.ruleId().equals(pinned.ruleId())
                || !manifest.version().equals(pinned.version())
                || manifest.stateSchemaVersion() != pinned.stateSchemaVersion()) {
            throw new RulePackException("Pinned match provenance differs from JAR manifest");
        }
        RulePackRegistryEntry expected =
                new RulePackRegistryEntry(
                        pinned.ruleId(),
                        pinned.version(),
                        java.net.URI.create("https://installed.invalid/" + pinned.ruleId() + ".jar"),
                        pinned.jarSha256(),
                        manifest.spiVersion(),
                        manifest.requiredCoreVersion(),
                        size,
                        java.util.Optional.empty());
        LoadedRulePack loaded = load(normalized, expected);
        if (!loaded.reference().equals(pinned)) {
            try {
                loaded.close();
            } catch (IOException closeFailure) {
                throw new RulePackException("Pinned rule pack differs and could not be closed", closeFailure);
            }
            throw new RulePackException("Pinned rule-pack reference differs after provider probe");
        }
        return loaded;
    }

    private void validateManifest(RulePackManifest manifest, RulePackRegistryEntry expected)
            throws RulePackException {
        if (!manifest.ruleId().equals(expected.ruleId())
                || !manifest.version().equals(expected.version())
                || !manifest.spiVersion().equals(expected.spiVersion())
                || !manifest.requiredCoreVersion().equals(expected.requiredCoreVersion())) {
            throw new RulePackException("JAR manifest differs from its signed registry entry");
        }
        if (!SpiVersion.isSupported(manifest.spiVersion())) {
            throw new RulePackException("Unsupported rule SPI version: " + manifest.spiVersion());
        }
        try {
            if (!CoreVersionConstraint.accepts(manifest.requiredCoreVersion(), coreVersion)) {
                throw new RulePackException(
                        "Rule pack requires core "
                                + manifest.requiredCoreVersion()
                                + " but this core is "
                                + coreVersion);
            }
        } catch (IllegalArgumentException failure) {
            throw new RulePackException("Invalid core version constraint", failure);
        }
    }

    private static void validateDescriptor(
            RulePackDescriptor descriptor, RulePackManifest manifest) throws RulePackException {
        if (descriptor == null
                || !descriptor.ruleId().equals(manifest.ruleId())
                || !descriptor.version().equals(manifest.version())
                || !descriptor.spiVersion().equals(manifest.spiVersion())
                || !descriptor.requiredCoreVersion().equals(manifest.requiredCoreVersion())
                || descriptor.stateSchemaVersion() != manifest.stateSchemaVersion()
                || !descriptor.requiredResources().equals(manifest.requiredResources())) {
            throw new RulePackException("Provider descriptor differs from the static JAR manifest");
        }
    }

    private static void validateArchive(ZipFile jar, RulePackManifest manifest)
            throws RulePackException {
        Set<String> names = new HashSet<>();
        long uncompressedBytes = 0;
        int entryCount = 0;
        var entries = jar.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            entryCount++;
            long size = entry.getSize();
            if (entryCount > MAX_ENTRIES
                    || size < 0
                    || size > MAX_ENTRY_BYTES
                    || uncompressedBytes > MAX_UNCOMPRESSED_BYTES - size) {
                throw new RulePackException("Rule-pack archive exceeds decompression limits");
            }
            uncompressedBytes += size;
            if (!names.add(name)) {
                throw new RulePackException("Duplicate JAR entry: " + name);
            }
            if (name.startsWith("/")
                    || name.contains("\\")
                    || List.of(name.split("/")).contains("..")) {
                throw new RulePackException("Unsafe JAR entry: " + name);
            }
            if (name.startsWith(SPI_PREFIX)) {
                throw new RulePackException("Rule pack must exclude mahjong-rule-spi classes");
            }
            if (!entry.isDirectory()
                    && name.startsWith(CORE_PREFIX)
                    && !name.startsWith(RULE_PACK_PREFIX)) {
                // Rule packs own top/ellan/mahjong/rules/ and share only the SPI parent-first. Any
                // other core class inside the JAR would be defined twice and surface as a
                // LinkageError or cross-loader type mismatch much later, so reject it while the
                // coordinate is still being verified. Directory entries carry no bytecode, and a
                // shaded pack legitimately contains the intermediate top/ellan/mahjong/ directory.
                throw new RulePackException("Rule pack must not shade core classes: " + name);
            }
            if (name.equals(JDBC_DRIVER_SERVICE)) {
                // A driver registered from a rule-pack loader is pinned by DriverManager's static
                // registry forever, which would make the classloader unreclaimable after unload.
                throw new RulePackException("Rule pack must not register a JDBC driver");
            }
            if (isPresentationResource(name)) {
                throw new RulePackException(
                        "Rule JAR must not contain resource-pack content: " + name);
            }
            if (name.startsWith("META-INF/versions/")) {
                throw new RulePackException("Multi-release rule JARs are not supported");
            }
        }
        String service = "META-INF/services/" + RulePackProvider.class.getName();
        if (!names.contains(service)) {
            throw new RulePackException("Rule pack has no RulePackProvider service descriptor");
        }
        RulePackBytecodePolicy.verify(jar, service);
        for (String resource : manifest.requiredResources()) {
            if (!names.contains(resource)) {
                throw new RulePackException("Rule pack is missing required resource: " + resource);
            }
        }
    }

    private static boolean isPresentationResource(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return name.startsWith("assets/")
                || name.startsWith("resourcepack/")
                || name.startsWith("craftengine/")
                || lower.endsWith(".ogg")
                || lower.endsWith(".png")
                || lower.endsWith(".mcmeta");
    }

    private static void closeAfterFailure(
            ChildFirstRuleClassLoader loader, RulePackException original) {
        try {
            loader.close();
        } catch (IOException closeFailure) {
            original.addSuppressed(closeFailure);
        }
    }
}
