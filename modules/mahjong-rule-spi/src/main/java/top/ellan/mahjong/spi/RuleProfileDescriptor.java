package top.ellan.mahjong.spi;

import java.util.Objects;

/** One supported rule profile and its JSON configuration schema. */
public record RuleProfileDescriptor(ProfileId id, String displayName, String configurationSchemaJson) {
    public RuleProfileDescriptor {
        Objects.requireNonNull(id, "id");
        displayName = Objects.requireNonNull(displayName, "displayName");
        configurationSchemaJson = Objects.requireNonNull(configurationSchemaJson, "configurationSchemaJson");
        if (displayName.length() > 256 || configurationSchemaJson.length() > 262_144) {
            throw new IllegalArgumentException("Rule profile descriptor is too large");
        }
    }
}
