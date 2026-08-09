package top.ellan.mahjong.plugin;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import top.ellan.mahjong.application.concurrent.FairRuleExecutor;
import top.ellan.mahjong.application.persistence.PersistenceOutbox;
import top.ellan.mahjong.application.projection.SceneProjectionPort;
import top.ellan.mahjong.application.security.SecureActionTokenIssuer;
import top.ellan.mahjong.application.table.actor.TableActor;
import top.ellan.mahjong.application.table.TableActorConfig;
import top.ellan.mahjong.application.table.TableActorRegistry;
import top.ellan.mahjong.application.table.TableActionEndpoint;
import top.ellan.mahjong.application.concurrent.TaskScheduler;
import top.ellan.mahjong.domain.CompetitionRef;
import top.ellan.mahjong.domain.MatchBinding;
import top.ellan.mahjong.domain.MatchId;
import top.ellan.mahjong.domain.ParticipantRole;
import top.ellan.mahjong.domain.TableAggregate;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.domain.TableLifecycle;
import top.ellan.mahjong.domain.TableParticipant;
import top.ellan.mahjong.persistence.sql.JdbcEventStore;
import top.ellan.mahjong.persistence.sql.JdbcMatchRepository;
import top.ellan.mahjong.persistence.sql.MatchInstanceRecord;
import top.ellan.mahjong.persistence.sql.MatchRecoveryData;
import top.ellan.mahjong.persistence.sql.RuleStateReplayVerifier;
import top.ellan.mahjong.persistence.sql.VerifiedRecovery;
import top.ellan.mahjong.runtime.RulePackRuntime;
import top.ellan.mahjong.spi.MatchPlayer;
import top.ellan.mahjong.spi.MatchSetup;
import top.ellan.mahjong.spi.RulePackProvider;
import top.ellan.mahjong.spi.RulePackRef;
import top.ellan.mahjong.spi.RuleState;
import top.ellan.mahjong.spi.RuleStateSnapshot;

/** Creates and recovers actors only after provenance and durable boundaries are established. */
public final class RulePackMatchCoordinator {
    private final Executor actorDispatcher;
    private final Executor ioExecutor;
    private final FairRuleExecutor rules;
    private final TaskScheduler deadlines;
    private final TableActorRegistry actors;
    private final JdbcMatchRepository matches;
    private final JdbcEventStore events;
    private final RulePackRuntime rulePacks;
    private final SceneProjectionPort projector;
    private final Clock clock;

