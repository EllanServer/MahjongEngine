package top.ellan.mahjong.table.render;

import top.ellan.mahjong.metrics.MetricsCollector;
import top.ellan.mahjong.metrics.NoopMetricsCollector;
import top.ellan.mahjong.table.core.TableSessionMutator;
import top.ellan.mahjong.render.snapshot.TableRenderPrecomputeResult;
import top.ellan.mahjong.render.snapshot.TableRenderSnapshot;
import org.bukkit.Bukkit;

public final class TableRenderCoordinator {
    private static final long DISPLAY_RESTORE_CHECK_INTERVAL_TICKS = 60L;

    private final TableSessionMutator session;
    private long renderRequestVersion;
    private long renderCancellationNonce;
    private boolean renderPrecomputeRunning;
    private boolean renderFlushScheduled;
    private long nextDisplayRestoreCheckTick;
    private TableRenderSnapshot pendingRenderSnapshot;

    public TableRenderCoordinator(TableSessionMutator session) {
        this.session = session;
    }

    public void render() {
        long startedAt = System.nanoTime();
        MetricsCollector metrics = this.metrics();
        metrics.incrementCounter("table.render.request.calls");
        this.session.prepareRenderRequest();
        if (this.renderFlushScheduled) {
            metrics.incrementCounter("table.render.request.coalesced");
            metrics.recordTimerNanos("table.render.request.nanos", System.nanoTime() - startedAt);
            return;
        }
        this.renderFlushScheduled = true;
        metrics.incrementCounter("table.render.request.scheduled");
        this.session.plugin().scheduler().runRegion(this.session.center(), this::flushRender);
        metrics.recordTimerNanos("table.render.request.nanos", System.nanoTime() - startedAt);
    }

    public void clearDisplays() {
        this.metrics().incrementCounter("table.render.clear.calls");
        this.invalidatePendingRenderPrecompute();
        this.nextDisplayRestoreCheckTick = 0L;
        this.session.clearRenderDisplays();
    }

    public void shutdown() {
        this.metrics().incrementCounter("table.render.shutdown.calls");
        this.invalidatePendingRenderPrecompute();
        this.renderFlushScheduled = false;
        this.nextDisplayRestoreCheckTick = 0L;
    }

    public void restoreDisplaysIfNeeded() {
        MetricsCollector metrics = this.metrics();
        metrics.incrementCounter("table.render.restore.checks");
        if (!this.session.hasRegionDisplays() || this.renderFlushScheduled || this.renderPrecomputeRunning) {
            metrics.incrementCounter("table.render.restore.skipped_busy_or_empty");
            return;
        }
        long nowTick = Bukkit.getCurrentTick();
        if (nowTick < this.nextDisplayRestoreCheckTick) {
            metrics.incrementCounter("table.render.restore.skipped_interval");
            return;
        }
        this.nextDisplayRestoreCheckTick = nowTick + DISPLAY_RESTORE_CHECK_INTERVAL_TICKS;
        if (!this.session.isCenterChunkLoaded() || !this.session.hasStaleDisplayRegions()) {
            metrics.incrementCounter("table.render.restore.skipped_not_stale");
            return;
        }
        metrics.incrementCounter("table.render.restore.triggered");
        this.render();
    }

    private void flushRender() {
        long startedAt = System.nanoTime();
        MetricsCollector metrics = this.metrics();
        metrics.incrementCounter("table.render.flush.calls");
        this.renderFlushScheduled = false;
        // completeRenderFlush() is what reschedules bot turns and the viewer
        // presentation tick. If scheduleAsyncRenderPrecompute() throws
        // (network jitter, async executor refusal, an unexpected NPE, etc.)
        // the bot would never get rescheduled and a 4-bot match would just
        // freeze on whichever bot is currently up. Wrap the precompute call
        // in try/finally so the finalize step always runs.
        try {
            this.scheduleAsyncRenderPrecompute();
        } catch (RuntimeException exception) {
            metrics.incrementCounter("table.render.flush.precompute_failed");
            org.bukkit.Bukkit.getLogger().log(
                java.util.logging.Level.WARNING,
                "Table render precompute scheduling failed; falling through to completeRenderFlush",
                exception
            );
        } finally {
            this.session.completeRenderFlush();
        }
        metrics.recordTimerNanos("table.render.flush.nanos", System.nanoTime() - startedAt);
    }

    private void scheduleAsyncRenderPrecompute() {
        MetricsCollector metrics = this.metrics();
        TableRenderSnapshot snapshot = this.session.captureRenderSnapshot(++this.renderRequestVersion, this.renderCancellationNonce);
        this.pendingRenderSnapshot = snapshot;
        metrics.recordGauge("table.render.precompute.pending_version", snapshot.version());
        if (this.renderPrecomputeRunning) {
            metrics.incrementCounter("table.render.precompute.queued_while_running");
            return;
        }
        this.startAsyncRenderPrecompute(snapshot);
    }

