package top.ellan.mahjong.perf;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import top.ellan.mahjong.table.core.DelimitedFingerprintBuilder;

/**
 * Small, server-independent benchmark used to verify the A/B infrastructure.
 *
 * <p>Production performance PRs should add focused benchmarks beside this one. Fork,
 * warmup and measurement policy deliberately lives outside benchmark annotations so the
 * base-owned CI configuration remains authoritative.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class DelimitedFingerprintBuilderBenchmark {
    private static final String EXPECTED =
        "seat-label:EAST:00000000-0000-0000-0000-000000000001:25000:true:false;wall:70:1";

    @Setup(Level.Iteration)
    public void verifyFingerprintContract() {
        String value = buildFingerprint();
        if (!EXPECTED.equals(value)) {
            throw new IllegalStateException(
                "Delimited fingerprint contract changed: expected=" + EXPECTED + ", actual=" + value
            );
        }
    }

    @Benchmark
    public String buildRepresentativeFingerprint() {
        return buildFingerprint();
    }

    private static String buildFingerprint() {
        return DelimitedFingerprintBuilder.create(192)
            .field("seat-label")
            .field("EAST")
            .field("00000000-0000-0000-0000-000000000001")
            .field(25_000)
            .field(true)
            .field(false)
            .entrySeparator()
            .field("wall")
            .field(70)
            .field(1)
            .toString();
    }
}
