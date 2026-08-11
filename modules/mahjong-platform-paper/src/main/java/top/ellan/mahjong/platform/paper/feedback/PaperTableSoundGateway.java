package top.ellan.mahjong.platform.paper.feedback;

import java.util.ArrayList;
import java.util.Objects;
import top.ellan.mahjong.application.feedback.TableCueBatch;
import top.ellan.mahjong.application.feedback.TablePresentationCuePort;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RulePresentationCue;
import top.ellan.mahjong.spi.RulePresentationCueType;

/** Schedules bounded sound delivery onto each target player's Folia-owned scheduler. */
public final class PaperTableSoundGateway implements TablePresentationCuePort {
    private final PaperSoundDispatcher dispatcher;
    private final PaperRuleSoundCatalog catalog;

    public PaperTableSoundGateway(
            PaperSoundDispatcher dispatcher,
            PaperRuleSoundCatalog catalog) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public void publish(TableCueBatch batch) {
        Objects.requireNonNull(batch, "batch");
        for (PlayerId playerId : batch.audience()) {
            dispatcher.play(playerId, applicable(batch, playerId));
        }
    }

    private java.util.List<PaperSoundProfile> applicable(
            TableCueBatch batch, PlayerId playerId) {
        ArrayList<PaperSoundProfile> applicable = new ArrayList<>(batch.cues().size());
        for (RulePresentationCue cue : batch.cues()) {
            if (cue.target().isPresent() && !cue.target().orElseThrow().equals(playerId)) {
                continue;
            }
            catalog.cue(batch.rulePack(), cue.type()).ifPresent(applicable::add);
        }
        return applicable;
    }
}
