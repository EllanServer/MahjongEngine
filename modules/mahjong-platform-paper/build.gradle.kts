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
    implementation(project(":mahjong-application"))
    implementation(project(":mahjong-domain"))
    compileOnly("io.papermc.paper:paper-api:26.2.build.111-stable")
}
