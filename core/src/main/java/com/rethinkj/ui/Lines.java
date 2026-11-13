package com.rethinkj.ui;

import java.util.Arrays;
import java.util.Objects;

/**
 * The lines of a {@link El#multiline multi-line} field's string, which its {@link Editor} keeps
 * across rebuilds and changes with each edit rather than finding them in the string again: where
 * each line starts, and, while the text wraps, how many rows of the field each takes.
 *
 * <p>The starts are a sorted array, so the line of a position is a binary search and the start of a
 * line a lookup. An edit replaces the starts inside the replaced range by those of the inserted
 * text and moves the starts after it by the difference in length: {@code System.arraycopy} and
 * one addition per line after the edit, about 0.1 ms for 100 000 lines, less than the copy of the
 * string that the edit makes anyway.
 *
 * <p>Rows: a line takes as many rows as wrapping breaks it into at the field's width, which only
 * shaping it tells. Until layout has shaped a line, it counts as an estimate from its length: its
 * characters times the average advance of the font, divided by the width, at least one row. A
 * Fenwick tree sums the rows, measured or estimated, so that the row at a height, the height of a
 * line and the height of the whole text cost a logarithm of the number of lines, also after layout
 * has measured a line that the estimate got wrong. An edit that changes the number of lines builds
 * the tree again, in one pass over the lines; another width, font or size forgets what was measured.
 *
 * <p>Without wrapping each line is one row, and the widest line that layout has shaped so far gives
 * the width of the text, which grows as longer lines come into view, as in editors; an edit of that
 * line, or another font, size, scale, tab size or features, forgets it, and the lines in view
 * measure it again.
 *
 * <p>Highlighting ({@link El#highlight}): the state each line starts in, known for the lines from
 * the first up to {@code known}, and per line the {@link Spans} the highlighter gave and the state
 * they were given for. An edit moves both arrays along with the starts, forgets the spans of the
 * lines it touched and lowers {@code known} to the first of them, without asking the highlighter
 * anything: the lines are asked again when a line at or after the edit is shaped, from the edit
 * down to that line. A line whose start state comes out as before keeps its spans.
 */
final class Lines {

  private int[] starts;
  private int count;
  private int length;

  // Wrapping: the rows each line takes at the width of `layout`, 0 where not measured yet.
  private Object layout; // what the rows depend on, null while the text does not wrap
  private float perRow; // the characters a row holds, estimated from the font
  private int[] rows;
  private long[] tree; // Fenwick tree over the rows each line counts as, from index 1
  private long total;

  private float widest; // the widest line measured, in physical pixels, without wrapping
  private int widestLine = -1;
  private Object measuredFor; // what the widths depend on: font, size, scale and so on

  // Highlighting: the key the view gave, the state each line starts in, valid for lines 0 to
  // known - 1, and the spans of each line, null where not asked or changed since.
  private Object highlightKey;
  private Object[] states;
  private int known;
  private Spans[] spans;

  /** For tests: how many lines have been shaped for the texts that showed these lines. */
  int shaped;

  /** For tests: how many cells the gutter of the fields that showed these lines built. */
  int gutterCells;

  /** For tests: how many times the highlighter was asked about a line of this text. */
  int highlighted;

  private Lines(int[] starts, int count, int length) {
    this.starts = starts;
    this.count = count;
    this.length = length;
  }

  /** The lines of a string, which must not contain a carriage return: one line per line feed. */
  static Lines of(String text) {
    int[] starts = new int[16];
    int count = 1;
    for (int at = text.indexOf('\n'); at >= 0; at = text.indexOf('\n', at + 1)) {
      if (count == starts.length) {
        starts = Arrays.copyOf(starts, count * 2);
      }
      starts[count++] = at + 1;
    }
    return new Lines(starts, count, text.length());
  }

  /** A copy, for the string with a composition in it, which an edit of the copy makes. */
  Lines copy() {
    Lines copy = new Lines(Arrays.copyOf(starts, Math.max(count, 1)), count, length);
    copy.widest = widest;
    copy.widestLine = widestLine;
    copy.measuredFor = measuredFor;
    if (states != null) {
      copy.highlightKey = highlightKey;
      copy.states = Arrays.copyOf(states, states.length);
      copy.spans = Arrays.copyOf(spans, spans.length);
      copy.known = known;
    }
    return copy;
  }

