package top.ellan.mahjong.runtime.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import top.ellan.mahjong.runtime.common.RulePackException;
import top.ellan.mahjong.runtime.security.OfficialTrustRoot;
import top.ellan.mahjong.spi.SpiVersion;

class SignedRegistryCodecTest {
    @Test
    void verifiesEd25519BeforeParsingPackCoordinates() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] payload = payload();
        byte[] signature = sign(keyPair, payload);
        byte[] envelope = envelope(payload, signature);

        VerifiedRegistryDocument decoded =
                SignedRegistryCodec.decode(
                        envelope, OfficialTrustRoot.fromX509(keyPair.getPublic().getEncoded()));

        assertEquals(1, decoded.registry().entries().size());
        assertEquals("riichi", decoded.registry().entries().getFirst().ruleId().value());
        assertEquals("1.2.3", decoded.registry().entries().getFirst().version());
    }

    @Test
    void rejectsAnyPayloadMutation() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] original = payload();
        byte[] signature = sign(keyPair, original);
        byte[] mutated = original.clone();
        mutated[mutated.length - 2] ^= 1;

        OfficialTrustRoot trust = OfficialTrustRoot.fromX509(keyPair.getPublic().getEncoded());
        assertThrows(
                RulePackException.class,
                () -> SignedRegistryCodec.decode(envelope(mutated, signature), trust));
    }

    @Test
    void formatTwoBindsASeparateResourceArtifact() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] payload = payloadV2();
        byte[] envelope = envelope(payload, sign(keyPair, payload));

        VerifiedRegistryDocument decoded =
                SignedRegistryCodec.decode(
                        envelope, OfficialTrustRoot.fromX509(keyPair.getPublic().getEncoded()));

        RulePackRegistryEntry entry = decoded.registry().entries().getFirst();
        assertEquals(2, decoded.registry().formatVersion());
        assertEquals(2, decoded.registry().entries().size());
        assertTrue(entry.resources().isPresent());
        assertEquals(
                "https://example.invalid/riichi-resources.zip",
                entry.resources().orElseThrow().uri().toString());
        assertTrue(decoded.registry().entries().get(1).resources().isEmpty());
    }

    private static byte[] payload() {
        return ("{\"format\":1,\"generatedAt\":\"2026-08-08T00:00:00Z\",\"packs\":[{"
                        + "\"id\":\"riichi\",\"version\":\"1.2.3\","
                        + "\"url\":\"https://example.invalid/riichi.jar\","
                        + "\"sha256\":\""
                        + "0".repeat(64)
                        + "\",\"spiVersion\":\""
                        + SpiVersion.CURRENT
                        + "\","
                        + "\"requiredCoreVersion\":\">=2.0.0\",\"sizeBytes\":1234}]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] payloadV2() {
        return ("{\"format\":2,\"generatedAt\":\"2026-08-11T00:00:00Z\",\"packs\":[{"
                        + "\"id\":\"riichi\",\"version\":\"2.0.2\","
                        + "\"url\":\"https://example.invalid/riichi.jar\","
                        + "\"sha256\":\""
                        + "0".repeat(64)
                        + "\",\"spiVersion\":\""
                        + SpiVersion.CURRENT
                        + "\",\"requiredCoreVersion\":\">=2.0.0\",\"sizeBytes\":1234,"
                        + "\"resourceUrl\":\"https://example.invalid/riichi-resources.zip\","
                        + "\"resourceSha256\":\""
                        + "1".repeat(64)
                        + "\",\"resourceSizeBytes\":4321},{"
                        + "\"id\":\"mcr\",\"version\":\"2.0.1\","
                        + "\"url\":\"https://example.invalid/mcr.jar\","
                        + "\"sha256\":\""
                        + "2".repeat(64)
                        + "\",\"spiVersion\":\""
                        + SpiVersion.CURRENT
                        + "\",\"requiredCoreVersion\":\">=2.0.0\",\"sizeBytes\":1000}]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sign(KeyPair keyPair, byte[] payload) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(payload);
        return signer.sign();
    }

    private static byte[] envelope(byte[] payload, byte[] signature) {
        return ("{\"format\":1,\"payload\":\""
                        + Base64.getEncoder().encodeToString(payload)
                        + "\",\"signature\":\""
                        + Base64.getEncoder().encodeToString(signature)
                        + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }
}
