package top.ellan.mahjong.presentation.projection.support;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import top.ellan.mahjong.application.interaction.InteractionHandle;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.spi.PlayerId;

/** Stable identifiers shared by independent scene contributors. */
public final class SceneNodeIdentity {
    private SceneNodeIdentity() {}

    public static InteractionHandle interaction(String material) {
        return new InteractionHandle(
                UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)));
    }

    public static SceneNodeId hud(PlayerId viewer, String namespace, String key) {
        String material = namespace + ':' + key;
        String stable = UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
        return new SceneNodeId(
                "hud/" + compact(viewer) + '/' + namespace + '/' + stable);
    }

    public static String compact(PlayerId player) {
        return player.toString().replace("-", "");
    }
}
