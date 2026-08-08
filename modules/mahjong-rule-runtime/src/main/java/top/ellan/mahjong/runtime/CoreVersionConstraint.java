package top.ellan.mahjong.runtime;

import java.util.Objects;

/** Minimal fail-closed version constraint used by official descriptors. */
final class CoreVersionConstraint {
    private CoreVersionConstraint() {}

    static boolean accepts(String constraint, String coreVersion) {
        Objects.requireNonNull(constraint, "constraint");
        Version current = Version.parse(coreVersion);
        if (constraint.startsWith(">=")) {
            return current.compareTo(Version.parse(constraint.substring(2))) >= 0;
        }
        if (constraint.startsWith("[") && constraint.endsWith(")")) {
            String[] bounds = constraint.substring(1, constraint.length() - 1).split(",", -1);
            return bounds.length == 2
                    && current.compareTo(Version.parse(bounds[0])) >= 0
                    && current.compareTo(Version.parse(bounds[1])) < 0;
        }
        return current.compareTo(Version.parse(constraint)) == 0;
    }

    private record Version(int major, int minor, int patch) implements Comparable<Version> {
        private static Version parse(String text) {
            String numeric = Objects.requireNonNull(text, "version").split("[+-]", 2)[0];
            String[] parts = numeric.split("\\.", -1);
            if (parts.length < 2 || parts.length > 3) {
                throw new IllegalArgumentException("Unsupported version: " + text);
            }
            try {
                return new Version(
                        component(parts[0]),
                        component(parts[1]),
                        parts.length == 3 ? component(parts[2]) : 0);
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("Unsupported version: " + text, failure);
            }
        }

        private static int component(String text) {
            if (!text.matches("0|[1-9][0-9]*")) {
                throw new NumberFormatException(text);
            }
            return Integer.parseInt(text);
        }

        @Override
        public int compareTo(Version other) {
            int compared = Integer.compare(major, other.major);
            if (compared == 0) {
                compared = Integer.compare(minor, other.minor);
            }
            if (compared == 0) {
                compared = Integer.compare(patch, other.patch);
            }
            return compared;
        }
    }
}
