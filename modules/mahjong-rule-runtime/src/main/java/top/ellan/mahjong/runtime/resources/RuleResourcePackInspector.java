package top.ellan.mahjong.runtime.resources;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import top.ellan.mahjong.runtime.common.RulePackException;
import top.ellan.mahjong.runtime.registry.RulePackRegistryEntry;
import top.ellan.mahjong.runtime.registry.RuleResourcePackArtifact;
import top.ellan.mahjong.runtime.security.Hashing;
import top.ellan.mahjong.spi.RulePresentationCueType;

/** Verifies that a signed companion ZIP contains resources and no executable rule code. */
public final class RuleResourcePackInspector {
    private static final String DESCRIPTOR = "META-INF/mahjong-rule-resources.properties";
    private static final String SOUNDS = "META-INF/mahjong-rule-sounds.properties";
    private static final String CRAFT_ROOT = "craftengine/";
    private static final String INDEX = CRAFT_ROOT + "_bundle_index.txt";
    private static final String MANIFEST = "_bundle_manifest.sha256";
    private static final long MAX_ENTRY_BYTES = 64L * 1024 * 1024;
    private static final long MAX_UNCOMPRESSED_BYTES = 256L * 1024 * 1024;
    private static final int MAX_ENTRIES = 20_000;

    public InspectedRuleResourcePack inspect(Path archive, RulePackRegistryEntry expected)
            throws IOException, RulePackException {
        Path normalized = archive.toAbsolutePath().normalize();
        RuleResourcePackArtifact resource = expected.resources()
                .orElseThrow(() -> new RulePackException("Registry entry has no resource artifact"));
        if (!Files.isRegularFile(normalized)
                || Files.size(normalized) != resource.sizeBytes()
                || !Hashing.sha256(normalized).equals(resource.sha256())) {
            throw new RulePackException("Rule resource ZIP differs from signed registry");
        }
        try (ZipFile zip = new ZipFile(normalized.toFile(), StandardCharsets.UTF_8)) {
            Set<String> names = inspectEntries(zip);
            Properties descriptor = readProperties(zip, DESCRIPTOR);
            requireDescriptor(descriptor, expected);
            RuleSoundCatalog sounds = readSounds(zip, names, expected);
            verifyCraftEngineBundle(zip, names);
            return new InspectedRuleResourcePack(
                    expected.ruleId(),
                    expected.version(),
                    expected.sha256(),
                    normalized,
                    sounds);
        } catch (IllegalArgumentException failure) {
            throw new RulePackException("Invalid rule resource ZIP", failure);
        }
    }

