package top.ellan.mahjong.plugin.bootstrap.rules;

import java.util.Optional;
import top.ellan.mahjong.runtime.resources.InspectedRuleResourcePack;
import top.ellan.mahjong.spi.RulePackRef;

/** Platform callback that installs one verified rule resource pack and switches its sound map. */
@FunctionalInterface
public interface RuleResourceActivator {
    void activate(RulePackRef reference, Optional<InspectedRuleResourcePack> resource)
            throws Exception;
}
