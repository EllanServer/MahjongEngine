package top.ellan.mahjong.application.interaction;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.spi.PlayerId;

/** Non-blocking client-only overhead camera boundary. */
public interface OverheadViewPort {
    OverheadViewPort DISABLED = new OverheadViewPort() {
        @Override
        public CompletionStage<ToggleResult> toggle(
                TableId tableId, PlayerId playerId, long revision) {
            return CompletableFuture.completedFuture(ToggleResult.UNAVAILABLE);
        }

        @Override
        public boolean active(PlayerId playerId) {
            return false;
        }

        @Override
        public boolean exit(PlayerId playerId) {
            return false;
        }
    };

    CompletionStage<ToggleResult> toggle(TableId tableId, PlayerId playerId, long revision);

    boolean active(PlayerId playerId);

    boolean exit(PlayerId playerId);

    enum ToggleResult {
        ENTERED,
        EXITED,
        UNAVAILABLE
    }
}
