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
    private final Map<String, String> variantPrefixes;

    public PaperTableSoundGateway(
            PaperSoundDispatcher dispatcher,
            Map<RulePresentationCueType, PaperSoundProfile> profiles,
            Map<String, String> variantPrefixes) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        EnumMap<RulePresentationCueType, PaperSoundProfile> copy =
                new EnumMap<>(RulePresentationCueType.class);
        copy.putAll(Objects.requireNonNull(profiles, "profiles"));
        if (copy.size() != RulePresentationCueType.values().length) {
            throw new IllegalArgumentException("a sound profile is required for every cue type");
        }
        this.profiles = Map.copyOf(copy);
        this.variantPrefixes = Map.copyOf(Objects.requireNonNull(variantPrefixes, "variantPrefixes"));
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
            applicable.add(variant(batch, cue.type(), profiles.get(cue.type())));
        }
        return applicable;
    }

    /**
     * Applies the per-rule sound variant (v1.5.0 {@code variantSound}) unless the profile is
     * missing, the batch carries no rule, or the cue is the riichi call which always uses its
     * dedicated sound.
     */
    private PaperSoundProfile variant(
            TableCueBatch batch, RulePresentationCueType type, PaperSoundProfile profile) {
        if (profile == null
                || type == RulePresentationCueType.RIICHI
                || batch.ruleId() == null) {
            return profile;
        }
        String prefix = variantPrefixes.get(batch.ruleId().value());
        if (prefix == null || prefix.isEmpty()) {
            return profile;
        }
        return new PaperSoundProfile(
                variantKey(profile.key(), prefix), profile.volume(), profile.pitch());
    }

    private static String variantKey(String key, String prefix) {
        int separator = key.indexOf(':');
        if (separator < 0) {
            return key;
        }
        return key.substring(0, separator + 1) + prefix + key.substring(separator + 1);
    }
}
