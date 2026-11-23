package com.rethinkj.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
  private static final El.Block[] NO_BLOCKS = new El.Block[0];

  // Wrapping: the rows each line takes at the width of `layout`, 0 where not measured yet.
  private Object layout; // what the rows depend on, null while the text does not wrap
  private float perRow; // the characters a row holds, estimated from the font
  private int[] rows;
  private long[] tree; // Fenwick tree over the rows each line counts as, from index 1
  private long total;

  // Folding and line blocks: raw lists last applied for count lines.
  // foldHiddenFirst/Last hold merged fold-hidden intervals (each >= 1) with visibleFolds holding
  // the outermost fold for each. hiddenFirst/Last hold the merged union of fold-hidden and
  // active REPLACE block intervals (may start at 0).
  private List<El.Fold> rawFolds = List.of();
  private int foldsCount = -1;
  private int[] foldHiddenFirst = NO_LINES;
  private int[] foldHiddenLast = NO_LINES;
  private El.Fold[] visibleFolds = NO_FOLDS;
  private int[] hiddenFirst = NO_LINES;
  private int[] hiddenLast = NO_LINES;

  // Blocks between lines: active blocks sorted by first line (INSERT before REPLACE, list order
  // among INSERTs at one line), their heights in physical pixels, and a Fenwick tree over the
  // count + 1 line slots (slot `count` is after the last line). Null when there are no blocks.
  private List<El.Block> rawBlocks = List.of();
  private int blocksCount = -1;
  private Object blockLayout;
  private long blockLineHeight = 1;
  private Map<BlockKey, Long> measuredBlocks = new HashMap<>();
  private El.Block[] activeBlocks = NO_BLOCKS;
  private int[] activeBlockOrdinals = NO_LINES;
  private int[] blockSlots = NO_LINES;
  private int[] blockSlotStarts = NO_LINES;
  private long[] blockHeights;
  private long[] slotPixels;
  private long[] pixelTree; // 1-based Fenwick tree over slotPixels[0..count]
  private long totalPixels;

  private record BlockKey(El.Block block, int ordinal) {}

  private float widest; // the widest line measured, in physical pixels, without wrapping
  private int widestLine = -1;
  private Object measuredFor; // what the widths depend on: font, size, scale and so on
  // The widths of the chunks of long lines, by line: estimated, or as measured once shaped, with
  // the spans whose fonts they were measured with.
  private Map<Integer, Chunked> chunkWidths = new HashMap<>();
  // What the lines were last measured with, moved by each edit; null in a copy, which takes the
  // next ones as measured: while composing, hints are shown at their offsets in the field's text.
  private List<El.Hint> hints = List.of();

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
    copy.hints = null;
    copy.blockLayout = blockLayout;
    copy.blockLineHeight = blockLineHeight;
    if (!measuredBlocks.isEmpty()) {
      copy.measuredBlocks = new HashMap<>(measuredBlocks);
    }
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
    if (hints != null && !hints.isEmpty()) {
      int oldEnd = at + removed;
      int newEnd = at + inserted.length();
      List<El.Hint> moved = new ArrayList<>(hints.size());
      for (El.Hint h : hints) {
        int pos = h.at();
        if (pos > length - delta) {
          continue; // past the end, so neither shown nor counted, and moving it could overflow
        }
        int mapped = pos <= at ? pos : pos >= oldEnd ? pos + delta : Math.min(pos, newEnd);
        moved.add(mapped == pos ? h : new El.Hint(mapped, h.affinity(), h.text()));
      }
      hints = moved;
    }
    if (!within && (hasHidden() || hasBlocks())) {
      foldsCount = count;
      blocksCount = count;
      normalizeFoldsAndBlocks();
    }
    if (rows == null) {
      if (!within && (tree != null || hasHidden())) {
        if (hasHidden()) {
          build();
        } else {
          tree = null;
        }
      }
      if (!within && (pixelTree != null || hasBlocks())) {
        if (hasBlocks()) {
          buildPixels();
        } else {
          clearPixels();
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
      if (pixelTree != null || hasBlocks()) {
        if (hasBlocks()) {
          buildPixels();
        } else {
          clearPixels();
        }
      }
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

  /**
   * Sets the inline hints ({@link El#hints}) the lines are measured with. Lines whose hints changed
   * forget their rows and widths; hints stored from before are mapped through each {@link #edit},
   * so unchanged lines after an edit keep their measurements.
   */
  void hints(List<El.Hint> next) {
    if (next == hints) {
      return;
    }
    if (hints != null && !next.equals(hints)) {
      // Counted, so [h, h] to [h] changes; hints past the end are not shown and change nothing.
      Map<El.Hint, Integer> counts = new HashMap<>();
      for (El.Hint hint : hints) {
        if (hint.at() <= length) {
          counts.merge(hint, 1, Integer::sum);
        }
      }
      for (El.Hint hint : next) {
        if (hint.at() <= length) {
          counts.merge(hint, -1, Integer::sum);
        }
      }
      counts.forEach((hint, count) -> {
        if (count != 0) {
          forget(line(hint.at()));
        }
      });
    }
    hints = next;
  }

  /** Forgets what was measured of line {@code line}: its rows, chunk widths and widest width. */
  private void forget(int line) {
    if (line == widestLine) {
      widest = 0;
      widestLine = -1;
    }
    chunkWidths.remove(line);
    if (rows != null && rows[line] > 0) {
      int before = effective(line);
      rows[line] = 0;
      add(line, (long) effective(line) - before);
    }
  }

  // ---- Folding and blocks

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
    reconcileFoldsAndBlocks();
  }

  /**
   * Sets the blocks between lines of this text: {@link El.Block.Placement#INSERT} blocks sit
   * before their line (or after the last line when {@code first == count}), and {@link
   * El.Block.Placement#REPLACE} blocks hide every line of {@code first..last} inclusive. A block
   * whose line is hidden by an enclosing fold (or an INSERT strictly inside a replaced range) is
   * hidden and takes 0 pixels.
   */
  void blocks(List<El.Block> blocks) {
    List<El.Block> next = blocks == null ? List.of() : blocks;
    if (blocksCount == count && next.equals(rawBlocks)) {
      return;
    }
    rawBlocks = next;
    blocksCount = count;
    if (!measuredBlocks.isEmpty()) { // blocks that moved or left: their heights go with them
      Set<El.Block> kept = new HashSet<>(next);
      measuredBlocks.keySet().removeIf(key -> !kept.contains(key.block()));
    }
    reconcileFoldsAndBlocks();
  }

  /**
   * Sets what measured block heights depend on (content width, scale, font, size and line height):
   * another layout forgets cached heights and re-estimates from {@code lineHeight}.
   */
  void blocksLayout(Object layout, float lineHeight) {
    long lh = Math.max(1, Math.round(lineHeight));
    layout = layout == null ? blockLayout : layout; // not laid out yet: the width is not known
    if (Objects.equals(layout, blockLayout) && lh == blockLineHeight) {
      return;
    }
    blockLayout = layout;
    blockLineHeight = lh;
    measuredBlocks.clear();
    if (hasBlocks()) {
      buildPixels();
    }
  }

  private void reconcileFoldsAndBlocks() {
    int[] oldFirst = hiddenFirst;
    int[] oldLast = hiddenLast;
    normalizeFoldsAndBlocks();
    if (!Arrays.equals(oldFirst, hiddenFirst) || !Arrays.equals(oldLast, hiddenLast)) {
      if (widestLine >= 0 && isHidden(widestLine)) {
        widest = 0;
        widestLine = -1;
      }
      if (rows != null || hasHidden()) {
        build();
      } else {
        tree = null;
      }
    }
    if (hasBlocks()) {
      buildPixels();
    } else {
      clearPixels();
    }
  }

  private void normalizeFoldsAndBlocks() {
    normalizeFolds();
    normalizeBlocks();
    mergeHidden();
  }

  private void normalizeFolds() {
    if (rawFolds.isEmpty() || count <= 1) {
      foldHiddenFirst = NO_LINES;
      foldHiddenLast = NO_LINES;
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
      foldHiddenFirst = NO_LINES;
      foldHiddenLast = NO_LINES;
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
    foldHiddenFirst = Arrays.copyOf(firsts, n);
    foldHiddenLast = Arrays.copyOf(lasts, n);
    visibleFolds = Arrays.copyOf(headers, n);
  }

  private void normalizeBlocks() {
    if (rawBlocks.isEmpty()) {
      activeBlocks = NO_BLOCKS;
      activeBlockOrdinals = NO_LINES;
      blockSlots = NO_LINES;
      blockSlotStarts = NO_LINES;
      return;
    }
    // Collect active REPLACE blocks first so INSERTs strictly inside a replaced range are hidden.
    List<El.Block> replaces = new ArrayList<>();
    for (El.Block b : rawBlocks) {
      if (b.placement() == El.Block.Placement.REPLACE
          && b.first() < count
          && !isFoldHidden(b.first())) {
        replaces.add(b);
      }
    }
    replaces.sort((a, b) -> Integer.compare(a.first(), b.first()));
    int[] repFirst = new int[replaces.size()];
    int[] repLast = new int[replaces.size()];
    for (int i = 0; i < replaces.size(); i++) {
      repFirst[i] = replaces.get(i).first();
      repLast[i] = Math.min(replaces.get(i).last(), count - 1);
    }

    List<El.Block> active = new ArrayList<>(rawBlocks.size());
    for (El.Block b : rawBlocks) {
      if (b.placement() == El.Block.Placement.REPLACE) {
        if (b.first() < count && !isFoldHidden(b.first())) {
          active.add(b);
        }
      } else {
        int line = b.first();
        if (line > count) {
          continue;
        }
        if (line < count && (isFoldHidden(line) || insideReplaceBody(line, repFirst, repLast))) {
          continue;
        }
        active.add(b);
      }
    }
    if (active.isEmpty()) {
      activeBlocks = NO_BLOCKS;
      activeBlockOrdinals = NO_LINES;
      blockSlots = NO_LINES;
      blockSlotStarts = NO_LINES;
      return;
    }
    // Stable sort: by slot (first), with INSERT before REPLACE at the same slot, keeping list
    // order among INSERT blocks before the same line.
    active.sort((a, b) -> {
      int c = Integer.compare(a.first(), b.first());
      if (c != 0) {
        return c;
      }
      if (a.placement() != b.placement()) {
        return a.placement() == El.Block.Placement.INSERT ? -1 : 1;
      }
      return 0;
    });

    int m = active.size();
    activeBlocks = active.toArray(new El.Block[m]);
    activeBlockOrdinals = new int[m];
    Map<El.Block, Integer> seen = new HashMap<>();
    int[] slots = new int[m];
    int[] starts = new int[m + 1];
    int sCount = 0;
    for (int i = 0; i < m; i++) {
      El.Block b = activeBlocks[i];
      int ord = seen.getOrDefault(b, 0);
      seen.put(b, ord + 1);
      activeBlockOrdinals[i] = ord;
      if (sCount == 0 || b.first() != slots[sCount - 1]) {
        slots[sCount] = b.first();
        starts[sCount] = i;
        sCount++;
      }
    }
    starts[sCount] = m;
    blockSlots = Arrays.copyOf(slots, sCount);
    blockSlotStarts = Arrays.copyOf(starts, sCount + 1);
  }

  private static boolean insideReplaceBody(int line, int[] repFirst, int[] repLast) {
    if (repFirst.length == 0) {
      return false;
    }
    int found = Arrays.binarySearch(repFirst, line);
    int i = found >= 0 ? found : -found - 2;
    return i >= 0 && line > repFirst[i] && line <= repLast[i];
  }

  private void mergeHidden() {
    // Fast path: no active REPLACE blocks -> hidden intervals are the fold-hidden intervals.
    int replaceCount = 0;
    for (El.Block b : activeBlocks) {
      if (b.placement() == El.Block.Placement.REPLACE) {
        replaceCount++;
      }
    }
    if (replaceCount == 0) {
      hiddenFirst = foldHiddenFirst;
      hiddenLast = foldHiddenLast;
      return;
    }
    int totalIntervals = foldHiddenFirst.length + replaceCount;
    int[][] intervals = new int[totalIntervals][2];
    int k = 0;
    for (int i = 0; i < foldHiddenFirst.length; i++) {
      intervals[k][0] = foldHiddenFirst[i];
      intervals[k][1] = foldHiddenLast[i];
      k++;
    }
    for (El.Block b : activeBlocks) {
      if (b.placement() == El.Block.Placement.REPLACE) {
        intervals[k][0] = b.first();
        intervals[k][1] = Math.min(b.last(), count - 1);
        k++;
      }
    }
    Arrays.sort(intervals, (a, b) -> {
      int c = Integer.compare(a[0], b[0]);
      return c != 0 ? c : Integer.compare(b[1], a[1]);
    });
    int[] firsts = new int[totalIntervals];
    int[] lasts = new int[totalIntervals];
    int n = 0;
    for (int[] iv : intervals) {
      if (n > 0 && iv[0] <= lasts[n - 1] + 1) {
        if (iv[1] > lasts[n - 1]) {
          lasts[n - 1] = iv[1];
        }
      } else {
        firsts[n] = iv[0];
        lasts[n] = iv[1];
        n++;
      }
    }
    hiddenFirst = Arrays.copyOf(firsts, n);
    hiddenLast = Arrays.copyOf(lasts, n);
  }

  /** The lines not hidden in a fold or replaced block. */
  int shownCount() {
    int shown = count;
    for (int i = 0; i < hiddenFirst.length; i++) {
      shown -= hiddenLast[i] - hiddenFirst[i] + 1;
    }
    return shown;
  }

  boolean hasFolds() {
    return foldHiddenFirst.length > 0;
  }

  boolean hasHidden() {
    return hiddenFirst.length > 0;
  }

  boolean hasBlocks() {
    return activeBlocks.length > 0;
  }

  boolean hasReplaceBlocks() {
    for (El.Block b : rawBlocks) {
      if (b.placement() == El.Block.Placement.REPLACE && b.first() < count) {
        return true;
      }
    }
    return false;
  }

  /** Whether line {@code line} is hidden inside a fold (ignoring REPLACE blocks). */
  boolean isFoldHidden(int line) {
    if (foldHiddenFirst.length == 0 || line <= 0) {
      return false;
    }
    int found = Arrays.binarySearch(foldHiddenFirst, line);
    int i = found >= 0 ? found : -found - 2;
    return i >= 0 && line <= foldHiddenLast[i];
  }

  /** The first line after {@code line} that no fold hides; a replaced line counts as shown. */
  int nextUnfolded(int line) {
    int next = line + 1;
    int found = Arrays.binarySearch(foldHiddenFirst, next);
    int i = found >= 0 ? found : -found - 2;
    return i >= 0 && next <= foldHiddenLast[i] ? foldHiddenLast[i] + 1 : next;
  }

  /** Whether line {@code line} is hidden inside a fold or a replaced block. */
  boolean isHidden(int line) {
    return hiddenIndex(line) >= 0;
  }

  private int hiddenIndex(int line) {
    if (hiddenFirst.length == 0 || line < 0) {
      return -1;
    }
    int found = Arrays.binarySearch(hiddenFirst, line);
    int i = found >= 0 ? found : -found - 2;
    return i >= 0 && line <= hiddenLast[i] ? i : -1;
  }

  /** The outermost fold whose shown first line is {@code line}, or null. */
  El.Fold foldAt(int line) {
    if (foldHiddenFirst.length == 0 || isHidden(line)) {
      return null;
    }
    int found = Arrays.binarySearch(foldHiddenFirst, line + 1);
    return found >= 0 ? visibleFolds[found] : null;
  }

  /** The first visible line of the text, or -1 if all lines are hidden. */
  int firstVisible() {
    return nextVisible(-1);
  }

  /** The first visible line after {@code line}, or -1 if none exists. */
  int nextVisible(int line) {
    int next = Math.max(0, line + 1);
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

  /**
   * Line {@code line} if visible, else the last visible line before it, or -1 if every line from 0
   * through {@code line} is hidden.
   */
  int visibleAtOrBefore(int line) {
    line = Math.clamp(line, 0, count - 1);
    int i = hiddenIndex(line);
    return i >= 0 ? hiddenFirst[i] - 1 : line;
  }

  /**
   * Line {@code line} if visible, else the first visible line after it, or -1 if every line from
   * {@code line} through the end is hidden.
   */
  int visibleAtOrAfter(int line) {
    line = Math.clamp(line, 0, count - 1);
    int i = hiddenIndex(line);
    if (i < 0) {
      return line;
    }
    int next = hiddenLast[i] + 1;
    return next < count ? next : -1;
  }

  /** All raw folds that hide at least one line in {@code [firstLine, lastLine]}. */
  List<El.Fold> foldsHiding(int firstLine, int lastLine) {
    if (foldHiddenFirst.length == 0) {
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
    if (foldHiddenFirst.length == 0 || !isFoldHidden(lineA) && !isFoldHidden(lineB)) {
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

  /** All raw REPLACE blocks that hide at least one line in {@code [firstLine, lastLine]}. */
  List<El.Block> blocksReplacing(int firstLine, int lastLine) {
    if (rawBlocks.isEmpty()) {
      return List.of();
    }
    List<El.Block> out = new ArrayList<>();
    for (El.Block b : rawBlocks) {
      if (b.placement() == El.Block.Placement.REPLACE && b.first() < count) {
        int s = b.first();
        int e = Math.min(b.last(), count - 1);
        if (s <= lastLine && firstLine <= e) {
          out.add(b);
        }
      }
    }
    return out;
  }

  /** All raw REPLACE blocks that hide {@code lineA} or {@code lineB}. */
  List<El.Block> blocksReplacingLine(int lineA, int lineB) {
    if (rawBlocks.isEmpty()) {
      return List.of();
    }
    List<El.Block> out = new ArrayList<>();
    for (El.Block b : rawBlocks) {
      if (b.placement() == El.Block.Placement.REPLACE && b.first() < count) {
        int s = b.first();
        int e = Math.min(b.last(), count - 1);
        if (lineA >= s && lineA <= e || lineB >= s && lineB <= e) {
          out.add(b);
        }
      }
    }
    return out;
  }

  // ---- Block heights and pixel Fenwick tree

  private void clearPixels() {
    blockHeights = null;
    slotPixels = null;
    pixelTree = null;
    totalPixels = 0;
  }

  private long initialBlockHeight(El.Block block, int ordinal) {
    if (block.lines() > 0) {
      return block.lines() * blockLineHeight;
    }
    Long cached = measuredBlocks.get(new BlockKey(block, ordinal));
    return cached != null ? cached : blockLineHeight;
  }

  private void buildPixels() {
    int n = count + 1;
    slotPixels = new long[n];
    blockHeights = new long[activeBlocks.length];
    for (int i = 0; i < activeBlocks.length; i++) {
      long h = initialBlockHeight(activeBlocks[i], activeBlockOrdinals[i]);
      blockHeights[i] = h;
      slotPixels[activeBlocks[i].first()] += h;
    }
    pixelTree = new long[n + 1];
    totalPixels = 0;
    for (int i = 1; i <= n; i++) {
      long value = slotPixels[i - 1];
      totalPixels += value;
      pixelTree[i] += value;
      int parent = i + (i & -i);
      if (parent <= n) {
        pixelTree[parent] += pixelTree[i];
      }
    }
  }

  /** Records the measured height of active block {@code activeIndex}, in physical pixels. */
  void blockHeight(int activeIndex, long measuredPixels) {
    El.Block b = activeBlocks[activeIndex];
    if (b.lines() > 0) {
      return;
    }
    long h = Math.max(0, measuredPixels);
    measuredBlocks.put(new BlockKey(b, activeBlockOrdinals[activeIndex]), h);
    long delta = h - blockHeights[activeIndex];
    if (delta == 0) {
      return;
    }
    blockHeights[activeIndex] = h;
    int slot = b.first();
    slotPixels[slot] += delta;
    totalPixels += delta;
    int n = count + 1;
    for (int i = slot + 1; i <= n; i += i & -i) {
      pixelTree[i] += delta;
    }
  }

  long blockHeight(int activeIndex) {
    return blockHeights[activeIndex];
  }

  El.Block activeBlock(int activeIndex) {
    return activeBlocks[activeIndex];
  }

  int activeBlockCount() {
    return activeBlocks.length;
  }

  /** Index into {@code blockSlots} for {@code slot}, or negative if {@code slot} has no blocks. */
  int blockSlotIndex(int slot) {
    return blockSlots.length == 0 ? -1 : Arrays.binarySearch(blockSlots, slot);
  }

  boolean hasBlockAt(int slot) {
    return blockSlotIndex(slot) >= 0;
  }

  int blockSlotStart(int slotIndex) {
    return blockSlotStarts[slotIndex];
  }

  int blockSlotEnd(int slotIndex) {
    return blockSlotStarts[slotIndex + 1];
  }

  /** Next slot strictly greater than {@code slot} that has active blocks, or -1. */
  int nextBlockSlot(int slot) {
    if (blockSlots.length == 0) {
      return -1;
    }
    int found = Arrays.binarySearch(blockSlots, slot + 1);
    int idx = found >= 0 ? found : -found - 1;
    return idx < blockSlots.length ? blockSlots[idx] : -1;
  }

  /** Next slot strictly greater than {@code slot} with a visible line or an active block, or -1. */
  int nextSlot(int slot) {
    int vis = nextVisible(slot);
    int blk = nextBlockSlot(slot);
    if (vis < 0) {
      return blk;
    }
    if (blk < 0) {
      return vis;
    }
    return Math.min(vis, blk);
  }

  private long pixelPrefix(int limit) {
    if (pixelTree == null || limit <= 0) {
      return 0;
    }
    int clamped = Math.min(limit, count + 1);
    long sum = 0;
    for (int i = clamped; i > 0; i -= i & -i) {
      sum += pixelTree[i];
    }
    return sum;
  }

  /**
   * Total height in physical pixels of all active blocks above line {@code line}, including blocks
   * at slot {@code line} (inserted before {@code line} or replacing starting at {@code line}).
   */
  long blockPixelsBefore(int line) {
    return pixelPrefix(line + 1);
  }

  /** Total height in physical pixels of all active blocks across all slots. */
  long totalBlockPixels() {
    return pixelTree == null ? 0 : totalPixels;
  }

  /** Top of the block band at {@code slot} (0..count), in physical pixels. */
  double slotTop(int slot, double lineHeight) {
    int clamped = Math.clamp(slot, 0, count);
    return rowsBefore(clamped) * lineHeight + pixelPrefix(clamped);
  }

  /** Top of text line {@code line}, after any blocks at {@code line}, in physical pixels. */
  double lineTop(int line, double lineHeight) {
    int clamped = Math.clamp(line, 0, count);
    return rowsBefore(clamped) * lineHeight + pixelPrefix(clamped + 1);
  }

  /** Top of active block {@code activeIndex}, in physical pixels. */
  double blockTop(int activeIndex, double lineHeight) {
    int slot = activeBlocks[activeIndex].first();
    double top = slotTop(slot, lineHeight);
    for (int i = activeIndex - 1; i >= 0 && activeBlocks[i].first() == slot; i--) {
      top += blockHeights[i];
    }
    return top;
  }

  /** Active block index at {@code slot} covering {@code y}, or -1 if {@code slot} has none. */
  int blockAtY(int slot, double y, double lineHeight) {
    int si = blockSlotIndex(slot);
    if (si < 0) {
      return -1;
    }
    int start = blockSlotStarts[si];
    int end = blockSlotStarts[si + 1];
    double top = slotTop(slot, lineHeight);
    for (int i = start; i < end; i++) {
      top += blockHeights[i];
      if (y < top || i + 1 == end) {
        return i;
      }
    }
    return -1;
  }

  /** Total height of all visible text rows and active blocks, in physical pixels. */
  double totalHeight(double lineHeight) {
    return totalRows() * lineHeight + totalBlockPixels();
  }

  /**
   * The vertical span {@code {top, bottom}} of line {@code line} in physical pixels from the top of
   * the text, using measured or estimated rows and block heights without shaping the line. A line
   * hidden by a fold maps to its fold's header line; one hidden by a replace block maps to that
   * block.
   */
  void lineSpan(int line, double lineHeight, double[] out) {
    int l = Math.clamp(line, 0, Math.max(count - 1, 0));
    if (isFoldHidden(l)) {
      int found = Arrays.binarySearch(foldHiddenFirst, l);
      int i = found >= 0 ? found : -found - 2;
      l = visibleFolds[i].first();
    }
    if (!isHidden(l)) {
      out[0] = lineTop(l, lineHeight);
      out[1] = slotTop(l + 1, lineHeight);
      return;
    }
    int bi = replaceBlockContaining(l);
    if (bi >= 0) {
      double top = blockTop(bi, lineHeight);
      out[0] = top;
      out[1] = top + blockHeight(bi);
      return;
    }
    double top = slotTop(l, lineHeight);
    out[0] = top;
    out[1] = top + lineHeight;
  }

  private int replaceBlockContaining(int line) {
    if (blockSlots.length == 0) {
      return -1;
    }
    int found = Arrays.binarySearch(blockSlots, line);
    int si = found >= 0 ? found : -found - 2;
    if (si < 0) {
      return -1;
    }
    int bi = blockSlotStarts[si + 1] - 1;
    El.Block b = activeBlocks[bi];
    return b.placement() == El.Block.Placement.REPLACE && line >= b.first() && line <= b.last()
        ? bi
        : -1;
  }

  /**
   * The slot (0..count) whose vertical band contains {@code y}, in physical pixels, descending the
   * row and block-pixel Fenwick trees in lockstep. Slot {@code count} holds blocks after the last
   * line.
   */
  int slotAt(double y, double lineHeight) {
    if (pixelTree == null) {
      int line = lineOfRow((long) Math.floor(y / lineHeight));
      return line >= 0 ? line : 0;
    }
    if (y <= 0) {
      return 0;
    }
    double total = totalHeight(lineHeight);
    if (y >= total) {
      if (slotPixels[count] > 0) {
        return count;
      }
      int lastVis = visibleAtOrBefore(count - 1);
      int lastBlk = activeBlocks.length > 0 ? activeBlocks[activeBlocks.length - 1].first() : -1;
      return Math.max(Math.max(lastVis, lastBlk), 0);
    }
    int slot = 0;
    double left = y;
    for (int step = Integer.highestOneBit(count); step > 0; step >>= 1) {
      int next = slot + step;
      if (next <= count) {
        double rowsPx = (tree != null ? tree[next] : step) * lineHeight;
        double band = rowsPx + pixelTree[next];
        if (band <= left) {
          slot = next;
          left -= band;
        }
      }
    }
    return slot;
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
    if (rows != null || hasHidden()) {
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
    if (total == 0) {
      return -1;
    }
    if (row <= 0) {
      return firstVisible();
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
