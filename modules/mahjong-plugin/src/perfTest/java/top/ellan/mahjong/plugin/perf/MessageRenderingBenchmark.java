package top.ellan.mahjong.plugin.perf;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.momirealms.sparrow.message.tag.resolver.Placeholder;
import top.ellan.mahjong.plugin.i18n.SparrowMessageRenderer;

/** Dependency-free same-process comparison of the message renderers available on Paper. */
public final class MessageRenderingBenchmark {
    private static final int SAMPLE_COUNT = 7;
    private static final int DEFAULT_WARMUP = 20_000;
    private static final int DEFAULT_ITERATIONS = 100_000;
    private static final String TEMPLATE =
            "<dark_aqua><strikethrough>------------</strikethrough></dark_aqua> "
                    + "<gold><bold><title></bold></gold> "
                    + "<dark_aqua><strikethrough>------------</strikethrough></dark_aqua> "
                    + "<gray><usage></gray><dark_gray> - </dark_gray>"
                    + "<white><description></white> <aqua>[<page>/<pages>]</aqua>";
    private static final String EXPECTED =
            "------------ MahjongPaper Commands ------------ /mahjong help <page> - Browse commands [2/4]";
    private static final net.kyori.adventure.text.minimessage.MiniMessage KYORI =
            net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
    private static final com.sun.management.ThreadMXBean ALLOCATION_BEAN = allocationBean();

    private static volatile Object sink;

    private MessageRenderingBenchmark() {}

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        int warmup = positiveArgument(args, 0, DEFAULT_WARMUP);
        int iterations = positiveArgument(args, 1, DEFAULT_ITERATIONS);
        verifyOutput("direct", MessageRenderingBenchmark::direct);
        verifyOutput("sparrow", MessageRenderingBenchmark::sparrow);
        verifyOutput("kyori", MessageRenderingBenchmark::kyori);

        run("direct-builder", warmup, iterations, MessageRenderingBenchmark::direct);
        run("sparrow-minimessage", warmup, iterations, MessageRenderingBenchmark::sparrow);
        run("kyori-minimessage", warmup, iterations, MessageRenderingBenchmark::kyori);
    }

    private static Component direct() {
        return Component.empty()
                .append(Component.text("------------", NamedTextColor.DARK_AQUA)
                        .decorate(TextDecoration.STRIKETHROUGH))
                .append(Component.space())
                .append(Component.text("MahjongPaper Commands", NamedTextColor.GOLD)
                        .decorate(TextDecoration.BOLD))
                .append(Component.space())
                .append(Component.text("------------", NamedTextColor.DARK_AQUA)
                        .decorate(TextDecoration.STRIKETHROUGH))
                .append(Component.space())
                .append(Component.text("/mahjong help <page>", NamedTextColor.GRAY))
                .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                .append(Component.text("Browse commands", NamedTextColor.WHITE))
                .append(Component.text(" [2/4]", NamedTextColor.AQUA));
    }

    private static Component sparrow() {
        return SparrowMessageRenderer.render(
                TEMPLATE,
                Placeholder.unparsed("title", "MahjongPaper Commands"),
                Placeholder.unparsed("usage", "/mahjong help <page>"),
                Placeholder.unparsed("description", "Browse commands"),
                Placeholder.unparsed("page", "2"),
                Placeholder.unparsed("pages", "4"));
    }

    private static Component kyori() {
        return KYORI.deserialize(
                TEMPLATE,
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(
                        "title", "MahjongPaper Commands"),
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(
                        "usage", "/mahjong help <page>"),
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(
                        "description", "Browse commands"),
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("page", "2"),
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("pages", "4"));
    }

    private static void verifyOutput(String name, Supplier<Component> operation) {
        String rendered = PlainTextComponentSerializer.plainText().serialize(operation.get());
        if (!EXPECTED.equals(rendered)) {
            throw new IllegalStateException(name + " rendered unexpected text: " + rendered);
        }
    }

    private static void run(String name, int warmup, int iterations, Supplier<Component> operation) {
        for (int index = 0; index < warmup; index++) {
            sink = operation.get();
        }
        long[] samples = new long[SAMPLE_COUNT];
        long[] allocationSamples = new long[SAMPLE_COUNT];
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            long allocatedBefore = allocatedBytes();
            long started = System.nanoTime();
            for (int index = 0; index < iterations; index++) {
                sink = operation.get();
            }
            samples[sample] = System.nanoTime() - started;
            long allocatedAfter = allocatedBytes();
            allocationSamples[sample] =
                    allocatedBefore < 0L ? -1L : allocatedAfter - allocatedBefore;
        }
        Arrays.sort(samples);
        Arrays.sort(allocationSamples);
        double nanosPerOperation = (double) samples[SAMPLE_COUNT / 2] / iterations;
        double operationsPerSecond = 1_000_000_000.0D / nanosPerOperation;
        double bytesPerOperation = allocationSamples[SAMPLE_COUNT / 2] < 0L
                ? Double.NaN
                : (double) allocationSamples[SAMPLE_COUNT / 2] / iterations;
        System.out.printf(
                "BENCHMARK name=%s warmup=%d iterations=%d samples=%d ns/op=%.1f ops/s=%.1f bytes/op=%.1f%n",
                name,
                warmup,
                iterations,
                SAMPLE_COUNT,
                nanosPerOperation,
                operationsPerSecond,
                bytesPerOperation);
    }

    private static int positiveArgument(String[] args, int index, int fallback) {
        if (args.length <= index) {
            return fallback;
        }
        int value = Integer.parseInt(args[index]);
        if (value <= 0) {
            throw new IllegalArgumentException("benchmark arguments must be positive");
        }
        return value;
    }

    private static com.sun.management.ThreadMXBean allocationBean() {
        java.lang.management.ThreadMXBean platformBean = ManagementFactory.getThreadMXBean();
        if (!(platformBean instanceof com.sun.management.ThreadMXBean allocationBean)
                || !allocationBean.isThreadAllocatedMemorySupported()) {
            return null;
        }
        if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
            allocationBean.setThreadAllocatedMemoryEnabled(true);
        }
        return allocationBean;
    }

    private static long allocatedBytes() {
        return ALLOCATION_BEAN == null
                ? -1L
                : ALLOCATION_BEAN.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }
}
