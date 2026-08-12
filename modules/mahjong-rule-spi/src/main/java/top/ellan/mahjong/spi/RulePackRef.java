package top.ellan.mahjong.spi;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable provenance pinned to every match.
 *
 * @param ruleId stable identifier of the rule family
 * @param version exact provider version pinned to the match
 * @param jarSha256 lowercase SHA-256 of the provider JAR
 * @param stateSchemaVersion provider state-schema version pinned to the match
 */
public record RulePackRef(RuleId ruleId, String version, String jarSha256, int stateSchemaVersion) {
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z._+-]{0,63}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /**
     * Creates validated immutable rule-pack provenance.
     *
     * @param ruleId stable identifier of the rule family
     * @param version exact provider version pinned to the match
     * @param jarSha256 lowercase SHA-256 of the provider JAR
     * @param stateSchemaVersion provider state-schema version pinned to the match
     */
    public RulePackRef {
        Objects.requireNonNull(ruleId, "ruleId");
        version = Objects.requireNonNull(version, "version");
        jarSha256 = Objects.requireNonNull(jarSha256, "jarSha256").toLowerCase(Locale.ROOT);
        if (!VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("Invalid rule-pack version: " + version);
        }
        if (!SHA256.matcher(jarSha256).matches()) {
            throw new IllegalArgumentException("Invalid SHA-256: " + jarSha256);
        }
        if (stateSchemaVersion < 1) {
            throw new IllegalArgumentException("State schema version must be positive");
        }
    }
}
