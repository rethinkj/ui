package com.rethinkj.ui.kit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * What the typed forms of the choice components share: they keep each value once, find the
 * selected value's index, make the texts and turn the handler of a value into one of an index, and
 * hand those on to the forms with indexes.
 */
final class Choices {

  private Choices() {}

  /**
   * The values with each kept once, at its first place, by {@code equals}: the forms with indexes
   * step from index to index, and from the second of two equal values the next build would find
   * the first selected again, so that the keys never got past them. The list itself where its
   * values differ.
   */
  static <T> List<T> distinct(List<T> values) {
    LinkedHashSet<T> once = new LinkedHashSet<>(values);
    return once.size() == values.size() ? values : new ArrayList<>(once);
  }

  /**
   * The index of the first value equal to {@code selected}, null among them too; -1 for one not
   * there.
   */
  static <T> int index(List<T> values, T selected) {
    for (int i = 0; i < values.size(); i++) {
      if (Objects.equals(values.get(i), selected)) {
        return i;
      }
    }
    return -1;
  }

  static <T> List<String> texts(List<T> values, Function<? super T, String> text) {
    List<String> texts = new ArrayList<>(values.size());
    for (T v : values) {
      texts.add(text.apply(v));
    }
    return texts;
  }

  /** A handler of the index into {@code values}, null for a null handler. */
  static <T> IntConsumer handler(List<T> values, Consumer<? super T> onSelect) {
    return onSelect == null ? null : i -> onSelect.accept(values.get(i));
  }
}
