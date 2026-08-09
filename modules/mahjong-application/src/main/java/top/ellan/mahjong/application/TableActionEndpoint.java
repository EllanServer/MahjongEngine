package top.ellan.mahjong.application;

import java.util.concurrent.CompletionStage;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.PlayerId;

/** Bounded, single-writer interaction endpoint for either a lobby or an active match. */
public interface TableActionEndpoint extends AutoCloseable {
    CompletionStage<TableActionResult> submit(PlayerId actor, ActionToken token);

    TableActorSnapshot snapshot();

    CompletionStage<Void> closeAndDrain();

    @Override
    void close();
}
