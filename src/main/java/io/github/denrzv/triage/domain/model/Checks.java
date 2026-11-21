package io.github.denrzv.triage.domain.model;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Package-private argument checks shared by the domain value types. Messages never include customer text. */
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

    static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }

    static int requireRange(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be within [" + min + ", " + max + "]");
        }
        return value;
    }

    /** Copies an enum set; an empty set is allowed. */
    static <E extends Enum<E>> Set<E> enumSet(Class<E> type, Collection<E> source, String name) {
        requireNonNull(source, name);
        EnumSet<E> copy = EnumSet.noneOf(type);
        for (E item : source) {
            copy.add(requireNonNull(item, name + " item"));
        }
        return Collections.unmodifiableSet(copy);
    }

    /** Requires a non-blank description for every constant of {@code type} and returns an unmodifiable copy. */
    static <E extends Enum<E>> Map<E, String> completeDefinitions(Class<E> type, Map<E, String> source, String name) {
        requireNonNull(source, name);
        EnumMap<E, String> copy = new EnumMap<>(type);
        for (E constant : type.getEnumConstants()) {
            copy.put(constant, requireText(source.get(constant), name + "." + constant.name()));
        }
        if (source.size() != copy.size()) {
            throw new IllegalArgumentException(name + " must define exactly the constants of " + type.getSimpleName());
        }
        return Collections.unmodifiableMap(copy);
    }
}