  int count() {
    return count;
  }

  int length() {
    return length;
  }

  /** Where line {@code line} starts in the string. */
  int start(int line) {
    return starts[line];
  }

  /** Where line {@code line} ends, before its line feed. */
  int end(int line) {
    return line + 1 < count ? starts[line + 1] - 1 : length;
  }

  /** The line that position {@code index} is on; a line feed belongs to the line it ends. */
  int line(int index) {
    int found = Arrays.binarySearch(starts, 0, count, index);
    return found >= 0 ? found : -found - 2;
  }

  /**
   * Follows an edit of the string: {@code removed} characters from {@code at} replaced by {@code
   * inserted}, which must not contain a carriage return.
   */
  void edit(int at, int removed, String inserted) {
    int first = line(at);
    int last = line(at + removed);
    int added = 0;
    for (int i = inserted.indexOf('\n'); i >= 0; i = inserted.indexOf('\n', i + 1)) {
      added++;
    }
    boolean within = first == last && added == 0; // the edit stays inside one line
    int before = rows != null && within ? effective(first) : 0;
    int oldCount = count;
    int newCount = count - (last - first) + added;
    int delta = inserted.length() - removed;
    if (newCount > starts.length) {
      starts = Arrays.copyOf(starts, Math.max(newCount, starts.length * 3 / 2));
    }
    if (states != null) {
      if (newCount > states.length) {
        states = Arrays.copyOf(states, starts.length);
        spans = Arrays.copyOf(spans, starts.length);
      }
      // The lines after the edit move along; those it touched are asked again.
      System.arraycopy(states, last + 1, states, first + 1 + added, oldCount - last - 1);
      System.arraycopy(spans, last + 1, spans, first + 1 + added, oldCount - last - 1);
      Arrays.fill(spans, first, first + 1 + added, null);
      if (newCount < oldCount) { // what lay after the last line
        Arrays.fill(states, newCount, oldCount, null);
        Arrays.fill(spans, newCount, oldCount, null);
      }
      known = Math.min(known, first + 1);
    }
    // The starts after the edit move to their place and by the difference in length.
    System.arraycopy(starts, last + 1, starts, first + 1 + added, oldCount - last - 1);
    for (int l = first + 1 + added; l < newCount; l++) {
      starts[l] += delta;
    }
    int l = first + 1;
    for (int i = inserted.indexOf('\n'); i >= 0; i = inserted.indexOf('\n', i + 1)) {
      starts[l++] = at + i + 1;
    }
    count = newCount;
    length += delta;
    if (widestLine >= first && widestLine <= last) {
      widest = 0;
      widestLine = -1;
    } else if (widestLine > last) {
      widestLine += newCount - oldCount;
    }
    if (rows == null) {
      return;
    }
    if (within) {
      rows[first] = 0; // not measured any more
      add(first, effective(first) - before);
    } else {
      int[] next = new int[Math.max(newCount, 1)];
      System.arraycopy(rows, 0, next, 0, first);
      System.arraycopy(rows, last + 1, next, first + 1 + added, oldCount - last - 1);
      rows = next; // the lines of the edit are not measured
      build();
    }
  }

  // ---- Highlighting

  /**
   * The spans of line {@code line} of {@code text}, the string these are the lines of, as {@code
   * highlighter} gives them: kept from before while the key, the line and the state it starts in
   * are the same; otherwise the lines from the first whose start state is not known are asked
   * first, keeping nothing but their end states.
   */
  Spans spans(int line, String text, Highlighter highlighter, Object key) {
    if (states == null || !Objects.equals(key, highlightKey)) {
      highlightKey = key;
      states = new Object[starts.length];
      spans = new Spans[starts.length];
      known = 1; // the first line starts in null
    }
    for (int l = known - 1; l < line; l++) {
      Spans kept = spans[l];
      if (kept != null && Objects.equals(kept.startState, states[l])) {
        states[l + 1] = kept.endState;
      } else {
        Spans scratch = new Spans(end(l) - start(l), true);
        states[l + 1] = ask(highlighter, text, l, scratch);
      }
      known = l + 2;
    }
    Spans kept = spans[line];
    if (kept != null && Objects.equals(kept.startState, states[line])) {
      return kept;
    }
    Spans out = new Spans(end(line) - start(line), false);
    Object endState = ask(highlighter, text, line, out);
    out.startState = states[line];
    out.endState = endState;
    spans[line] = out;
    if (known == line + 1 && line + 1 < count) {
      states[line + 1] = endState;
      known = line + 2;
    }
    return out;
  }

