package top.ellan.mahjong.plugin.command.handler;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.ellan.mahjong.application.lobby.runtime.HostedLobby;
import top.ellan.mahjong.application.projection.TableProjection;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.plugin.command.CommandSupport;
import top.ellan.mahjong.plugin.command.SubcommandHandler;
import top.ellan.mahjong.plugin.match.StartedRulePackMatch;
import top.ellan.mahjong.presentation.projection.LatestSceneProjector;
import top.ellan.mahjong.spi.PlayerId;

/**
 * Table display maintenance. Render re-emits the full scene for the caller's table, inspect
 * reports the applied scene and anchor diagnostics, and clear despawns the displays until the
 * next projection re-renders them.
 */
public final class TableRenderHandler implements SubcommandHandler {
    private static final Set<String> NAMES = Set.of("render", "inspect", "clear");

    private final CommandSupport support;

    public TableRenderHandler(CommandSupport support) {
        this.support = java.util.Objects.requireNonNull(support, "support");
    }

    @Override
    public Set<String> names() {
        return NAMES;
    }

    @Override
    public void execute(CommandSender sender, String[] arguments) {
        support.requireAdmin(sender);
        Player player = support.requirePlayer(sender);
        String command = arguments[0].toLowerCase(Locale.ROOT);
        if (arguments.length != 1) {
            throw CommandSupport.usage("/mahjong " + command);
        }
        PlayerId playerId = new PlayerId(player.getUniqueId());
        switch (command) {
            case "render" -> render(sender, playerId);
            case "inspect" -> inspect(sender, playerId);
            case "clear" -> clear(sender, playerId);
            default -> throw CommandSupport.usage("/mahjong <render|inspect|clear>");
        }
    }

    private void render(CommandSender sender, PlayerId playerId) {
        ResolvedTable table = tableOf(playerId);
        TableProjection projection =
                table.latest().orElseThrow(() -> CommandSupport.failure(
                        "mahjongpaper.command.render_no_scene",
                        "This table has no rendered scene to refresh yet."));
        LatestSceneProjector projector = support.runtime().sceneProjector();
        projector.refresh(table.tableId());
        projector.publish(projection);
        support.reply(sender, CommandSupport.message(
                "mahjongpaper.command.rendered", "Table re-rendered."));
    }

    private void inspect(CommandSender sender, PlayerId playerId) {
        ResolvedTable table = tableOf(playerId);
        TableId tableId = table.tableId();
        Optional<Location> anchor = support.runtime().tableAnchor(tableId);
        Optional<LatestSceneProjector.AppliedScene> scene =
                support.runtime().sceneProjector().appliedScene(tableId);
        support.reply(sender, CommandSupport.message(
                "mahjongpaper.command.inspect_summary",
                "Render inspect complete for table %s | anchor=%s | scene revision %s"
                        + " | nodes %s | interactions %s | backend %s",
                tableId,
                anchor.map(TableRenderHandler::formatLocation).orElse("unknown"),
                scene.map(latest -> Long.toString(latest.revision())).orElse("none"),
                scene.map(latest -> Integer.toString(latest.nodes())).orElse("0"),
                scene.map(latest -> Integer.toString(latest.interactionBindings())).orElse("0"),
                support.runtime().sceneBackend().ready() ? "ready" : "suspended"));
    }

    private void clear(CommandSender sender, PlayerId playerId) {
        TableId tableId = tableOf(playerId).tableId();
        support.runtime().sceneProjector().remove(tableId);
        support.reply(sender, CommandSupport.message(
                "mahjongpaper.command.cleared", "Displays cleared."));
    }

    private ResolvedTable tableOf(PlayerId playerId) {
        Optional<HostedLobby> lobby = support.runtime().lobbyTables().findByPlayer(playerId);
        if (lobby.isPresent()) {
            HostedLobby hosted = lobby.orElseThrow();
            return new ResolvedTable(hosted.tableId(), hosted.actor().latestProjection());
        }
        StartedRulePackMatch match =
                support.runtime()
                        .liveTables()
                        .findByPlayer(playerId)
                        .orElseThrow(() -> CommandSupport.failure(
                                "mahjongpaper.command.not_at_table",
                                "You do not belong to a table."));
        return new ResolvedTable(match.tableId(), match.actor().latestProjection());
    }

    private static String formatLocation(Location location) {
        return location.blockX() + "," + location.blockY() + "," + location.blockZ();
    }

    @Override
    public List<String> complete(CommandSender sender, String[] arguments) {
        return List.of();
    }

    private record ResolvedTable(TableId tableId, Optional<TableProjection> latest) {
        ResolvedTable {
            Objects.requireNonNull(tableId, "tableId");
            Objects.requireNonNull(latest, "latest");
        }
    }
}
