plugins {
    `java-library`
}

dependencies {
    api(project(":mahjong-rule-spi"))
    api(project(":mahjong-domain"))
    implementation(project(":mahjong-application"))
}

val perfTest = sourceSets.create("perfTest")
perfTest.compileClasspath += sourceSets.main.get().output
perfTest.runtimeClasspath += sourceSets.main.get().output

configurations[perfTest.implementationConfigurationName].extendsFrom(
    configurations.testImplementation.get(),
)
configurations[perfTest.runtimeOnlyConfigurationName].extendsFrom(
    configurations.testRuntimeOnly.get(),
)

tasks.register<JavaExec>("sceneProjectionBenchmark") {
    group = "verification"
    description = "Measures the representative four-seat scene projection hot path."
    dependsOn(perfTest.classesTaskName)
    classpath = perfTest.runtimeClasspath
    mainClass.set("top.ellan.mahjong.presentation.perf.SceneProjectionBenchmark")
    jvmArgs("-Xms512m", "-Xmx512m", "-XX:+UseG1GC")

    providers.gradleProperty("benchmarkJfr").orNull?.let { recording ->
        val recordingFile = rootProject.file(recording)
        doFirst {
            recordingFile.parentFile.mkdirs()
        }
        val destination = recordingFile.absolutePath.replace('\\', '/')
        jvmArgs(
            "-XX:StartFlightRecording=filename=$destination,settings=profile,dumponexit=true",
        )
    }
}
