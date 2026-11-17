package dev.sixik.sdmal.build;

import java.util.Arrays;

/** Matches numeric Minecraft version prefixes and inclusive prefix ranges at build time. */
public final class VersionSelector {
    /** Prevents instantiation of the selector utility. */
    private VersionSelector() {}

    /**
     * Matches a target against a numeric prefix or an inclusive range of prefixes.
     * @param version complete numeric target, for example 26.1.2
     * @param selector prefix or range, for example 1.19-1.21
     * @return true if the target belongs to the selector
     * @throws IllegalArgumentException if either value is malformed or the range is reversed
     */
    public static boolean matches(String version, String selector) {
        int[] target = parse(version);
        String[] bounds = selector.strip().split("-", -1);
        if (bounds.length == 1) return comparePrefix(target, parse(bounds[0])) == 0;
        if (bounds.length != 2) throw new IllegalArgumentException("Invalid version selector: " + selector);
        int[] lower = parse(bounds[0]);
        int[] upper = parse(bounds[1]);
        if (Arrays.compare(lower, upper) > 0) {
            throw new IllegalArgumentException("Reversed version range: " + selector);
        }
        return comparePrefix(target, lower) >= 0 && comparePrefix(target, upper) <= 0;
    }

    /**
     * Parses decimal components without permitting snapshots, empty parts, or signed values.
     * @param value non-null numeric version
     * @return non-empty array of non-negative version components
     * @throws IllegalArgumentException if the value is not a supported numeric version
     */
    private static int[] parse(String value) {
        String numeric = value.strip();
        if (!numeric.matches("[0-9]+(?:\\.[0-9]+)*")) {
            throw new IllegalArgumentException("Expected a numeric Minecraft version: " + value);
        }
        try {
            return Arrays.stream(numeric.split("\\.")).mapToInt(Integer::parseInt).toArray();
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Version component is too large: " + value, exception);
        }
    }

    /**
     * Compares the target only as far as the bound's specified prefix.
     * @param target complete numeric version components
     * @param bound numeric prefix components; absent target components count as zero
     * @return negative, zero, or positive according to numeric prefix order
     */
    private static int comparePrefix(int[] target, int[] bound) {
        for (int index = 0; index < bound.length; index++) {
            int comparison = Integer.compare(index < target.length ? target[index] : 0, bound[index]);
            if (comparison != 0) return comparison;
        }
        return 0;
    }
}
