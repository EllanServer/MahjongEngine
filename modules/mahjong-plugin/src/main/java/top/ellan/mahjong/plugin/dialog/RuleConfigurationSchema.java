package top.ellan.mahjong.plugin.dialog;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.kyori.adventure.text.Component;
import top.ellan.mahjong.runtime.common.RulePackException;
import top.ellan.mahjong.runtime.registry.MiniJson;

/** Strict JSON-Schema subset used to turn rule-pack settings into native dialog inputs. */
record RuleConfigurationSchema(List<Field> fields) {
    private static final int MAX_FIELDS = 16;

    RuleConfigurationSchema {
        fields = List.copyOf(fields);
    }

    static RuleConfigurationSchema parse(String json) {
        try {
            Map<?, ?> root = object(MiniJson.parse(Objects.requireNonNull(json, "json")), "root");
            if (!"object".equals(root.get("type"))) {
                throw new IllegalArgumentException("configuration schema root must be an object");
            }
            Object rawProperties = root.get("properties");
            Map<?, ?> properties = object(
                    rawProperties == null ? Map.of() : rawProperties, "properties");
            if (properties.size() > MAX_FIELDS) {
                throw new IllegalArgumentException("configuration schema has too many fields");
            }
            Set<String> required = strings(root.get("required"));
            ArrayList<Field> fields = new ArrayList<>(properties.size());
            int index = 0;
            for (Map.Entry<?, ?> entry : properties.entrySet()) {
                if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                    throw new IllegalArgumentException("configuration field key is invalid");
                }
                fields.add(field("cfg_" + index++, key, object(entry.getValue(), key), required));
            }
            return new RuleConfigurationSchema(fields);
        } catch (RulePackException failure) {
            throw new IllegalArgumentException("configuration schema is invalid", failure);
        }
    }

    Map<String, String> read(DialogResponseView response) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (Field field : fields) {
            String value = field.read(response);
            if (value != null) {
                values.put(field.key(), value);
            }
        }
        return Map.copyOf(values);
    }

    record Field(
            String inputKey,
            String key,
            String title,
            String description,
            Kind kind,
            boolean required,
            String defaultValue,
            BigDecimal minimum,
            BigDecimal maximum,
            int maxLength,
            List<String> options) {
        Field {
            Objects.requireNonNull(inputKey, "inputKey");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(kind, "kind");
            options = List.copyOf(options);
        }

        DialogInput input(Component label, String configured) {
            String initial = configured == null ? defaultValue : configured;
            return switch (kind) {
                case BOOLEAN -> DialogInput.bool(inputKey, label)
                        .initial(Boolean.parseBoolean(initial == null ? "false" : initial))
                        .build();
                case INTEGER_RANGE -> DialogInput.numberRange(
                                inputKey,
                                label,
                                minimum.floatValue(),
                                maximum.floatValue())
                        .width(300)
                        .labelFormat("options.generic_value")
                        .initial(numberInitial(initial))
                        .step(1.0F)
                        .build();
                case OPTION -> DialogInput.singleOption(
                                inputKey, label, optionEntries(initial))
                        .width(300)
                        .build();
                case TEXT -> DialogInput.text(inputKey, label)
                        .width(300)
                        .initial(initial == null ? "" : initial)
                        .maxLength(maxLength)
                        .build();
            };
        }

        String read(DialogResponseView response) {
            return switch (kind) {
                case BOOLEAN -> {
                    Boolean value = response.getBoolean(inputKey);
                    yield value == null ? missing() : Boolean.toString(value);
                }
                case INTEGER_RANGE -> {
                    Float value = response.getFloat(inputKey);
                    if (value == null || !Float.isFinite(value)) {
                        yield missing();
                    }
                    int rounded = Math.round(value);
                    BigDecimal exact = BigDecimal.valueOf(rounded);
                    if (Math.abs(value - rounded) > 0.001F
                            || exact.compareTo(minimum) < 0
                            || exact.compareTo(maximum) > 0) {
                        throw new IllegalArgumentException(key + " is outside its allowed range");
                    }
                    yield Integer.toString(rounded);
                }
                case OPTION -> {
                    String value = response.getText(inputKey);
                    if (value == null || !options.contains(value)) {
                        yield missing();
                    }
                    yield value;
                }
                case TEXT -> {
                    String value = response.getText(inputKey);
                    if (value == null || value.length() > maxLength) {
                        yield missing();
                    }
                    yield value.isEmpty() && !required ? null : value;
                }
            };
        }

        private Float numberInitial(String initial) {
            BigDecimal value = initial == null ? minimum : decimal(initial, key + " default");
            if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
                throw new IllegalArgumentException(key + " default is outside its allowed range");
            }
            return value.floatValue();
        }

        private List<SingleOptionDialogInput.OptionEntry> optionEntries(String initial) {
            String selected = options.contains(initial) ? initial : options.getFirst();
            return options.stream()
                    .map(value -> SingleOptionDialogInput.OptionEntry.create(
                            value, Component.text(value), value.equals(selected)))
                    .toList();
        }

        private String missing() {
            if (required) {
                throw new IllegalArgumentException(key + " is required");
            }
            return null;
        }
    }

    private static Field field(
            String inputKey, String key, Map<?, ?> schema, Set<String> required) {
        String title = text(schema.get("title"), humanize(key));
        String description = text(schema.get("description"), "");
        List<String> options = stringsList(schema.get("enum"));
        String type = text(schema.get("type"), options.isEmpty() ? "string" : "string");
        String defaultValue = scalar(schema.get("default"));
        if (!options.isEmpty()) {
            return new Field(inputKey, key, title, description, Kind.OPTION,
                    required.contains(key), defaultValue, null, null, 128, options);
        }
        if ("boolean".equals(type)) {
            return new Field(inputKey, key, title, description, Kind.BOOLEAN,
                    required.contains(key), defaultValue, null, null, 5, List.of());
        }
        BigDecimal minimum = number(schema.get("minimum"));
        BigDecimal maximum = number(schema.get("maximum"));
        if ("integer".equals(type) && minimum != null && maximum != null) {
            if (minimum.scale() > 0 || maximum.scale() > 0
                    || minimum.compareTo(maximum) >= 0
                    || minimum.compareTo(BigDecimal.valueOf(-16_777_216)) < 0
                    || maximum.compareTo(BigDecimal.valueOf(16_777_216)) > 0) {
                throw new IllegalArgumentException(key + " has an unsupported integer range");
            }
            return new Field(inputKey, key, title, description, Kind.INTEGER_RANGE,
                    required.contains(key), defaultValue, minimum, maximum, 32, List.of());
        }
        int maxLength = integer(schema.get("maxLength"), 256, 1, 1_024);
        return new Field(inputKey, key, title, description, Kind.TEXT,
                required.contains(key), defaultValue, null, null, maxLength, List.of());
    }

    private static Map<?, ?> object(Object value, String name) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new IllegalArgumentException(name + " must be an object");
    }

    private static Set<String> strings(Object value) {
        return new LinkedHashSet<>(stringsList(value));
    }

    private static List<String> stringsList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("schema string list is invalid");
        }
        ArrayList<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException("schema option is invalid");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static String scalar(Object value) {
        return value == null ? null : value instanceof BigDecimal decimal
                ? decimal.stripTrailingZeros().toPlainString()
                : value.toString();
    }

    private static String text(Object value, String fallback) {
        return value instanceof String text ? text : fallback;
    }

    private static BigDecimal number(Object value) {
        return value == null ? null : value instanceof BigDecimal decimal
                ? decimal
                : decimal(value.toString(), "schema number");
    }

    private static BigDecimal decimal(String value, String name) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(name + " is invalid", failure);
        }
    }

    private static int integer(Object value, int fallback, int minimum, int maximum) {
        if (value == null) {
            return fallback;
        }
        int result = number(value).intValueExact();
        if (result < minimum || result > maximum) {
            throw new IllegalArgumentException("schema integer is outside its allowed range");
        }
        return result;
    }

    private static String humanize(String value) {
        return value.replaceAll("([a-z])([A-Z])", "$1 $2")
                .replace('_', ' ')
                .replace('.', ' ');
    }

    enum Kind {
        BOOLEAN,
        INTEGER_RANGE,
        OPTION,
        TEXT
    }
}
