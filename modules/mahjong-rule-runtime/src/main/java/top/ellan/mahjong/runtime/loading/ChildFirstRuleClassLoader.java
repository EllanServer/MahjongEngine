package top.ellan.mahjong.runtime.loading;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/** Child-first isolation, with JDK and the shared SPI forced parent-first. */
public final class ChildFirstRuleClassLoader extends URLClassLoader {
    static {
        registerAsParallelCapable();
    }

    private static final String[] PARENT_FIRST = {
        "java.", "javax.", "jdk.", "sun.", "top.ellan.mahjong.spi."
    };
    private final URL artifact;

    public ChildFirstRuleClassLoader(URL artifact, ClassLoader parent) {
        super(new URL[] {artifact}, parent);
        this.artifact = artifact;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String resourceName = name.replace('.', '/') + ".class";
        URL resource = findResource(resourceName);
        if (resource == null) {
            throw new ClassNotFoundException(name);
        }
        try (InputStream input = resource.openStream()) {
            byte[] bytecode = RulePackClassInstrumenter.instrument(input.readAllBytes());
            definePackageIfNeeded(name);
            CodeSource source = new CodeSource(artifact, (Certificate[]) null);
            return defineClass(name, bytecode, 0, bytecode.length, source);
        } catch (IOException | IllegalArgumentException | VerifyError failure) {
            throw new ClassNotFoundException("Unable to instrument rule class " + name, failure);
        }
    }

    private void definePackageIfNeeded(String className) {
        int separator = className.lastIndexOf('.');
        if (separator < 1) {
            return;
        }
        String packageName = className.substring(0, separator);
        if (getDefinedPackage(packageName) != null) {
            return;
        }
        try {
            definePackage(packageName, null, null, null, null, null, null, null);
        } catch (IllegalArgumentException concurrentDefinition) {
            if (getDefinedPackage(packageName) == null) {
                throw concurrentDefinition;
            }
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (parentFirst(name)) {
                    loaded = super.loadClass(name, false);
                } else {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException missingInChild) {
                        loaded = super.loadClass(name, false);
                    }
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    /**
     * Resources follow the same child-first order as classes. Leaving them parent-first would let a
     * rule pack read the host's copy of a resource it also ships, which is the opposite of the
     * isolation the class path already provides.
     */
    @Override
    public URL getResource(String name) {
        URL own = findResource(name);
        return own != null ? own : super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        Enumeration<URL> own = findResources(name);
        Enumeration<URL> parent = getParent() == null
                ? Collections.emptyEnumeration()
                : getParent().getResources(name);
        List<URL> ordered = new ArrayList<>();
        while (own.hasMoreElements()) {
            ordered.add(own.nextElement());
        }
        while (parent.hasMoreElements()) {
            URL candidate = parent.nextElement();
            if (!ordered.contains(candidate)) {
                ordered.add(candidate);
            }
        }
        return Collections.enumeration(ordered);
    }

    private static boolean parentFirst(String className) {
        for (String prefix : PARENT_FIRST) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