    private static Set<String> inspectEntries(ZipFile zip) throws RulePackException {
        Set<String> names = new HashSet<>();
        long total = 0;
        int count = 0;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.isDirectory()
                    ? validatedDirectoryPath(entry.getName())
                    : validatedPath(entry.getName());
            if (!names.add(name) || ++count > MAX_ENTRIES) {
                throw new RulePackException("Duplicate or excessive resource ZIP entries");
            }
            if (entry.isDirectory()) {
                continue;
            }
            long size = entry.getSize();
            if (size < 0
                    || size > MAX_ENTRY_BYTES
                    || total > MAX_UNCOMPRESSED_BYTES - size) {
                throw new RulePackException("Rule resource ZIP exceeds decompression limits");
            }
            total += size;
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".class")
                    || lower.endsWith(".jar")
                    || lower.endsWith(".java")
                    || name.contains("META-INF/services/")
                    || (!name.startsWith(CRAFT_ROOT) && !allowedMetadata(name))) {
                throw new RulePackException("Executable or unexpected resource ZIP entry: " + name);
            }
        }
        if (!names.containsAll(Set.of(DESCRIPTOR, SOUNDS, INDEX, CRAFT_ROOT + MANIFEST))) {
            throw new RulePackException("Rule resource ZIP is missing required metadata");
        }
        return Set.copyOf(names);
    }

    private static boolean allowedMetadata(String name) {
        return name.equals(DESCRIPTOR)
                || name.equals(SOUNDS)
                || name.equals("META-INF/RESOURCEPACK_ATTRIBUTION.md")
                || name.startsWith("META-INF/licenses/");
    }

    private static void requireDescriptor(
            Properties descriptor, RulePackRegistryEntry expected) throws RulePackException {
        if (!descriptor.stringPropertyNames().equals(Set.of("format", "id", "version"))
                || !"1".equals(descriptor.getProperty("format"))
                || !expected.ruleId().value().equals(descriptor.getProperty("id"))
                || !expected.version().equals(descriptor.getProperty("version"))) {
            throw new RulePackException("Rule resource descriptor differs from signed coordinate");
        }
    }

    private static RuleSoundCatalog readSounds(
            ZipFile zip, Set<String> names, RulePackRegistryEntry expected)
            throws IOException, RulePackException {
        Properties properties = readProperties(zip, SOUNDS);
        EnumMap<RulePresentationCueType, RuleSoundProfile> cues =
                new EnumMap<>(RulePresentationCueType.class);
        RuleSoundProfile dice = null;
        RuleSoundProfile wall = null;
        for (String key : properties.stringPropertyNames()) {
            RuleSoundProfile profile = parseProfile(properties.getProperty(key));
            if (key.startsWith("cue.")) {
                try {
                    cues.put(
                            RulePresentationCueType.valueOf(key.substring("cue.".length())),
                            profile);
                } catch (IllegalArgumentException failure) {
                    throw new RulePackException("Unknown rule sound cue: " + key, failure);
                }
            } else if (key.equals("opening.dice")) {
                dice = profile;
            } else if (key.equals("opening.wall")) {
                wall = profile;
            } else {
                throw new RulePackException("Unknown rule sound property: " + key);
            }
        }
        if (dice == null || wall == null) {
            throw new RulePackException("Rule resource pack must define both opening sounds");
        }
        validateSoundRegistry(zip, names, cues.values(), dice, wall, expected);
        return new RuleSoundCatalog(cues, dice, wall);
    }

    private static RuleSoundProfile parseProfile(String value) throws RulePackException {
        String[] parts = value.split(",", -1);
        if (parts.length != 3) {
            throw new RulePackException("Sound profile must be key,volume,pitch");
        }
        try {
            return new RuleSoundProfile(
                    parts[0].trim(),
                    Float.parseFloat(parts[1].trim()),
                    Float.parseFloat(parts[2].trim()));
        } catch (IllegalArgumentException failure) {
            throw new RulePackException("Invalid sound profile", failure);
        }
    }

    private static void validateSoundRegistry(
            ZipFile zip,
            Set<String> names,
            java.util.Collection<RuleSoundProfile> cues,
            RuleSoundProfile dice,
            RuleSoundProfile wall,
            RulePackRegistryEntry expected)
            throws IOException, RulePackException {
        List<RuleSoundProfile> profiles = new ArrayList<>(cues);
        profiles.add(dice);
        profiles.add(wall);
        Set<String> namespaces = new HashSet<>();
        for (RuleSoundProfile profile : profiles) {
            namespaces.add(profile.key().substring(0, profile.key().indexOf(':')));
        }
        if (namespaces.size() != 1) {
            throw new RulePackException("One rule resource pack must use one sound namespace");
        }
        String namespace = namespaces.iterator().next();
        String expectedNamespace = versionedNamespace(expected);
        if (!namespace.equals(expectedNamespace)) {
            throw new RulePackException(
                    "Rule sound namespace must be version-isolated: " + expectedNamespace);
        }
        String registryPath = CRAFT_ROOT + "resourcepack/assets/" + namespace + "/sounds.json";
        if (!names.contains(registryPath)) {
            throw new RulePackException("Rule resource pack has no namespaced sounds.json");
        }
        String registry = readUtf8(zip, registryPath);
        for (RuleSoundProfile profile : profiles) {
            String event = profile.key().substring(profile.key().indexOf(':') + 1);
            if (!registry.contains('"' + event + '"')) {
                throw new RulePackException("sounds.json has no event " + profile.key());
            }
        }
        List<String> declaredNamespaces = readUtf8(zip, CRAFT_ROOT + "pack.yml").lines()
                .map(String::trim)
                .filter(line -> line.startsWith("namespace:"))
                .toList();
        if (!declaredNamespaces.equals(List.of("namespace: " + expectedNamespace))) {
            throw new RulePackException("CraftEngine pack namespace differs from rule version");
        }
    }

    private static String versionedNamespace(RulePackRegistryEntry expected) {
        return "mahjong_"
                + expected.ruleId().value()
                + "_v"
                + expected.version().replaceAll("[^0-9A-Za-z]", "_").toLowerCase(
                        java.util.Locale.ROOT);
    }

    private static void verifyCraftEngineBundle(ZipFile zip, Set<String> archiveNames)
            throws IOException, RulePackException {
        List<String> indexed = readUtf8(zip, INDEX).lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(RuleResourcePackInspector::validatedPath)
                .toList();
        if (indexed.size() != new HashSet<>(indexed).size() || !indexed.contains(MANIFEST)) {
            throw new RulePackException("CraftEngine resource index is invalid");
        }
        Set<String> actual = new HashSet<>();
        for (String name : archiveNames) {
            if (name.startsWith(CRAFT_ROOT) && !name.endsWith("/") && !name.equals(INDEX)) {
                actual.add(name.substring(CRAFT_ROOT.length()));
            }
        }
        if (!actual.equals(new HashSet<>(indexed))) {
            throw new RulePackException("CraftEngine resource index differs from ZIP contents");
        }
        Map<String, String> hashes = readManifest(zip);
        Set<String> content = new HashSet<>(indexed);
        content.remove(MANIFEST);
        if (!hashes.keySet().equals(content)) {
            throw new RulePackException("CraftEngine resource index and manifest differ");
        }
        for (Map.Entry<String, String> expected : hashes.entrySet()) {
            if (!expected.getValue().equals(hash(zip, CRAFT_ROOT + expected.getKey()))) {
                throw new RulePackException(
                        "CraftEngine resource hash mismatch: " + expected.getKey());
            }
        }
    }

    private static Map<String, String> readManifest(ZipFile zip)
            throws IOException, RulePackException {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String line : readUtf8(zip, CRAFT_ROOT + MANIFEST).lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            if (line.length() < 67 || !line.substring(64, 66).equals("  ")) {
                throw new RulePackException("Malformed CraftEngine resource manifest");
            }
            String sha256 = line.substring(0, 64);
            String path = validatedPath(line.substring(66));
            if (!sha256.matches("[0-9a-f]{64}") || hashes.put(path, sha256) != null) {
                throw new RulePackException("Invalid CraftEngine resource manifest entry");
            }
        }
        return Map.copyOf(hashes);
    }

    private static Properties readProperties(ZipFile zip, String name)
            throws IOException, RulePackException {
        Properties properties = new Properties();
        try (InputStream input = entryStream(zip, name);
                InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static String readUtf8(ZipFile zip, String name)
            throws IOException, RulePackException {
        try (InputStream input = entryStream(zip, name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String hash(ZipFile zip, String name) throws IOException, RulePackException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK has no SHA-256", impossible);
        }
        try (InputStream input = entryStream(zip, name)) {
            byte[] buffer = new byte[8192];
            for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static InputStream entryStream(ZipFile zip, String name)
            throws IOException, RulePackException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) {
            throw new RulePackException("Missing rule resource entry: " + name);
        }
        return zip.getInputStream(entry);
    }

    private static String validatedPath(String raw) {
        String path = raw.trim();
        Path parsed = Path.of(path).normalize();
        if (path.isEmpty()
                || path.indexOf('\\') >= 0
                || parsed.isAbsolute()
                || parsed.startsWith("..")
                || !parsed.toString().replace('\\', '/').equals(path)) {
            throw new IllegalArgumentException("Unsafe resource ZIP path: " + raw);
        }
        return path;
    }

    private static String validatedDirectoryPath(String raw) {
        if (!raw.endsWith("/") || raw.length() == 1) {
            throw new IllegalArgumentException("Unsafe resource ZIP directory: " + raw);
        }
        return validatedPath(raw.substring(0, raw.length() - 1)) + '/';
    }
}
