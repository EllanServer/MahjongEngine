package top.ellan.mahjong.runtime.admin;

import java.util.List;
import java.util.Objects;

import top.ellan.mahjong.runtime.activation.RuleActivationState;

/** Immutable response for the administrative list command. */
public record RulePackInventory(
        List<InstalledRulePack> installed, RuleActivationState activation) {
    public RulePackInventory {
        installed = List.copyOf(Objects.requireNonNull(installed, "installed"));
        Objects.requireNonNull(activation, "activation");
    }
}
