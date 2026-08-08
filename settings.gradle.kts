pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "mahjong-plugin"

include(
    ":mahjong-rule-spi",
    ":mahjong-rule-tck",
    ":mahjong-domain",
    ":mahjong-application",
    ":mahjong-rule-runtime",
    ":mahjong-presentation",
    ":mahjong-persistence-sql",
    ":mahjong-platform-paper",
    ":mahjong-craftengine",
)

project(":mahjong-rule-spi").projectDir = file("modules/mahjong-rule-spi")
project(":mahjong-rule-tck").projectDir = file("modules/mahjong-rule-tck")
project(":mahjong-domain").projectDir = file("modules/mahjong-domain")
project(":mahjong-application").projectDir = file("modules/mahjong-application")
project(":mahjong-rule-runtime").projectDir = file("modules/mahjong-rule-runtime")
project(":mahjong-presentation").projectDir = file("modules/mahjong-presentation")
project(":mahjong-persistence-sql").projectDir = file("modules/mahjong-persistence-sql")
project(":mahjong-platform-paper").projectDir = file("modules/mahjong-platform-paper")
project(":mahjong-craftengine").projectDir = file("modules/mahjong-craftengine")
