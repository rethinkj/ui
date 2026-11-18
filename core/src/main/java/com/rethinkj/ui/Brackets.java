package com.rethinkj.ui;

import java.util.Arrays;

/** Bracket matching for {@link El#matchingBracket}. */
final class Brackets {

  private Brackets() {}

  /** The most characters matching looks through from the bracket, each way. */
  static final int REACH = 100_000;

  private static final String BRACKETS = "([{)]}";

  /**
   * The bracket just before {@code caret}, or else just after it, and its partner in {@code text}:
   * among those {@code highlighter} marks, read from {@code lines}, where it marks brackets;
   * among all of the text's otherwise.
   *
   * @param lines the lines of {@code text}, whose highlighting they keep; null for lines made here
   */
  static int[] matching(String text, int caret, Lines lines, Highlighter highlighter, Object key) {
    boolean marked = highlighter != null && highlighter.marksBrackets();
    if (marked && lines == null) {
      lines = Lines.of(text);
    }
    for (int at : new int[] {caret - 1, caret}) {
      if (at < 0 || at >= text.length()) {
        continue;
      }
      int kind = BRACKETS.indexOf(text.charAt(at));
      if (kind < 0) {
        continue;
      }
      char own = text.charAt(at);
      char other = BRACKETS.charAt((kind + 3) % 6);
      int step = kind < 3 ? 1 : -1;
      if (!marked) {
        int depth = 0;
        for (int i = at; i >= 0 && i < text.length() && Math.abs(i - at) < REACH; i += step) {
          char c = text.charAt(i);
          if (c == own) {
            depth++;
          } else if (c == other && --depth == 0) {
            return new int[] {at, i};
          }
        }
        return new int[] {at};
      }
      int first = lines.line(at);
      Spans spans = lines.spans(first, text, highlighter, key);
      if (!has(spans, at - lines.start(first))) {
        continue; // in a string or a comment
      }
      int depth = 0;
      for (int line = first; line >= 0 && line < lines.count(); line += step) {
        int start = lines.start(line);
        if (step > 0 ? start - at >= REACH : at - lines.end(line) >= REACH) {
          break; // asks nothing about lines beyond the reach
        }
        spans = line == first ? spans : lines.spans(line, text, highlighter, key);
        int[] marks = spans.brackets();
        int n = spans.bracketCount;
        for (int k = step > 0 ? 0 : n - 1; k >= 0 && k < n; k += step) {
          int i = start + marks[k];
          if ((i - at) * step < 0 || k != (step > 0 ? 0 : n - 1) && marks[k] == marks[k - step]) {
            continue; // before the bracket, or given twice
          }
          if (Math.abs(i - at) >= REACH) {
            return new int[] {at};
          }
          char c = text.charAt(i);
          if (c == own) {
            depth++;
          } else if (c == other && --depth == 0) {
            return new int[] {at, i};
          }
        }
      }
      return new int[] {at};
    }
    return new int[0];
  }

  private static boolean has(Spans spans, int offset) {
    int[] marks = spans.brackets();
    return Arrays.binarySearch(marks, 0, spans.bracketCount, offset) >= 0;
  }
}
