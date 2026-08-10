package top.ellan.mahjong.runtime.activation;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.LongSupplier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import top.ellan.mahjong.runtime.catalog.OfficialRuleIds;
import top.ellan.mahjong.runtime.common.RulePackException;
import top.ellan.mahjong.runtime.registry.MiniJson;
import top.ellan.mahjong.runtime.storage.AtomicFiles;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackRef;

/** Atomic activation state that distinguishes plugin reload from a full JVM restart. */
public final class RuleActivationStore {
    private static final java.util.Set<String> LEGACY_FIELDS =
            java.util.Set.of("format", "active", "pending", "pendingJvmStartMillis");
    private static final java.util.Set<String> CURRENT_FIELDS =
            java.util.Set.of("format", "active", "pending", "pendingJvmStartMillis", "previous");

    private final Path stateFile;
    private final LongSupplier jvmStartMillis;

    public RuleActivationStore(Path stateFile) {
        this(
                stateFile,
                () ->
                        java.lang.management.ManagementFactory.getRuntimeMXBean()
                                .getStartTime());
    }

    RuleActivationStore(Path stateFile, LongSupplier jvmStartMillis) {
        this.stateFile = Objects.requireNonNull(stateFile, "stateFile").toAbsolutePath().normalize();
        this.jvmStartMillis = Objects.requireNonNull(jvmStartMillis, "jvmStartMillis");
    }

    public synchronized RuleActivationState requestActivation(RulePackRef reference)
            throws IOException, RulePackException {
        Objects.requireNonNull(reference, "reference");
        requireOfficial(reference);
        RuleActivationState state = read();
        Map<RuleId, RulePackRef> pending = new LinkedHashMap<>(state.pending());
        pending.put(reference.ruleId(), reference);
        RuleActivationState updated =
                new RuleActivationState(
                        state.active(), pending, jvmStartMillis.getAsLong(), state.previous());
        write(updated);
        return updated;
    }

    /**
     * Activates a version for new matches immediately and records the coordinate it replaced.
     *
     * <p>Unlike {@link #requestActivation}, this does not wait for another JVM. Matches already in
     * progress keep the provider they were created with, so the replaced version stays reachable
     * through its pinned reference until those matches finish.</p>
     */
    public synchronized RuleActivationState activateNow(RulePackRef reference)
            throws IOException, RulePackException {
        Objects.requireNonNull(reference, "reference");
        requireOfficial(reference);
        RuleActivationState state = read();
        Map<RuleId, RulePackRef> active = new LinkedHashMap<>(state.active());
        RulePackRef replaced = active.put(reference.ruleId(), reference);
        Map<RuleId, RulePackRef> pending = new LinkedHashMap<>(state.pending());
        // An immediate activation supersedes any restart-scoped request for the same rule.
        pending.remove(reference.ruleId());
        Map<RuleId, RulePackRef> previous = new LinkedHashMap<>(state.previous());
        if (replaced == null || replaced.equals(reference)) {
            previous.remove(reference.ruleId());
        } else {
            previous.put(reference.ruleId(), replaced);
        }
        RuleActivationState updated =
                new RuleActivationState(
                        active,
                        pending,
                        pending.isEmpty() ? -1 : state.pendingJvmStartMillis(),
                        previous);
        write(updated);
        return updated;
    }

    /** Restores the coordinate that the last immediate activation replaced, if one is recorded. */
    public synchronized RuleActivationState rollback(RuleId ruleId)
            throws IOException, RulePackException {
        Objects.requireNonNull(ruleId, "ruleId");
        RuleActivationState state = read();
        RulePackRef target = state.previous().get(ruleId);
        if (target == null) {
            throw new RulePackException("No previous version is recorded for " + ruleId);
        }
        return activateNow(target);
    }

    /** Stops handing this rule to new matches without touching installed artifacts. */
    public synchronized RuleActivationState deactivate(RuleId ruleId)
            throws IOException, RulePackException {
        Objects.requireNonNull(ruleId, "ruleId");
        RuleActivationState state = read();
        Map<RuleId, RulePackRef> active = new LinkedHashMap<>(state.active());
        RulePackRef removed = active.remove(ruleId);
        if (removed == null) {
            throw new RulePackException("Rule is not active: " + ruleId);
        }
        Map<RuleId, RulePackRef> pending = new LinkedHashMap<>(state.pending());
        pending.remove(ruleId);
        Map<RuleId, RulePackRef> previous = new LinkedHashMap<>(state.previous());
        // Keep the deactivated coordinate so it can be restored with a rollback.
        previous.put(ruleId, removed);
        RuleActivationState updated =
                new RuleActivationState(
                        active,
                        pending,
                        pending.isEmpty() ? -1 : state.pendingJvmStartMillis(),
                        previous);
        write(updated);
        return updated;
    }

    private static void requireOfficial(RulePackRef reference) throws RulePackException {
        if (!OfficialRuleIds.ALL.contains(reference.ruleId())) {
            throw new RulePackException("Only official rule packs may be activated");
        }
    }

