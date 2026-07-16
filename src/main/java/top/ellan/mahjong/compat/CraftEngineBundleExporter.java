package top.ellan.mahjong.compat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Objects;
import org.bukkit.plugin.Plugin;

final class CraftEngineBundleExporter {
    private static final String BUNDLE_ROOT = "craftengine/mahjongpaper";
    private static final String BUNDLE_INDEX = BUNDLE_ROOT + "/_bundle_index.txt";
    private static final String WRAPPED_BLOCK_STATE_HELPER_CLASS =
        "net.momirealms.craftengine.bukkit.compatibility.packetevents.WrappedBlockStateHelper";
    private static final String WRAPPED_BLOCK_STATE_REGISTER_METHOD = "register";
    private static final String GRIM_PACKETEVENTS_PACKAGE = "ac{}grim{}grimac{}shaded{}com{}github{}retrooper{}packetevents";
    private static final String[] VULCAN_PACKETEVENTS_PACKAGES = new String[] {
        "me{}frep{}vulcan{}shaded{}com{}github{}retrooper{}packetevents",
        "me{}frep{}vulcan{}libs{}com{}github{}retrooper{}packetevents"
    };

    private final CraftEngineBridgeContext context;
    private final String bundleFolderName;
    private final boolean injectAntiCheatPacketEventsMappings;
    private volatile boolean antiCheatMappingsInjected;

    CraftEngineBundleExporter(
        CraftEngineBridgeContext context,
        String bundleFolderName,
        boolean injectAntiCheatPacketEventsMappings
    ) {
        this.context = context;
        this.bundleFolderName = bundleFolderName;
        this.injectAntiCheatPacketEventsMappings = injectAntiCheatPacketEventsMappings;
    }

    void exportBundle(Plugin craftEngine) {
        try (InputStream indexStream = this.context.plugin().getResource(BUNDLE_INDEX)) {
            if (indexStream == null) {
                this.context.plugin().getLogger().warning("Missing bundled CraftEngine index. Skipping CraftEngine export.");
                return;
            }

            Path targetRoot = craftEngine.getDataFolder().toPath().resolve("resources").resolve(this.bundleFolderName);
            Collection<String> entries = new String(indexStream.readAllBytes(), StandardCharsets.UTF_8).lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
            for (String entry : entries) {
                this.copyBundledFile(entry, targetRoot.resolve(entry));
            }

            this.context.plugin().getLogger().info("CraftEngine detected. Exported MahjongPaper bundle to " + targetRoot.toAbsolutePath());
        } catch (IOException exception) {
            this.context.plugin().getLogger().warning("Failed to export MahjongPaper CraftEngine bundle: " + exception.getMessage());
        }
    }

    void injectAntiCheatMappingsIfNeeded(Plugin craftEngine) {
        if (!this.injectAntiCheatPacketEventsMappings || this.antiCheatMappingsInjected) {
            return;
        }
        if (craftEngine == null || !craftEngine.isEnabled()) {
            return;
        }

        boolean attempted = false;
        boolean injected = false;
        if (this.context.isPluginEnabled("packetevents")) {
            attempted = true;
            injected = this.invokeWrappedBlockStateRegister(craftEngine, null, "PacketEvents") || injected;
        }
        if (this.context.isPluginEnabled("GrimAC")) {
            attempted = true;
            injected = this.invokeWrappedBlockStateRegister(craftEngine, GRIM_PACKETEVENTS_PACKAGE, "GrimAC") || injected;
        }
        if (this.context.isPluginEnabled("Vulcan")) {
            attempted = true;
            for (String candidate : VULCAN_PACKETEVENTS_PACKAGES) {
                injected = this.invokeWrappedBlockStateRegister(craftEngine, candidate, "Vulcan") || injected;
                if (injected) {
                    break;
                }
            }
        }
        if (attempted && !injected) {
            this.context.plugin().debug().log(
                "lifecycle",
                "CraftEngine anti-cheat PacketEvents mapping injection was attempted but no compatible package was found."
            );
        }
        this.antiCheatMappingsInjected = injected;
    }

    private void copyBundledFile(String relativePath, Path targetPath) throws IOException {
        String resourcePath = BUNDLE_ROOT + "/" + relativePath;
        try (InputStream resourceStream = this.context.plugin().getResource(resourcePath)) {
            if (resourceStream == null) {
                throw new IOException("Missing bundled resource: " + resourcePath);
            }
            Files.createDirectories(Objects.requireNonNull(targetPath.getParent()));
            Files.copy(resourceStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean invokeWrappedBlockStateRegister(Plugin craftEngine, String packageName, String sourceName) {
        // CraftEngine 26.7 has no public API for registering PacketEvents' wrapped-block-state
        // package. Keep this optional anti-cheat compatibility hook isolated to this one method.
        try {
            ClassLoader classLoader = craftEngine.getClass().getClassLoader();
            Class<?> helperClass = Class.forName(WRAPPED_BLOCK_STATE_HELPER_CLASS, true, classLoader);
            Method registerMethod = helperClass.getMethod(WRAPPED_BLOCK_STATE_REGISTER_METHOD, String.class);
            registerMethod.invoke(null, packageName);
            this.context.plugin().debug().log(
                "lifecycle",
                "CraftEngine anti-cheat PacketEvents mapping injection succeeded via " + sourceName + "."
            );
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            this.context.plugin().debug().log(
                "lifecycle",
                "CraftEngine anti-cheat PacketEvents mapping injection skipped for "
                    + sourceName + ": " + exception.getClass().getSimpleName() + ": " + exception.getMessage()
            );
            return false;
        }
    }
}
