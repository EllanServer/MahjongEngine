package top.ellan.mahjong.plugin.bootstrap.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import top.ellan.mahjong.runtime.common.RulePackException;
import top.ellan.mahjong.runtime.resources.InspectedRuleResourcePack;
import top.ellan.mahjong.spi.RulePackRef;

/** Verifies and hands startup/recovery resource coordinates to CraftEngine as one snapshot. */
public final class RuleResourceStartup {
    private final RulePackRuntimeServices rules;
    private final Installer installer;

    public RuleResourceStartup(RulePackRuntimeServices rules, Installer installer) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.installer = Objects.requireNonNull(installer, "installer");
    }

    public void installActive() {
        if (rules.resources().isEmpty()) {
            return;
        }
        try {
            installer.install(rules.resources().orElseThrow().resolveActive());
        } catch (java.io.IOException | RulePackException failure) {
            throw new IllegalStateException("Active rule resources failed verification", failure);
        }
    }

    public void installLoaded() {
        if (rules.resources().isEmpty() || rules.runtime().isEmpty()) {
            return;
        }
        try {
            ArrayList<InspectedRuleResourcePack> resources = new ArrayList<>();
            for (RulePackRef reference : rules.runtime().orElseThrow().loadedReferences()) {
                rules.resources().orElseThrow().resolve(reference).ifPresent(resources::add);
            }
            installer.install(resources);
        } catch (java.io.IOException | RulePackException failure) {
            throw new IllegalStateException(
                    "Recovered rule resources failed verification", failure);
        }
    }

    @FunctionalInterface
    public interface Installer {
        void install(List<InspectedRuleResourcePack> resources) throws java.io.IOException;
    }
}
