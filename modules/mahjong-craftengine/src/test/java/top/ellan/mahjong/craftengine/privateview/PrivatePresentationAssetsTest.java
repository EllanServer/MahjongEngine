package top.ellan.mahjong.craftengine.privateview;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.presentation.node.ActionLabelNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.presentation.node.SceneTransform;
import top.ellan.mahjong.presentation.node.SceneVisibility;
import top.ellan.mahjong.spi.PlayerId;

class PrivatePresentationAssetsTest {
    private static final PlayerId VIEWER = new PlayerId(UUID.randomUUID());
    private static final SceneTransform TRANSFORM =
            new SceneTransform(0, 1, 0, 0, 0, 0, 1);

    @Test
    void labelIsResolvedServerSideInsteadOfDependingOnClientTranslations() {
        ActionLabelNode label = new ActionLabelNode(
                new SceneNodeId("label/ready"),
                SceneVisibility.privateTo(VIEWER),
                "action.ready",
                TRANSFORM,
                false);

        String json = PrivatePresentationAssets.labelJson(
                label,
                Locale.SIMPLIFIED_CHINESE,
                (locale, key, fallback) -> "准备");

        assertTrue(json.contains("准备"));
        assertFalse(json.contains("translate"));
        assertFalse(json.contains("action.ready"));
    }

    @Test
    void translationIsSingleLineAndChoiceSuffixIsJsonEscaped() {
        ActionLabelNode label = new ActionLabelNode(
                new SceneNodeId("label/respond"),
                SceneVisibility.privateTo(VIEWER),
                "action.respond:pon:one.two",
                TRANSFORM,
                true);

        String json = PrivatePresentationAssets.labelJson(
                label,
                Locale.ENGLISH,
                (locale, key, fallback) -> "line\n\"q\"");

        assertTrue(json.contains("line \\\"q\\\" one.two"));
        assertFalse(json.contains("\\n"));
        assertTrue(json.contains("one.two"));
    }

    @Test
    void semanticTileArgumentsAreLocalizedAsOneV15StyleLabel() {
        ActionLabelNode label = new ActionLabelNode(
                new SceneNodeId("label/chii"),
                SceneVisibility.privateTo(VIEWER),
                "action.chii:tile.m2:tile.m3",
                TRANSFORM,
                false);

        String json = PrivatePresentationAssets.labelJson(
                label,
                Locale.SIMPLIFIED_CHINESE,
                (locale, key, fallback) -> switch (key) {
                    case "mahjongpaper.action.chii" -> "吃";
                    case "mahjongpaper.tile.m2" -> "二万";
                    case "mahjongpaper.tile.m3" -> "三万";
                    default -> fallback;
                });

        assertTrue(json.contains("吃 二万 三万"));
        assertFalse(json.contains("tile.m2"));
        assertFalse(json.contains("translate"));
    }
}
