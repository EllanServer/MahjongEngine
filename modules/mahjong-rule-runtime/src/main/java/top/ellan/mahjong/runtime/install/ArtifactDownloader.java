package top.ellan.mahjong.runtime.install;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

import top.ellan.mahjong.runtime.common.RulePackException;

/** Downloads exactly one registry-bound artifact to a newly-created target file. */
@FunctionalInterface
public interface ArtifactDownloader {
    void download(URI uri, long expectedSize, Path target)
            throws IOException, InterruptedException, RulePackException;
}
