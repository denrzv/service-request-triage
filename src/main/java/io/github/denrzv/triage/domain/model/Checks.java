package io.github.denrzv.triage.domain.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Package-private argument checks shared by the Jev value types. Messages never include customer text. */
final class Checks {

    private Checks() {}

    static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static double requireUnit(double value, String name) {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be within [0, 1]");
        }
        return value;
    }

    static void requireSize(int size, int min, int max, String name) {
        if (size < min || size > max) {
            throw new IllegalArgumentException(name + " size must be within [" + min + ", " + max + "]");
        }
    }

    /** Copies a map into an unmodifiable view that keeps the caller's iteration order. */
    static <V> Map<String, V> orderedCopy(Map<String, V> source, String name) {
        if (source == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        Map<String, V> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            requireText(key, name + " key");
            if (value == null) {
                throw new IllegalArgumentException(name + " value must not be null");
            }
            copy.put(key, value);
        });
        return Collections.unmodifiableMap(copy);
    }

    static List<String> textList(List<String> source, String name) {
        if (source == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        source.forEach(item -> requireText(item, name + " item"));
        return List.copyOf(source);
    }
}
