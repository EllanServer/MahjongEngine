import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import top.ellan.mahjong.build.CraftEngineBundleGenerator

plugins {
    java
    id("com.gradleup.shadow")
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
val generateCraftEngineBundle =
    tasks.register("generateCraftEngineBundle") {
        val resourcepackDir = rootProject.layout.projectDirectory.dir("resourcepack").asFile
        val configurationDir =
            rootProject.layout.projectDirectory.dir("craftengine/configuration").asFile
        val attribution = resourcepackDir.resolve("ATTRIBUTION.md")
        inputs.dir(configurationDir)
        inputs.dir(resourcepackDir)
        inputs.file(attribution)
        outputs.dir(generatedResources.map { it.dir("craftengine") })
        doLast {
            CraftEngineBundleGenerator.writeCraftEngineBundle(
                configurationDir,
                resourcepackDir,
                attribution,
                generatedResources.get().asFile,
                project.version.toString(),
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

dependencies {
    implementation(project(":mahjong-rule-spi"))
    implementation(project(":mahjong-domain"))
    implementation(project(":mahjong-application"))
    implementation(project(":mahjong-rule-runtime"))
    implementation(project(":mahjong-presentation"))
    implementation(project(":mahjong-persistence-sql"))
    implementation(project(":mahjong-platform-paper"))
    implementation(project(":mahjong-craftengine"))

    compileOnly("io.papermc.paper:paper-api:26.2.build.111-stable")
    compileOnly("net.momirealms:craft-engine-core:26.7.4")
    compileOnly("net.momirealms:craft-engine-bukkit:26.7.4")

    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("com.h2database:h2:2.4.240")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.10")
    implementation("com.mysql:mysql-connector-j:9.7.0")
    implementation("net.momirealms:sparrow-heart:0.73")
    implementation("net.momirealms:sparrow-reflection:0.34")
    implementation("net.momirealms:sparrow-yaml:1.0.12")
    implementation("org.ow2.asm:asm:9.10.1")
}

sourceSets.main {
    resources.srcDir(generatedResources)
}

tasks.processResources {
    dependsOn(generateCraftEngineBundle, generateRuleTrustRoot)
    filteringCharset = "UTF-8"
    inputs.property("version", project.version)
    filesMatching(listOf("plugin.yml", "paper-plugin.yml")) {
        expand("version" to project.version.toString())
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

tasks.jar {
    archiveClassifier.set("dev")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    mergeServiceFiles()
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
