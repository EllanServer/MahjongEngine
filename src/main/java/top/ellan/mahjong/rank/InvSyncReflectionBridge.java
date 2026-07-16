package top.ellan.mahjong.rank;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;

/**
 * Narrow reflection boundary for InvSync 2.x's documented addon interface.
 * No private InvSync state or offline API is accessed.
 */
final class InvSyncReflectionBridge {
    static final String ADDON_CLASS = "com.xbaimiao.invsync.api.addon.InvSyncAddon";
    static final String MANAGER_CLASS = "com.xbaimiao.invsync.api.addon.InvSyncAddonManager";

    private InvSyncReflectionBridge() {
    }

    static Object register(ClassLoader invSyncClassLoader, EventHandler eventHandler) throws ReflectiveOperationException {
        Class<?> addonClass = Class.forName(ADDON_CLASS, true, invSyncClassLoader);
        if (!addonClass.isInterface()) {
            throw new NoSuchMethodException(ADDON_CLASS + " is not an interface");
        }
        verifyDocumentedEventApi(addonClass);
        Object addon = Proxy.newProxyInstance(addonClass.getClassLoader(), new Class<?>[] { addonClass }, new AddonInvocationHandler(eventHandler));
        Class<?> managerClass = Class.forName(MANAGER_CLASS, true, invSyncClassLoader);
        Method register = Arrays.stream(managerClass.getMethods())
            .filter(method -> method.getName().equals("register"))
            .filter(method -> method.getParameterCount() == 1)
            .filter(method -> method.getParameterTypes()[0] == addonClass)
            .findFirst()
            .orElseThrow(() -> new NoSuchMethodException(MANAGER_CLASS + ".register(InvSyncAddon)"));
        Object receiver = null;
        if (!Modifier.isStatic(register.getModifiers())) {
            Field instance = managerClass.getField("INSTANCE");
            receiver = instance.get(null);
        }
        try {
            register.invoke(receiver, addon);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ReflectiveOperationException reflective) {
                throw reflective;
            }
            throw new ReflectiveOperationException("InvSync rejected the MahjongPaper addon registration", cause);
        }
        return addon;
    }

    private static void verifyDocumentedEventApi(Class<?> addonClass) throws NoSuchMethodException {
        Method onSync = Arrays.stream(addonClass.getMethods())
            .filter(method -> method.getName().equals("onSync") && method.getParameterCount() == 1)
            .findFirst()
            .orElseThrow(() -> new NoSuchMethodException(ADDON_CLASS + ".onSync(event)"));
        Class<?> syncEvent = onSync.getParameterTypes()[0];
        requireMethod(syncEvent, "getPlayer");
        Method readData = requireMethod(syncEvent, "readData", String.class);
        if (readData.getReturnType() != byte[].class) {
            throw new NoSuchMethodException(syncEvent.getName() + ".readData(String) must return byte[]");
        }

        Method onSave = Arrays.stream(addonClass.getMethods())
            .filter(method -> method.getName().equals("onSave"))
            .filter(method -> method.getParameterCount() == 2)
            .findFirst()
            .orElseThrow(() -> new NoSuchMethodException(ADDON_CLASS + ".onSave(event, reason)"));
        Class<?> saveEvent = onSave.getParameterTypes()[0];
        Class<?> savePlayerType = requireMethod(saveEvent, "getPlayer").getReturnType();
        requireMethod(savePlayerType, "getUniqueId");
        requireMethod(savePlayerType, "getName");
        requireMethod(saveEvent, "putData", String.class, byte[].class);

        Class<?> playerType = requireMethod(syncEvent, "getPlayer").getReturnType();
        requireMethod(playerType, "getUniqueId");
        requireMethod(playerType, "getName");
    }

    private static Method requireMethod(Class<?> owner, String name, Class<?>... parameters) throws NoSuchMethodException {
        Method method = owner.getMethod(name, parameters);
        if (!Modifier.isPublic(method.getModifiers())) {
            throw new NoSuchMethodException(owner.getName() + '.' + name + " is not public");
        }
        return method;
    }

    interface EventHandler {
        void onSync(Object event) throws ReflectiveOperationException;

        void onSave(Object event, Object reason) throws ReflectiveOperationException;

        default void onRuntimeFailure(Throwable failure) {
        }
    }

    private record AddonInvocationHandler(EventHandler eventHandler) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            return switch (method.getName()) {
                case "onSync" -> {
                    if (arguments == null || arguments.length != 1) {
                        throw new IllegalArgumentException("Unexpected InvSync onSync signature");
                    }
                    try {
                        this.eventHandler.onSync(arguments[0]);
                    } catch (Throwable failure) {
                        this.eventHandler.onRuntimeFailure(failure);
                        throw failure;
                    }
                    yield null;
                }
                case "onSave" -> {
                    if (arguments == null || arguments.length < 1 || arguments.length > 2) {
                        throw new IllegalArgumentException("Unexpected InvSync onSave signature");
                    }
                    try {
                        this.eventHandler.onSave(arguments[0], arguments.length == 2 ? arguments[1] : null);
                    } catch (Throwable failure) {
                        this.eventHandler.onRuntimeFailure(failure);
                        throw failure;
                    }
                    yield null;
                }
                case "toString" -> "MahjongPaperInvSyncAddon";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> arguments != null && arguments.length == 1 && arguments[0] == proxy;
                default -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, arguments) : null;
            };
        }
    }
}
