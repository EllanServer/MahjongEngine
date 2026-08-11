package top.ellan.mahjong.plugin.platform;

import java.util.EnumMap;
import java.util.Map;
import top.ellan.mahjong.platform.paper.feedback.PaperSoundProfile;
import top.ellan.mahjong.platform.paper.feedback.RuleSoundBinding;
import top.ellan.mahjong.platform.paper.feedback.RuleSoundProfiles;
import top.ellan.mahjong.plugin.config.PluginConfiguration;
import top.ellan.mahjong.presentation.asset.TableSceneAssets;
import top.ellan.mahjong.presentation.layout.TableGeometry;
import top.ellan.mahjong.runtime.resources.InspectedRuleResourcePack;
import top.ellan.mahjong.runtime.resources.RuleSoundCatalog;
import top.ellan.mahjong.runtime.resources.RuleSoundProfile;
import top.ellan.mahjong.spi.RulePresentationCueType;

/** Converts immutable plugin and rule-resource configuration into platform value objects. */
final class CraftEnginePlatformMappings {
    private CraftEnginePlatformMappings() {}

    static TableSceneAssets sceneAssets(PluginConfiguration.CraftEngineAssets configured) {
        return new TableSceneAssets(
                configured.table(),
                configured.seat(),
                configured.standingBack(),
                configured.flatBack(),
                configured.handHitbox(),
                configured.actionHitbox(),
                TableSceneAssets.actionInteractionVariants(configured.actionHitboxPrefix()));
    }

    static TableGeometry tableGeometry(PluginConfiguration.LayoutGeometry configured) {
        return new TableGeometry(
                configured.tileWidth(),
                configured.tileHeight(),
                configured.tileDepth(),
                configured.tileGap(),
                configured.surfaceHeight(),
                configured.handRadius(),
                configured.wallRadius(),
                configured.tableHalfLength(),
                configured.emphasisRaise(),
                configured.actionColumnSpacing(),
                configured.actionRowSpacing(),
                configured.secondaryActionOffset(),
                configured.maxHandTiles(),
                configured.maxDiscards(),
                configured.maxMeldTiles(),
                configured.maxPointSticks(),
                configured.maxAuxiliaryTiles(),
                configured.maxActions());
    }

    static RuleSoundBinding soundBinding(InspectedRuleResourcePack resource) {
        return new RuleSoundBinding(
                resource.ruleId(),
                resource.version(),
                resource.jarSha256(),
                soundProfiles(resource.sounds()));
    }

    static String ruleBundleFolder(InspectedRuleResourcePack resource) {
        String version = resource.version().replaceAll("[^0-9A-Za-z._-]", "_");
        return "mahjongpaper-rule-"
                + resource.ruleId().value()
                + '-'
                + version
                + '-'
                + resource.jarSha256().substring(0, 12);
    }

    private static RuleSoundProfiles soundProfiles(RuleSoundCatalog configured) {
        EnumMap<RulePresentationCueType, PaperSoundProfile> profiles =
                new EnumMap<>(RulePresentationCueType.class);
        configured.cues().forEach(
                (type, profile) -> profiles.put(type, soundProfile(profile)));
        return new RuleSoundProfiles(
                Map.copyOf(profiles),
                soundProfile(configured.openingDice()),
                soundProfile(configured.openingWall()));
    }

    private static PaperSoundProfile soundProfile(RuleSoundProfile configured) {
        return new PaperSoundProfile(configured.key(), configured.volume(), configured.pitch());
    }
}
