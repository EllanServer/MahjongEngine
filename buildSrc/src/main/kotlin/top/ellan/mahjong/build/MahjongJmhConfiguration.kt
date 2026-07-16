package top.ellan.mahjong.build

import me.champeau.jmh.JmhParameters
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named

/** Keeps the isolated JMH convention out of the already-large root build script. */
object MahjongJmhConfiguration {
    private const val JMH_VERSION = "1.37"

    fun configure(
        project: Project,
        paperApiVersion: String,
    ) {
        project.pluginManager.apply("me.champeau.jmh")
        project.dependencies.add("jmh", "org.openjdk.jmh:jmh-core:$JMH_VERSION")
        project.dependencies.add(
            "jmhAnnotationProcessor",
            "org.openjdk.jmh:jmh-generator-annprocess:$JMH_VERSION",
        )
        project.dependencies.add("jmhImplementation", "io.papermc.paper:paper-api:$paperApiVersion")

        project.extensions.getByType<SourceSetContainer>().named("jmh") {
            java.setSrcDirs(listOf("src/perfTest/java"))
            resources.setSrcDirs(listOf("src/perfTest/resources"))
        }
        project.extensions.getByType<JmhParameters>().apply {
            fork.set(
                project.providers
                    .gradleProperty("jmhForks")
                    .map(String::toInt)
                    .orElse(1),
            )
            warmupIterations.set(
                project.providers
                    .gradleProperty("jmhWarmups")
                    .map(String::toInt)
                    .orElse(5),
            )
            iterations.set(
                project.providers
                    .gradleProperty("jmhIterations")
                    .map(String::toInt)
                    .orElse(8),
            )
            warmup.set(project.providers.gradleProperty("jmhWarmupTime").orElse("1s"))
            timeOnIteration.set(project.providers.gradleProperty("jmhMeasurementTime").orElse("1s"))
            benchmarkMode.set(listOf("avgt"))
            timeUnit.set("ns")
            resultFormat.set("JSON")
            resultsFile.set(project.layout.buildDirectory.file("reports/jmh/results.json"))
            failOnError.set(true)
            forceGC.set(true)
            profilers.set(listOf("gc"))
            includeTests.set(false)
            duplicateClassesStrategy.set(DuplicatesStrategy.EXCLUDE)
        }
        project.tasks.named<Jar>("jmhJar") {
            // Paper's development bundle contains more than the classic ZIP entry limit.
            isZip64 = true
        }
    }
}
