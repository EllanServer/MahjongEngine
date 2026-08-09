package top.ellan.mahjong.craftengine.privateview;

import java.util.Optional;
import org.bukkit.entity.Player;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.presentation.node.CameraNode;
import top.ellan.mahjong.spi.PlayerId;

/** Private-scene callbacks required by the isolated overhead camera lifecycle. */
interface CameraProjectionCallbacks {
    Optional<CameraNode> cameraNode(TableId tableId, PlayerId viewer);

    void hideForCamera(Player player, PlayerId viewer);

    void restoreAfterCamera(Player player, PlayerId viewer);
}
