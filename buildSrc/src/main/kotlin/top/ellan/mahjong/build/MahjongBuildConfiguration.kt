package top.ellan.mahjong.build

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.language.jvm.tasks.ProcessResources
import java.nio.charset.StandardCharsets

data class MahjongBuildInputs(
    val generatedResourcesDir: Provider<Directory>,
    val generatedNativeResourcesDir: Provider<Directory>,
    val generatedTaskDependencies: List<TaskProvider<out Task>>,
    val javaTargetVersion: Int,
    val resourceProperties: Map<String, Any>,
)

/** Build wiring extracted to keep the root script within its architecture budget. */
object MahjongBuildConfiguration {
    fun configureRepositories(project: Project) {
        project.repositories.apply {
            mavenCentral()
            maven { setUrl("https://repo.papermc.io/repository/maven-public/") }
            maven { setUrl("https://repo.codemc.io/repository/maven-releases/") }
            maven { setUrl("https://repo.momirealms.net/releases/") }
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
    }

    private fun configureResources(
        project: Project,
        inputs: MahjongBuildInputs,
    ) {
        project.tasks.named("processResources", ProcessResources::class.java).configure {
            dependsOn(inputs.generatedTaskDependencies)
            filteringCharset = StandardCharsets.UTF_8.name()
            this.inputs.property("pluginVersion", inputs.resourceProperties.getValue("version").toString())
            inputs.resourceProperties
                .filterKeys { it != "version" }
                .forEach(this.inputs::property)
            from(inputs.generatedResourcesDir)
            from(inputs.generatedNativeResourcesDir)
            includeLicense("LICENSE", "LICENSE.txt", "META-INF")
            from(project.rootProject.file("THIRD_PARTY_NOTICES.md")) {
                into("META-INF")
            }
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
        targetName: String = source.substringAfterLast('/'),
        targetDirectory: String = "META-INF/licenses",
    ) {
        from(project.rootProject.file(source)) {
            into(targetDirectory)
            rename { targetName }
        }
    }
}
