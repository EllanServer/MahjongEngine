package top.ellan.mahjong.platform.paper.feedback;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import top.ellan.mahjong.application.feedback.TableCueBatch;
import top.ellan.mahjong.application.feedback.TablePresentationCuePort;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RulePresentationCue;
import top.ellan.mahjong.spi.RulePresentationCueType;

/** Schedules bounded sound delivery onto each target player's Folia-owned scheduler. */
public final class PaperTableSoundGateway implements TablePresentationCuePort {
    private final PaperSoundDispatcher dispatcher;
    private final Map<RulePresentationCueType, PaperSoundProfile> profiles;

    public PaperTableSoundGateway(
            PaperSoundDispatcher dispatcher,
            Map<RulePresentationCueType, PaperSoundProfile> profiles) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        EnumMap<RulePresentationCueType, PaperSoundProfile> copy =
                new EnumMap<>(RulePresentationCueType.class);
        copy.putAll(Objects.requireNonNull(profiles, "profiles"));
        if (copy.size() != RulePresentationCueType.values().length) {
            throw new IllegalArgumentException("a sound profile is required for every cue type");
        }
        this.profiles = Map.copyOf(copy);
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
            applicable.add(profiles.get(cue.type()));
        }
        return applicable;
    }
}
