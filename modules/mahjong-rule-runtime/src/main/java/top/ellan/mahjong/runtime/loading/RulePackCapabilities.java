package top.ellan.mahjong.runtime.loading;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.Handle;

/** Capability allowlist for deterministic, in-process rule providers. */
final class RulePackCapabilities {
    private static final Set<String> SAFE_IO = Set.of(
            "java/io/ByteArrayInputStream",
            "java/io/ByteArrayOutputStream",
            "java/io/Closeable",
            "java/io/DataInput",
            "java/io/DataInputStream",
            "java/io/DataOutput",
            "java/io/DataOutputStream",
            "java/io/EOFException",
            "java/io/Flushable",
            "java/io/IOException",
            "java/io/InputStream",
            "java/io/OutputStream",
            "java/io/Reader",
            "java/io/StringReader",
            "java/io/StringWriter",
            "java/io/UncheckedIOException",
            "java/io/Writer");
    private static final List<String> BLOCKED_PREFIXES = List.of(
            "java/net/",
            "java/nio/channels/",
            "java/nio/file/",
            "java/sql/",
            "java/lang/instrument/",
            "java/lang/invoke/",
            "java/lang/management/",
            "java/lang/ref/",
            "java/lang/reflect/",
            "java/lang/runtime/",
            "java/awt/",
            "java/beans/",
            "java/prefs/",
            "java/rmi/",
            "java/util/logging/",
            "javax/imageio/",
            "javax/management/",
            "javax/naming/",
            "javax/print/",
            "javax/script/",
            "javax/sound/",
            "javax/swing/",
            "javax/sql/",
            "javax/xml/",
            "jdk/internal/",
            "org/w3c/dom/",
            "org/xml/",
            "sun/",
            "org/bukkit/",
            "net/momirealms/craftengine/");
    private static final Set<String> BLOCKED_TYPES = Set.of(
            "java/lang/ClassLoader",
            "java/lang/ModuleLayer",
            "java/lang/Module",
            "java/lang/Process",
            "java/lang/ProcessBuilder",
            "java/lang/ProcessHandle",
            "java/lang/Runtime",
            "java/lang/SecurityManager",
            "java/lang/Thread",
            "java/lang/ThreadGroup",
            "java/lang/ThreadLocal",
            "java/security/AccessController",
            "java/security/SecureClassLoader",
            "java/security/SecureRandom",
            "java/util/ResourceBundle",
            "java/util/ServiceLoader",
            "java/util/Timer");
    private static final Set<String> SAFE_BOOTSTRAPS = Set.of(
            "java/lang/invoke/LambdaMetafactory",
            "java/lang/invoke/StringConcatFactory",
            "java/lang/runtime/ObjectMethods",
            "java/lang/runtime/SwitchBootstraps");
    private static final Set<String> SAFE_CONCURRENT = Set.of(
            "java/util/concurrent/ConcurrentHashMap",
            "java/util/concurrent/ConcurrentMap");
    private static final Set<String> BLOCKING_MONITOR_METHODS =
            Set.of("wait", "notify", "notifyAll");
    private static final Set<String> SAFE_CLASS_METHODS = Set.of(
            "cast",
            "descriptorString",
            "getName",
            "getSimpleName",
            "isAssignableFrom",
            "isEnum",
            "isInstance");

    private RulePackCapabilities() {}

    static void verifyMember(
            String owner, String name, String descriptor, boolean field, String source) {
        verifyOwner(owner, name, source);
        if (owner.equals("top/ellan/mahjong/spi/RuleExecutionBudget")) {
            throw violation("host execution-budget capability", source, name);
        }
        if (owner.equals("java/lang/System") && (field || !name.equals("arraycopy"))) {
            throw violation("System capability", source, name);
        }
        if (owner.equals("java/lang/Object") && BLOCKING_MONITOR_METHODS.contains(name)) {
            throw violation("blocking monitor capability", source, name);
        }
        if (owner.equals("java/lang/Throwable") && name.equals("printStackTrace")) {
            throw violation("process-output capability", source, name);
        }
        if (owner.equals("java/lang/Class") && !SAFE_CLASS_METHODS.contains(name)) {
            throw violation("reflective capability", source, name);
        }
        if (owner.equals("java/nio/ByteBuffer") && name.equals("allocateDirect")) {
            throw violation("off-heap allocation capability", source, name);
        }
        if ((owner.equals("java/util/Random") || owner.equals("java/util/SplittableRandom"))
                && name.equals("<init>")
                && descriptor.equals("()V")) {
            throw violation("unseeded randomness", source, owner);
        }
        if (owner.equals("java/util/Collections")
                && name.equals("shuffle")
                && descriptor.equals("(Ljava/util/List;)V")) {
            throw violation("unseeded shuffle", source, name);
        }
        if (((owner.equals("java/lang/Math") || owner.equals("java/lang/StrictMath"))
                                && name.equals("random"))
                || (owner.equals("java/util/UUID") && name.equals("randomUUID"))) {
            throw violation("unseeded randomness", source, name);
        }
        if (owner.startsWith("java/time/")
                && (name.equals("now") || name.startsWith("system"))) {
            throw violation("wall-clock capability", source, owner + '.' + name);
        }
        if ((name.equals("parallel") || name.equals("parallelStream"))
                && (owner.startsWith("java/util/stream/")
                        || owner.startsWith("java/util/Collection"))) {
            throw violation("parallel execution", source, name);
        }
    }

    static void verifyOwner(String owner, String member, String source) {
        if (owner == null) {
            return;
        }
        if (owner.startsWith("[")) {
            verifyArrayComponent(owner, member, source);
            return;
        }
        boolean outsideRuleBoundary = !owner.startsWith("java/")
                && !owner.startsWith("top/ellan/mahjong/spi/")
                && !owner.startsWith("top/ellan/mahjong/rules/");
        boolean blocked = outsideRuleBoundary
                || BLOCKED_TYPES.contains(owner)
                || BLOCKED_PREFIXES.stream().anyMatch(owner::startsWith)
                || owner.startsWith("java/util/concurrent/") && !SAFE_CONCURRENT.contains(owner)
                || owner.startsWith("java/io/") && !SAFE_IO.contains(owner)
                || owner.startsWith("top/ellan/mahjong/")
                        && !owner.startsWith("top/ellan/mahjong/spi/")
                        && !owner.startsWith("top/ellan/mahjong/rules/");
        if (blocked) {
            throw violation("forbidden capability", source, owner + '.' + member);
        }
    }

    private static void verifyArrayComponent(String descriptor, String member, String source) {
        int component = 0;
        while (component < descriptor.length() && descriptor.charAt(component) == '[') {
            component++;
        }
        if (component < descriptor.length()
                && descriptor.charAt(component) == 'L'
                && descriptor.endsWith(";")) {
            verifyOwner(descriptor.substring(component + 1, descriptor.length() - 1), member, source);
        }
    }

    static void verifyBootstrap(Handle bootstrap, String source) {
        if (SAFE_BOOTSTRAPS.contains(bootstrap.getOwner())) {
            return;
        }
        verifyMember(
                bootstrap.getOwner(),
                bootstrap.getName(),
                bootstrap.getDesc(),
                false,
                source);
    }

    static PolicyViolation violation(String kind, String source, String target) {
        return new PolicyViolation("Rule class " + source + " uses " + kind + ": " + target);
    }

    static final class PolicyViolation extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private PolicyViolation(String message) {
            super(message);
        }
    }
}
