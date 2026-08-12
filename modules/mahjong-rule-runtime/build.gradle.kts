plugins {
    `java-library`
}

dependencies {
    api(project(":mahjong-rule-spi"))
    implementation(project(":mahjong-domain"))
    implementation("org.ow2.asm:asm:9.10.1")
    implementation("org.ow2.asm:asm-tree:9.10.1")
}
