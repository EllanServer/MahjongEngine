package top.ellan.mahjong

import net.momirealms.sparrow.yaml.SparrowYaml
import net.momirealms.sparrow.yaml.route.Route
import top.ellan.mahjong.config.PluginSettings

internal fun pluginSettings(vararg values: Pair<String, Any?>): PluginSettings {
    val document = SparrowYaml.builder().build().load("")
    values.forEach { (path, value) ->
        document.set(Route.from(*path.split('.').toTypedArray()), value)
    }
    return PluginSettings.from(document)
}
