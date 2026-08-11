package top.ellan.mahjong.runtime.loading;

import java.nio.file.Path;

import top.ellan.mahjong.runtime.common.RulePackException;
import top.ellan.mahjong.spi.RulePackRef;

/**
 * The single loading operation the lifecycle registry needs.
 *
 * <p>Keeping it separate from {@link RulePackLoader} lets the two-generation logic be exercised
 * without fabricating signed JARs, and it documents that the registry never performs registry
 * verification itself.</p>
 */
public interface PinnedRulePackLoader {
    LoadedRulePack loadPinned(Path artifact, RulePackRef pinned) throws RulePackException;
}
