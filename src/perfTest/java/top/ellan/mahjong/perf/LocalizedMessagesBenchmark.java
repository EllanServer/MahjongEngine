package top.ellan.mahjong.perf;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import top.ellan.mahjong.i18n.LocalizedMessages;

/**
 * Exercises the parametrised message rendering paths hit by every render flush.
 *
 * <p>Each method mirrors a hot Spark-sampled call site: bot display names
 * ({@code table.bot_name}), per-seat status lines ({@code table.public.seat_status}),
 * empty-seat placeholders ({@code table.public.seat_empty}) and the table centre line
 * ({@code table.public.center_waiting}). Placeholders flow through the {@link Map}-based
 * overload so both implementations (baseline delegation and cached) share one contract.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class LocalizedMessagesBenchmark {
    private static final String[] SEATS = {"East", "South", "West", "North"};
    private static final String[] STATUSES = {"", "Riichi", "", "Ready"};

    private final LocalizedMessages messages = new LocalizedMessages();
    private final Locale locale = LocalizedMessages.DEFAULT_LOCALE;
    private final String[] points = {
        this.messages.formatNumber(this.locale, "points", 25_000),
        this.messages.formatNumber(this.locale, "points", 18_500),
        this.messages.formatNumber(this.locale, "points", 36_200),
        this.messages.formatNumber(this.locale, "points", 5_300)
    };

    @Benchmark
    public String plainBotName() {
        return this.messages.plain(this.locale, "table.bot_name", Map.of("index", "1"))
            + this.messages.plain(this.locale, "table.bot_name", Map.of("index", "2"))
            + this.messages.plain(this.locale, "table.bot_name", Map.of("index", "3"))
            + this.messages.plain(this.locale, "table.bot_name", Map.of("index", "4"));
    }

    @Benchmark
    public String plainSeatStatus() {
        String result = "";
        for (int index = 0; index < 4; index++) {
            result += this.messages.plain(
                this.locale,
                "table.public.seat_status",
                Map.of("seat", SEATS[index], "points", this.points[index], "status", STATUSES[index])
            );
        }
        return result;
    }

    @Benchmark
    public String plainSeatEmpty() {
        return this.messages.plain(this.locale, "table.public.seat_empty", Map.of("seat", "South"))
            + this.messages.plain(this.locale, "table.public.seat_empty", Map.of("seat", "West"));
    }

    @Benchmark
    public String plainCenterWaiting() {
        return this.messages.plain(
            this.locale,
            "table.public.center_waiting",
            Map.of("table_id", "table-7", "summary", "Waiting for South")
        );
    }

    @Benchmark
    public Component renderSeatStatus() {
        Component result = Component.empty();
        for (int index = 0; index < 4; index++) {
            result = result.append(this.messages.render(
                this.locale,
                "table.public.seat_status",
                Map.of("seat", SEATS[index], "points", this.points[index], "status", STATUSES[index])
            ));
        }
        return result;
    }
}
