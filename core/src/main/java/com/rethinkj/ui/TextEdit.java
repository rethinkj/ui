package com.rethinkj.ui;

import java.util.Objects;

/**
 * One replacement in a text: UTF-16 characters {@code from} to {@code to} replaced by {@code
 * text}. Used both to apply several replacements in one undo step ({@link App#edit(El,
 * java.util.List, int)}) and to read the edits a field itself made ({@link
 * FieldState#editsSince}).
 *
 * @param from start offset in UTF-16 units, 0 or more
 * @param to exclusive end offset in UTF-16 units, at least {@code from}
 * @param text replacement text
 */
public record TextEdit(int from, int to, String text) {
  public TextEdit {
    Objects.requireNonNull(text, "text");
    if (from < 0 || to < from) {
      throw new IllegalArgumentException(
          "from must be 0 or more and to at least from, were " + from + " and " + to);
    }
  }
}
