plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

dependencies {
    implementation(project(":mahjong-rule-spi"))
    implementation(project(":mahjong-domain"))
    implementation(project(":mahjong-application"))
    implementation(project(":mahjong-presentation"))
    implementation(project(":mahjong-platform-paper"))
    compileOnly("net.momirealms:craft-engine-core:26.8")
    compileOnly("net.momirealms:craft-engine-bukkit:26.8")
    compileOnly("net.momirealms:craft-engine-bukkit-proxy:26.8")
    compileOnly("io.papermc.paper:paper-api:26.2.build.111-stable")
    compileOnly("net.momirealms:sparrow-reflection:0.34")
    testImplementation("net.momirealms:craft-engine-core:26.8")
    testImplementation("net.momirealms:craft-engine-bukkit:26.8")
    testImplementation("net.momirealms:craft-engine-bukkit-proxy:26.8")
    testImplementation("io.papermc.paper:paper-api:26.2.build.111-stable")
    testImplementation("net.momirealms:sparrow-reflection:0.34")
}