    public RulePackMatchCoordinator(
            Executor actorDispatcher,
            Executor ioExecutor,
            FairRuleExecutor rules,
            TaskScheduler deadlines,
            TableActorRegistry actors,
            JdbcMatchRepository matches,
            JdbcEventStore events,
            RulePackRuntime rulePacks,
            SceneProjectionPort projector,
            Clock clock) {
        this.actorDispatcher = Objects.requireNonNull(actorDispatcher, "actorDispatcher");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
        this.actors = Objects.requireNonNull(actors, "actors");
        this.matches = Objects.requireNonNull(matches, "matches");
        this.events = Objects.requireNonNull(events, "events");
        this.rulePacks = Objects.requireNonNull(rulePacks, "rulePacks");
        this.projector = Objects.requireNonNull(projector, "projector");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CompletionStage<StartedRulePackMatch> create(NewRulePackMatch command) {
        return create(command, Optional.empty(), false);
    }

    public CompletionStage<StartedRulePackMatch> createFromLobby(
            NewRulePackMatch command, TableActionEndpoint lobbyEndpoint) {
        Objects.requireNonNull(lobbyEndpoint, "lobbyEndpoint");
        return create(command, Optional.of(lobbyEndpoint), true);
    }

    private CompletionStage<StartedRulePackMatch> create(
            NewRulePackMatch command,
            Optional<TableActionEndpoint> replacedEndpoint,
            boolean consumeLobby) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(replacedEndpoint, "replacedEndpoint");
        if (!events.available()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "Database is unavailable; new recoverable matches are disabled"));
        }
        RulePackProvider provider =
                rulePacks
                        .providerForNewMatch(command.ruleId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No active rule pack for " + command.ruleId()));
        RulePackRef reference = rulePacks.activeReference(command.ruleId()).orElseThrow();
        List<MatchPlayer> players =
                command.participants().stream()
                        .filter(participant -> participant.role() == ParticipantRole.PLAYER)
                        .sorted(Comparator.comparing(participant -> participant.seat().orElseThrow()))
                        .map(
                                participant ->
                                        new MatchPlayer(
                                                participant.playerId(),
                                                participant.seat().orElseThrow()))
                        .toList();
        MatchSetup setup =
                new MatchSetup(
                        command.profileId(),
                        command.seed(),
                        players,
                        command.configuration());
        Instant createdAt = Instant.now(clock);
        MatchBinding binding =
                new MatchBinding(
                        MatchId.random(),
                        reference,
                        command.profileId(),
                        configurationHash(command.configuration()),
                        createdAt);
        return rules.submit(
                        command.ruleId(),
                        () -> createInitialRuleState(provider, reference, setup))
                .thenCompose(
                        created ->
                                persistAndStart(
                                        command,
                                        provider,
                                        binding,
                                        created.state(),
                                        created.snapshot(),
                                        0,
                                        0,
                                        replacedEndpoint,
                                        consumeLobby));
    }

    public CompletionStage<StartedRulePackMatch> recover(
            MatchId matchId, CompetitionRef competitionRef) {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(competitionRef, "competitionRef");
        CompletionStage<VerifiedInput> verified =
                CompletableFuture.supplyAsync(
                                () -> {
                                    try {
                                        MatchRecoveryData data = matches.recover(matchId);
                                        RulePackProvider provider =
                                                rulePacks.providerForPinnedMatch(
                                                        data.match().binding().rulePack());
                                        return new RecoveryInput(data, provider);
                                    } catch (Exception failure) {
                                        throw new CompletionException(failure);
                                    }
                                },
                                ioExecutor)
                        .thenCompose(
                                input ->
                                        rules.submit(
                                                        input.data()
                                                                .match()
                                                                .binding()
                                                                .rulePack()
                                                                .ruleId(),
                                                        () ->
                                                                RuleStateReplayVerifier.replay(
                                                                        input.provider(),
                                                                        input.data()))
                                                .thenApply(
                                                        result ->
                                                                new VerifiedInput(input, result)));
        return markRulePackBlockedOnFailure(matchId, verified)
                .thenCompose(
                        input -> restoreActor(input.input(), input.verified(), competitionRef));
    }

    private CompletionStage<StartedRulePackMatch> persistAndStart(
            NewRulePackMatch command,
            RulePackProvider provider,
            MatchBinding binding,
            RuleState state,
            RuleStateSnapshot initial,
            long stateRevision,
            long eventSequence,
            Optional<TableActionEndpoint> replacedEndpoint,
            boolean consumeLobby) {
        MatchInstanceRecord metadata =
                new MatchInstanceRecord(
                        binding,
                        command.tableId(),
                        TableLifecycle.ACTIVE,
                        binding.createdAt(),
                        eventSequence);
        return CompletableFuture.runAsync(
                        () -> {
                            try {
                                if (consumeLobby) {
                                    matches.createRecoverableMatchFromLobby(
                                            metadata,
                                            command.participants(),
                                            initial,
                                            command.anchor());
                                } else {
                                    matches.createRecoverableMatch(
                                            metadata,
                                            command.participants(),
                                            initial,
                                            command.anchor());
                                }
                            } catch (java.sql.SQLException failure) {
                                throw new CompletionException(failure);
                            }
                        },
                        ioExecutor)
                .thenCompose(
                        ignored ->
                                createActorOrMarkReview(
                                        command.tableId(),
                                        command.participants(),
                                        command.competitionRef(),
                                        provider,
                                        binding,
                                        state,
                                        TableLifecycle.ACTIVE,
                                        stateRevision,
                                        eventSequence,
                                        replacedEndpoint));
    }

    private CompletionStage<StartedRulePackMatch> restoreActor(
            RecoveryInput input,
            VerifiedRecovery verified,
            CompetitionRef competitionRef) {
        MatchRecoveryData data = input.data();
        if (data.participants().isEmpty()) {
            return CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            matches.updateStatus(
                                    data.match().binding().matchId(),
                                    TableLifecycle.NEEDS_ADMIN_REVIEW,
                                    Instant.now(clock));
                        } catch (java.sql.SQLException failure) {
                            throw new CompletionException(failure);
                        }
                        throw new CompletionException(
                                new IllegalStateException(
                                        "Recovered match has no participants"));
                    },
                    ioExecutor);
        }
        return CompletableFuture.runAsync(
                        () -> {
                            try {
                                matches.updateStatus(
                                        data.match().binding().matchId(),
                                        TableLifecycle.ACTIVE,
                                        Instant.now(clock));
                            } catch (java.sql.SQLException failure) {
                                throw new CompletionException(failure);
                            }
                        },
                        ioExecutor)
                .thenCompose(
                        ignored ->
                                createActorOrMarkReview(
                                        data.match().tableId(),
                                        data.participants(),
                                        competitionRef,
                                        input.provider(),
                                        data.match().binding(),
                                        verified.state(),
                                        TableLifecycle.ACTIVE,
                                        verified.stateRevision(),
                                        verified.eventSequence(),
                                        Optional.empty()));
    }

    private CompletionStage<StartedRulePackMatch> createActorOrMarkReview(
            TableId tableId,
            List<TableParticipant> participants,
            CompetitionRef competitionRef,
            RulePackProvider provider,
            MatchBinding binding,
            RuleState state,
            TableLifecycle lifecycle,
            long stateRevision,
            long eventSequence,
            Optional<TableActionEndpoint> replacedEndpoint) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        TableActor actor =
                                createActor(
                                        tableId,
                                        participants,
                                        competitionRef,
                                        provider,
                                        binding,
                                        state,
                                        lifecycle,
                                        stateRevision,
                                        eventSequence,
                                        replacedEndpoint);
                        return new StartedRulePackMatch(binding, tableId, participants, actor);
                    } catch (RuntimeException failure) {
                        try {
                            matches.updateStatus(
                                    binding.matchId(),
                                    TableLifecycle.NEEDS_ADMIN_REVIEW,
                                    Instant.now(clock));
                        } catch (java.sql.SQLException updateFailure) {
                            failure.addSuppressed(updateFailure);
                        }
                        throw failure;
                    }
                },
                ioExecutor);
    }

    private TableActor createActor(
            TableId tableId,
            List<TableParticipant> participants,
            CompetitionRef competitionRef,
            RulePackProvider provider,
            MatchBinding binding,
            RuleState state,
            TableLifecycle lifecycle,
            long stateRevision,
            long eventSequence,
            Optional<TableActionEndpoint> replacedEndpoint) {
        TableAggregate aggregate =
                new TableAggregate(
                        tableId,
                        stateRevision,
                        lifecycle,
                        participants,
                        Optional.of(binding),
                        competitionRef);
        PersistenceOutbox outbox =
                new PersistenceOutbox(
                        binding.matchId(), eventSequence, events, deadlines, clock);
        TableActor actor =
                new TableActor(
                        actorDispatcher,
                        rules,
                        provider,
                        outbox,
                        projector,
                        new SecureActionTokenIssuer(),
                        clock,
                        TableActorConfig.DEFAULT,
                        aggregate,
                        state,
                        eventSequence);
        try {
            if (replacedEndpoint.isPresent()) {
                if (!actors.replace(tableId, replacedEndpoint.orElseThrow(), actor)) {
                    throw new IllegalStateException("Lobby actor was replaced during activation");
                }
            } else {
                actors.register(tableId, actor);
            }
            actor.start();
            return actor;
        } catch (RuntimeException failure) {
            actors.remove(tableId, actor);
            actor.close();
            throw failure;
        }
    }

    private <T> CompletionStage<T> markRulePackBlockedOnFailure(
            MatchId matchId, CompletionStage<T> source) {
        CompletableFuture<T> result = new CompletableFuture<>();
        source.whenComplete(
                (value, failure) -> {
                    if (failure == null) {
                        result.complete(value);
                        return;
                    }
                    Throwable cause = unwrap(failure);
                    CompletableFuture.runAsync(
                                    () -> {
                                        try {
                                            matches.updateStatus(
                                                    matchId,
                                                    TableLifecycle.BLOCKED_RULE_PACK,
                                                    Instant.now(clock));
                                        } catch (java.sql.SQLException updateFailure) {
                                            cause.addSuppressed(updateFailure);
                                        }
                                    },
                                    ioExecutor)
                            .whenComplete(
                                    (ignored, updateFailure) -> {
                                        if (updateFailure != null) {
                                            cause.addSuppressed(unwrap(updateFailure));
                                        }
                                        result.completeExceptionally(cause);
                                    });
                });
        return result;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void verifySnapshot(
            RuleStateSnapshot snapshot, RulePackRef reference, long expectedSequence) {
        Objects.requireNonNull(snapshot, "provider returned null snapshot");
        if (snapshot.sequence() != expectedSequence
                || snapshot.schemaVersion() != reference.stateSchemaVersion()
                || !snapshot.sha256().equals(sha256(snapshot.payload()))) {
            throw new IllegalStateException("Provider returned an invalid initial snapshot");
        }
    }

    private static CreatedRuleState createInitialRuleState(
            RulePackProvider provider, RulePackRef reference, MatchSetup setup) {
        if (provider.descriptor().profiles().stream()
                .noneMatch(profile -> profile.id().equals(setup.profileId()))) {
            throw new IllegalArgumentException(
                    "Rule pack does not support profile " + setup.profileId());
        }
        RuleState state = Objects.requireNonNull(provider.createMatch(setup), "initial rule state");
        RuleStateSnapshot snapshot = provider.snapshot(state, 0);
        verifySnapshot(snapshot, reference, 0);
        return new CreatedRuleState(state, snapshot);
    }

    private static String configurationHash(java.util.Map<String, String> configuration) {
        MessageDigest digest = digest();
        configuration.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(
                        entry -> {
                            updateLengthPrefixed(digest, entry.getKey());
                            updateLengthPrefixed(digest, entry.getValue());
                        });
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static void updateLengthPrefixed(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String sha256(byte[] payload) {
        return java.util.HexFormat.of().formatHex(digest().digest(payload));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK has no SHA-256", impossible);
        }
    }

    private record RecoveryInput(MatchRecoveryData data, RulePackProvider provider) {
        private RecoveryInput {
            Objects.requireNonNull(data, "data");
            Objects.requireNonNull(provider, "provider");
        }
    }

    private record CreatedRuleState(RuleState state, RuleStateSnapshot snapshot) {
        private CreatedRuleState {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    private record VerifiedInput(RecoveryInput input, VerifiedRecovery verified) {
        private VerifiedInput {
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(verified, "verified");
        }
    }
}
