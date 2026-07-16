package top.ellan.mahjong.build

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.nio.charset.StandardCharsets

data class MahjongBuildInputs(
    val generatedResourcesDir: Provider<Directory>,
    val generatedNativeResourcesDir: Provider<Directory>,
    val generatedTaskDependencies: List<TaskProvider<out Task>>,
    val mockitoAgent: Configuration,
    val javaTargetVersion: Int,
    val resourceProperties: Map<String, Any>,
)

/** Shared Gradle lifecycle wiring kept out of the root build script. */
object MahjongBuildConfiguration {
    fun configureRepositories(project: Project) {
        project.repositories.apply {
            mavenCentral()
            maven { setUrl("https://repo.papermc.io/repository/maven-public/") }
            maven { setUrl("https://repo.codemc.io/repository/maven-releases/") }
            maven { setUrl("https://repo.momirealms.net/releases/") }
            maven { setUrl("https://repo.catnies.top/releases/") }
            maven { setUrl("https://jitpack.io") }
        }
    }

    fun configureLifecycle(
        project: Project,
        inputs: MahjongBuildInputs,
    ) {
        project.tasks.withType(JavaCompile::class.java).configureEach {
            options.encoding = StandardCharsets.UTF_8.name()
            options.release.set(inputs.javaTargetVersion)
            options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Werror"))
        }
        configureResources(project, inputs)
        configureTests(project, inputs)
    }

    private fun configureResources(
        project: Project,
        inputs: MahjongBuildInputs,
    ) {
        project.tasks.named("processResources", ProcessResources::class.java).configure {
            dependsOn(inputs.generatedTaskDependencies)
            filteringCharset = StandardCharsets.UTF_8.name()
            inputs.resourceProperties.forEach(this.inputs::property)
            from(inputs.generatedResourcesDir)
            from(inputs.generatedNativeResourcesDir)
            includeLicense("LICENSE", "MahjongPaper-source-MIT.txt")
            from(project.rootProject.file("THIRD_PARTY_NOTICES.md")) {
                into("META-INF")
            }
            includeLicense(
                "third-party-licenses/sparrow-reflection-GPL-3.0.txt",
                "sparrow-reflection-GPL-3.0.txt",
            )
            includeLicense(
                "third-party-licenses/sparrow-reflection-GPL-3.0.txt",
                "sparrow-yaml-GPL-3.0.txt",
            )
            includeLicense("third-party-licenses/mapping-io-Apache-2.0.txt", "mapping-io-Apache-2.0.txt")
            includeLicense("third-party-licenses/ASM-BSD-3-Clause.txt", "ASM-BSD-3-Clause.txt")
            from(project.rootProject.file("resourcepack/ATTRIBUTION.md")) {
                into("META-INF")
                rename { "RESOURCEPACK_ATTRIBUTION.md" }
            }
            includeLicense("native/gbmahjong/vendor/GB-Mahjong/LICENSE", "GB-Mahjong-LICENSE.txt")
            includeLicense("native/gbmahjong/WINPTHREADS-COPYING.txt", "winpthreads-COPYING.txt")
            filesMatching(listOf("plugin.yml", "paper-plugin.yml")) {
                expand(inputs.resourceProperties)
            }
        }
    }

    private fun ProcessResources.includeLicense(
        source: String,
        targetName: String,
    ) {
        from(project.rootProject.file(source)) {
            into("META-INF/licenses")
            rename { targetName }
        }
    }

    private fun configureTests(
        project: Project,
        inputs: MahjongBuildInputs,
    ) {
        val test = project.tasks.named("test", Test::class.java)
        val jacocoReport = project.tasks.named("jacocoTestReport", JacocoReport::class.java)
        test.configure {
            useJUnitPlatform { excludeTags("perf") }
            jvmArgumentProviders.add(
                CommandLineArgumentProvider {
                    listOf("-javaagent:${inputs.mockitoAgent.singleFile.absolutePath}")
                },
            )
            jvmArgs("-Dnet.bytebuddy.experimental=true")
            systemProperty("mahjong.test.expectedClassfileMajor", inputs.javaTargetVersion + 44)
            systemProperty(
                "mahjong.test.requireNative",
                project.providers
                    .gradleProperty("mahjongRequireNative")
                    .orElse("false")
                    .get(),
            )
            finalizedBy(jacocoReport)
        }
        jacocoReport.configure {
            dependsOn(test)
            reports.apply {
                xml.required.set(true)
                html.required.set(true)
                csv.required.set(false)
            }
        }
        project.tasks.named("check").configure {
            dependsOn(jacocoReport, "verifyMahjongTileResources", "generateCraftEngineBundle", "spotlessCheck", "detekt")
        }
    }
}
