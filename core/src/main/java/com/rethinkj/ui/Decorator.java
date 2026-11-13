package com.rethinkj.ui;

/**
 * Marks the lines of a {@link El#multiline multi-line} field that are in view, given with {@link
 * El#decorate}: the line of the caret, matches of a search, a pair of brackets, an error. Unlike a
 * {@link Highlighter} it is asked anew for each line in view and a few around them whenever the
 * field lays out its rows, and keeps nothing, so it may depend on anything in the app's state.
 */
@FunctionalInterface
public interface Decorator {

  /**
   * Says what is drawn behind and under the line.
   *
   * @param line the line's index, from 0
   * @param start where the line starts in the field's string, so that a position of the string,
   *     such as the caret's, is {@code start + i} in the line
   * @param text the line's string, without its line feed
   * @param out takes backgrounds and underlines; colours and fonts are a highlighter's
   */
  void line(int line, int start, String text, Spans out);
}
