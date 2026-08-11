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
    void literalTranslationAndChoiceSuffixAreJsonEscaped() {
        ActionLabelNode label = new ActionLabelNode(
                new SceneNodeId("label/respond"),
                SceneVisibility.privateTo(VIEWER),
                "action.respond:pon:one.two",
                TRANSFORM,
                true);

        String json = PrivatePresentationAssets.labelJson(
                label,
                Locale.ENGLISH,
                (locale, key, fallback) -> "line\n\"quoted\"");

        assertTrue(json.contains("line\\n\\\"quoted\\\""));
        assertTrue(json.contains("one.two"));
    }
}
