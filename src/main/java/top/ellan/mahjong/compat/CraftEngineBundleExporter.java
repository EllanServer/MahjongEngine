package top.ellan.mahjong.compat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.bukkit.plugin.Plugin;

final class CraftEngineBundleExporter {
    private static final String BUNDLE_ROOT = "craftengine/mahjongpaper";
    private static final String BUNDLE_INDEX = BUNDLE_ROOT + "/_bundle_index.txt";
    private static final String BUNDLE_MANIFEST = "_bundle_manifest.sha256";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
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
                throw new IOException("Missing bundled CraftEngine index");
            }

            Path targetRoot = craftEngine.getDataFolder().toPath().resolve("resources").resolve(this.bundleFolderName);
            Collection<String> entries = new String(indexStream.readAllBytes(), StandardCharsets.UTF_8).lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(CraftEngineBundleExporter::validatedRelativePath)
                .toList();
            if (!entries.contains(BUNDLE_MANIFEST)) {
                throw new IOException("CraftEngine bundle index has no SHA-256 manifest");
            }
            Map<String, String> expectedHashes = this.readManifest();
            Set<String> contentEntries = entries.stream()
                .filter(entry -> !entry.equals(BUNDLE_MANIFEST))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!expectedHashes.keySet().equals(contentEntries)) {
                throw new IOException("CraftEngine bundle index/manifest file sets differ");
            }
            this.installAtomically(targetRoot, entries, expectedHashes);

            this.context.plugin().getLogger().info("CraftEngine detected. Exported MahjongPaper bundle to " + targetRoot.toAbsolutePath());
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Failed to verify and atomically install the MahjongPaper CraftEngine bundle",
                exception
            );
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

    private Map<String, String> readManifest() throws IOException {
        try (InputStream manifestStream = this.context.plugin().getResource(
            BUNDLE_ROOT + "/" + BUNDLE_MANIFEST
        )) {
            if (manifestStream == null) {
                throw new IOException("Missing bundled CraftEngine SHA-256 manifest");
            }
            Map<String, String> result = new LinkedHashMap<>();
            for (String line : new String(
                manifestStream.readAllBytes(), StandardCharsets.UTF_8
            ).lines().toList()) {
                if (line.isBlank()) {
                    continue;
                }
                int separator = line.indexOf("  ");
                if (separator != 64) {
                    throw new IOException("Malformed CraftEngine bundle manifest line");
                }
                String hash = line.substring(0, separator);
                String relativePath = validatedRelativePath(line.substring(separator + 2));
                if (!SHA256.matcher(hash).matches() || result.put(relativePath, hash) != null) {
                    throw new IOException("Invalid or duplicate CraftEngine bundle manifest entry");
                }
            }
            return Map.copyOf(result);
        }
    }

    private void installAtomically(
        Path targetRoot,
        Collection<String> entries,
        Map<String, String> expectedHashes
    ) throws IOException {
        Path parent = Objects.requireNonNull(targetRoot.getParent()).toAbsolutePath().normalize();
        Files.createDirectories(parent);
        String nonce = UUID.randomUUID().toString();
        Path staging = parent.resolve("." + targetRoot.getFileName() + ".staging-" + nonce);
        Path backup = parent.resolve("." + targetRoot.getFileName() + ".backup-" + nonce);
        boolean previousMoved = false;
        boolean installed = false;
        try {
            Files.createDirectory(staging);
            for (String entry : entries) {
                this.copyBundledFile(entry, staging.resolve(entry));
            }
            for (Map.Entry<String, String> expected : expectedHashes.entrySet()) {
                String actual = sha256(staging.resolve(expected.getKey()));
                if (!actual.equals(expected.getValue())) {
                    throw new IOException("CraftEngine bundle hash mismatch: " + expected.getKey());
                }
            }
            if (Files.exists(targetRoot)) {
                Files.move(targetRoot, backup, StandardCopyOption.ATOMIC_MOVE);
                previousMoved = true;
            }
            Files.move(staging, targetRoot, StandardCopyOption.ATOMIC_MOVE);
            installed = true;
        } catch (IOException failure) {
            if (previousMoved && !Files.exists(targetRoot) && Files.exists(backup)) {
                try {
                    Files.move(backup, targetRoot, StandardCopyOption.ATOMIC_MOVE);
                    previousMoved = false;
                } catch (IOException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        } finally {
            deleteTree(staging);
            if (installed || !previousMoved) {
                deleteTree(backup);
            }
        }
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

    private static String validatedRelativePath(String rawPath) {
        String relativePath = rawPath.trim();
        Path parsed = Path.of(relativePath).normalize();
        if (relativePath.isEmpty()
            || relativePath.indexOf('\\') >= 0
            || parsed.isAbsolute()
            || parsed.startsWith("..")
            || !parsed.toString().replace('\\', '/').equals(relativePath)) {
            throw new IllegalArgumentException("Invalid CraftEngine bundle path: " + rawPath);
        }
        return relativePath;
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK has no SHA-256", impossible);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
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
