package top.ellan.mahjong.perf

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path

@Tag("perf")
@Tag("perf-regression")
class PerformanceRegressionCheckTest {
    @Test
    fun `candidate hot paths stay within configured regression limits`() {
        val baselineDir = Path.of(requiredProperty("mahjong.perf.baselineDir"))
        val candidateDir = Path.of(requiredProperty("mahjong.perf.candidateDir"))
        val reportDir = Path.of(requiredProperty("mahjong.perf.regressionReportDir"))
        val thresholds =
            PerformanceRegressionGate.thresholdsWithOverrides(
                System.getProperty("mahjong.perf.regressionThresholds"),
            )
        val minimumRuns = requiredProperty("mahjong.perf.regressionMinRuns").toInt()
        val report =
            PerformanceRegressionGate.compare(
                baselineDir,
                candidateDir,
                PerformanceRegressionGate.Config(thresholds, minimumRuns),
            )
        PerformanceRegressionGate.writeReport(report, reportDir)

        val failures = report.comparisons.filterNot { it.passed }
        assertTrue(
            failures.isEmpty(),
            failures.joinToString(prefix = "Performance regressions detected: ") { comparison ->
                "${comparison.name} ${format(comparison.changePercent)}% > " +
                    "${format(comparison.thresholdPercent)}%"
            },
        )
    }

    private fun requiredProperty(name: String): String =
        System.getProperty(name)?.takeIf(String::isNotBlank)
            ?: error("required system property is missing: $name")

    private fun format(value: Double): String = "%.2f".format(java.util.Locale.ROOT, value)
}
