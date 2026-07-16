package top.ellan.mahjong.perf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.math.roundToLong

object PerformanceRegressionGate {
    val defaultThresholdsPercent: Map<String, Double> =
        linkedMapOf(
            "render.snapshot.create.started_session" to 25.0,
            "render.region_fingerprints.precompute.started_snapshot" to 25.0,
            "render.layout.precompute.started_snapshot" to 30.0,
        )

    data class Config(
        val thresholdsPercent: Map<String, Double> = defaultThresholdsPercent,
        val minimumRuns: Int = 3,
    ) {
        init {
            require(thresholdsPercent.isNotEmpty()) { "at least one benchmark threshold is required" }
            thresholdsPercent.forEach { (benchmark, threshold) ->
                require(benchmark.isNotBlank()) { "benchmark names must not be blank" }
                require(threshold.isFinite() && threshold >= 0.0) {
                    "threshold for $benchmark must be a finite percentage >= 0"
                }
            }
            require(minimumRuns >= 3) { "minimumRuns must be at least 3 to reject single-run noise" }
        }
    }

    data class BenchmarkComparison(
        val name: String,
        val baselineMedianNsPerOperation: Double,
        val candidateMedianNsPerOperation: Double,
        val pairedMedianRatio: Double,
        val thresholdPercent: Double,
        val pairedRatios: List<Double>,
    ) {
        val changePercent: Double = (pairedMedianRatio - 1.0) * 100.0
        val passed: Boolean = pairedMedianRatio <= 1.0 + thresholdPercent / 100.0
    }

    data class Report(
        val runIds: List<String>,
        val comparisons: List<BenchmarkComparison>,
    ) {
        val passed: Boolean = comparisons.all(BenchmarkComparison::passed)
    }

    fun compare(
        baselineDir: Path,
        candidateDir: Path,
        config: Config = Config(),
    ): Report {
        val baselineRuns = readRuns(baselineDir)
        val candidateRuns = readRuns(candidateDir)
        require(baselineRuns.keys == candidateRuns.keys) {
            "baseline and candidate run ids differ: baseline=${baselineRuns.keys}, candidate=${candidateRuns.keys}"
        }
        require(baselineRuns.size >= config.minimumRuns) {
            "expected at least ${config.minimumRuns} paired runs, found ${baselineRuns.size}"
        }

        val comparisons =
            config.thresholdsPercent.map { (benchmark, threshold) ->
                val baselineValues =
                    baselineRuns.map { (runId, results) ->
                        results[benchmark]
                            ?: error("baseline run $runId does not contain benchmark $benchmark")
                    }
                val candidateValues =
                    candidateRuns.map { (runId, results) ->
                        results[benchmark]
                            ?: error("candidate run $runId does not contain benchmark $benchmark")
                    }
                val pairedRatios =
                    baselineValues.zip(candidateValues) { baseline, candidate ->
                        require(baseline.isFinite() && baseline > 0.0) {
                            "baseline value for $benchmark must be finite and > 0"
                        }
                        require(candidate.isFinite() && candidate > 0.0) {
                            "candidate value for $benchmark must be finite and > 0"
                        }
                        candidate / baseline
                    }
                BenchmarkComparison(
                    name = benchmark,
                    baselineMedianNsPerOperation = median(baselineValues),
                    candidateMedianNsPerOperation = median(candidateValues),
                    pairedMedianRatio = median(pairedRatios),
                    thresholdPercent = threshold,
                    pairedRatios = pairedRatios,
                )
            }

        return Report(baselineRuns.keys.toList(), comparisons)
    }

    fun thresholdsWithOverrides(rawOverrides: String?): Map<String, Double> {
        if (rawOverrides.isNullOrBlank()) {
            return defaultThresholdsPercent
        }
        val thresholds = defaultThresholdsPercent.toMutableMap()
        rawOverrides
            .split(';')
            .filter(String::isNotBlank)
            .forEach { entry ->
                val separator = entry.indexOf('=')
                require(separator > 0 && separator < entry.lastIndex) {
                    "invalid performance threshold '$entry'; expected benchmark=percent"
                }
                val benchmark = entry.substring(0, separator).trim()
                val threshold = entry.substring(separator + 1).trim().toDouble()
                require(threshold.isFinite() && threshold >= 0.0) {
                    "threshold for $benchmark must be a finite percentage >= 0"
                }
                thresholds[benchmark] = threshold
            }
        return thresholds
    }

