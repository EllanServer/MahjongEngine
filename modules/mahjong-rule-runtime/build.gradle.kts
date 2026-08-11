plugins {
    `java-library`
}

dependencies {
    api(project(":mahjong-rule-spi"))
    implementation(project(":mahjong-domain"))
}