  private Object ask(Highlighter highlighter, String text, int line, Spans out) {
    highlighted++;
    return highlighter.line(text.substring(start(line), end(line)), states[line], out);
  }

  // ---- The widest line, without wrapping

  /**
   * Sets what the widths of lines depend on, without wrapping: another value forgets the widest
   * line, which the lines shaped next measure again.
   */
  void measuredFor(Object layout) {
    if (!Objects.equals(layout, measuredFor)) {
      measuredFor = layout;
      widest = 0;
      widestLine = -1;
    }
  }

  /** Layout shaped a line this wide. */
  void measured(int line, float width) {
    if (width > widest) {
      widest = width;
      widestLine = line;
    }
  }

  /** The widest line shaped since it was last edited, in physical pixels. */
  float widest() {
    return widest;
  }

  // ---- Rows, while wrapping

  /**
   * Sets what rows are counted for: the width and what else breaks lines, or null for a text that
   * does not wrap, whose lines are one row each. Another layout forgets the rows measured.
   *
   * @param perRow the characters a row holds, estimated
   */
  void wrap(Object layout, float perRow) {
    if (Objects.equals(layout, this.layout)) {
      return;
    }
    this.layout = layout;
    this.perRow = perRow;
    rows = layout == null ? null : new int[Math.max(count, 1)];
    tree = null;
    if (rows != null) {
      build();
    }
  }

  boolean wraps() {
    return rows != null;
  }

  /** Layout broke a line into this many rows. */
  void rows(int line, int measured) {
    if (rows != null && rows[line] != measured) {
      int before = effective(line);
      rows[line] = measured;
      add(line, measured - before);
    }
  }

  /** The rows a line counts as: as measured, or estimated. */
  private int effective(int line) {
    if (rows[line] > 0) {
      return rows[line];
    }
    double characters = end(line) - starts[line];
    return (int) Math.max(1, Math.min(Math.ceil(characters / perRow), Integer.MAX_VALUE));
  }

  /** The rows before line {@code line}, measured or estimated; its own index without wrapping. */
  long rowsBefore(int line) {
    if (rows == null) {
      return line;
    }
    long sum = 0;
    for (int i = line; i > 0; i -= i & -i) {
      sum += tree[i];
    }
    return sum;
  }

  /** All rows. */
  long totalRows() {
    return rows == null ? count : total;
  }

  /** The line that row {@code row} of the whole text belongs to, clamped to the lines. */
  int lineOfRow(long row) {
    if (rows == null) {
      return (int) Math.clamp(row, 0, count - 1);
    }
    if (row <= 0) {
      return 0;
    }
    // The last line whose rows before it are at most `row`: down the tree from its top.
    int line = 0;
    long left = row;
    for (int step = Integer.highestOneBit(count); step > 0; step >>= 1) {
      int next = line + step;
      if (next <= count && tree[next] <= left) {
        line = next;
        left -= tree[next];
      }
    }
    return Math.min(line, count - 1);
  }

  private void build() {
    tree = new long[count + 1];
    total = 0;
    for (int i = 1; i <= count; i++) {
      long value = effective(i - 1);
      total += value;
      tree[i] += value;
      int parent = i + (i & -i);
      if (parent <= count) {
        tree[parent] += tree[i];
      }
    }
  }

  private void add(int line, long delta) {
    total += delta;
    for (int i = line + 1; i <= count; i += i & -i) {
      tree[i] += delta;
    }
  }
}
