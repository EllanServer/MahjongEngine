package top.ellan.mahjong.application.lobby.command;

import java.util.Map;
import java.util.Objects;
import top.ellan.mahjong.domain.SeatPresence;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.SeatId;

/** Closed command family accepted by the pre-match actor. */
public sealed interface LobbyCommand
        permits LobbyCommand.JoinSeat,
                LobbyCommand.Leave,
                LobbyCommand.Spectate,
                LobbyCommand.Unspectate,
                LobbyCommand.ToggleReady,
                LobbyCommand.Start,
                LobbyCommand.ChangeRules,
                LobbyCommand.SetPresence {
    PlayerId actor();

    record JoinSeat(PlayerId actor, SeatId seatId) implements LobbyCommand {
        public JoinSeat {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(seatId, "seatId");
        }
    }

    record Leave(PlayerId actor) implements LobbyCommand {
        public Leave {
            Objects.requireNonNull(actor, "actor");
        }
    }

    record Spectate(PlayerId actor) implements LobbyCommand {
        public Spectate {
            Objects.requireNonNull(actor, "actor");
        }
    }

    record Unspectate(PlayerId actor) implements LobbyCommand {
        public Unspectate {
            Objects.requireNonNull(actor, "actor");
        }
    }

    record ToggleReady(PlayerId actor) implements LobbyCommand {
        public ToggleReady {
            Objects.requireNonNull(actor, "actor");
        }
    }

    record Start(PlayerId actor) implements LobbyCommand {
        public Start {
            Objects.requireNonNull(actor, "actor");
        }
    }

    record ChangeRules(
            PlayerId actor,
            RuleId ruleId,
            ProfileId profileId,
            Map<String, String> configuration)
            implements LobbyCommand {
        public ChangeRules {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(profileId, "profileId");
            configuration = Map.copyOf(Objects.requireNonNull(configuration, "configuration"));
        }
    }

    record SetPresence(PlayerId actor, SeatPresence presence) implements LobbyCommand {
        public SetPresence {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(presence, "presence");
        }
    }
}
