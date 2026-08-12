package top.ellan.mahjong.plugin.dialog;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import top.ellan.mahjong.application.lobby.runtime.HostedLobby;
import top.ellan.mahjong.domain.lobby.LobbySeat;
import top.ellan.mahjong.domain.lobby.TableLobby;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.plugin.MahjongRuntime;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RulePackDescriptor;
import top.ellan.mahjong.spi.RuleProfileDescriptor;

/** Secondary lobby pages: seat administration and rule-pack-described settings. */
final class LobbySetupDialogs {
    private final MahjongDialogService host;
    private final MahjongRuntime runtime;

    LobbySetupDialogs(MahjongDialogService host, MahjongRuntime runtime) {
        this.host = Objects.requireNonNull(host, "host");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    void openSeats(Player player, TableId tableId) {
        HostedLobby hosted = runtime.lobbyTables().find(tableId).orElse(null);
        if (hosted == null) {
            host.openTable(player, Optional.of(tableId));
            return;
        }
        TableLobby state = hosted.state();
        PlayerId viewer = MahjongDialogService.id(player);
        boolean owner = state.ownerId().equals(viewer);
        boolean seated = state.seatOf(viewer).isPresent();
        List<ActionButton> actions = new ArrayList<>();
        for (LobbySeat seat : state.seats()) {
            String seatName = host.seatName(player, seat.seatId());
            if (seat.occupant().isEmpty()) {
                if (!seated) {
                    actions.add(host.button(player, "mahjongpaper.dialog.button.join_seat",
                            "Sit · %s", NamedTextColor.GREEN, host.callback(ignored -> host.lobbyAction(
                                    player, runtime.lobbyUseCases().join(
                                            tableId, seat.seatId(), viewer), tableId, true)),
                            seatName));
                }
                if (owner) {
                    actions.add(host.button(player, "mahjongpaper.dialog.button.add_bot",
                            "Add bot · %s", NamedTextColor.YELLOW,
                            host.callback(ignored -> host.lobbyAction(player,
                                    runtime.lobbyUseCases().addBot(viewer, seat.seatId()),
                                    tableId, true)), seatName));
                }
            } else if (owner && state.isBotSeat(seat)) {
                actions.add(host.button(player, "mahjongpaper.dialog.button.remove_bot",
                        "Remove bot · %s", NamedTextColor.RED,
                        host.callback(ignored -> host.lobbyAction(player,
                                runtime.lobbyUseCases().removeBot(viewer, seat.seatId()),
                                tableId, true)), seatName));
            } else if (owner && !seat.occupant().orElseThrow().equals(viewer)) {
                actions.add(host.button(player, "mahjongpaper.dialog.button.transfer_owner",
                        "Transfer owner · %s", NamedTextColor.GOLD,
                        host.callback(ignored -> host.lobbyAction(player,
                                runtime.lobbyUseCases().transferOwner(viewer, seat.seatId()),
                                tableId, true)), seatName));
            }
        }
        actions.add(host.backButton(player,
                ignored -> host.openTable(player, Optional.of(tableId))));
        host.show(player, DialogUi.multi(
                host.title(player, "mahjongpaper.dialog.seats.title", "Seats · %s",
                        DialogContent.shortId(tableId)),
                host.lobbySeatLines(player, state), List.of(), actions, 2,
                host.closeButton(player)));
    }

    void openRules(Player player, TableId tableId) {
        HostedLobby hosted = runtime.lobbyTables().find(tableId).orElse(null);
        if (hosted == null) {
            host.openTable(player, Optional.of(tableId));
            return;
        }
        TableLobby state = hosted.state();
        boolean owner = state.ownerId().equals(MahjongDialogService.id(player));
        List<ActionButton> actions = new ArrayList<>();
        if (owner) {
            for (RulePackDescriptor descriptor : runtime.ruleDescriptors()) {
                for (RuleProfileDescriptor profile : descriptor.profiles()) {
                    String label = host.ruleName(player, descriptor.ruleId())
                            + " · "
                            + host.profileName(player, profile.id(), profile.displayName());
                    actions.add(host.button(player, label, NamedTextColor.GOLD,
                            host.callback(ignored -> openRuleEditor(
                                    player, tableId, descriptor, profile))));
                }
            }
        }
        actions.add(host.backButton(player,
                ignored -> host.openTable(player, Optional.of(tableId))));
        Component body = host.labeled(player, "mahjongpaper.dialog.rules.current", "Current rules",
                        host.ruleProfile(player, state.ruleId(), state.profileId()),
                        NamedTextColor.WHITE)
                .appendNewline().append(Component.text(
                        DialogContent.configurationText(state.configuration()), NamedTextColor.GRAY));
        if (!owner) {
            body = body.appendNewline().append(host.textComponent(player,
                    "mahjongpaper.dialog.rules.owner_only",
                    "Only the table owner can change rules.", NamedTextColor.YELLOW));
        }
        host.show(player, DialogUi.multi(
                host.title(player, "mahjongpaper.dialog.rules.title", "Rule settings"),
                body, List.of(), actions, 2, host.closeButton(player)));
    }

    private void openRuleEditor(
            Player player, TableId tableId, RulePackDescriptor descriptor,
            RuleProfileDescriptor profile) {
        HostedLobby hosted = runtime.lobbyTables().find(tableId).orElse(null);
        if (hosted == null
                || !hosted.state().ownerId().equals(MahjongDialogService.id(player))) {
            openRules(player, tableId);
            return;
        }
        RuleConfigurationSchema schema;
        try {
            schema = RuleConfigurationSchema.parse(profile.configurationSchemaJson());
        } catch (IllegalArgumentException failure) {
            host.tell(player, "mahjongpaper.dialog.rules.schema_error",
                    "This rule pack published an invalid settings schema.", NamedTextColor.RED);
            return;
        }
        Map<String, String> current = hosted.state().ruleId().equals(descriptor.ruleId())
                        && hosted.state().profileId().equals(profile.id())
                ? hosted.state().configuration() : Map.of();
        List<DialogInput> inputs = schema.fields().stream()
                .map(field -> field.input(host.textComponent(player,
                                "mahjongpaper.dialog.config." + field.key(), field.title(),
                                NamedTextColor.WHITE),
                        current.get(field.key())))
                .toList();
        Component body = Component.text(
                        host.profileName(player, profile.id(), profile.displayName()),
                        NamedTextColor.GOLD)
                .appendNewline().append(host.textComponent(player,
                        "mahjongpaper.dialog.rules.ready_reset",
                        "Applying settings resets every human ready state.",
                        NamedTextColor.YELLOW));
        List<ActionButton> actions = List.of(
                host.button(player, "mahjongpaper.dialog.button.apply", "Apply",
                        NamedTextColor.GREEN, (response, audience) -> applyRules(
                                player, tableId, descriptor, profile, schema, response)),
                host.backButton(player, ignored -> openRules(player, tableId)));
        host.show(player, DialogUi.multi(
                host.title(player, "mahjongpaper.dialog.rules.edit_title", "Edit · %s",
                        host.ruleName(player, descriptor.ruleId())), body, inputs, actions, 2,
                host.closeButton(player)));
    }

    private void applyRules(
            Player player, TableId tableId, RulePackDescriptor descriptor,
            RuleProfileDescriptor profile, RuleConfigurationSchema schema,
            DialogResponseView response) {
        try {
            Map<String, String> values = schema.read(response);
            host.lobbyAction(player, runtime.lobbyUseCases().changeRules(
                    MahjongDialogService.id(player), descriptor.ruleId(), profile.id(), values),
                    tableId, true);
        } catch (IllegalArgumentException failure) {
            host.tell(player, "mahjongpaper.dialog.rules.invalid_value", "Invalid setting: %s",
                    NamedTextColor.RED, failure.getMessage());
            openRuleEditor(player, tableId, descriptor, profile);
        }
    }
}
