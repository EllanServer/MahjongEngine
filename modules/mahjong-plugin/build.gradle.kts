import java.util.zip.ZipFile
import org.gradle.jvm.tasks.Jar
import top.ellan.mahjong.build.CraftEngineBundleGenerator

plugins {
    java
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

val generatedResources = layout.buildDirectory.dir("generated/resources/mahjong")
val bundleVersion = version.toString()
val internalProjectPaths =
    listOf(
        ":mahjong-rule-spi",
        ":mahjong-domain",
        ":mahjong-application",
        ":mahjong-rule-runtime",
        ":mahjong-presentation",
        ":mahjong-persistence-sql",
        ":mahjong-platform-paper",
        ":mahjong-craftengine",
    )
val internalProjects = internalProjectPaths.map { project(it) }
val runtimeLibraries =
    listOf(
        "com.zaxxer:HikariCP:7.1.0",
        "com.h2database:h2:2.4.240",
        "org.mariadb.jdbc:mariadb-java-client:3.5.9",
        "com.mysql:mysql-connector-j:9.7.0",
        "net.momirealms:antigrieflib:1.0.17",
        "net.momirealms:sparrow-minimessage:0.5",
        "net.momirealms:sparrow-reflection:0.34",
        "net.momirealms:sparrow-yaml:1.0.12",
        "org.ow2.asm:asm:9.10.1",
        "org.ow2.asm:asm-tree:9.10.1",
    )
val generateCraftEngineBundle =
    tasks.register("generateCraftEngineBundle") {
        val resourcepackDir = rootProject.layout.projectDirectory.dir("resourcepack").asFile
        val configurationDir =
            rootProject.layout.projectDirectory.dir("craftengine/configuration").asFile
        val attribution = resourcepackDir.resolve("ATTRIBUTION.md")
        inputs.dir(configurationDir)
        inputs.dir(resourcepackDir)
        inputs.file(attribution)
        inputs.property("bundleVersion", bundleVersion)
        outputs.dir(generatedResources.map { it.dir("craftengine") })
        doLast {
            CraftEngineBundleGenerator.writeCraftEngineBundle(
                configurationDir,
                resourcepackDir,
                attribution,
                generatedResources.get().asFile,
                bundleVersion,
            )
        }
    }

val rulePackPublicKey =
    providers
        .gradleProperty("mahjongRulePackPublicKeyBase64")
        .orElse(providers.environmentVariable("MAHJONG_RULE_PACK_PUBLIC_KEY_BASE64"))
        .orElse("")
val generateRuleTrustRoot =
    tasks.register("generateRuleTrustRoot") {
        val output = generatedResources.map { it.file("META-INF/mahjong-rule-trust-root.txt") }
        inputs.property("rulePackPublicKey", rulePackPublicKey)
        outputs.file(output)
        doLast {
            val target = output.get().asFile
            target.parentFile.mkdirs()
            target.writeText(rulePackPublicKey.get().trim() + "\n", Charsets.UTF_8)
        }
    }
val generateRuntimeLibraryCatalog =
    tasks.register("generateRuntimeLibraryCatalog") {
        val output = generatedResources.map { it.file("META-INF/mahjong-runtime-libraries.txt") }
        inputs.property("runtimeLibraries", runtimeLibraries)
        outputs.file(output)
        doLast {
            val target = output.get().asFile
            target.parentFile.mkdirs()
            target.writeText(runtimeLibraries.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        }
    }

dependencies {
    internalProjectPaths.forEach { implementation(project(it)) }

    compileOnly("io.papermc.paper:paper-api:26.2.build.111-stable")
    testImplementation("io.papermc.paper:paper-api:26.2.build.111-stable")
    compileOnly("net.momirealms:craft-engine-core:26.8")
    compileOnly("net.momirealms:craft-engine-bukkit:26.8")

    runtimeLibraries.forEach { implementation(it) }
}

val perfTest = sourceSets.create("perfTest")
perfTest.compileClasspath += sourceSets.main.get().output
perfTest.runtimeClasspath += sourceSets.main.get().output

configurations[perfTest.implementationConfigurationName].extendsFrom(
    configurations.testImplementation.get(),
)
configurations[perfTest.runtimeOnlyConfigurationName].extendsFrom(
    configurations.testRuntimeOnly.get(),
)

sourceSets.main {
    resources.srcDir(generatedResources)
}

tasks.register<JavaExec>("messageRenderingBenchmark") {
    group = "verification"
    description = "Compares direct, Kyori and Sparrow Adventure message rendering."
    dependsOn(perfTest.classesTaskName)
    classpath = perfTest.runtimeClasspath
    mainClass.set("top.ellan.mahjong.plugin.perf.MessageRenderingBenchmark")
    jvmArgs("-Xms256m", "-Xmx256m", "-XX:+UseG1GC")
}

tasks.processResources {
    dependsOn(generateCraftEngineBundle, generateRuleTrustRoot, generateRuntimeLibraryCatalog)
    filteringCharset = "UTF-8"
    inputs.property("version", bundleVersion)
    filesMatching(listOf("plugin.yml", "paper-plugin.yml")) {
        expand("version" to bundleVersion)
    }
    from(rootProject.file("LICENSE")) {
        into("META-INF/licenses")
        rename { "MahjongPaper-source-MIT.txt" }
    }
    from(rootProject.file("THIRD_PARTY_NOTICES.md")) {
        into("META-INF")
    }
    from(rootProject.file("resourcepack/ATTRIBUTION.md")) {
        into("META-INF")
        rename { "RESOURCEPACK_ATTRIBUTION.md" }
    }
    from(rootProject.file("third-party-licenses")) {
        into("META-INF/licenses")
    }
}

tasks.named<Jar>("sourcesJar") {
    dependsOn(generateCraftEngineBundle, generateRuleTrustRoot, generateRuntimeLibraryCatalog)
}

tasks.jar {
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.FAIL
    internalProjects.forEach { internalProject ->
        val internalJar = internalProject.tasks.named<Jar>("jar")
        dependsOn(internalJar)
        from(internalJar.map { zipTree(it.archiveFile.get().asFile) }) {
            exclude("META-INF/MANIFEST.MF", "module-info.class")
        }
    }
    manifest {
        attributes(
            "Implementation-Title" to "MahjongPaper",
            "Implementation-Version" to bundleVersion,
        )
    }
}

val pluginJar = tasks.named<Jar>("jar")
val verifyThinJar =
    tasks.register("verifyThinJar") {
        group = "verification"
        description = "Rejects bundled third-party classes and validates Paper's runtime loader."
        dependsOn(pluginJar)
        val archive = pluginJar.flatMap { it.archiveFile }
        inputs.file(archive)
        doLast {
            ZipFile(archive.get().asFile).use { zip ->
                val entries = zip.entries().asSequence().map { it.name }.toList()
                val foreignClasses =
                    entries.filter { it.endsWith(".class") && !it.startsWith("top/ellan/mahjong/") }
                check(foreignClasses.isEmpty()) {
                    "Thin plugin contains third-party classes: ${foreignClasses.take(20)}"
                }
                check(entries.none { it.endsWith(".jar") }) {
                    "Thin plugin must not contain nested JARs"
                }
                listOf(
                    "paper-plugin.yml",
                    "META-INF/mahjong-runtime-libraries.txt",
                    "top/ellan/mahjong/plugin/loader/MahjongPaperLoader.class",
                    "top/ellan/mahjong/domain/table/TableId.class",
                ).forEach { required ->
                    check(required in entries) { "Thin plugin is missing $required" }
                }
            }
        }
    }

tasks.check {
    dependsOn(verifyThinJar)
}
