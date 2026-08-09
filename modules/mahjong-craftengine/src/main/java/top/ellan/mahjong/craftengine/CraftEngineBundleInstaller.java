package top.ellan.mahjong.craftengine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.bukkit.plugin.Plugin;

/** Verifies and atomically installs the embedded CraftEngine resource bundle. */
public final class CraftEngineBundleInstaller {
    private static final String BUNDLE_ROOT = "craftengine/mahjongpaper";
    private static final String BUNDLE_INDEX = BUNDLE_ROOT + "/_bundle_index.txt";
    private static final String BUNDLE_MANIFEST = "_bundle_manifest.sha256";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final Plugin plugin;
    private final String bundleFolderName;

    public CraftEngineBundleInstaller(Plugin plugin, String bundleFolderName) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.bundleFolderName = Objects.requireNonNull(bundleFolderName, "bundleFolderName");
        if (!bundleFolderName.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid CraftEngine bundle folder");
        }
    }

    public Path install(Plugin craftEngine) throws IOException {
        Objects.requireNonNull(craftEngine, "craftEngine");
        try (InputStream indexStream = plugin.getResource(BUNDLE_INDEX)) {
            if (indexStream == null) {
                throw new IOException("Missing embedded CraftEngine bundle index");
            }
            Collection<String> entries =
                    new String(indexStream.readAllBytes(), StandardCharsets.UTF_8)
                            .lines()
                            .map(String::trim)
                            .filter(line -> !line.isEmpty())
                            .map(CraftEngineBundleInstaller::validatedRelativePath)
                            .toList();
            if (!entries.contains(BUNDLE_MANIFEST)) {
                throw new IOException("CraftEngine bundle index has no SHA-256 manifest");
            }
            Map<String, String> expectedHashes = readManifest();
            Set<String> contentEntries =
                    entries.stream()
                            .filter(entry -> !entry.equals(BUNDLE_MANIFEST))
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!expectedHashes.keySet().equals(contentEntries)) {
                throw new IOException("CraftEngine bundle index and manifest differ");
            }
            Path target =
                    craftEngine
                            .getDataFolder()
                            .toPath()
                            .resolve("resources")
                            .resolve(bundleFolderName);
            installAtomically(target, entries, expectedHashes);
            return target;
        }
    }

    private Map<String, String> readManifest() throws IOException {
        try (InputStream stream = plugin.getResource(BUNDLE_ROOT + '/' + BUNDLE_MANIFEST)) {
            if (stream == null) {
                throw new IOException("Missing embedded CraftEngine SHA-256 manifest");
            }
            Map<String, String> result = new LinkedHashMap<>();
            for (String line :
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
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
            Map<String, String> expectedHashes)
            throws IOException {
        Path parent = Objects.requireNonNull(targetRoot.getParent()).toAbsolutePath().normalize();
        Files.createDirectories(parent);
        String nonce = UUID.randomUUID().toString();
        Path staging = parent.resolve('.' + targetRoot.getFileName().toString() + ".staging-" + nonce);
        Path backup = parent.resolve('.' + targetRoot.getFileName().toString() + ".backup-" + nonce);
        boolean previousMoved = false;
        boolean installed = false;
        try {
            Files.createDirectory(staging);
            for (String entry : entries) {
                copyBundledFile(entry, staging.resolve(entry));
            }
            for (Map.Entry<String, String> expected : expectedHashes.entrySet()) {
                String actual = sha256(staging.resolve(expected.getKey()));
                if (!actual.equals(expected.getValue())) {
                    throw new IOException("CraftEngine bundle hash mismatch: " + expected.getKey());
                }
            }
            if (Files.exists(targetRoot)) {
                move(targetRoot, backup);
                previousMoved = true;
            }
            move(staging, targetRoot);
            installed = true;
        } catch (IOException failure) {
            if (previousMoved && !Files.exists(targetRoot) && Files.exists(backup)) {
                try {
                    move(backup, targetRoot);
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
        try (InputStream stream = plugin.getResource(BUNDLE_ROOT + '/' + relativePath)) {
            if (stream == null) {
                throw new IOException("Missing embedded resource: " + relativePath);
            }
            Files.createDirectories(Objects.requireNonNull(targetPath.getParent()));
            Files.copy(stream, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target);
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
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
