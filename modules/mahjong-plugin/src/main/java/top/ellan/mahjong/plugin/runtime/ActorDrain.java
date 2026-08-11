package top.ellan.mahjong.plugin.runtime;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import top.ellan.mahjong.application.table.TableActorRegistry;

/**
 * Bounded shutdown drain for every live table actor.
 *
 * <p>Waiting here is what gives each per-match outbox a chance to flush before the pools close. A
 * timeout is reported rather than thrown: shutdown must continue even if one table is stuck.</p>
 */
public final class ActorDrain {
    private ActorDrain() {}

    public static void awaitAll(TableActorRegistry actors, Duration timeout, Logger logger) {
        Objects.requireNonNull(actors, "actors");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(logger, "logger");
        List<CompletionStage<Void>> drains = actors.closeAll();
        CompletableFuture<?>[] futures =
                drains.stream()
                        .map(CompletionStage::toCompletableFuture)
                        .toArray(CompletableFuture<?>[]::new);
        if (futures.length == 0) {
            return;
        }
        try {
            CompletableFuture.allOf(futures).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException failure) {
            logger.warning("Timed out draining persistence outboxes");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException failure) {
            logger.log(Level.WARNING, "A persistence outbox failed during shutdown", failure);
        }
    }
}
