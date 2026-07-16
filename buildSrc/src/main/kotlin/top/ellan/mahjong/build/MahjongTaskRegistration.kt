package top.ellan.mahjong.build

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.io.File

/**
 * Registers codegen and native build tasks for the Mahjong plugin.
 * Called from the root build.gradle.kts to keep it thin.
 */
object MahjongTaskRegistration {
    fun registerPerformanceTasks(
        project: Project,
        paperApiVersion: String,
    ) {
        registerPerfTestTask(project)
        MahjongJmhConfiguration.configure(project, paperApiVersion)
    }

    fun configureVerificationTasks(
        project: Project,
        javaTargetVersion: Int,
    ) {
        val jacocoReport =
            project.tasks.named<JacocoReport>("jacocoTestReport") {
                dependsOn(project.tasks.named("test"))
                reports {
                    xml.required.set(true)
                    html.required.set(true)
                    csv.required.set(false)
                }
            }
        project.tasks.named<Test>("test") {
            useJUnitPlatform { excludeTags("perf") }
            jvmArgs("-Dnet.bytebuddy.experimental=true")
            systemProperty("mahjong.test.expectedClassfileMajor", javaTargetVersion + 44)
            finalizedBy(jacocoReport)
        }
        project.tasks.named("check") {
            dependsOn(
                jacocoReport,
                "verifyMahjongTileResources",
                "generateCraftEngineBundle",
                "spotlessCheck",
                "detekt",
            )
        }
    }

    @Suppress("LongMethod")
    fun registerCodegenTasks(
        project: Project,
        generatedResourcesDir: Provider<Directory>,
        pluginVersion: String,
    ): List<TaskProvider<out org.gradle.api.Task>> {
        val tasks = mutableListOf<TaskProvider<out org.gradle.api.Task>>()
        val generatedDir = generatedResourcesDir.get().asFile

        tasks +=
            project.tasks.register("generateMessageIndex") {
                val inputDir =
                    project.layout.projectDirectory
                        .dir("src/main/resources")
                        .asFile
                val outputFile = File(generatedDir, "i18n/_index.json")
                inputs.dir(inputDir)
                outputs.file(outputFile)
                doLast {
                    MahjongCodegen.writeMessageIndex(inputDir, outputFile, "zh-CN")
                }
            }

        tasks +=
            project.tasks.register("generateLocalizedConfigs") {
                val configTemplateDir = project.layout.projectDirectory.dir("src/main/config-template")
                val templateFile = configTemplateDir.file("config.template.yml").asFile
                val translations =
                    linkedMapOf(
                        "config.yml" to configTemplateDir.file("config_comments_en.properties").asFile,
                        "config_zh_CN.yml" to configTemplateDir.file("config_comments_zh_CN.properties").asFile,
                        "config_zh_TW.yml" to configTemplateDir.file("config_comments_zh_TW.properties").asFile,
                    )

                inputs.file(templateFile)
                translations.values.forEach { inputs.file(it) }
                outputs.files(translations.keys.map { File(generatedDir, it) })

                doLast {
                    generatedDir.mkdirs()
                    val template = templateFile.readText(Charsets.UTF_8)
                    translations.forEach { (targetFileName, translationFile) ->
                        val rendered =
                            MahjongCodegen.renderTemplateWithTokens(
                                template,
                                MahjongCodegen.loadUtf8Properties(translationFile),
                            )
                        File(generatedDir, targetFileName).writeText(rendered, Charsets.UTF_8)
                    }
                }
            }

        tasks +=
            project.tasks.register("verifyMahjongTileResources") {
                val enumSource =
                    project.layout.projectDirectory
                        .file("src/main/java/top/ellan/mahjong/model/MahjongTile.java")
                        .asFile
                val tileResourceDir = project.layout.projectDirectory.dir("resourcepack/assets/mahjongcraft")
                val itemsDir = tileResourceDir.dir("items/mahjong_tile").asFile
                val modelsDir = tileResourceDir.dir("models/item/mahjong_tile").asFile
                val texturesDir = tileResourceDir.dir("textures/item/mahjong_tile").asFile
                val outputFile = File(generatedDir, "resourcepack/mahjong_tile_index.json")
                inputs.file(enumSource)
                inputs.dir(itemsDir)
                inputs.dir(modelsDir)
                inputs.dir(texturesDir)
                outputs.file(outputFile)
                doLast {
                    MahjongCodegen.writeMahjongTileResourceIndex(enumSource, itemsDir, modelsDir, texturesDir, outputFile)
                }
            }

        tasks +=
            project.tasks.register("generateCraftEngineBundle") {
                val enumSource =
                    project.layout.projectDirectory
                        .file("src/main/java/top/ellan/mahjong/model/MahjongTile.java")
                        .asFile
                val resourcepackDir =
                    project.layout.projectDirectory
                        .dir("resourcepack")
                        .asFile
                val attributionFile =
                    project.layout.projectDirectory
                        .file("resourcepack/ATTRIBUTION.md")
                        .asFile
                inputs.file(enumSource)
                inputs.dir(resourcepackDir)
                inputs.file(attributionFile)
                outputs.dir(File(generatedDir, "craftengine"))
                doLast {
                    CraftEngineBundleGenerator.writeCraftEngineBundle(
                        enumSource,
                        resourcepackDir,
                        attributionFile,
                        generatedDir,
                        pluginVersion,
                    )
                }
            }

        return tasks
    }

