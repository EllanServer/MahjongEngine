package top.ellan.mahjong.runtime;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small strict parser used only for signed release metadata; it deliberately has no serializer. */
final class MiniJson {
    private static final int MAX_DEPTH = 32;
    private final String input;
    private int index;

    private MiniJson(String input) {
        this.input = input;
    }

    static Object parse(String input) throws RulePackException {
        if (input.length() > 1_048_576) {
            throw new RulePackException("Registry JSON exceeds 1 MiB");
        }
        MiniJson parser = new MiniJson(input);
        Object value = parser.value(0);
        parser.whitespace();
        if (parser.index != input.length()) {
            throw parser.error("Trailing JSON content");
        }
        return value;
    }

    private Object value(int depth) throws RulePackException {
        if (depth > MAX_DEPTH) {
            throw error("JSON nesting is too deep");
        }
        whitespace();
        if (index >= input.length()) {
            throw error("Unexpected end of JSON");
        }
        return switch (input.charAt(index)) {
            case '{' -> object(depth + 1);
            case '[' -> array(depth + 1);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) throws RulePackException {
        index++;
        whitespace();
        Map<String, Object> values = new LinkedHashMap<>();
        if (consume('}')) {
            return values;
        }
        while (true) {
            whitespace();
            if (index >= input.length() || input.charAt(index) != '"') {
                throw error("Object key must be a string");
            }
            String key = string();
            whitespace();
            require(':');
            if (values.containsKey(key)) {
                throw error("Duplicate object key: " + key);
            }
            values.put(key, value(depth));
            whitespace();
            if (consume('}')) {
                return values;
            }
            require(',');
        }
    }

    private List<Object> array(int depth) throws RulePackException {
        index++;
        whitespace();
        List<Object> values = new ArrayList<>();
        if (consume(']')) {
            return values;
        }
        while (true) {
            values.add(value(depth));
            whitespace();
            if (consume(']')) {
                return values;
            }
            require(',');
        }
    }

    private String string() throws RulePackException {
        require('"');
        StringBuilder output = new StringBuilder();
        while (index < input.length()) {
            char current = input.charAt(index++);
            if (current == '"') {
                return output.toString();
            }
            if (current < 0x20) {
                throw error("Control character in string");
            }
            if (current != '\\') {
                output.append(current);
                continue;
            }
            if (index >= input.length()) {
                throw error("Incomplete string escape");
            }
            char escaped = input.charAt(index++);
            switch (escaped) {
                case '"', '\\', '/' -> output.append(escaped);
                case 'b' -> output.append('\b');
                case 'f' -> output.append('\f');
                case 'n' -> output.append('\n');
                case 'r' -> output.append('\r');
                case 't' -> output.append('\t');
                case 'u' -> output.append(unicodeEscape());
                default -> throw error("Unknown string escape");
            }
        }
        throw error("Unterminated string");
    }

    private char unicodeEscape() throws RulePackException {
        if (index + 4 > input.length()) {
            throw error("Incomplete unicode escape");
        }
        int value = 0;
        for (int offset = 0; offset < 4; offset++) {
            int digit = Character.digit(input.charAt(index++), 16);
            if (digit < 0) {
                throw error("Invalid unicode escape");
            }
            value = value * 16 + digit;
        }
        return (char) value;
    }

    private Object number() throws RulePackException {
        int start = index;
        if (consume('-') && index >= input.length()) {
            throw error("Incomplete number");
        }
        if (consume('0')) {
            if (index < input.length() && Character.isDigit(input.charAt(index))) {
                throw error("Leading zero in number");
            }
        } else {
            requireDigits();
        }
        if (consume('.')) {
            requireDigits();
        }
        if (consume('e') || consume('E')) {
            if (!consume('+')) {
                consume('-');
            }
            requireDigits();
        }
        try {
            return new BigDecimal(input.substring(start, index));
        } catch (NumberFormatException failure) {
            throw error("Invalid number", failure);
        }
    }

    private void requireDigits() throws RulePackException {
        int start = index;
        while (index < input.length() && Character.isDigit(input.charAt(index))) {
            index++;
        }
        if (start == index) {
            throw error("Expected number digit");
        }
    }

    private Object literal(String text, Object value) throws RulePackException {
        if (!input.startsWith(text, index)) {
            throw error("Invalid literal");
        }
        index += text.length();
        return value;
    }

    private void whitespace() {
        while (index < input.length()) {
            char current = input.charAt(index);
            if (current != ' ' && current != '\n' && current != '\r' && current != '\t') {
                return;
            }
            index++;
        }
    }

    private boolean consume(char expected) {
        if (index < input.length() && input.charAt(index) == expected) {
            index++;
            return true;
        }
        return false;
    }

    private void require(char expected) throws RulePackException {
        if (!consume(expected)) {
            throw error("Expected '" + expected + "'");
        }
    }

    private RulePackException error(String message) {
        return new RulePackException(message + " at JSON offset " + index);
    }

    private RulePackException error(String message, Throwable cause) {
        return new RulePackException(message + " at JSON offset " + index, cause);
    }
}
