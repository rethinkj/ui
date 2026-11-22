package com.rethinkj.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bracket matching and foldable bracket ranges for {@link El}. */
final class Brackets {

  private Brackets() {}

  /** The most characters matching looks through from the bracket, each way. */
  static final int REACH = 100_000;

  private static final String BRACKETS = "([{)]}";

  /**
   * The bracket just before {@code caret}, or else just after it, and its partner in {@code text}:
   * among those {@code highlighter} marks, read from {@code lines}, where it marks brackets and
   * {@code lines} know the state the bracket's line starts in; among all of the text's otherwise,
   * which costs no pass over the lines before the bracket.
   *
   * @param lines the lines of {@code text}, whose highlighting they keep; null for none
   */
  static int[] matching(String text, int caret, Lines lines, Highlighter highlighter, Object key) {
    boolean marked = highlighter != null && highlighter.marksBrackets() && lines != null;
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
      int first = marked ? lines.line(at) : -1;
      if (!marked || !lines.knows(first, key)) {
        if (marked) {
          lines.unread(first);
        }
        return plain(text, at, own, other, step);
      }
      Spans spans = lines.spans(first, text, highlighter, key);
      if (spans == null) {
        lines.unread(first);
        return plain(text, at, own, other, step);
      }
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
        if (spans == null) {
          lines.unread(line); // out of the frame's budget: the next build goes on from there
          break;
        }
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

  private static int[] plain(String text, int at, char own, char other, int step) {
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

  private static boolean has(Spans spans, int offset) {
    int[] marks = spans.brackets();
    return Arrays.binarySearch(marks, 0, spans.bracketCount, offset) >= 0;
  }

  /**
   * Foldable bracket pairs of {@code text}: each pair's lines start on the line of its opening
   * bracket and end on the line before its closing bracket ({@code closeLine > openLine + 1}),
   * sorted by their first line, with the widest pair when several start on one line.
   * Stops at the frame's highlighting budget when {@code lines} has one, sets {@link Lines#unread}
   * so the next frame continues, and caches the complete result on {@code lines}.
   */
  static El.BracketFolds folds(String text, Lines lines, Highlighter highlighter, Object key) {
    boolean marked = highlighter != null && highlighter.marksBrackets();
    Object cacheKey = marked ? key : null;
    if (lines != null) {
      El.BracketFolds cached = lines.bracketFolds(marked, cacheKey);
      if (cached != null) {
        return cached;
      }
    }
    int lineCount = lines != null ? lines.count() : countLines(text);
    if (lineCount <= 2) {
      El.BracketFolds empty = new El.BracketFolds(List.of(), lineCount, true);
      if (lines != null) {
        lines.bracketFolds(marked, cacheKey, empty);
      }
      return empty;
    }
    El.BracketFolds result = marked
        ? markedFolds(text, lines != null ? lines : Lines.of(text), highlighter, key)
        : plainFolds(text, lineCount);
    if (lines != null && result.complete()) {
      lines.bracketFolds(marked, cacheKey, result);
    }
    return result;
  }

  private static El.BracketFolds markedFolds(
      String text, Lines lines, Highlighter highlighter, Object key) {
    Pairs pairs = lines.foldScan != null && Objects.equals(lines.foldScan.key, key)
        ? lines.foldScan
        : new Pairs(key);
    lines.foldScan = null;
    int count = lines.count();
    for (int line = pairs.line; line < count; line++) {
      Spans spans = lines.spans(line, text, highlighter, key);
      if (spans == null) {
        lines.unread(line);
        pairs.line = line;
        lines.foldScan = pairs;
        return new El.BracketFolds(pairs.folds(), line, false);
      }
      int start = lines.start(line);
      int[] marks = spans.brackets();
      int n = spans.bracketCount;
      for (int k = 0; k < n; k++) {
        int i = start + marks[k];
        if ((k == 0 || marks[k] != marks[k - 1]) && i < text.length()) { // a mark given twice
          pairs.step(text.charAt(i), i, line);
        }
      }
    }
    return new El.BracketFolds(pairs.folds(), count, true);
  }

  private static El.BracketFolds plainFolds(String text, int lineCount) {
    Pairs pairs = new Pairs(null);
    int line = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\n') {
        line++;
      } else {
        pairs.step(c, i, line);
      }
    }
    return new El.BracketFolds(pairs.folds(), lineCount, true);
  }

  /** Pairs brackets per kind as they come, keeping the widest pair that spans lines per line. */
  static final class Pairs {
    private final Object key; // the highlight key of a scan of marked brackets
    private int line; // the line a stopped scan goes on from
    private final int[][] opens = new int[3][16]; // per kind: the open brackets' positions
    private final int[][] lines = new int[3][16]; // and their lines
    private final int[] depths = new int[3];
    private final Map<Integer, El.BracketFold> byFirst = new HashMap<>();

    Pairs(Object key) {
      this.key = key;
    }

    void step(char c, int at, int line) {
      int kind = BRACKETS.indexOf(c);
      if (kind < 0) {
        return;
      }
      if (kind < 3) {
        int depth = depths[kind];
        if (depth == opens[kind].length) {
          opens[kind] = Arrays.copyOf(opens[kind], depth * 2);
          lines[kind] = Arrays.copyOf(lines[kind], depth * 2);
        }
        opens[kind][depth] = at;
        lines[kind][depth] = line;
        depths[kind] = depth + 1;
      } else if (depths[kind - 3] > 0) {
        int depth = --depths[kind - 3];
        int first = lines[kind - 3][depth];
        if (line > first + 1) {
          El.BracketFold fold =
              new El.BracketFold(new El.Fold(first, line - 1), opens[kind - 3][depth], at);
          byFirst.merge(first, fold, (a, b) -> a.close() >= b.close() ? a : b);
        }
      }
    }

    List<El.BracketFold> folds() {
      List<El.BracketFold> out = new ArrayList<>(byFirst.values());
      out.sort(Comparator.comparingInt(f -> f.lines().first()));
      return List.copyOf(out);
    }
  }

  private static int countLines(String text) {
    int count = 1;
    for (int at = text.indexOf('\n'); at >= 0; at = text.indexOf('\n', at + 1)) {
      count++;
    }
    return count;
  }
}
