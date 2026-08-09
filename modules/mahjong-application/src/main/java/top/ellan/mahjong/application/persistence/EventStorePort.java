package top.ellan.mahjong.application.persistence;

import java.util.concurrent.CompletionStage;

/** Async persistence boundary. Implementations must make sequence writes idempotent. */
public interface EventStorePort {
    CompletionStage<PersistAck> appendBatch(MatchWriteBatch batch);

    boolean available();
}
