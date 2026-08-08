package top.ellan.mahjong.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackRef;

/** Local-only inventory reader; listing installed packs never needs the network or signing key. */
public final class RulePackInventoryReader {
    private final RulePackPaths paths;
    private final RuleActivationStore activationStore;

    public RulePackInventoryReader(RulePackPaths paths, RuleActivationStore activationStore) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.activationStore = Objects.requireNonNull(activationStore, "activationStore");
    }

    public RulePackInventory read() throws IOException, RulePackException {
        paths.createLayout();
        RuleActivationState activation = activationStore.read();
        List<InstalledRulePack> result = new ArrayList<>();
        for (RuleId ruleId : OfficialRuleIds.ALL.stream().sorted().toList()) {
            Path ruleDirectory = paths.requireInsideRoot(paths.root().resolve(ruleId.value()));
            try (var versions = Files.list(ruleDirectory)) {
                for (Path directory : versions.filter(Files::isDirectory).sorted().toList()) {
                    String version = directory.getFileName().toString();
                    Path artifact;
                    try {
                        artifact = paths.installedJar(ruleId, version);
                    } catch (IllegalArgumentException unsafeDirectory) {
                        continue;
                    }
                    if (!Files.isRegularFile(artifact)) {
                        continue;
                    }
                    result.add(new InstalledRulePack(
                            ruleId,
                            version,
                            artifact,
                            hasCoordinate(activation.active().get(ruleId), version),
                            hasCoordinate(activation.pending().get(ruleId), version)));
                }
            }
        }
        result.sort(Comparator.comparing(InstalledRulePack::ruleId)
                .thenComparing(InstalledRulePack::version));
        return new RulePackInventory(result, activation);
    }

    private static boolean hasCoordinate(RulePackRef reference, String version) {
        return reference != null && reference.version().equals(version);
    }
}
