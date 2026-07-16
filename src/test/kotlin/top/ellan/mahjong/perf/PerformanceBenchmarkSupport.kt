package top.ellan.mahjong.perf

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.roundToLong

object PerformanceBenchmarkSupport {
    private val results = mutableListOf<BenchmarkResult>()

    @Volatile
    private var blackhole: Int = 0
    private val reportDir: Path by lazy {
        Path.of(System.getProperty("mahjong.perf.reportDir", "build/reports/performance"))
    }
    private val warmupIterations: Int
        get() = System.getProperty("mahjong.perf.warmupIterations", "5").toInt()
    private val measurementIterations: Int
        get() = System.getProperty("mahjong.perf.measurementIterations", "10").toInt()
    private val batchSize: Int
        get() = System.getProperty("mahjong.perf.batchSize", "200").toInt()

    fun run(
        name: String,
        warmups: Int = warmupIterations,
        measurements: Int = measurementIterations,
        batch: Int = batchSize,
        block: () -> Unit,
    ): BenchmarkResult {
        require(warmups >= 0) { "warmups must be >= 0" }
        require(measurements > 0) { "measurements must be > 0" }
        require(batch > 0) { "batch must be > 0" }

        repeat(warmups) {
            repeat(batch) {
                block()
            }
        }

        val samples = LongArray(measurements)
        repeat(measurements) { index ->
            val startedAt = System.nanoTime()
            repeat(batch) {
                block()
            }
            samples[index] = System.nanoTime() - startedAt
        }

        val sorted = samples.sorted()
        val totalNanos = samples.sum()
        val result =
            BenchmarkResult(
                name = name,
                warmupIterations = warmups,
                measurementIterations = measurements,
                batchSize = batch,
                avgNsPerOperation = totalNanos.toDouble() / (measurements.toDouble() * batch.toDouble()),
                medianNsPerOperation = percentile(sorted, 0.50) / batch.toDouble(),
                p90NsPerOperation = percentile(sorted, 0.90) / batch.toDouble(),
                minNsPerOperation = sorted.first() / batch.toDouble(),
                maxNsPerOperation = sorted.last() / batch.toDouble(),
                totalMillis = totalNanos / 1_000_000.0,
            )
        record(result)
        return result
    }

    fun consume(value: Int) {
        blackhole = blackhole xor value
    }

    @Synchronized
    private fun record(result: BenchmarkResult) {
        results.removeAll { it.name == result.name }
        results += result
        writeReports()
    }

    @Synchronized
    private fun writeReports() {
        Files.createDirectories(reportDir)
        Files.writeString(reportDir.resolve("results.json"), renderJson(results.sortedBy { it.name }))
        Files.writeString(reportDir.resolve("results.md"), renderMarkdown(results.sortedBy { it.name }))
    }

    private fun renderJson(results: List<BenchmarkResult>): String =
        buildString {
            appendLine("{")
            appendLine("  \"generatedAt\": ${jsonString(timestamp())},")
            appendLine("  \"results\": [")
            results.forEachIndexed { index, result ->
                val suffix = if (index == results.lastIndex) "" else ","
                appendLine("    ${result.toJson()}$suffix")
            }
            appendLine("  ]")
            appendLine("}")
        }

    private fun renderMarkdown(results: List<BenchmarkResult>): String =
        buildString {
            appendLine("# Performance Results")
            appendLine()
            appendLine("- Generated: ${timestamp()}")
            appendLine()
            appendLine(
                "| Benchmark | Warmups | Measurements | Batch | Avg ns/op | Median ns/op | " +
                    "P90 ns/op | Min ns/op | Max ns/op | Total ms |",
            )
            appendLine("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
            results.forEach { result ->
                appendLine(
                    "| ${result.name} | ${result.warmupIterations} | ${result.measurementIterations} | " +
                        "${result.batchSize} | ${format(result.avgNsPerOperation)} | " +
                        "${format(result.medianNsPerOperation)} | ${format(result.p90NsPerOperation)} | " +
                        "${format(result.minNsPerOperation)} | ${format(result.maxNsPerOperation)} | " +
                        "${format(result.totalMillis)} |",
                )
            }
        }

    private fun percentile(
        sortedSamples: List<Long>,
        percentile: Double,
    ): Double {
        if (sortedSamples.isEmpty()) {
            return 0.0
        }
        val index = ceil((sortedSamples.size - 1) * percentile).toInt().coerceIn(0, sortedSamples.lastIndex)
        return sortedSamples[index].toDouble()
    }

    private fun format(value: Double): String =
        (value * 100.0).roundToLong().let { rounded ->
            (rounded / 100.0).toString()
        }

    private fun timestamp(): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.now().atOffset(ZoneOffset.UTC))

    private fun jsonString(value: String): String =
        buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
            append('"')
        }

    data class BenchmarkResult(
        val name: String,
        val warmupIterations: Int,
        val measurementIterations: Int,
        val batchSize: Int,
        val avgNsPerOperation: Double,
        val medianNsPerOperation: Double,
        val p90NsPerOperation: Double,
        val minNsPerOperation: Double,
        val maxNsPerOperation: Double,
        val totalMillis: Double,
    ) {
        fun toJson(): String =
            buildString {
                append("{")
                append("\"name\":").append(PerformanceBenchmarkSupport.jsonString(name)).append(',')
                append("\"warmupIterations\":").append(warmupIterations).append(',')
                append("\"measurementIterations\":").append(measurementIterations).append(',')
                append("\"batchSize\":").append(batchSize).append(',')
                append("\"avgNsPerOperation\":").append(avgNsPerOperation).append(',')
                append("\"medianNsPerOperation\":").append(medianNsPerOperation).append(',')
                append("\"p90NsPerOperation\":").append(p90NsPerOperation).append(',')
                append("\"minNsPerOperation\":").append(minNsPerOperation).append(',')
                append("\"maxNsPerOperation\":").append(maxNsPerOperation).append(',')
                append("\"totalMillis\":").append(totalMillis)
                append("}")
            }
    }
}
