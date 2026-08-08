import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import dev.detekt.gradle.Detekt
import top.ellan.mahjong.build.MahjongBuildConfiguration
import top.ellan.mahjong.build.MahjongBuildInputs
import top.ellan.mahjong.build.MahjongTaskRegistration

plugins {
    java
    jacoco
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    id("com.gradleup.shadow") version "9.6.1" apply false
    id("io.papermc.paperweight.userdev") version "2.0.0-SNAPSHOT"
    id("com.diffplug.spotless") version "8.8.0"
    id("dev.detekt") version "2.0.0-alpha.5"
}

group = "top.ellan"
version = "1.5.0"
val minimumPaperDevBundleVersion = "1.20.1-R0.1-SNAPSHOT"
val paperDevBundleVersion =
    providers
        .gradleProperty("mahjongPaperDevBundle")
        .orElse(minimumPaperDevBundleVersion)
        .get()
val paperApiVersion = "1.20"
val minimumJavaVersion = 21
val javaTargetVersion =
    providers
        .gradleProperty("mahjongJavaTarget")
        .map(String::toInt)
        .orElse(minimumJavaVersion)
        .get()
require(javaTargetVersion >= minimumJavaVersion) {
    "mahjongJavaTarget must be Java $minimumJavaVersion or newer (was $javaTargetVersion)"
}
val toolchainJavaVersion =
    providers
        .gradleProperty("mahjongJavaToolchain")
        .map(String::toInt)
        .orElse(if (Runtime.version().feature() >= javaTargetVersion) Runtime.version().feature() else javaTargetVersion)
val kotlinRuntimeVersion = "2.4.10"
val kotlinSerializationVersion = "1.11.0"
val mahjongUtilsVersion = "0.7.7"
val mariadbVersion = "3.5.9"
val mysqlVersion = "9.7.0"
val h2Version = "2.4.240"
val hikariVersion = "7.1.0"
val caffeineVersion = "3.2.4"
val antiGriefLibVersion = "1.0.14"
val sparrowHeartVersion = "0.72"
val sparrowReflectionVersion = "0.33"
val sparrowYamlVersion = "1.0.7"
val asmVersion = "9.10.1"
val adventureVersion = "4.14.0"
val junitVersion = "6.1.2"
val testcontainersVersion = "1.21.4"
val generatedResourcesDir = layout.buildDirectory.dir("generated/resources/mahjong")
val generatedNativeResourcesDir = layout.buildDirectory.dir("generated/resources/native")
val generatedRuleTrustRootDir = layout.buildDirectory.dir("generated/resources/rule-trust-root")
val relocatedRuntime = configurations.create("relocatedRuntime")
val internalModules =
    configurations.create("internalModules") {
        isTransitive = false
    }
val mockitoAgent = configurations.create("mockitoAgent")
MahjongBuildConfiguration.configureRepositories(project)

