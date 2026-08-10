package top.ellan.mahjong.runtime.loading;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
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

    public ChildFirstRuleClassLoader(URL artifact, ClassLoader parent) {
        super(new URL[] {artifact}, parent);
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