    private void startAsyncRenderPrecompute(TableRenderSnapshot snapshot) {
        this.renderPrecomputeRunning = true;
        MetricsCollector metrics = this.metrics();
        metrics.incrementCounter("table.render.precompute.started");
        metrics.recordGauge("table.render.precompute.running", 1L);
        this.session.plugin().async().executeCpu("render-precompute-" + this.session.id(), () -> {
            long startedAt = System.nanoTime();
            try {
                TableRenderPrecomputeResult result = this.session.precomputeRender(snapshot);
                this.metrics().recordTimerNanos("table.render.precompute.compute.nanos", System.nanoTime() - startedAt);
                this.session.plugin().scheduler().runRegion(this.session.center(), () -> this.finishAsyncRenderPrecompute(result));
            } catch (RuntimeException exception) {
                // The async precompute threw. Without this guard renderPrecomputeRunning
                // would stay true forever, so scheduleAsyncRenderPrecompute() would skip
                // every future precompute (the "if (renderPrecomputeRunning) return"
                // branch) and the table display would freeze permanently - which looks
                // exactly like "the bots stopped playing". Recover on the region thread
                // so the flag reset and any queued snapshot replay are correctly ordered.
                this.metrics().incrementCounter("table.render.precompute.compute_failed");
                this.session.plugin().scheduler().runRegion(
                    this.session.center(),
                    () -> this.recoverFromFailedPrecompute(snapshot, exception)
                );
            }
        });
    }

    private void recoverFromFailedPrecompute(TableRenderSnapshot snapshot, RuntimeException exception) {
        this.renderPrecomputeRunning = false;
        this.metrics().recordGauge("table.render.precompute.running", 0L);
        org.bukkit.Bukkit.getLogger().log(
            java.util.logging.Level.WARNING,
            "Async render precompute failed for table " + this.session.id() + "; display will retry on the next render request",
            exception
        );
        // Replay the newest pending snapshot if one queued up while we were running,
        // so a transient failure does not leave the display stuck on a stale frame.
        this.startNextPendingRenderIfNeeded(snapshot.version());
    }

    private void finishAsyncRenderPrecompute(TableRenderPrecomputeResult result) {
        long startedAt = System.nanoTime();
        MetricsCollector metrics = this.metrics();
        metrics.incrementCounter("table.render.precompute.finish.calls");
        this.renderPrecomputeRunning = false;
        metrics.recordGauge("table.render.precompute.running", 0L);
        if (result.snapshot().cancellationNonce() != this.renderCancellationNonce) {
            metrics.incrementCounter("table.render.precompute.dropped_cancelled");
            metrics.recordTimerNanos("table.render.precompute.finish.nanos", System.nanoTime() - startedAt);
            this.startNextPendingRenderIfNeeded(result.snapshot().version());
            return;
        }

        TableRenderSnapshot latestSnapshot = this.pendingRenderSnapshot;
        if (latestSnapshot != null && latestSnapshot.version() > result.snapshot().version()) {
            metrics.incrementCounter("table.render.precompute.dropped_stale");
            metrics.recordTimerNanos("table.render.precompute.finish.nanos", System.nanoTime() - startedAt);
            this.startNextPendingRenderIfNeeded(result.snapshot().version());
            return;
        }

        boolean deferred = this.session.applyRenderPrecompute(result);
        metrics.incrementCounter("table.render.precompute.applied");
        this.nextDisplayRestoreCheckTick = Bukkit.getCurrentTick() + DISPLAY_RESTORE_CHECK_INTERVAL_TICKS;
        if (deferred) {
            metrics.incrementCounter("table.render.precompute.deferred");
            this.session.plugin().scheduler().runRegionDelayed(this.session.center(), this::render, 1L);
        }
        this.startNextPendingRenderIfNeeded(result.snapshot().version());
        metrics.recordTimerNanos("table.render.precompute.finish.nanos", System.nanoTime() - startedAt);
    }

    private void startNextPendingRenderIfNeeded(long completedVersion) {
        TableRenderSnapshot latestSnapshot = this.pendingRenderSnapshot;
        if (latestSnapshot == null || latestSnapshot.version() <= completedVersion) {
            return;
        }
        this.metrics().incrementCounter("table.render.precompute.pending_replay");
        this.startAsyncRenderPrecompute(latestSnapshot);
    }

    private void invalidatePendingRenderPrecompute() {
        this.metrics().incrementCounter("table.render.precompute.invalidated");
        this.renderCancellationNonce++;
        this.pendingRenderSnapshot = null;
        this.renderPrecomputeRunning = false;
        this.metrics().recordGauge("table.render.precompute.running", 0L);
    }

    private MetricsCollector metrics() {
        try {
            if (this.session == null || this.session.plugin() == null || this.session.plugin().metrics() == null) {
                return NoopMetricsCollector.instance();
            }
            return this.session.plugin().metrics();
        } catch (RuntimeException ignored) {
            return NoopMetricsCollector.instance();
        }
    }
}
