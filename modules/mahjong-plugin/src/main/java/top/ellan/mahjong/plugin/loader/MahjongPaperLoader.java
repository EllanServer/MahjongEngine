package top.ellan.mahjong.plugin.loader;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

/** Resolves the thin plugin's third-party runtime libraries through Paper's library cache. */
public final class MahjongPaperLoader implements PluginLoader {
    static final String CATALOG = "META-INF/mahjong-runtime-libraries.txt";
    private static final Pattern COORDINATE = Pattern.compile(
            "[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+\\-]+(?:[:][A-Za-z0-9_.-]+)?");
    private static final int MAX_LIBRARIES = 32;

    @Override
    public void classloader(PluginClasspathBuilder classpathBuilder) {
        Objects.requireNonNull(classpathBuilder, "classpathBuilder");
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        resolver.addRepository(new RemoteRepository.Builder(
                        "paper-central",
                        "default",
                        MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR)
                .build());
        resolver.addRepository(new RemoteRepository.Builder(
                        "momirealms-releases",
                        "default",
                        "https://repo.momirealms.net/releases/")
                .build());
        for (String coordinate : runtimeLibraries()) {
            resolver.addDependency(new Dependency(new DefaultArtifact(coordinate), null));
        }
        classpathBuilder.addLibrary(resolver);
    }

    static List<String> runtimeLibraries() {
        InputStream input = MahjongPaperLoader.class
                .getClassLoader()
                .getResourceAsStream(CATALOG);
        if (input == null) {
            throw new IllegalStateException("Missing runtime library catalog " + CATALOG);
        }
        try (input;
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(input, StandardCharsets.UTF_8))) {
            List<String> libraries = reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
            if (libraries.isEmpty() || libraries.size() > MAX_LIBRARIES) {
                throw new IllegalStateException(
                        "Invalid runtime library count: " + libraries.size());
            }
            libraries.forEach(coordinate -> {
                if (!COORDINATE.matcher(coordinate).matches()) {
                    throw new IllegalStateException(
                            "Invalid runtime library coordinate: " + coordinate);
                }
            });
            return libraries;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read runtime library catalog", failure);
        }
    }
}
