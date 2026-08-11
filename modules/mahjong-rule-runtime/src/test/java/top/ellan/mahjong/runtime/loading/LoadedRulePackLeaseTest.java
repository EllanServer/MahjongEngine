package top.ellan.mahjong.runtime.loading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackProvider;
import top.ellan.mahjong.spi.RulePackRef;
import top.ellan.mahjong.spi.SpiVersion;

/**
 * Lease and unload semantics for one loaded artifact.
 *
 * <p>The reclaim assertion is what turns "we closed the loader" into "the loader really became
 * unreachable"; a silent leak here would keep classes loaded forever.</p>
 */
class LoadedRulePackLeaseTest {
    @TempDir Path temporaryDirectory;

    @Test
    void aLeasedPackCannotBeClosedUntilEveryHolderReleases() throws Exception {
        LoadedRulePack pack = pack();
        Object firstMatch = new Object();
        Object secondMatch = new Object();

        pack.acquire(firstMatch);
        pack.acquire(firstMatch);
        pack.acquire(secondMatch);
        assertEquals(2, pack.leaseCount(), "repeated acquisition by one holder must be idempotent");
        assertThrows(IllegalStateException.class, pack::close);

        assertFalse(pack.release(firstMatch));
        assertTrue(pack.release(secondMatch));
        pack.close();
        assertTrue(pack.closed());
        assertThrows(IllegalStateException.class, () -> pack.acquire(firstMatch));
    }

    @Test
    void closingAnUnleasedPackLetsItsClassLoaderBeReclaimed() throws Exception {
        // The pack must go out of scope as well; a live LoadedRulePack keeps its own loader
        // reachable, which is exactly the situation the runtime avoids by dropping the map entry.
        WeakReference<ClassLoader> watch = closeAndForget();

        assertTrue(reclaimed(watch), "rule-pack classloader was not reclaimed after unload");
    }

    private WeakReference<ClassLoader> closeAndForget() throws Exception {
        LoadedRulePack pack = pack();
        WeakReference<ClassLoader> watch = pack.classLoaderWatch();
        assertTrue(watch.get() != null, "watch must observe the live loader before close");
        pack.close();
        return watch;
    }

    private static boolean reclaimed(WeakReference<ClassLoader> watch) throws InterruptedException {
        for (int attempt = 0; attempt < 20 && watch.get() != null; attempt++) {
            System.gc();
            Thread.sleep(25L);
        }
        return watch.get() == null;
    }

    /** Builds a real child-first loader over a minimal JAR; no provider probe is needed here. */
    private LoadedRulePack pack() throws Exception {
        Path jar = temporaryDirectory.resolve("lease-pack.jar");
        try (OutputStream output = Files.newOutputStream(jar);
                ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(RulePackManifest.PATH));
            zip.write(
                    ("id=riichi\nversion=1.0.0\nspiVersion="
                                    + SpiVersion.CURRENT
                                    + "\nrequiredCoreVersion=>=2.0.0\nstateSchemaVersion=1\n"
                                    + "requiredResources=\n")
                            .getBytes(StandardCharsets.ISO_8859_1));
            zip.closeEntry();
        }
        RulePackRef reference = new RulePackRef(new RuleId("riichi"), "1.0.0", "a".repeat(64), 1);
        ChildFirstRuleClassLoader loader =
                new ChildFirstRuleClassLoader(
                        jar.toUri().toURL(), RulePackProvider.class.getClassLoader());
        return new LoadedRulePack(reference, jar, new StubProvider(), loader);
    }

    /** Lease and unload behaviour never calls into rule logic, so every method fails loudly. */
    private static final class StubProvider implements RulePackProvider {
        private static UnsupportedOperationException unused() {
            return new UnsupportedOperationException("not needed for lease tests");
        }

        @Override
        public top.ellan.mahjong.spi.RulePackDescriptor descriptor() {
            throw unused();
        }

        @Override
        public top.ellan.mahjong.spi.RuleState createMatch(
                top.ellan.mahjong.spi.MatchSetup setup) {
            throw unused();
        }

        @Override
        public top.ellan.mahjong.spi.RuleTransition transition(
                top.ellan.mahjong.spi.RuleState state,
                top.ellan.mahjong.spi.PlayerId actor,
                top.ellan.mahjong.spi.RuleAction action) {
            throw unused();
        }

        @Override
        public java.util.List<top.ellan.mahjong.spi.LegalAction> legalActions(
                top.ellan.mahjong.spi.RuleState state, top.ellan.mahjong.spi.PlayerId actor) {
            throw unused();
        }

        @Override
        public top.ellan.mahjong.spi.PublicRuleView publicView(
                top.ellan.mahjong.spi.RuleState state, long revision) {
            throw unused();
        }

        @Override
        public top.ellan.mahjong.spi.PrivateRuleView privateView(
                top.ellan.mahjong.spi.RuleState state,
                top.ellan.mahjong.spi.PlayerId viewer,
                long revision) {
            throw unused();
        }

        @Override
        public top.ellan.mahjong.spi.RuleStateSnapshot snapshot(
                top.ellan.mahjong.spi.RuleState state, long sequence) {
            throw unused();
        }

        @Override
        public top.ellan.mahjong.spi.RuleState restore(
                top.ellan.mahjong.spi.RuleStateSnapshot snapshot) {
            throw unused();
        }
    }
}