    /** Promotes pending versions only when this is a different JVM from the request origin. */
    public synchronized RuleActivationState promoteForStartup()
            throws IOException, RulePackException {
        RuleActivationState state = read();
        if (state.pending().isEmpty()
                || state.pendingJvmStartMillis() == jvmStartMillis.getAsLong()) {
            return state;
        }
        Map<RuleId, RulePackRef> active = new LinkedHashMap<>(state.active());
        Map<RuleId, RulePackRef> previous = new LinkedHashMap<>(state.previous());
        for (Map.Entry<RuleId, RulePackRef> entry : state.pending().entrySet()) {
            RulePackRef replaced = active.put(entry.getKey(), entry.getValue());
            if (replaced != null && !replaced.equals(entry.getValue())) {
                previous.put(entry.getKey(), replaced);
            }
        }
        RuleActivationState promoted = new RuleActivationState(active, Map.of(), -1, previous);
        write(promoted);
        return promoted;
    }

    public synchronized RuleActivationState read() throws IOException, RulePackException {
        if (!Files.isRegularFile(stateFile)) {
            return RuleActivationState.empty();
        }
        Object parsed = MiniJson.parse(Files.readString(stateFile));
        Map<String, Object> root = object(parsed, "activation root");
        // Format 1 predates rollback support, so "previous" is accepted but not required.
        if (!root.keySet().equals(LEGACY_FIELDS) && !root.keySet().equals(CURRENT_FIELDS)) {
            throw new RulePackException("Invalid activation-state fields");
        }
        if (integer(root.get("format"), "format") != 1) {
            throw new RulePackException("Unsupported activation-state format");
        }
        Map<RuleId, RulePackRef> active = references(root.get("active"), "active");
        Map<RuleId, RulePackRef> pending = references(root.get("pending"), "pending");
        Map<RuleId, RulePackRef> previous = root.containsKey("previous")
                ? references(root.get("previous"), "previous")
                : Map.of();
        long epoch = integer(root.get("pendingJvmStartMillis"), "pendingJvmStartMillis");
        try {
            return new RuleActivationState(active, pending, epoch, previous);
        } catch (IllegalArgumentException failure) {
            throw new RulePackException("Invalid activation state", failure);
        }
    }

    private void write(RuleActivationState state) throws IOException {
        StringBuilder json = new StringBuilder(512);
        json.append("{\"format\":1,\"active\":");
        appendReferences(json, state.active());
        json.append(",\"pending\":");
        appendReferences(json, state.pending());
        json.append(",\"pendingJvmStartMillis\":")
                .append(state.pendingJvmStartMillis())
                .append(",\"previous\":");
        appendReferences(json, state.previous());
        json.append('}');
        AtomicFiles.write(stateFile, json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void appendReferences(StringBuilder json, Map<RuleId, RulePackRef> references) {
        json.append('{');
        boolean first = true;
        for (RuleId id : references.keySet().stream().sorted().toList()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            RulePackRef reference = references.get(id);
            json.append('\"').append(id.value()).append("\":{")
                    .append("\"version\":\"").append(reference.version()).append("\",")
                    .append("\"sha256\":\"").append(reference.jarSha256()).append("\",")
                    .append("\"schema\":").append(reference.stateSchemaVersion()).append('}');
        }
        json.append('}');
    }

    private static Map<RuleId, RulePackRef> references(Object value, String name)
            throws RulePackException {
        Map<String, Object> values = object(value, name);
        Map<RuleId, RulePackRef> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Map<String, Object> coordinate = object(entry.getValue(), name + " coordinate");
            if (!coordinate.keySet().equals(java.util.Set.of("version", "sha256", "schema"))) {
                throw new RulePackException("Invalid " + name + " coordinate fields");
            }
            try {
                RuleId id = new RuleId(entry.getKey());
                RulePackRef reference =
                        new RulePackRef(
                                id,
                                string(coordinate.get("version"), "version"),
                                string(coordinate.get("sha256"), "sha256"),
                                Math.toIntExact(integer(coordinate.get("schema"), "schema")));
                if (!OfficialRuleIds.ALL.contains(id)) {
                    throw new RulePackException("Activation state contains a non-official rule id");
                }
                result.put(id, reference);
            } catch (IllegalArgumentException failure) {
                throw new RulePackException("Invalid " + name + " coordinate", failure);
            }
        }
        return Map.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String name) throws RulePackException {
        if (!(value instanceof Map<?, ?> map)
                || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw new RulePackException(name + " must be an object");
        }
        return (Map<String, Object>) map;
    }

    private static String string(Object value, String name) throws RulePackException {
        if (!(value instanceof String text)) {
            throw new RulePackException(name + " must be a string");
        }
        return text;
    }

    private static long integer(Object value, String name) throws RulePackException {
        if (!(value instanceof BigDecimal number) || number.scale() > 0) {
            throw new RulePackException(name + " must be an integer");
        }
        try {
            return number.longValueExact();
        } catch (ArithmeticException failure) {
            throw new RulePackException(name + " is outside the integer range", failure);
        }
    }
}
