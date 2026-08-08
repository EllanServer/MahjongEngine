package top.ellan.mahjong.runtime;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import top.ellan.mahjong.spi.RuleId;

/** Signed-registry installer. It probes in staging and exposes artifacts only by an atomic move. */
public final class RulePackInstaller {
    private final RulePackPaths paths;
    private final RegistrySource registrySource;
    private final ArtifactDownloader downloader;
    private final OfficialTrustRoot trustRoot;
    private final RulePackLoader loader;
    private final Clock clock;

    public RulePackInstaller(
            RulePackPaths paths,
            RegistrySource registrySource,
            ArtifactDownloader downloader,
            OfficialTrustRoot trustRoot,
            RulePackLoader loader,
            Clock clock) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.registrySource = Objects.requireNonNull(registrySource, "registrySource");
        this.downloader = Objects.requireNonNull(downloader, "downloader");
        this.trustRoot = Objects.requireNonNull(trustRoot, "trustRoot");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public InstallationResult install(RuleId ruleId, Optional<String> requestedVersion)
            throws RulePackException, IOException, InterruptedException {
        if (!OfficialRuleIds.ALL.contains(Objects.requireNonNull(ruleId, "ruleId"))) {
            throw new RulePackException("Only official rule packs may be installed");
        }
        Objects.requireNonNull(requestedVersion, "requestedVersion");
        paths.createLayout();
        byte[] envelope = registrySource.fetch();
        VerifiedRegistryDocument verified = SignedRegistryCodec.decode(envelope, trustRoot);
        RulePackRegistryEntry entry =
                verified.registry().find(ruleId, requestedVersion)
                        .orElseThrow(
                                () ->
                                        new RulePackException(
                                                "Requested rule-pack version is absent from registry"));
        AtomicFiles.write(paths.registryCache(), verified.envelopeBytes());
        Path installed = paths.installedJar(ruleId, entry.version());
        if (Files.isRegularFile(installed)) {
            try (LoadedRulePack loaded = loader.load(installed, entry)) {
                return new InstallationResult(loaded.reference(), installed, entry, true);
            }
        }

        String nonce = UUID.randomUUID().toString();
        Path stagingDirectory =
                paths.requireInsideRoot(
                        paths.staging().resolve(ruleId.value() + '-' + entry.version() + '-' + nonce));
        Files.createDirectory(stagingDirectory);
        Path stagedJar = stagingDirectory.resolve(ruleId.value() + "-rule-pack.jar");
        try {
            downloader.download(entry, stagedJar);
            if (Files.size(stagedJar) != entry.sizeBytes()) {
                throw new RulePackException("Downloaded artifact has the wrong size");
            }
            top.ellan.mahjong.spi.RulePackRef reference;
            try (LoadedRulePack loaded = loader.load(stagedJar, entry)) {
                reference = loaded.reference();
            }
            Path destinationDirectory = paths.versionDirectory(ruleId, entry.version());
            Files.createDirectories(destinationDirectory.getParent());
            try {
                Files.move(stagingDirectory, destinationDirectory, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException failure) {
                throw new RulePackException(
                        "Filesystem cannot atomically install rule-pack directories", failure);
            } catch (FileAlreadyExistsException race) {
                try (LoadedRulePack loaded = loader.load(installed, entry)) {
                    quarantine(stagingDirectory, ruleId, entry.version(), race);
                    return new InstallationResult(loaded.reference(), installed, entry, true);
                }
            }
            return new InstallationResult(reference, installed, entry, false);
        } catch (RulePackException | IOException | InterruptedException failure) {
            quarantine(stagingDirectory, ruleId, entry.version(), failure);
            throw failure;
        } catch (RuntimeException failure) {
            quarantine(stagingDirectory, ruleId, entry.version(), failure);
            throw failure;
        }
    }

    private void quarantine(
            Path stagingDirectory, RuleId ruleId, String version, Throwable original) {
        if (!Files.exists(stagingDirectory)) {
            return;
        }
        String timestamp = Long.toString(Instant.now(clock).toEpochMilli());
        Path target =
                paths.requireInsideRoot(
                        paths.quarantine()
                                .resolve(
                                        ruleId.value()
                                                + '-'
                                                + version
                                                + '-'
                                                + timestamp
                                                + '-'
                                                + UUID.randomUUID()));
        try {
            Files.move(stagingDirectory, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException quarantineFailure) {
            original.addSuppressed(quarantineFailure);
        }
    }
}