    @Suppress("LongMethod")
    fun registerNativeTasks(
        project: Project,
        generatedNativeResourcesDir: Provider<Directory>,
    ): List<TaskProvider<out org.gradle.api.Task>> {
        val tasks = mutableListOf<TaskProvider<out org.gradle.api.Task>>()
        val generatedNativeDir = generatedNativeResourcesDir.get().asFile

        val gbNativeSourceDir = project.layout.projectDirectory.dir("native/gbmahjong")
        val gbNativeBuildDir = project.layout.buildDirectory.dir("native/gbmahjong")
        val gbNativeCmakeExecutable = NativeBuildSupport.findExecutable("cmake")
        val gbNativeGxxExecutable = NativeBuildSupport.findExecutable("g++")
        val gbNativeNinjaExecutable = NativeBuildSupport.findExecutable("ninja")
        val gbNativeToolchainAvailable =
            gbNativeCmakeExecutable != null &&
                gbNativeGxxExecutable != null
        val gbNativeWindowsRuntimeDir = gbNativeGxxExecutable?.let { File(it).parentFile }
        val gbNativeCurrentOsName = System.getProperty("os.name", "")
        val gbNativeCurrentArch = System.getProperty("os.arch", "")
        val gbNativePlatformKey =
            NativeBuildSupport.nativePlatformKey(gbNativeCurrentOsName, gbNativeCurrentArch)
        val gbNativeLibraryFileName = NativeBuildSupport.nativeLibraryFileName(gbNativeCurrentOsName)

        val configureGbMahjongNative =
            project.tasks.register<Exec>("configureGbMahjongNative") {
                group = "build"
                description = "Configure the JNI GB Mahjong native project with CMake."
                onlyIf { gbNativeToolchainAvailable }
                val sourceDir = gbNativeSourceDir.asFile
                val buildDir = gbNativeBuildDir.get().asFile
                inputs.dir(sourceDir)
                outputs.dir(buildDir)
                doFirst {
                    buildDir.mkdirs()
                }
                val command =
                    mutableListOf(
                        gbNativeCmakeExecutable ?: "cmake",
                        "-S",
                        sourceDir.absolutePath,
                        "-B",
                        buildDir.absolutePath,
                    )
                if (gbNativeNinjaExecutable != null) {
                    command += listOf("-G", "Ninja", "-DCMAKE_MAKE_PROGRAM=$gbNativeNinjaExecutable")
                }
                if (gbNativeGxxExecutable != null) {
                    command += listOf("-DCMAKE_CXX_COMPILER=$gbNativeGxxExecutable")
                }
                commandLine(command)
            }
        tasks += configureGbMahjongNative

        val buildGbMahjongNative =
            project.tasks.register<Exec>("buildGbMahjongNative") {
                group = "build"
                description = "Build the JNI GB Mahjong native project with CMake."
                dependsOn(configureGbMahjongNative)
                onlyIf { gbNativeToolchainAvailable }
                val buildDir = gbNativeBuildDir.get().asFile
                inputs.dir(gbNativeSourceDir)
                outputs.dir(buildDir)
                commandLine(
                    gbNativeCmakeExecutable ?: "cmake",
                    "--build",
                    buildDir.absolutePath,
                    "--config",
                    "Release",
                )
            }
        tasks += buildGbMahjongNative

        tasks +=
            project.tasks.register("packageGbMahjongNative") {
                group = "build"
                description = "Copy built GB Mahjong native runtime files into generated resources."
                dependsOn(buildGbMahjongNative)
                onlyIf { gbNativeToolchainAvailable }
                val buildDir = gbNativeBuildDir.get().asFile
                val outputDir = File(generatedNativeDir, "native/$gbNativePlatformKey")
                inputs.dir(buildDir)
                outputs.dir(outputDir)
                doLast {
                    outputDir.mkdirs()
                    val library = File(buildDir, gbNativeLibraryFileName)
                    if (!library.isFile) {
                        throw GradleException("Expected GB Mahjong native library at ${library.absolutePath}")
                    }
                    library.copyTo(File(outputDir, library.name), overwrite = true)

                    if (gbNativePlatformKey == "windows-x86_64") {
                        val winPthread = gbNativeWindowsRuntimeDir?.let { File(it, "libwinpthread-1.dll") }
                        if (winPthread?.isFile == true) {
                            winPthread.copyTo(File(outputDir, winPthread.name), overwrite = true)
                        }
                    }
                }
            }

        return tasks
    }

