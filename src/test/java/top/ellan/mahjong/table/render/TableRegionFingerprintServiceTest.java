package top.ellan.mahjong.table.render;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Objects;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TableRegionFingerprintServiceTest {
    @Test
    void primitiveFieldsPreserveTheDelimitedTextFingerprint() throws ReflectiveOperationException {
        Class<?> builderType = Class.forName(TableRegionFingerprintService.class.getName() + "$FingerprintBuilder");
        Constructor<?> constructor = builderType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object builder = constructor.newInstance();

        Method objectField = accessible(builderType.getDeclaredMethod("field", Object.class));
        Method booleanField = accessible(builderType.getDeclaredMethod("field", boolean.class));
        Method charField = accessible(builderType.getDeclaredMethod("field", char.class));
        Method intField = accessible(builderType.getDeclaredMethod("field", int.class));
        Method longField = accessible(builderType.getDeclaredMethod("field", long.class));
        Method value = accessible(builderType.getDeclaredMethod("value"));

        objectField.invoke(builder, "prefix");
        booleanField.invoke(builder, true);
        booleanField.invoke(builder, false);
        charField.invoke(builder, 'Z');
        intField.invoke(builder, 0);
        intField.invoke(builder, 1);
        intField.invoke(builder, -1);
        intField.invoke(builder, Integer.MIN_VALUE);
        intField.invoke(builder, Integer.MAX_VALUE);
        longField.invoke(builder, Long.MIN_VALUE);
        longField.invoke(builder, Long.MAX_VALUE);
        objectField.invoke(builder, new Object[] {null});

        assertEquals(
            referenceFingerprint(
                "prefix",
                true,
                false,
                'Z',
                0,
                1,
                -1,
                Integer.MIN_VALUE,
                Integer.MAX_VALUE,
                Long.MIN_VALUE,
                Long.MAX_VALUE,
                null
            ),
            value.invoke(builder)
        );
    }

    private static Method accessible(Method method) {
        method.setAccessible(true);
        return method;
    }

    private static long referenceFingerprint(Object... fields) {
        long hash = 0xcbf29ce484222325L;
        boolean needsSeparator = false;
        for (Object field : fields) {
            if (needsSeparator) {
                hash = mix(hash, ':');
            }
            String text = Objects.toString(field, "");
            for (int index = 0; index < text.length(); index++) {
                hash = mix(hash, text.charAt(index));
            }
            needsSeparator = true;
        }
        return hash;
    }

    private static long mix(long hash, char value) {
        return (hash ^ value) * 0x100000001b3L;
    }
}
