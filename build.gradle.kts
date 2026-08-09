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
                if (
                    module == "mahjong-application" &&
                        relative.startsWith(
                            "modules/mahjong-application/src/main/java/top/ellan/mahjong/application/",
                        ) &&
                        relative
                            .removePrefix(
                                "modules/mahjong-application/src/main/java/top/ellan/mahjong/application/",
                            ).contains('/').not()
                ) {
                    violations +=
                        "$relative is unclassified; application types must live in a responsibility package"
                }
                if (
                    module == "mahjong-craftengine" &&
                        relative.startsWith(
                            "modules/mahjong-craftengine/src/main/java/top/ellan/mahjong/craftengine/",
                        ) &&
                        relative
                            .removePrefix(
                                "modules/mahjong-craftengine/src/main/java/top/ellan/mahjong/craftengine/",
                            ).contains('/').not()
                ) {
                    violations +=
                        "$relative is unclassified; CraftEngine types must live in bundle/interaction/port/scene/privateview"
                }
                if (
                    module == "mahjong-craftengine" &&
                        text.lineSequence().count() > 550
                ) {
                    violations +=
                        "$relative exceeds the 550-line CraftEngine responsibility limit"
                }
                if (
                    module == "mahjong-persistence-sql" &&
                        relative.startsWith(
                            "modules/mahjong-persistence-sql/src/main/java/top/ellan/mahjong/persistence/sql/",
                        ) &&
                        relative
                            .removePrefix(
                                "modules/mahjong-persistence-sql/src/main/java/top/ellan/mahjong/persistence/sql/",
                            ).contains('/').not()
                ) {
                    violations +=
                        "$relative is unclassified; SQL types must live in connection/schema/event/match/lobby/anchor/recovery/common"
                }
                if (
                    module == "mahjong-persistence-sql" &&
                        text.lineSequence().count() > 550
                ) {
                    violations +=
                        "$relative exceeds the 550-line SQL responsibility limit"
                }
                if (
                    module == "mahjong-application" &&
                        text.lineSequence().count() > 550
                ) {
                    violations +=
                        "$relative exceeds the 550-line application responsibility limit"
                }
                if (relative.endsWith("/plugin/MahjongRuntime.java")) {
                    if (text.lineSequence().count() > 500) {
                        violations +=
                            "$relative exceeds the 500-line composition-root limit"
                    }
                    listOf(
                            "com.zaxxer.hikari.",
                            "java.net.http.",
                            "net.momirealms.craftengine.",
                            "top.ellan.mahjong.persistence.sql.Jdbc",
                        ).filter(text::contains)
                        .forEach { forbidden ->
                            violations +=
                                "$relative owns concrete bootstrap logic for $forbidden"
                        }
                }
            }
            listOf("src", "native", "perf").forEach { removedRoot ->
                if (file(removedRoot).exists()) {
                    violations +=
                        "removed 1.x root '$removedRoot' exists; all code must live in 2.0 modules"
                }
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
