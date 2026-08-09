package top.ellan.mahjong.platform.paper.feedback;

import java.util.Objects;
import top.ellan.mahjong.application.opening.TableOpeningBatch;
import top.ellan.mahjong.application.opening.TableOpeningEffectPort;

/** Paper-only transient sounds synchronized with the CE-owned dice animation. */
public final class PaperOpeningSoundGateway implements TableOpeningEffectPort {
    private final PaperSoundDispatcher dispatcher;
    private final PaperSoundProfile dice;
    private final PaperSoundProfile wallOpen;

    public PaperOpeningSoundGateway(
            PaperSoundDispatcher dispatcher,
            PaperSoundProfile dice,
            PaperSoundProfile wallOpen) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.dice = Objects.requireNonNull(dice, "dice");
        this.wallOpen = Objects.requireNonNull(wallOpen, "wallOpen");
    }

    @Override
    public void rollStarted(TableOpeningBatch batch, int rollIndex) {
        Objects.requireNonNull(batch, "batch");
        if (rollIndex < 0 || rollIndex >= batch.opening().rolls().size()) {
            throw new IllegalArgumentException("rollIndex is outside the opening presentation");
        }
        dispatcher.play(batch.audience(), dice);
    }

    @Override
    public void wallOpened(TableOpeningBatch batch) {
        Objects.requireNonNull(batch, "batch");
        dispatcher.play(batch.audience(), wallOpen);
    }
}
