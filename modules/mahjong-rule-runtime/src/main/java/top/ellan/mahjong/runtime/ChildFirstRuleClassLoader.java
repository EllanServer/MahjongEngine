package top.ellan.mahjong.runtime;

import java.net.URL;
import java.net.URLClassLoader;

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

    private static boolean parentFirst(String className) {
        for (String prefix : PARENT_FIRST) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
