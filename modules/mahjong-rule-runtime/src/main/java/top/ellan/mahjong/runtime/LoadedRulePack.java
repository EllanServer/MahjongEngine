package top.ellan.mahjong.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import top.ellan.mahjong.spi.RulePackProvider;
import top.ellan.mahjong.spi.RulePackRef;

/** Provider and classloader pinned for the lifetime of all matches using this artifact. */
public final class LoadedRulePack implements AutoCloseable {
    private final RulePackRef reference;
    private final Path artifact;
    private final RulePackProvider provider;
    private final ChildFirstRuleClassLoader classLoader;

    LoadedRulePack(
            RulePackRef reference,
            Path artifact,
            RulePackProvider provider,
            ChildFirstRuleClassLoader classLoader) {
        this.reference = Objects.requireNonNull(reference, "reference");
        this.artifact = Objects.requireNonNull(artifact, "artifact");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    }

    public RulePackRef reference() {
        return reference;
    }

    public Path artifact() {
        return artifact;
    }

    public RulePackProvider provider() {
        return provider;
    }

    @Override
    public void close() throws IOException {
        classLoader.close();
    }
}
