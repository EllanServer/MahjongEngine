plugins {
    `java-library`
}

dependencies {
    implementation(project(":mahjong-rule-spi"))
    implementation(project(":mahjong-domain"))
    implementation(project(":mahjong-application"))
    testImplementation("com.h2database:h2:2.4.240")
}
