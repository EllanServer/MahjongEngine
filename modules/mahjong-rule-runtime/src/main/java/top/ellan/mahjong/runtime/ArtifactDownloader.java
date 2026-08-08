package top.ellan.mahjong.runtime;

import java.io.IOException;
import java.nio.file.Path;

/** Downloads exactly one registry-bound artifact to a newly-created target file. */
@FunctionalInterface
public interface ArtifactDownloader {
    void download(RulePackRegistryEntry entry, Path target)
            throws IOException, InterruptedException, RulePackException;
}
