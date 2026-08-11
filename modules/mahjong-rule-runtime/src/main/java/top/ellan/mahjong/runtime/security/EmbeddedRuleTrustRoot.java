package top.ellan.mahjong.runtime.security;

import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import top.ellan.mahjong.runtime.common.RulePackException;

/** Loads the release-build Ed25519 public key. Empty development builds fail closed. */
public final class EmbeddedRuleTrustRoot {
    public static final String RESOURCE = "META-INF/mahjong-rule-trust-root.txt";

    private EmbeddedRuleTrustRoot() {}

    public static OfficialTrustRoot load(ClassLoader classLoader)
            throws IOException, RulePackException {
        Objects.requireNonNull(classLoader, "classLoader");
        try (InputStream input = classLoader.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new RulePackException("Release build has no embedded rule-pack trust root");
            }
            byte[] bytes = input.readNBytes(8_193);
            if (bytes.length > 8_192) {
                throw new RulePackException("Embedded rule-pack trust root is oversized");
            }
            String encoded = new String(bytes, StandardCharsets.US_ASCII).trim();
            if (encoded.isEmpty()) {
                throw new RulePackException(
                        "Rule-pack trust root is not configured for this build");
            }
            return OfficialTrustRoot.fromBase64X509(encoded);
        }
    }
}