    fun writeReport(
        report: Report,
        reportDir: Path,
    ) {
        Files.createDirectories(reportDir)
        Files.writeString(reportDir.resolve("regression.md"), renderMarkdown(report))
        Files.writeString(reportDir.resolve("regression.json"), renderJson(report).toString() + System.lineSeparator())
    }

    private fun readRuns(directory: Path): LinkedHashMap<String, Map<String, Double>> {
        require(Files.isDirectory(directory)) { "performance result directory does not exist: $directory" }
        val files =
            Files.list(directory).use { paths ->
                paths
                    .filter { it.isRegularFile() && it.extension.equals("json", ignoreCase = true) }
                    .sorted()
                    .toList()
            }
        require(files.isNotEmpty()) { "no JSON performance reports found in $directory" }
        return files.associateTo(linkedMapOf()) { file -> file.name.substringBeforeLast('.') to readRun(file) }
    }

    private fun readRun(file: Path): Map<String, Double> {
        val root = Json.parseToJsonElement(Files.readString(file)).jsonObject
        val results = root["results"]?.jsonArray ?: error("performance report has no results array: $file")
        return results.associate { result ->
            val resultObject = result.jsonObject
            val name = resultObject.requiredString("name", file)
            val median = resultObject.requiredDouble("medianNsPerOperation", file)
            name to median
        }
    }

    private fun JsonObject.requiredString(
        key: String,
        file: Path,
    ): String = this[key]?.jsonPrimitive?.content ?: error("performance result in $file has no $key")

    private fun JsonObject.requiredDouble(
        key: String,
        file: Path,
    ): Double = this[key]?.jsonPrimitive?.double ?: error("performance result in $file has no numeric $key")

    private fun median(values: List<Double>): Double {
        require(values.isNotEmpty()) { "cannot calculate a median without values" }
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private fun renderMarkdown(report: Report): String =
        buildString {
            appendLine("# Performance Regression Gate")
            appendLine()
            appendLine("- Result: ${if (report.passed) "PASS" else "FAIL"}")
            appendLine("- Paired runs: ${report.runIds.joinToString()}")
            appendLine("- Gate metric: median of paired candidate/baseline median-ns/op ratios")
            appendLine()
            appendLine(
                "| Benchmark | Baseline median ns/op | Candidate median ns/op | Paired changes | " +
                    "Median change | Limit | Result |",
            )
            appendLine("| --- | ---: | ---: | --- | ---: | ---: | :---: |")
            report.comparisons.forEach { comparison ->
                val pairedChanges =
                    comparison.pairedRatios.joinToString(", ") { ratio ->
                        signedPercent((ratio - 1.0) * 100.0)
                    }
                appendLine(
                    "| ${comparison.name} | ${format(comparison.baselineMedianNsPerOperation)} | " +
                        "${format(comparison.candidateMedianNsPerOperation)} | " +
                        "$pairedChanges | " +
                        "${signedPercent(comparison.changePercent)} | +${format(comparison.thresholdPercent)}% | " +
                        "${if (comparison.passed) "PASS" else "FAIL"} |",
                )
            }
        }

    private fun renderJson(report: Report): JsonObject =
        buildJsonObject {
            put("passed", JsonPrimitive(report.passed))
            put(
                "runIds",
                buildJsonArray {
                    report.runIds.forEach { add(JsonPrimitive(it)) }
                },
            )
            put(
                "comparisons",
                JsonArray(
                    report.comparisons.map { comparison ->
                        buildJsonObject {
                            put("name", JsonPrimitive(comparison.name))
                            put("baselineMedianNsPerOperation", JsonPrimitive(comparison.baselineMedianNsPerOperation))
                            put(
                                "candidateMedianNsPerOperation",
                                JsonPrimitive(comparison.candidateMedianNsPerOperation),
                            )
                            put("pairedMedianRatio", JsonPrimitive(comparison.pairedMedianRatio))
                            put("changePercent", JsonPrimitive(comparison.changePercent))
                            put("thresholdPercent", JsonPrimitive(comparison.thresholdPercent))
                            put("passed", JsonPrimitive(comparison.passed))
                            put(
                                "pairedRatios",
                                JsonArray(comparison.pairedRatios.map(::JsonPrimitive)),
                            )
                        }
                    },
                ),
            )
        }

    private fun signedPercent(value: Double): String =
        if (value >= 0.0) {
            "+${format(value)}%"
        } else {
            "${format(value)}%"
        }

    private fun format(value: Double): String = ((value * 100.0).roundToLong() / 100.0).toString()
}
