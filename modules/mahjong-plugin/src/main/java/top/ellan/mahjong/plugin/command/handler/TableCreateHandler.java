package top.ellan.mahjong.plugin.command.handler;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.ellan.mahjong.application.lobby.usecase.CreateLobbyRequest;
import top.ellan.mahjong.domain.TableAnchor;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.plugin.command.CommandSupport;
import top.ellan.mahjong.plugin.command.SubcommandHandler;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleId;

/** Creates an empty reusable four-seat table; players join through CE chairs or join command. */
public final class TableCreateHandler implements SubcommandHandler {
    private static final List<String> RULES = List.of("riichi", "mcr", "sichuan");
    private final CommandSupport support;

    public TableCreateHandler(CommandSupport support) {
        this.support = java.util.Objects.requireNonNull(support, "support");
    }

    @Override
    public Set<String> names() {
        return Set.of("create");
    }

    @Override
    public void execute(CommandSender sender, String[] arguments) {
        if (arguments.length < 2 || arguments.length > 3) {
            throw new IllegalArgumentException(
                    "Usage: /mahjong create <riichi|mcr|sichuan> [profile]");
        }
        Player owner = support.requirePlayer(sender);
        RuleId ruleId = CommandSupport.ruleId(arguments[1]);
        ProfileId profileId =
                arguments.length == 3
                        ? new ProfileId(arguments[2].toLowerCase(Locale.ROOT))
                        : CommandSupport.defaultProfile(ruleId);
        TableId tableId = TableId.random();
        Location source = owner.getLocation();
        Location location =
                new Location(
                        java.util.Objects.requireNonNull(source.getWorld(), "player world"),
                        Math.floor(source.getX()) + 0.5D,
                        Math.floor(source.getY()),
                        Math.floor(source.getZ()) + 0.5D,
                        source.getYaw(),
                        0.0F);
        TableAnchor anchor =
                new TableAnchor(
                        tableId,
                        location.getWorld().getUID().toString(),
                        location.getX(),
                        location.getY(),
                        location.getZ(),
                        location.getYaw(),
                        location.getPitch());
        CreateLobbyRequest request =
                new CreateLobbyRequest(
                        anchor,
                        new PlayerId(owner.getUniqueId()),
                        ruleId,
                        profileId,
                        Map.of(),
                        4);
        support.reply(sender, "Creating lobby " + tableId + " asynchronously...");
        support.complete(
                sender,
                support.runtime().createLobby(request, location),
                hosted ->
                        "CREATED "
                                + hosted.tableId()
                                + " rule="
                                + hosted.state().ruleId()
                                + " profile="
                                + hosted.state().profileId()
                                + "; click a CraftEngine chair to sit");
    }

    @Override
    public List<String> complete(CommandSender sender, String[] arguments) {
        return arguments.length == 2
                ? CommandSupport.filter(arguments[1], RULES)
                : List.of();
    }
}
