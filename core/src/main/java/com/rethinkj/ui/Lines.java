package com.rethinkj.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * down to that line. A line whose start state comes out as before keeps its spans. When attached
 * to an {@link App}, a frame asks the highlighter about at most {@link #HIGHLIGHT_BUDGET} lines
 * across its fields; once that budget is spent, {@link #spans} returns {@code null} so the view
 * draws unreached lines plain and catches up in the following frames.
 */
final class Lines {

  /** The most lines one frame asks a highlighter about before leaving the rest for the next. */
  static final int HIGHLIGHT_BUDGET = 4_000;

  /**
   * Per-frame line budget shared by the {@link Lines} of one {@link App}: refilled at the start of
   * each frame, so highlighting stays bounded across every field and pass in that frame.
   */
  static final class Budget {
    private int remaining = HIGHLIGHT_BUDGET;

    void reset() {
      remaining = HIGHLIGHT_BUDGET;
    }

    boolean take() {
      if (remaining <= 0) {
        return false;
      }
      remaining--;
      return true;
    }
  }

  private int[] starts;
  private int count;
  private int length;

  private static final int[] NO_LINES = new int[0];
  private static final El.Fold[] NO_FOLDS = new El.Fold[0];

  // Wrapping: the rows each line takes at the width of `layout`, 0 where not measured yet.
  private Object layout; // what the rows depend on, null while the text does not wrap
  private float perRow; // the characters a row holds, estimated from the font
  private int[] rows;
  private long[] tree; // Fenwick tree over the rows each line counts as, from index 1
  private long total;

  // Folding: raw folds last applied for foldsCount lines, merged hidden line intervals
  // [hiddenFirst[i]..hiddenLast[i]] (sorted, disjoint, non-adjacent, each >= 1), and the outermost
  // fold whose visible first line (hiddenFirst[i] - 1) carries the mark for that hidden range.
  private List<El.Fold> rawFolds = List.of();
  private int foldsCount = -1;
  private int[] hiddenFirst = NO_LINES;
  private int[] hiddenLast = NO_LINES;
  private El.Fold[] visibleFolds = NO_FOLDS;

  private float widest; // the widest line measured, in physical pixels, without wrapping
  private int widestLine = -1;
  private Object measuredFor; // what the widths depend on: font, size, scale and so on
  // The widths of the chunks of long lines, by line: estimated, or as measured once shaped, with
  // the spans whose fonts they were measured with.
  private Map<Integer, Chunked> chunkWidths = new HashMap<>();

  private static final class Chunked {
    final Spans spans;
    final float[] widths;

    Chunked(Spans spans, float[] widths) {
      this.spans = spans;
      this.widths = widths;
    }
  }

  // Highlighting: the key the view gave, the state each line starts in, valid for lines 0 to
  // known - 1, and the spans of each line, null where not asked or changed since.
  private Object highlightKey;
  private Object[] states;
  private int known;
  private Spans[] spans;
  private Budget budget;
  // The line a bracket lookup could not read the marks of, for want of its state or of the
  // frame's budget, or -1: the app builds the view again once the state is known.
  int unread = -1;
  // Complete bracket fold ranges cached for this text and highlight key.
  private Object bracketFoldsKey;
  private boolean bracketFoldsMarked;
  private El.BracketFolds bracketFolds;
  // The scan for them that the frame's budget stopped, which the next frame goes on with.
  Brackets.Pairs foldScan;

  /** For tests: how many lines have been shaped for the texts that showed these lines. */
  int shaped;

  /** For tests: how many characters of these lines have been handed to the shaper. */
  long shapedCharacters;

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
    copy.chunkWidths = new HashMap<>(chunkWidths);
    copy.budget = budget;
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
    bracketFolds = null;
    foldScan = null;
    if (widestLine >= first && widestLine <= last) {
      widest = 0;
      widestLine = -1;
    } else if (widestLine > last) {
      widestLine += newCount - oldCount;
    }
    if (!chunkWidths.isEmpty()) { // the lines of the edit are measured again, those after move
      Map<Integer, Chunked> moved = new HashMap<>();
      chunkWidths.forEach((line, kept) -> {
        if (line < first || line > last) {
          moved.put(line > last ? line + newCount - oldCount : line, kept);
        }
      });
      chunkWidths = moved;
    }
    if (!within && hasFolds()) {
      foldsCount = count;
      normalizeFolds();
    }
    if (rows == null) {
      if (!within && tree != null) {
        if (hasFolds()) {
          build();
        } else {
          tree = null;
        }
      }
      return;
    }
    if (within) {
      rows[first] = 0; // not measured any more
      add(first, (long) effective(first) - before);
    } else {
      int[] next = new int[Math.max(newCount, 1)];
      System.arraycopy(rows, 0, next, 0, first);
      System.arraycopy(rows, last + 1, next, first + 1 + added, oldCount - last - 1);
      rows = next; // the lines of the edit are not measured
      build();
    }
  }

  // ---- Highlighting

  /** Sets the per-frame highlighting budget of the {@link App} these lines belong to, or null. */
  void budget(Budget budget) {
    this.budget = budget;
  }

  /**
   * The spans of line {@code line} of {@code text}, the string these are the lines of, as {@code
   * highlighter} gives them: kept from before while the key, the line and the state it starts in
   * are the same; otherwise the lines from the first whose start state is not known are asked
   * first, keeping nothing but their end states. Returns {@code null} when the frame's line budget
   * runs out before {@code line} is highlighted; {@code known} still reflects every line whose
   * start state was computed.
   */
  Spans spans(int line, String text, Highlighter highlighter, Object key) {
    if (states == null || !Objects.equals(key, highlightKey)) {
      highlightKey = key;
      states = new Object[starts.length];
      spans = new Spans[starts.length];
      known = 1; // the first line starts in null
      chunkWidths.clear(); // measured with the old spans, which they would keep
      bracketFolds = null;
      foldScan = null;
      foldScan = null;
    }
    for (int l = known - 1; l < line; l++) {
      Spans kept = spans[l];
      if (kept != null && Objects.equals(kept.startState, states[l])) {
        states[l + 1] = kept.endState;
      } else {
        if (!takeBudget()) {
          return null;
        }
        Spans scratch = new Spans(end(l) - start(l), true);
        states[l + 1] = ask(highlighter, text, l, scratch);
      }
      known = l + 2;
    }
    Spans kept = spans[line];
    if (kept != null && Objects.equals(kept.startState, states[line])) {
      return kept;
    }
    if (!takeBudget()) {
      return null;
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

  /**
   * Notes that a lookup could not read line {@code line}; the first such line counts, so that a
   * bracket lookup and the scan for folds in one build do not hide each other's.
   */
  void unread(int line) {
    unread = unread < 0 ? line : Math.min(unread, line);
  }

  /** Whether the line of {@link #unread} has its state now. */
  boolean unreadKnown() {
    return unread >= 0 && unread < known && states != null;
  }

  /** Cached complete bracket fold ranges for this text, or null if not cached for this key. */
  El.BracketFolds bracketFolds(boolean marked, Object key) {
    return bracketFolds != null
            && bracketFoldsMarked == marked
            && Objects.equals(bracketFoldsKey, key)
        ? bracketFolds
        : null;
  }

  void bracketFolds(boolean marked, Object key, El.BracketFolds folds) {
    bracketFoldsMarked = marked;
    bracketFoldsKey = key;
    bracketFolds = folds;
  }

  private boolean takeBudget() {
    return budget == null || budget.take();
  }

  /** Whether the state line {@code line} starts in is known for {@code key}. */
  boolean knows(int line, Object key) {
    return states != null && line < known && Objects.equals(key, highlightKey);
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
      chunkWidths.clear();
    }
  }

  /** The widths of the chunks of a long line with these spans, or null if none are kept. */
  float[] chunkWidths(int line, Spans spans) {
    Chunked kept = chunkWidths.get(line);
    return kept != null && kept.spans == spans ? kept.widths : null;
  }

  void chunkWidths(int line, Spans spans, float[] widths) {
    chunkWidths.put(line, new Chunked(spans, widths));
  }

  /** Layout shaped a line this wide. */
  void measured(int line, float width) {
    if (line == widestLine || width > widest) {
      widest = width;
      widestLine = line;
    }
  }

  /** The widest line shaped since it was last edited, in physical pixels. */
  float widest() {
    return widest;
  }

  // ---- Folding

  /**
   * Sets the folded line ranges of this text: for each fold, its first line stays shown and lines
   * {@code first + 1} through {@code last} are hidden. Unsorted, overlapping, nested and
   * out-of-range folds are normalized so that a line is hidden if any fold hides it and the
   * outermost fold owns the visible header line.
   */
  void folds(List<El.Fold> folds) {
    List<El.Fold> next = folds == null ? List.of() : folds;
    if (foldsCount == count && next.equals(rawFolds)) {
      return;
    }
    rawFolds = next;
    foldsCount = count;
    int[] oldFirst = hiddenFirst;
    int[] oldLast = hiddenLast;
    normalizeFolds();
    if (!Arrays.equals(oldFirst, hiddenFirst) || !Arrays.equals(oldLast, hiddenLast)) {
      if (widestLine >= 0 && isHidden(widestLine)) {
        widest = 0;
        widestLine = -1;
      }
      if (rows != null || hasFolds()) {
        build();
      } else {
        tree = null;
      }
    }
  }

  private void normalizeFolds() {
    if (rawFolds.isEmpty() || count <= 1) {
      hiddenFirst = NO_LINES;
      hiddenLast = NO_LINES;
      visibleFolds = NO_FOLDS;
      return;
    }
    List<El.Fold> active = new ArrayList<>();
    for (El.Fold f : rawFolds) {
      if (f.first() < count - 1) {
        active.add(f);
      }
    }
    if (active.isEmpty()) {
      hiddenFirst = NO_LINES;
      hiddenLast = NO_LINES;
      visibleFolds = NO_FOLDS;
      return;
    }
    active.sort((a, b) -> {
      int c = Integer.compare(a.first(), b.first());
      return c != 0 ? c : Integer.compare(b.last(), a.last());
    });
    int[] firsts = new int[active.size()];
    int[] lasts = new int[active.size()];
    El.Fold[] headers = new El.Fold[active.size()];
    int n = 0;
    for (El.Fold f : active) {
      int s = f.first() + 1;
      int e = Math.min(f.last(), count - 1);
      if (n > 0 && s <= lasts[n - 1] + 1) {
        if (e > lasts[n - 1]) {
          lasts[n - 1] = e;
        }
      } else {
        firsts[n] = s;
        lasts[n] = e;
        headers[n] = f;
        n++;
      }
    }
    hiddenFirst = Arrays.copyOf(firsts, n);
    hiddenLast = Arrays.copyOf(lasts, n);
    visibleFolds = Arrays.copyOf(headers, n);
  }

  /** The lines not hidden in a fold. */
  int shownCount() {
    int shown = count;
    for (int i = 0; i < hiddenFirst.length; i++) {
      shown -= hiddenLast[i] - hiddenFirst[i] + 1;
    }
    return shown;
  }

  boolean hasFolds() {
    return hiddenFirst.length > 0;
  }

  /** Whether line {@code line} is hidden inside a fold. */
  boolean isHidden(int line) {
    return hiddenIndex(line) >= 0;
  }

  private int hiddenIndex(int line) {
    if (hiddenFirst.length == 0 || line <= 0) {
      return -1;
    }
    int found = Arrays.binarySearch(hiddenFirst, line);
    int i = found >= 0 ? found : -found - 2;
    return i >= 0 && line <= hiddenLast[i] ? i : -1;
  }

  /** The outermost fold whose shown first line is {@code line}, or null. */
  El.Fold foldAt(int line) {
    if (hiddenFirst.length == 0) {
      return null;
    }
    int found = Arrays.binarySearch(hiddenFirst, line + 1);
    return found >= 0 ? visibleFolds[found] : null;
  }

  /** The first visible line after {@code line}, or -1 if none exists. */
  int nextVisible(int line) {
    int next = line + 1;
    if (next >= count) {
      return -1;
    }
    int i = hiddenIndex(next);
    if (i >= 0) {
      next = hiddenLast[i] + 1;
    }
    return next < count ? next : -1;
  }

  /** The last visible line before {@code line}, or -1 if none exists. */
  int prevVisible(int line) {
    int prev = line - 1;
    if (prev < 0) {
      return -1;
    }
    int i = hiddenIndex(prev);
    return i >= 0 ? hiddenFirst[i] - 1 : prev;
  }

  /** Line {@code line} if visible, else the visible line starting the fold that hides it. */
  int visibleAtOrBefore(int line) {
    line = Math.clamp(line, 0, count - 1);
    int i = hiddenIndex(line);
    return i >= 0 ? hiddenFirst[i] - 1 : line;
  }

  /** All raw folds that hide at least one line in {@code [firstLine, lastLine]}. */
  List<El.Fold> foldsHiding(int firstLine, int lastLine) {
    if (hiddenFirst.length == 0) {
      return List.of();
    }
    List<El.Fold> out = new ArrayList<>();
    for (El.Fold f : rawFolds) {
      if (f.first() < count - 1) {
        int s = f.first() + 1;
        int e = Math.min(f.last(), count - 1);
        if (s <= lastLine && firstLine <= e && !out.contains(f)) {
          out.add(f);
        }
      }
    }
    return out;
  }

  /** All raw folds that hide {@code lineA} or {@code lineB}. */
  List<El.Fold> foldsHidingLine(int lineA, int lineB) {
    if (hiddenFirst.length == 0 || !isHidden(lineA) && !isHidden(lineB)) {
      return List.of();
    }
    List<El.Fold> out = new ArrayList<>();
    for (El.Fold f : rawFolds) {
      if (f.first() < count - 1) {
        int s = f.first() + 1;
        int e = Math.min(f.last(), count - 1);
        if ((lineA >= s && lineA <= e || lineB >= s && lineB <= e) && !out.contains(f)) {
          out.add(f);
        }
      }
    }
    return out;
  }

  // ---- Rows, while wrapping or folded

  /**
   * Sets what rows are counted for: the width and what else breaks lines, or null for a text that
   * does not wrap, whose visible lines are one row each. Another layout forgets the rows measured.
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
    if (rows != null || hasFolds()) {
      build();
    } else {
      tree = null;
    }
  }

  boolean wraps() {
    return rows != null;
  }

  /** Layout broke a line into this many rows. */
  void rows(int line, int measured) {
    if (rows != null && !isHidden(line) && rows[line] != measured) {
      int before = effective(line);
      rows[line] = measured;
      add(line, (long) measured - before);
    }
  }

  /** The rows a line counts as: 0 when hidden, 1 without wrapping, else measured or estimated. */
  private int effective(int line) {
    if (isHidden(line)) {
      return 0;
    }
    if (rows == null) {
      return 1;
    }
    if (rows[line] > 0) {
      return rows[line];
    }
    double characters = (double) end(line) - starts[line];
    return (int) Math.clamp(Math.ceil(characters / perRow), 1, Integer.MAX_VALUE);
  }

  /** The rows before line {@code line}, measured or estimated; its own index without wrapping or folds. */
  long rowsBefore(int line) {
    if (tree == null) {
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
    return tree == null ? count : total;
  }

  /** The visible line that row {@code row} of the whole text belongs to, clamped to the visible lines. */
  int lineOfRow(long row) {
    if (tree == null) {
      return Math.clamp(row, 0, count - 1);
    }
    if (row <= 0) {
      return 0;
    }
    if (row >= total) {
      return visibleAtOrBefore(count - 1);
    }
    // The last line whose rows before it are at most `row`: down the tree from its top.
    // Because row < total and every hidden line adds 0, the next line's prefix exceeds row, so
    // this line itself has at least one row and is visible.
    int line = 0;
    long left = row;
    for (int step = Integer.highestOneBit(count); step > 0; step >>= 1) {
      int next = line + step;
      if (next <= count && tree[next] <= left) {
        line = next;
        left -= tree[next];
      }
    }
    return line;
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
