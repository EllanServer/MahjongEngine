package top.ellan.mahjong.runtime;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;

/** Ed25519 public trust root embedded by the release build; no private material is accepted here. */
public final class OfficialTrustRoot {
    private final PublicKey publicKey;

    private OfficialTrustRoot(PublicKey publicKey) {
        this.publicKey = publicKey;
    }

    public static OfficialTrustRoot fromX509(byte[] encoded) throws RulePackException {
        Objects.requireNonNull(encoded, "encoded");
        try {
            PublicKey key =
                    KeyFactory.getInstance("Ed25519")
                            .generatePublic(new X509EncodedKeySpec(encoded.clone()));
            return new OfficialTrustRoot(key);
        } catch (GeneralSecurityException failure) {
            throw new RulePackException("Invalid Ed25519 trust root", failure);
        }
    }

    public static OfficialTrustRoot fromBase64X509(String encoded) throws RulePackException {
        try {
            return fromX509(Base64.getDecoder().decode(Objects.requireNonNull(encoded, "encoded")));
        } catch (IllegalArgumentException failure) {
            throw new RulePackException("Trust root is not valid Base64", failure);
        }
    }

    public void verify(byte[] payload, byte[] signature) throws RulePackException {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(signature, "signature");
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(payload);
            if (!verifier.verify(signature)) {
                throw new RulePackException("Registry Ed25519 signature is invalid");
            }
        } catch (GeneralSecurityException failure) {
            throw new RulePackException("Unable to verify registry signature", failure);
        }
    }
}
