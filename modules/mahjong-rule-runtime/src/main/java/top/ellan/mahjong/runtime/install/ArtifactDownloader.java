package top.ellan.mahjong.runtime.install;

import java.io.IOException;
import java.nio.file.Path;

import top.ellan.mahjong.runtime.common.RulePackException;
import top.ellan.mahjong.runtime.registry.RulePackRegistryEntry;

/** Downloads exactly one registry-bound artifact to a newly-created target file. */
@FunctionalInterface
public interface ArtifactDownloader {
    void download(RulePackRegistryEntry entry, Path target)
            throws IOException, InterruptedException, RulePackException;
}
