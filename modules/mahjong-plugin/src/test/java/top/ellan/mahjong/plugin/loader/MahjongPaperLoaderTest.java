package top.ellan.mahjong.plugin.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.plugin.bootstrap.PluginProviderContext;
import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.library.ClassPathLibrary;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MahjongPaperLoaderTest {
    @Test
    void generatedCatalogContainsOnlyExternalRuntimeLibraries() {
        List<String> libraries = MahjongPaperLoader.runtimeLibraries();

        assertEquals(10, libraries.size());
        assertTrue(libraries.contains("com.zaxxer:HikariCP:7.1.0"));
        assertTrue(libraries.contains("net.momirealms:sparrow-yaml:1.0.12"));
        assertTrue(libraries.contains("org.ow2.asm:asm-tree:9.10.1"));
        assertFalse(libraries.stream().anyMatch(value -> value.contains("craft-engine")));
        assertFalse(libraries.stream().anyMatch(value -> value.contains("paper-api")));
        assertFalse(libraries.stream().anyMatch(value -> value.startsWith("top.ellan:")));
    }

    @Test
    void registersPaperMavenResolverWithoutLoadingPluginClasses() {
        AtomicReference<ClassPathLibrary> registered = new AtomicReference<>();
        PluginClasspathBuilder builder = new PluginClasspathBuilder() {
            @Override
            public PluginClasspathBuilder addLibrary(ClassPathLibrary library) {
                registered.set(library);
                return this;
            }

            @Override
            public PluginProviderContext getContext() {
                throw new UnsupportedOperationException("Loader must not need plugin context");
            }
        };

        new MahjongPaperLoader().classloader(builder);

        assertInstanceOf(MavenLibraryResolver.class, registered.get());
    }
}
