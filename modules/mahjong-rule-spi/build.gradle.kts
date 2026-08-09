plugins {
    `java-library`
    `maven-publish`
}

group = "top.ellan.mahjong"
version = "1.6.0"

java {
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("MahjongPaper Rule SPI")
                description.set("Java-only parent-classloader contract for MahjongPaper rule packs")
                url.set("https://github.com/EllanServer/MahjongEngine")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/EllanServer/MahjongEngine.git")
                    developerConnection.set("scm:git:ssh://git@github.com/EllanServer/MahjongEngine.git")
                    url.set("https://github.com/EllanServer/MahjongEngine")
                }
            }
        }
    }
    repositories {
        maven {
            name = "Test"
            url = rootProject.layout.buildDirectory.dir("rule-sdk-repository").get().asFile.toURI()
        }
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/EllanServer/MahjongEngine")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
    }
}
