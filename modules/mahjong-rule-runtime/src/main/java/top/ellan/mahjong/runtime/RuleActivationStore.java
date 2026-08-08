package top.ellan.mahjong.runtime;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackRef;

/** Atomic activation state that distinguishes plugin reload from a full JVM restart. */
public final class RuleActivationStore {
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
        if (!OfficialRuleIds.ALL.contains(reference.ruleId())) {
            throw new RulePackException("Only official rule packs may be activated");
        }
        RuleActivationState state = read();
        Map<RuleId, RulePackRef> pending = new LinkedHashMap<>(state.pending());
        pending.put(reference.ruleId(), reference);
        RuleActivationState updated =
                new RuleActivationState(state.active(), pending, jvmStartMillis.getAsLong());
        write(updated);
        return updated;
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
        active.putAll(state.pending());
        RuleActivationState promoted = new RuleActivationState(active, Map.of(), -1);
        write(promoted);
        return promoted;
    }

    public synchronized RuleActivationState read() throws IOException, RulePackException {
        if (!Files.isRegularFile(stateFile)) {
            return RuleActivationState.empty();
        }
        Object parsed = MiniJson.parse(Files.readString(stateFile));
        Map<String, Object> root = object(parsed, "activation root");
        if (!root.keySet().equals(java.util.Set.of("format", "active", "pending", "pendingJvmStartMillis"))) {
            throw new RulePackException("Invalid activation-state fields");
        }
        if (integer(root.get("format"), "format") != 1) {
            throw new RulePackException("Unsupported activation-state format");
        }
        Map<RuleId, RulePackRef> active = references(root.get("active"), "active");
        Map<RuleId, RulePackRef> pending = references(root.get("pending"), "pending");
        long epoch = integer(root.get("pendingJvmStartMillis"), "pendingJvmStartMillis");
        try {
            return new RuleActivationState(active, pending, epoch);
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
                .append('}');
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
