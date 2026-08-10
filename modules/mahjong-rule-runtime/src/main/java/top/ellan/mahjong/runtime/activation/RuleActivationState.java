package top.ellan.mahjong.runtime.activation;

import java.util.Map;
import java.util.Objects;

import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackRef;

/**
 * Durable active/pending selections plus the version each rule was replaced from.
 *
 * <p>Pending entries retain their requesting JVM epoch so a plugin reload cannot promote them.
 * {@code previous} records the coordinate an immediate activation superseded, which is what makes a
 * one-command rollback possible without consulting the registry again.</p>
 */
public record RuleActivationState(
        Map<RuleId, RulePackRef> active,
        Map<RuleId, RulePackRef> pending,
        long pendingJvmStartMillis,
        Map<RuleId, RulePackRef> previous) {
    public RuleActivationState {
        active = Map.copyOf(Objects.requireNonNull(active, "active"));
        pending = Map.copyOf(Objects.requireNonNull(pending, "pending"));
        previous = Map.copyOf(Objects.requireNonNull(previous, "previous"));
        if (pending.isEmpty() && pendingJvmStartMillis != -1) {
            throw new IllegalArgumentException("Empty pending state must use JVM epoch -1");
        }
        if (!pending.isEmpty() && pendingJvmStartMillis < 0) {
            throw new IllegalArgumentException("Pending activation requires a JVM epoch");
        }
    }

    public RuleActivationState(
            Map<RuleId, RulePackRef> active,
            Map<RuleId, RulePackRef> pending,
            long pendingJvmStartMillis) {
        this(active, pending, pendingJvmStartMillis, Map.of());
    }

    public static RuleActivationState empty() {
        return new RuleActivationState(Map.of(), Map.of(), -1, Map.of());
    }
}