subprojects {
    group = rootProject.group
    version = rootProject.version
    pluginManager.apply("java-library")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.momirealms.net/releases/")
    }

    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        toolchain.languageVersion.set(
            rootProject.providers
                .gradleProperty("mahjongJavaToolchain")
                .map(String::toInt)
                .orElse(if (Runtime.version().feature() >= 21) Runtime.version().feature() else 21)
                .map(JavaLanguageVersion::of),
        )
        withSourcesJar()
    }

    dependencies {
        add("testImplementation", "org.junit.jupiter:junit-jupiter:6.1.2")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

val codegenTasks =
    MahjongTaskRegistration.registerCodegenTasks(
        project,
        generatedResourcesDir,
        project.version.toString(),
    )
val rulePackPublicKey =
    providers
        .gradleProperty("mahjongRulePackPublicKeyBase64")
        .orElse(providers.environmentVariable("MAHJONG_RULE_PACK_PUBLIC_KEY_BASE64"))
        .orElse("")
val generateRuleTrustRoot =
    tasks.register("generateRuleTrustRoot") {
        val output =
            generatedRuleTrustRootDir.map {
                it.file("META-INF/mahjong-rule-trust-root.txt")
            }
        inputs.property("rulePackPublicKey", rulePackPublicKey)
        outputs.file(output)
        doLast {
            val target = output.get().asFile
            target.parentFile.mkdirs()
            target.writeText(rulePackPublicKey.get().trim() + "\n", Charsets.UTF_8)
        }
    }
sourceSets.main {
    resources.srcDir(generatedRuleTrustRootDir)
}
tasks.named("processResources") {
    dependsOn(generateRuleTrustRoot)
}
val nativeTasks =
    MahjongTaskRegistration.registerNativeTasks(
        project,
        generatedNativeResourcesDir,
    )
MahjongTaskRegistration.registerPerformanceTasks(project, minimumPaperDevBundleVersion)
pluginManager.apply("com.gradleup.shadow")

dependencies {
    implementation(project(":mahjong-rule-spi"))
    implementation(project(":mahjong-domain"))
    implementation(project(":mahjong-application"))
    implementation(project(":mahjong-rule-runtime"))
    implementation(project(":mahjong-presentation"))
    implementation(project(":mahjong-persistence-sql"))
    implementation(project(":mahjong-platform-paper"))
    implementation(project(":mahjong-craftengine"))
    internalModules(project(":mahjong-rule-spi"))
    internalModules(project(":mahjong-domain"))
    internalModules(project(":mahjong-application"))
    internalModules(project(":mahjong-rule-runtime"))
    internalModules(project(":mahjong-presentation"))
    internalModules(project(":mahjong-persistence-sql"))
    internalModules(project(":mahjong-platform-paper"))
    internalModules(project(":mahjong-craftengine"))
    paperweight.paperDevBundle(paperDevBundleVersion)
    compileOnly("net.momirealms:craft-engine-core:26.7")
    compileOnly("net.momirealms:craft-engine-bukkit:26.7")
    compileOnly(platform("net.kyori:adventure-bom:$adventureVersion"))
    compileOnly("net.kyori:adventure-api")
    compileOnly("net.kyori:adventure-text-minimessage")
    compileOnly("net.kyori:adventure-text-serializer-plain")
    implementation("io.github.ssttkkl:mahjong-utils-jvm:$mahjongUtilsVersion")
    implementation("org.mariadb.jdbc:mariadb-java-client:$mariadbVersion")
    implementation("com.mysql:mysql-connector-j:$mysqlVersion")
    implementation("com.h2database:h2:$h2Version")
    implementation("com.zaxxer:HikariCP:$hikariVersion")
    implementation("com.github.ben-manes.caffeine:caffeine:$caffeineVersion")
    implementation("net.momirealms:antigrieflib:$antiGriefLibVersion")
    implementation("net.momirealms:sparrow-heart:$sparrowHeartVersion")
    implementation("net.momirealms:sparrow-reflection:$sparrowReflectionVersion")
    implementation("net.momirealms:sparrow-yaml:$sparrowYamlVersion")
    implementation("org.ow2.asm:asm:$asmVersion")
    relocatedRuntime("net.momirealms:sparrow-reflection:$sparrowReflectionVersion")
    relocatedRuntime("org.ow2.asm:asm:$asmVersion")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinRuntimeVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinSerializationVersion")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("org.mockito:mockito-core:5.23.0")
    mockitoAgent("org.mockito:mockito-core:5.23.0") { isTransitive = false }
    testImplementation("org.testcontainers:testcontainers:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:mariadb:$testcontainersVersion")
    testImplementation("net.kyori:adventure-api")
    testImplementation("net.kyori:adventure-text-minimessage")
    testImplementation("net.kyori:adventure-text-serializer-plain")
    testRuntimeOnly("net.momirealms:craft-engine-core:26.7")
    testRuntimeOnly("net.momirealms:craft-engine-bukkit:26.7")
}

java {
    sourceCompatibility = JavaVersion.toVersion(javaTargetVersion)
    targetCompatibility = JavaVersion.toVersion(javaTargetVersion)
    toolchain.languageVersion.set(toolchainJavaVersion.map(JavaLanguageVersion::of))
    withSourcesJar()
}

kotlin {
    jvmToolchain(toolchainJavaVersion.get())
}

paperweight {
    javaLauncher =
        javaToolchains.launcherFor {
            languageVersion.set(toolchainJavaVersion.map(JavaLanguageVersion::of))
        }
    reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION
}

tasks {
    jar {
        // Keep the normal jar development-only; releases use the unclassified relocated Shadow jar.
        archiveClassifier.set("dev")
    }

    named<ShadowJar>("shadowJar") {
        configurations = listOf(relocatedRuntime, internalModules)
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        archiveClassifier.set("")
        relocate(
            "net.momirealms.sparrow.reflection",
            "top.ellan.mahjong.libs.sparrow.reflection",
        )
        relocate("org.objectweb.asm", "top.ellan.mahjong.libs.asm")
    }

    assemble {
        dependsOn(named("shadowJar"))
    }

    withType<Detekt>().configureEach {
        jvmTarget.set(javaTargetVersion.toString())
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(
                org.jetbrains.kotlin.gradle.dsl.JvmTarget
                    .fromTarget(javaTargetVersion.toString()),
            )
            freeCompilerArgs.add("-Xwarning-level=DEPRECATION:error")
        }
    }
}

