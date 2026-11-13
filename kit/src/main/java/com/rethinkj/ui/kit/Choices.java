package com.rethinkj.ui.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * What the typed forms of the choice components share: they find the selected value's index, make
 * the texts and turn the handler of a value into one of an index, and hand those on to the forms
 * with indexes.
 */
final class Choices {

  private Choices() {}

  /** The index of the first value equal to {@code selected}, -1 for null or one not there. */
  static <T> int index(List<T> values, T selected) {
    return selected == null ? -1 : values.indexOf(selected);
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
