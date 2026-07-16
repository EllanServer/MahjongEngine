package top.ellan.mahjong.bootstrap;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

public final class MahjongPaperLoader implements PluginLoader {
    @Override
    public void classloader(PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        resolver.addRepository(new RemoteRepository.Builder(
            "central",
            "default",
            this.mavenCentralRepositoryUrl()
        ).build());
        resolver.addRepository(new RemoteRepository.Builder(
            "momirealms-releases",
            "default",
            "https://repo.momirealms.net/releases/"
        ).build());

        this.addDependency(resolver, "io.github.ssttkkl:mahjong-utils-jvm:0.7.7");
        this.addDependency(resolver, "org.mariadb.jdbc:mariadb-java-client:3.5.9");
        this.addDependency(resolver, "com.mysql:mysql-connector-j:9.7.0");
        this.addDependency(resolver, "com.h2database:h2:2.4.240");
        this.addDependency(resolver, "com.zaxxer:HikariCP:7.1.0");
        this.addDependency(resolver, "com.github.ben-manes.caffeine:caffeine:3.2.4");
        this.addDependency(resolver, "net.momirealms:sparrow-heart:0.72");
        this.addDependency(resolver, "net.momirealms:sparrow-reflection:0.33");
        this.addDependency(resolver, "org.ow2.asm:asm:9.9.1");
        this.addDependency(resolver, "org.jetbrains.kotlin:kotlin-stdlib:2.4.0");
        this.addDependency(resolver, "org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0");

        classpathBuilder.addLibrary(resolver);
    }

    private void addDependency(MavenLibraryResolver resolver, String coordinates) {
        resolver.addDependency(new Dependency(new DefaultArtifact(coordinates), null));
    }

    private String mavenCentralRepositoryUrl() {
        try {
            Object mirror = MavenLibraryResolver.class.getField("MAVEN_CENTRAL_DEFAULT_MIRROR").get(null);
            if (mirror instanceof String url && !url.isBlank()) {
                return url;
            }
        } catch (ReflectiveOperationException ignored) {
            // Older Paper versions do not expose the mirror constant.
        }
        return "https://repo.maven.apache.org/maven2/";
    }
}
