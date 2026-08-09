package top.ellan.mahjong.plugin.match;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import top.ellan.mahjong.domain.match.MatchBinding;
import top.ellan.mahjong.domain.match.MatchId;
import top.ellan.mahjong.domain.table.ParticipantRole;
import top.ellan.mahjong.spi.MatchPlayer;
import top.ellan.mahjong.spi.MatchSetup;
import top.ellan.mahjong.spi.RulePackProvider;
import top.ellan.mahjong.spi.RulePackRef;
import top.ellan.mahjong.spi.RuleState;
import top.ellan.mahjong.spi.RuleStateSnapshot;

/** Encapsulates rule-state initialization and deterministic provenance hashing. */
final class RuleMatchStateFactory {
    private RuleMatchStateFactory() {}

    static Initialized initialize(
            NewRulePackMatch command,
            RulePackProvider provider,
            RulePackRef reference,
            Clock clock) {
        MatchSetup setup =
                new MatchSetup(
                        command.profileId(),
                        command.seed(),
                        command.participants().stream()
                                .filter(
                                        participant ->
                                                participant.role() == ParticipantRole.PLAYER)
                                .sorted(
                                        Comparator.comparing(
                                                participant ->
                                                        participant.seat().orElseThrow()))
                                .map(
                                        participant ->
                                                new MatchPlayer(
                                                        participant.playerId(),
                                                        participant.seat().orElseThrow()))
                                .toList(),
                        command.configuration());
        MatchBinding binding =
                new MatchBinding(
                        MatchId.random(),
                        reference,
                        command.profileId(),
                        configurationHash(command.configuration()),
                        Instant.now(clock));
        if (provider.descriptor().profiles().stream()
                .noneMatch(profile -> profile.id().equals(setup.profileId()))) {
            throw new IllegalArgumentException(
                    "Rule pack does not support profile " + setup.profileId());
        }
        RuleState state = Objects.requireNonNull(provider.createMatch(setup), "initial rule state");
        RuleStateSnapshot snapshot = provider.snapshot(state, 0);
        verifySnapshot(snapshot, reference);
        return new Initialized(binding, state, snapshot);
    }

    private static void verifySnapshot(RuleStateSnapshot snapshot, RulePackRef reference) {
        Objects.requireNonNull(snapshot, "provider returned null snapshot");
        if (snapshot.sequence() != 0
                || snapshot.schemaVersion() != reference.stateSchemaVersion()
                || !snapshot.sha256().equals(sha256(snapshot.payload()))) {
            throw new IllegalStateException("Provider returned an invalid initial snapshot");
        }
    }

    private static String configurationHash(Map<String, String> configuration) {
        MessageDigest digest = digest();
        configuration.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        entry -> {
                            updateLengthPrefixed(digest, entry.getKey());
                            updateLengthPrefixed(digest, entry.getValue());
                        });
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateLengthPrefixed(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String sha256(byte[] payload) {
        return HexFormat.of().formatHex(digest().digest(payload));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK has no SHA-256", impossible);
        }
    }

    record Initialized(
            MatchBinding binding,
            RuleState state,
            RuleStateSnapshot snapshot) {
        Initialized {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }
}
