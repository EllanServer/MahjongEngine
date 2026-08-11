package top.ellan.mahjong.runtime.storage;

import java.util.Set;

import top.ellan.mahjong.spi.RulePackRef;

/** Persistence query used to protect versions referenced by active snapshots. */
@FunctionalInterface
public interface RulePackReferenceIndex {
    Set<RulePackRef> referencedRulePacks() throws Exception;
}
