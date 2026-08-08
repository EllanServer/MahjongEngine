package top.ellan.mahjong.runtime;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import top.ellan.mahjong.spi.RuleId;

/** Decoder for an Ed25519-signed JSON envelope whose signature covers the raw payload bytes. */
public final class SignedRegistryCodec {
    private static final Set<String> ENVELOPE_KEYS = Set.of("format", "payload", "signature");
    private static final Set<String> PAYLOAD_KEYS = Set.of("format", "generatedAt", "packs");
    private static final Set<String> ENTRY_KEYS =
            Set.of(
                    "id",
                    "version",
                    "url",
                    "sha256",
                    "spiVersion",
                    "requiredCoreVersion",
                    "sizeBytes");

    private SignedRegistryCodec() {}

    public static VerifiedRegistryDocument decode(
            byte[] envelopeBytes, OfficialTrustRoot trustRoot) throws RulePackException {
        Objects.requireNonNull(envelopeBytes, "envelopeBytes");
        Objects.requireNonNull(trustRoot, "trustRoot");
        if (envelopeBytes.length > 1_500_000) {
            throw new RulePackException("Registry envelope exceeds 1.5 MiB");
        }
        Map<String, Object> envelope = object(MiniJson.parse(decodeUtf8(envelopeBytes)), "envelope");
        requireExactKeys(envelope, ENVELOPE_KEYS, "envelope");
        requireInteger(envelope, "format", 1, 1);
        byte[] payload = decodeBase64(requireString(envelope, "payload"), "payload");
        byte[] signature = decodeBase64(requireString(envelope, "signature"), "signature");
        trustRoot.verify(payload, signature);
        RulePackRegistry registry = decodePayload(payload);
        return new VerifiedRegistryDocument(registry, envelopeBytes);
    }

    private static RulePackRegistry decodePayload(byte[] payload) throws RulePackException {
        Map<String, Object> root = object(MiniJson.parse(decodeUtf8(payload)), "payload");
        requireExactKeys(root, PAYLOAD_KEYS, "payload");
        int format = Math.toIntExact(requireInteger(root, "format", 1, 1));
        Instant generatedAt;
        try {
            generatedAt = Instant.parse(requireString(root, "generatedAt"));
        } catch (DateTimeException failure) {
            throw new RulePackException("Invalid registry generatedAt", failure);
        }
        List<Object> packValues = array(root.get("packs"), "packs");
        List<RulePackRegistryEntry> entries = new ArrayList<>(packValues.size());
        for (Object value : packValues) {
            Map<String, Object> item = object(value, "pack entry");
            requireExactKeys(item, ENTRY_KEYS, "pack entry");
            try {
                entries.add(
                        new RulePackRegistryEntry(
                                new RuleId(requireString(item, "id")),
                                requireString(item, "version"),
                                URI.create(requireString(item, "url")),
                                requireString(item, "sha256"),
                                requireString(item, "spiVersion"),
                                requireString(item, "requiredCoreVersion"),
                                requireInteger(item, "sizeBytes", 1, 64L * 1024 * 1024)));
            } catch (IllegalArgumentException failure) {
                throw new RulePackException("Invalid registry pack entry", failure);
            }
        }
        try {
            return new RulePackRegistry(format, generatedAt, entries);
        } catch (IllegalArgumentException failure) {
            throw new RulePackException("Invalid registry payload", failure);
        }
    }

    private static String decodeUtf8(byte[] bytes) throws RulePackException {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new RulePackException("Registry contains invalid UTF-8", failure);
        }
    }

    private static byte[] decodeBase64(String value, String field) throws RulePackException {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException failure) {
            throw new RulePackException("Registry " + field + " is not Base64", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String name) throws RulePackException {
        if (!(value instanceof Map<?, ?> map)
                || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw new RulePackException("Registry " + name + " must be an object");
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, String name) throws RulePackException {
        if (!(value instanceof List<?> list)) {
            throw new RulePackException("Registry " + name + " must be an array");
        }
        return (List<Object>) list;
    }

    private static String requireString(Map<String, Object> object, String key)
            throws RulePackException {
        Object value = object.get(key);
        if (!(value instanceof String text)) {
            throw new RulePackException("Registry field " + key + " must be a string");
        }
        return text;
    }

    private static long requireInteger(
            Map<String, Object> object, String key, long minimum, long maximum)
            throws RulePackException {
        Object value = object.get(key);
        if (!(value instanceof BigDecimal number)
                || number.scale() > 0
                || number.compareTo(BigDecimal.valueOf(minimum)) < 0
                || number.compareTo(BigDecimal.valueOf(maximum)) > 0) {
            throw new RulePackException("Registry field " + key + " is outside its integer range");
        }
        return number.longValueExact();
    }

    private static void requireExactKeys(
            Map<String, Object> object, Set<String> expected, String name) throws RulePackException {
        if (!object.keySet().equals(expected)) {
            throw new RulePackException(
                    "Registry " + name + " fields differ from the signed format: " + object.keySet());
        }
    }
}
