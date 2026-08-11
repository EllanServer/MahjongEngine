package top.ellan.mahjong.platform.paper.feedback;

import java.util.Objects;
import top.ellan.mahjong.application.opening.TableOpeningBatch;
import top.ellan.mahjong.application.opening.TableOpeningEffectPort;

/** Paper-only transient sounds synchronized with the CE-owned dice animation. */
public final class PaperOpeningSoundGateway implements TableOpeningEffectPort {
    private final PaperSoundDispatcher dispatcher;
    private final PaperRuleSoundCatalog catalog;

    public PaperOpeningSoundGateway(
            PaperSoundDispatcher dispatcher,
            PaperRuleSoundCatalog catalog) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public void rollStarted(TableOpeningBatch batch, int rollIndex) {
        Objects.requireNonNull(batch, "batch");
        if (rollIndex < 0 || rollIndex >= batch.opening().rolls().size()) {
            throw new IllegalArgumentException("rollIndex is outside the opening presentation");
        }
        catalog.openingDice(batch.rulePack())
                .ifPresent(profile -> dispatcher.play(batch.audience(), profile));
    }

    @Override
    public void wallOpened(TableOpeningBatch batch) {
        Objects.requireNonNull(batch, "batch");
        catalog.openingWall(batch.rulePack())
                .ifPresent(profile -> dispatcher.play(batch.audience(), profile));
    }
}
