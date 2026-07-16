package top.ellan.mahjong.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PerformanceRegressionGateTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `single noisy pair cannot fail a three-run gate`() {
        val report = compare(listOf(100.0, 100.0, 100.0), listOf(110.0, 150.0, 110.0), 20.0)

        assertTrue(report.passed)
        assertEquals(1.1, report.comparisons.single().pairedMedianRatio, 0.0001)
    }

    @Test
    fun `majority regression fails a three-run gate`() {
        val report = compare(listOf(100.0, 100.0, 100.0), listOf(130.0, 110.0, 135.0), 20.0)

        assertFalse(report.passed)
        assertEquals(1.3, report.comparisons.single().pairedMedianRatio, 0.0001)
    }

    @Test
    fun `threshold overrides keep unspecified defaults`() {
        val thresholds =
            PerformanceRegressionGate.thresholdsWithOverrides(
                "render.snapshot.create.started_session=12.5;custom.hot_path=40",
            )

        assertEquals(12.5, thresholds["render.snapshot.create.started_session"])
        assertEquals(25.0, thresholds["render.region_fingerprints.precompute.started_snapshot"])
        assertEquals(40.0, thresholds["custom.hot_path"])
    }

    private fun compare(
        baseline: List<Double>,
        candidate: List<Double>,
        thresholdPercent: Double,
    ): PerformanceRegressionGate.Report {
        val baselineDir = Files.createDirectory(tempDir.resolve("baseline"))
        val candidateDir = Files.createDirectory(tempDir.resolve("candidate"))
        baseline.indices.forEach { index ->
            writeRun(baselineDir.resolve("run-${index + 1}.json"), baseline[index])
            writeRun(candidateDir.resolve("run-${index + 1}.json"), candidate[index])
        }
        return PerformanceRegressionGate.compare(
            baselineDir,
            candidateDir,
            PerformanceRegressionGate.Config(mapOf(BENCHMARK to thresholdPercent)),
        )
    }

    private fun writeRun(
        file: Path,
        median: Double,
    ) {
        Files.writeString(
            file,
            """
            {
              "results": [
                {"name":"$BENCHMARK","medianNsPerOperation":$median}
              ]
            }
            """.trimIndent(),
        )
    }

    private companion object {
        const val BENCHMARK = "hot.path"
    }
}
