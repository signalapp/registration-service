package org.signal.registration.util;

import org.apache.commons.lang3.tuple.Pair;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class MapUtil {

  public static <K, T, V> Map<T, V> mapKeys(Map<K, V> map, Function<K, T> fun) {
    return map.entrySet().stream().collect(Collectors.toMap(e -> fun.apply(e.getKey()), Map.Entry::getValue));
  }

  public static <K, V, R> Map<K, R> mapValues(Map<K, V> map, Function<V, R> fun) {
    return map.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> fun.apply(e.getValue())));
  }

  /**
   * Simultaneously transform and filter the values of a map
   *
   * @param map the map to transform
   * @param fun function that operates on values, returning empty if the value should be dropped
   *
   * @return the transformed map, with some values potentially dropped
   *
   * @param <K> Key type for the input map
   * @param <V> Value type for the input map
   * @param <R> Value type for the output map
   */
  public static <K, V, R> Map<K, R> filterMapValues(Map<K, V> map, Function<V, Optional<R>> fun) {
    return map.entrySet().stream().flatMap(entry ->
        fun.apply(entry.getValue()).stream().map(t -> Pair.of(entry.getKey(), t))
    ).collect(Collectors.toMap(Pair::getKey, Pair::getValue));
  }

  public static <V> Map<String, V> keysToUpperCase(Map<String, V> map) {
    return mapKeys(map, String::toUpperCase);
  }


}