    private fun registerPerfTestTask(project: Project): TaskProvider<Test> {
        val sourceSets = project.extensions.getByType<SourceSetContainer>()
        val testSourceSet = sourceSets["test"]
        return project.tasks.register<Test>("perfTest") {
            group = "verification"
            description = "Runs performance benchmarks tagged with @Tag(\"perf\")."
            dependsOn(project.tasks.named("testClasses"))
            testClassesDirs = testSourceSet.output.classesDirs
            classpath = testSourceSet.runtimeClasspath
            useJUnitPlatform {
                includeTags("perf")
            }
            jvmArgs("-Dnet.bytebuddy.experimental=true")
            systemProperty(
                "mahjong.perf.warmupIterations",
                project.providers
                    .gradleProperty("perfWarmups")
                    .orElse("5")
                    .get(),
            )
            systemProperty(
                "mahjong.perf.measurementIterations",
                project.providers
                    .gradleProperty("perfIterations")
                    .orElse("10")
                    .get(),
            )
            systemProperty(
                "mahjong.perf.batchSize",
                project.providers
                    .gradleProperty("perfBatchSize")
                    .orElse("200")
                    .get(),
            )
            systemProperty(
                "mahjong.perf.reportDir",
                project.layout.buildDirectory
                    .dir("reports/performance")
                    .get()
                    .asFile.absolutePath,
            )
            shouldRunAfter(project.tasks.named("test"))
        }
    }

    fun configureGitRatchet(
        project: Project,
        reference: String,
        ratchetFrom: (String) -> Unit,
    ) {
        when {
            gitReferenceExists(project, reference) -> ratchetFrom(reference)
            fetchRemoteTrackingReference(project, reference) -> ratchetFrom(reference)
            gitReferenceExists(project, "HEAD") -> ratchetFrom("HEAD")
        }
    }

    private fun fetchRemoteTrackingReference(
        project: Project,
        reference: String,
    ): Boolean {
        if (!reference.startsWith("origin/")) {
            return false
        }
        val branchName = reference.removePrefix("origin/")
        val refspec = "+refs/heads/$branchName:refs/remotes/$reference"
        return project.providers
            .exec {
                commandLine("git", "fetch", "--depth=1", "--no-tags", "origin", refspec)
                isIgnoreExitValue = true
            }.result
            .get()
            .exitValue == 0 &&
            gitReferenceExists(project, reference)
    }

    private fun gitReferenceExists(
        project: Project,
        reference: String,
    ): Boolean =
        project.providers
            .exec {
                commandLine("git", "rev-parse", "--verify", "--quiet", reference)
                isIgnoreExitValue = true
            }.result
            .get()
            .exitValue == 0
}
