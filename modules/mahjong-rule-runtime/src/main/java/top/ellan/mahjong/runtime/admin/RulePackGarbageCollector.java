package top.ellan.mahjong.runtime.admin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import top.ellan.mahjong.runtime.activation.RuleActivationState;
import top.ellan.mahjong.runtime.activation.RuleActivationStore;
import top.ellan.mahjong.runtime.catalog.OfficialRuleIds;
import top.ellan.mahjong.runtime.storage.RulePackPaths;
import top.ellan.mahjong.runtime.storage.RulePackReferenceIndex;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackRef;

/** Moves unreferenced versions to quarantine; no active or recoverable match artifact is deleted. */
public final class RulePackGarbageCollector {
    private final RulePackPaths paths;
    private final RulePackReferenceIndex references;
    private final RuleActivationStore activationStore;
    private final Supplier<Iterable<RulePackRef>> loadedCoordinates;
    private final Clock clock;

    public RulePackGarbageCollector(
            RulePackPaths paths,
            RulePackReferenceIndex references,
            RuleActivationStore activationStore,
            Clock clock) {
        this(paths, references, activationStore, List::of, clock);
    }

    public RulePackGarbageCollector(
            RulePackPaths paths,
            RulePackReferenceIndex references,
            RuleActivationStore activationStore,
            Supplier<Iterable<RulePackRef>> loadedCoordinates,
            Clock clock) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.references = Objects.requireNonNull(references, "references");
        this.activationStore = Objects.requireNonNull(activationStore, "activationStore");
        this.loadedCoordinates = Objects.requireNonNull(loadedCoordinates, "loadedCoordinates");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<Path> collect() throws Exception {
        paths.createLayout();
        Set<String> protectedCoordinates = new HashSet<>();
        RuleActivationState activation = activationStore.read();
        addCoordinates(protectedCoordinates, activation.active().values());
        addCoordinates(protectedCoordinates, activation.pending().values());
        addCoordinates(protectedCoordinates, activation.previous().values());
        addCoordinates(protectedCoordinates, references.referencedRulePacks());
        // A version whose classloader is still open keeps its JAR handle open too. Moving the
        // directory would fail outright on Windows, so skip it until the pack is unloaded.
        addCoordinates(protectedCoordinates, loadedCoordinates.get());
        List<Path> quarantined = new ArrayList<>();
        for (RuleId ruleId : OfficialRuleIds.ALL) {
            Path ruleDirectory = paths.root().resolve(ruleId.value());
            try (var versions = Files.list(ruleDirectory)) {
                for (Path versionDirectory : versions.filter(Files::isDirectory).toList()) {
                    String version = versionDirectory.getFileName().toString();
                    if (protectedCoordinates.contains(ruleId + ":" + version)) {
                        continue;
                    }
                    Path target =
                            paths.quarantine()
                                    .resolve(
                                            "gc-"
                                                    + ruleId
                                                    + '-'
                                                    + version
                                                    + '-'
                                                    + Instant.now(clock).toEpochMilli()
                                                    + '-'
                                                    + UUID.randomUUID());
                    paths.requireInsideRoot(versionDirectory);
                    paths.requireInsideRoot(target);
                    Files.move(versionDirectory, target, StandardCopyOption.ATOMIC_MOVE);
                    quarantined.add(target);
                }
            }
        }
        return List.copyOf(quarantined);
    }

    private static void addCoordinates(Set<String> target, Iterable<RulePackRef> references) {
        for (RulePackRef reference : references) {
            target.add(reference.ruleId() + ":" + reference.version());
        }
    }
}
