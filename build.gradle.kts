import dev.detekt.gradle.Detekt
import top.ellan.mahjong.build.MahjongTaskRegistration

plugins {
    java
    jacoco
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    id("io.papermc.paperweight.userdev") version "2.0.0-SNAPSHOT"
    id("com.diffplug.spotless") version "8.7.0"
    id("dev.detekt") version "2.0.0-alpha.5"
}

group = "top.ellan"
version = "1.4.1"

val minimumPaperDevBundleVersion = "1.20.1-R0.1-SNAPSHOT"
val paperDevBundleVersion =
    providers
        .gradleProperty("mahjongPaperDevBundle")
        .orElse(minimumPaperDevBundleVersion)
        .get()
val paperApiVersion = "1.20"
val javaTargetVersion =
    providers
        .gradleProperty("mahjongJavaTarget")
        .map(String::toInt)
        .orElse(17)
        .get()
val toolchainJavaVersion =
    providers
        .gradleProperty("mahjongJavaToolchain")
        .map(String::toInt)
        .orElse(if (Runtime.version().feature() >= javaTargetVersion) Runtime.version().feature() else javaTargetVersion)
val kotlinRuntimeVersion = "2.4.0"
val kotlinSerializationVersion = "1.11.0"
val mahjongUtilsVersion = "0.7.7"
val mariadbVersion = "3.5.9"
val mysqlVersion = "9.7.0"
val h2Version = "2.4.240"
val hikariVersion = "7.1.0"
val caffeineVersion = "3.2.4"
val adventureVersion = "4.14.0"
val junitVersion = "6.1.0"
val testcontainersVersion = "1.21.4"
val generatedResourcesDir = layout.buildDirectory.dir("generated/resources/mahjong")
val generatedNativeResourcesDir = layout.buildDirectory.dir("generated/resources/native")

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://jitpack.io")
}

val codegenTasks =
    MahjongTaskRegistration.registerCodegenTasks(
        project,
        generatedResourcesDir,
        project.version.toString(),
    )
val nativeTasks =
    MahjongTaskRegistration.registerNativeTasks(
        project,
        generatedNativeResourcesDir,
    )
MahjongTaskRegistration.registerPerfTestTask(project)

dependencies {
    paperweight.paperDevBundle(paperDevBundleVersion)
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
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinRuntimeVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinSerializationVersion")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("org.testcontainers:testcontainers:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:mariadb:$testcontainersVersion")
    testImplementation("net.kyori:adventure-api")
    testImplementation("net.kyori:adventure-text-minimessage")
    testImplementation("net.kyori:adventure-text-serializer-plain")
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
    withType<Detekt>().configureEach {
        jvmTarget.set(javaTargetVersion.toString())
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(
                org.jetbrains.kotlin.gradle.dsl.JvmTarget
                    .fromTarget(javaTargetVersion.toString()),
            )
        }
    }

    withType<JavaCompile>().configureEach {
        options.encoding = Charsets.UTF_8.name()
        options.release.set(javaTargetVersion)
    }

    processResources {
        dependsOn(codegenTasks + nativeTasks)
        filteringCharset = Charsets.UTF_8.name()
        inputs.property("pluginVersion", project.version.toString())
        inputs.property("paperApiVersion", paperApiVersion)
        inputs.property("mahjongUtilsVersion", mahjongUtilsVersion)
        inputs.property("mariadbVersion", mariadbVersion)
        inputs.property("h2Version", h2Version)
        inputs.property("hikariVersion", hikariVersion)
        inputs.property("kotlinRuntimeVersion", kotlinRuntimeVersion)
        inputs.property("kotlinSerializationVersion", kotlinSerializationVersion)
        from(generatedResourcesDir)
        from(generatedNativeResourcesDir)
        filesMatching(listOf("plugin.yml", "paper-plugin.yml")) {
            expand(
                "version" to project.version,
                "paperApiVersion" to paperApiVersion,
                "mahjongUtilsVersion" to mahjongUtilsVersion,
                "mariadbVersion" to mariadbVersion,
                "h2Version" to h2Version,
                "hikariVersion" to hikariVersion,
                "kotlinRuntimeVersion" to kotlinRuntimeVersion,
                "kotlinSerializationVersion" to kotlinSerializationVersion,
            )
        }
    }

    test {
        useJUnitPlatform {
            excludeTags("perf")
        }
        jvmArgs("-Dnet.bytebuddy.experimental=true")
        systemProperty("mahjong.test.expectedClassfileMajor", javaTargetVersion + 44)
        finalizedBy(jacocoTestReport)
    }

    jacocoTestReport {
        dependsOn(test)
        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
        }
    }

    check {
        dependsOn(jacocoTestReport, "verifyMahjongTileResources", "generateCraftEngineBundle", "spotlessCheck", "detekt")
    }
}

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
        target("src/main/java/**/*.java", "src/test/java/**/*.java")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt.yml"))
    ignoreFailures = true
}
