package top.ellan.mahjong.persistence.sql.recovery;

import top.ellan.mahjong.persistence.sql.common.PersistenceConflictException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import top.ellan.mahjong.spi.RuleEvent;
import top.ellan.mahjong.spi.RulePackProvider;
import top.ellan.mahjong.spi.RuleState;
import top.ellan.mahjong.spi.RuleStateSnapshot;
import top.ellan.mahjong.spi.RuleTransition;

/** Replays only committed actions and rejects any provider/event/hash divergence. */
public final class RuleStateReplayVerifier {
    private RuleStateReplayVerifier() {}

    public static VerifiedRecovery replay(
            RulePackProvider provider, MatchRecoveryData recovery) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(recovery, "recovery");
        RuleStateSnapshot snapshot = recovery.snapshot();
        if (snapshot.schemaVersion()
                != recovery.match().binding().rulePack().stateSchemaVersion()) {
            throw conflict("Snapshot schema differs from pinned rule-pack provenance");
        }
        if (!sha256(snapshot.payload()).equals(snapshot.sha256())) {
            throw conflict("Snapshot payload SHA-256 is invalid");
        }
        RuleState state = Objects.requireNonNull(
                provider.restore(snapshot), "Provider restored a null state");
        long revision = recovery.snapshotStateRevision();
        long eventSequence = snapshot.sequence();
        for (RecoveredAction action : recovery.actionsAfterSnapshot()) {
            if (action.stateRevision() != revision + 1) {
                throw conflict("Recovered action revision is not contiguous");
            }
            if (action.firstEventSequence() != eventSequence + 1) {
                throw conflict("Recovered event sequence is not contiguous");
            }
            String before = provider.stateHash(state);
            if (!before.equals(action.beforeStateSha256())) {
                throw conflict("Recovered action before-state hash differs");
            }
            RuleTransition transition = Objects.requireNonNull(
                    provider.transition(state, action.actor(), action.action()),
                    "Provider returned a null replay transition");
            if (!transition.accepted()) {
                throw conflict("A committed action is now rejected by its pinned provider");
            }
            if (!sameEvents(transition.events(), action.expectedEvents())) {
                throw conflict("Provider events differ from the committed canonical events");
            }
            String after = provider.stateHash(transition.nextState());
            if (!after.equals(action.afterStateSha256())) {
                throw conflict("Recovered action after-state hash differs");
            }
            long expectedLast = action.firstEventSequence() + transition.events().size() - 1L;
            if (action.lastEventSequence() != expectedLast) {
                throw conflict("Recovered action event count differs from committed rows");
            }
            state = transition.nextState();
            revision = action.stateRevision();
            eventSequence = action.lastEventSequence();
        }
        if (eventSequence != recovery.match().lastCommittedSequence()) {
            throw conflict("Recovery did not reach the match committed boundary");
        }
        return new VerifiedRecovery(state, revision, eventSequence);
    }

    private static boolean sameEvents(List<RuleEvent> actual, List<RuleEvent> expected) {
        if (actual.size() != expected.size()) {
            return false;
        }
        for (int index = 0; index < actual.size(); index++) {
            RuleEvent left = actual.get(index);
            RuleEvent right = expected.get(index);
            if (!left.type().equals(right.type())
                    || !Arrays.equals(left.canonicalPayload(), right.canonicalPayload())) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(byte[] payload) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK has no SHA-256", impossible);
        }
    }

    private static PersistenceConflictException conflict(String message) {
        return new PersistenceConflictException(message);
    }
}
