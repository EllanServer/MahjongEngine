package top.ellan.mahjong.runtime;

import java.util.Objects;

/** Verified registry plus its original signed envelope for atomic caching. */
public record VerifiedRegistryDocument(RulePackRegistry registry, byte[] envelopeBytes) {
    public VerifiedRegistryDocument {
        Objects.requireNonNull(registry, "registry");
        envelopeBytes = Objects.requireNonNull(envelopeBytes, "envelopeBytes").clone();
    }

    @Override
    public byte[] envelopeBytes() {
        return envelopeBytes.clone();
    }
}
