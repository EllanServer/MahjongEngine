plugins {
    `java-library`
}

dependencies {
    implementation(project(":mahjong-application"))
    implementation(project(":mahjong-domain"))
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")
}
