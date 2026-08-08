package top.ellan.mahjong.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LegacyRuleMappingTest {
    @Test
    void mapsOnlyKnownLosslessLegacyModes() {
        assertEquals("riichi", LegacyRuleMapping.resolve("RIICHI").orElseThrow().ruleId().value());
        assertEquals("green-book", LegacyRuleMapping.resolve("gb").orElseThrow().profileId().value());
        assertEquals(
                "t-tfmj-01-2024",
                LegacyRuleMapping.resolve("SICHUAN").orElseThrow().profileId().value());
        assertTrue(LegacyRuleMapping.resolve("CUSTOM").isEmpty());
    }
}