val architectureCheck =
    tasks.register("architectureCheck") {
        group = "verification"
        description = "Enforces the dependency direction and concurrency sentinels of the modular core."
        val moduleRoot = layout.projectDirectory.dir("modules")
        inputs.files(
            listOf(
                "mahjong-rule-spi",
                "mahjong-rule-tck",
                "mahjong-domain",
                "mahjong-application",
                "mahjong-rule-runtime",
                "mahjong-presentation",
            ).map { moduleRoot.dir("$it/src/main/java") },
        )
        doLast {
            val forbiddenImports =
                mapOf(
                    "mahjong-rule-spi" to
                        listOf(
                            "org.bukkit.",
                            "io.papermc.",
                            "net.momirealms.craftengine.",
                            "java.sql.",
                            "kotlin.",
                            "top.ellan.mahjong.rules.",
                        ),
                    "mahjong-rule-tck" to
                        listOf(
                            "org.bukkit.",
                            "io.papermc.",
                            "net.momirealms.craftengine.",
                            "java.sql.",
                            "kotlin.",
                            "top.ellan.mahjong.rules.",
                        ),
                    "mahjong-domain" to
                        listOf(
                            "org.bukkit.",
                            "io.papermc.",
                            "net.momirealms.craftengine.",
                            "java.sql.",
                            "kotlin.",
                            "top.ellan.mahjong.rules.",
                        ),
                    "mahjong-application" to
                        listOf(
                            "org.bukkit.",
                            "io.papermc.",
                            "net.momirealms.craftengine.",
                            "java.sql.",
                            "kotlin.",
                            "top.ellan.mahjong.rules.",
                        ),
                    "mahjong-rule-runtime" to
                        listOf("org.bukkit.", "net.momirealms.craftengine.", "top.ellan.mahjong.rules."),
                    "mahjong-presentation" to
                        listOf("org.bukkit.", "net.momirealms.craftengine.", "java.sql.", "top.ellan.mahjong.rules."),
                )
            val failures = mutableListOf<String>()
            forbiddenImports.forEach { (module, prefixes) ->
                val sourceDir = moduleRoot.dir("$module/src/main/java").asFile
                if (sourceDir.isDirectory) {
                    sourceDir
                        .walkTopDown()
                        .filter { it.isFile && it.extension == "java" }
                        .forEach { source ->
                            source.useLines { lines ->
                                lines.forEachIndexed { index, line ->
                                    val trimmed = line.trim()
                                    if (trimmed.startsWith("import ") && prefixes.any(trimmed::contains)) {
                                        failures += "${source.relativeTo(rootDir)}:${index + 1}: $trimmed"
                                    }
                                    if (
                                        trimmed.contains("new LinkedBlockingQueue") ||
                                        trimmed.contains("new ConcurrentLinkedQueue") ||
                                        trimmed.contains("Executors.newCachedThreadPool")
                                    ) {
                                        failures +=
                                            "${source.relativeTo(rootDir)}:${index + 1}: forbidden unbounded executor/queue"
                                    }
                                }
                            }
                        }
                }
            }
            if (failures.isNotEmpty()) {
                throw GradleException("Architecture boundary violations:\n${failures.joinToString("\n")}")
            }
        }
    }

tasks.named("check") {
    dependsOn(architectureCheck)
}

MahjongBuildConfiguration.configureLifecycle(
    project,
    MahjongBuildInputs(
        generatedResourcesDir,
        generatedNativeResourcesDir,
        codegenTasks + nativeTasks,
        mockitoAgent,
        javaTargetVersion,
        mapOf(
            "version" to project.version,
            "paperApiVersion" to paperApiVersion,
            "mahjongUtilsVersion" to mahjongUtilsVersion,
            "mariadbVersion" to mariadbVersion,
            "h2Version" to h2Version,
            "hikariVersion" to hikariVersion,
            "kotlinRuntimeVersion" to kotlinRuntimeVersion,
            "kotlinSerializationVersion" to kotlinSerializationVersion,
        ),
    ),
)

spotless {
    MahjongTaskRegistration.configureGitRatchet(project, "origin/dev") { ratchetFrom(it) }
    kotlin {
        target("src/main/kotlin/**/*.kt", "src/test/kotlin/**/*.kt", "buildSrc/**/*.kt")
        ktlint()
    }
    kotlinGradle {
        target("*.gradle.kts", "buildSrc/**/*.gradle.kts")
        ktlint()
    }
    java {
        target("src/main/java/**/*.java", "src/test/java/**/*.java", "src/perfTest/java/**/*.java")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt.yml"))
    ignoreFailures = true
}
