plugins {
    base
    id("com.gradleup.shadow") version "9.6.1" apply false
}

group = "top.ellan"
version = "2.0.0-SNAPSHOT"

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
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
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

val architectureCheck =
    tasks.register("architectureCheck") {
        group = "verification"
        description = "Rejects legacy imports, platform leakage and unbounded concurrency primitives."
        val production = fileTree("modules") { include("*/src/main/java/**/*.java") }
        inputs.files(production)
        doLast {
            val violations = mutableListOf<String>()
            val coreModules =
                setOf(
                    "mahjong-rule-spi",
                    "mahjong-domain",
                    "mahjong-application",
                    "mahjong-rule-runtime",
                    "mahjong-presentation",
                )
            production.files.sorted().forEach { source ->
                val relative = source.relativeTo(projectDir).invariantSeparatorsPath
                val module = relative.substringAfter("modules/").substringBefore('/')
                val text = source.readText(Charsets.UTF_8)
                if ("top.ellan.mahjong.rules." in text || "mahjong-utils" in text) {
                    violations += "$relative imports a concrete or legacy rule implementation"
                }
                if (
                    listOf(
                            "LinkedBlockingQueue",
                            "ConcurrentLinkedQueue",
                            "newCachedThreadPool",
                            "newFixedThreadPool",
                            "scheduleAtFixedRate",
                        ).any(text::contains)
                ) {
                    violations += "$relative uses a forbidden concurrency primitive"
                }
                if (module in coreModules) {
                    listOf(
                            "org.bukkit.",
                            "net.momirealms.craftengine.",
                            "java.sql.",
                            "com.zaxxer.hikari.",
                        ).filter(text::contains)
                        .forEach { forbidden ->
                            violations += "$relative leaks $forbidden into a core module"
                        }
                }
            }
            if (file("src/main").exists()) {
                violations += "root src/main exists; production must live in explicit modules"
            }
            if (file("native/gbmahjong").exists()) {
                violations += "legacy GB JNI/native tree still exists"
            }
            if (violations.isNotEmpty()) {
                throw GradleException(violations.joinToString("\n"))
            }
        }
    }

tasks.named("assemble") {
    dependsOn(":mahjong-plugin:shadowJar")
}

tasks.named("check") {
    dependsOn(architectureCheck)
    dependsOn(subprojects.map { "${it.path}:check" })
}
