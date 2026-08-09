package top.ellan.mahjong.runtime.activation;

import java.util.Map;
import java.util.Objects;

import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackRef;

/** Durable active/pending selections. Pending entries retain their requesting JVM epoch. */
public record RuleActivationState(
        Map<RuleId, RulePackRef> active,
        Map<RuleId, RulePackRef> pending,
        long pendingJvmStartMillis) {
    public RuleActivationState {
        active = Map.copyOf(Objects.requireNonNull(active, "active"));
        pending = Map.copyOf(Objects.requireNonNull(pending, "pending"));
        if (pending.isEmpty() && pendingJvmStartMillis != -1) {
            throw new IllegalArgumentException("Empty pending state must use JVM epoch -1");
        }
        if (!pending.isEmpty() && pendingJvmStartMillis < 0) {
            throw new IllegalArgumentException("Pending activation requires a JVM epoch");
        }
    }

    public static RuleActivationState empty() {
        return new RuleActivationState(Map.of(), Map.of(), -1);
    }
}
