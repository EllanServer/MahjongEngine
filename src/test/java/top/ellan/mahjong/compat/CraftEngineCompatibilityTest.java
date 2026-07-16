package top.ellan.mahjong.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

final class CraftEngineCompatibilityTest {
    @Test
    void readsInstalledVersionFromModernPluginMeta() {
        PluginManager pluginManager = mock(PluginManager.class);
        Plugin craftEngine = mock(Plugin.class);
        PluginMeta pluginMeta = mock(PluginMeta.class);
        when(pluginManager.getPlugin("CraftEngine")).thenReturn(craftEngine);
        when(craftEngine.getPluginMeta()).thenReturn(pluginMeta);
        when(pluginMeta.getVersion()).thenReturn("26.6");
        when(craftEngine.isEnabled()).thenReturn(true);

        CraftEngineCompatibility.Result result = CraftEngineCompatibility.inspect(pluginManager);

        assertFalse(result.compatible());
        assertEquals("26.6", result.installedVersion());
        verify(craftEngine).getPluginMeta();
    }

    @Test
    void comparesVersionSegmentsNumerically() {
        assertTrue(version("26.10").compareTo(version("26.7")) > 0);
        assertTrue(version("27.0").compareTo(version("26.99.99")) > 0);
        assertTrue(version("26.6.99").compareTo(version("26.7")) < 0);
        assertEquals(0, version("26.7").compareTo(version("26.7.0")));
    }

    @Test
    void acceptsCommonVersionQualifiers() {
        assertEquals(0, version("v26.7-SNAPSHOT").compareTo(version("26.7")));
        assertTrue(version("26.7.1+build.42").compareTo(version("26.7")) > 0);
    }

    @Test
    void rejectsVersionsWithoutNumericSegments() {
        assertTrue(CraftEngineCompatibility.NumericVersion.parse(null).isEmpty());
        assertTrue(CraftEngineCompatibility.NumericVersion.parse("").isEmpty());
        assertTrue(CraftEngineCompatibility.NumericVersion.parse("development").isEmpty());
    }

    @Test
    void rejectsOldVersionBeforeTouchingApiClassLoader() {
        CraftEngineCompatibility.Result result =
            CraftEngineCompatibility.inspectInstalled("26.6.99", true, null);

        assertFalse(result.compatible());
        assertTrue(result.failureMessage().contains("26.7 or newer is required"));
        assertFalse(result.failureMessage().contains("class loader"));
    }

    @Test
    void treatsTwentySixPointTenAsNewerThanTwentySixPointSeven() {
        CraftEngineCompatibility.Result result =
            CraftEngineCompatibility.inspectInstalled("26.10", true, null);

        assertFalse(result.compatible());
        assertTrue(result.failureMessage().contains("class loader is unavailable"));
        assertFalse(result.failureMessage().contains("26.10 is incompatible"));
    }

    @Test
    void reportsMissingRepresentativeApiWithoutThrowingLinkageError() {
        ClassLoader emptyLoader = new ClassLoader(null) {
        };

        String failure = CraftEngineCompatibility.probePublicApi(emptyLoader);

        assertTrue(failure.contains("ClassNotFoundException"));
        assertTrue(failure.contains("net.momirealms.craftengine.bukkit.api.CraftEngineItems"));
    }

    private static CraftEngineCompatibility.NumericVersion version(String rawVersion) {
        return CraftEngineCompatibility.NumericVersion.parse(rawVersion).orElseThrow();
    }
}
