package com.rethinkj.ui.editor;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Colors;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.Decorator;
import com.rethinkj.ui.Drag;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.ScrollState;
import com.rethinkj.ui.Scrollbars;
import com.rethinkj.ui.Spans;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.Text;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Texts;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tooltip;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Displays the {@link Diff} between two texts side by side or unified, using {@link CodeEditor}:
 * changed lines are tinted with {@link Theme.Diff} (added green, removed red, modified blue in side
 * by side), changed words inside modified lines carry a stronger tint of the same hue, gutter
 * change bars and scrollbar ticks mark each hunk, and long unchanged regions collapse into
 * clickable rows that expand on press.
 *
 * <p>Pressing **F7** moves to the next change and **Shift+F7** moves to the previous change,
 * wrapping around at both ends; {@link State#nextChange} and {@link State#previousChange} perform
 * the same navigation from toolbar buttons or menus. Both sides are read-only; the app supplies
 * both texts and the framework holds no version control state.
 *
 * <pre>{@code
 * DiffView.sideBySide(state.app, state.oldSql, state.newSql, state.diff, Sql.of())
 *     .wFull()
 *     .h(260)
 * }</pre>
 */
public final class DiffView {

  private DiffView() {}

  /** Unchanged context lines kept above and below each hunk before collapsing. */
  private static final int CONTEXT_LINES = 3;

  /** Minimum number of unchanged lines worth replacing with a 1-line collapsed row. */
  private static final int MIN_COLLAPSE = 2;

  /** Tab stop width matching {@link CodeEditor}. */
  private static final int TAB_SIZE = 4;

  /** Maximum number of removed lines placed in a single block in the unified view. */
  private static final int MAX_DELETED_BLOCK_LINES = 100;

  /** Markup layer name reserved for the diff view's own gutter change bars and scrollbar ticks. */
  private static final String DIFF_LAYER = "diff";

  /** Which side of a {@link DiffView} a mark or line action belongs to. */
  public enum Side {
    /** The original text (the left pane side by side). */
    OLD,
    /** The modified text (the right pane side by side). */
    NEW
  }

  /**
   * An inclusive range of 0-based lines on one {@link Side} of a {@link DiffView}, reported to
   * {@link State#lineAction}.
   *
   * @param side which side the lines belong to
   * @param first first line index, 0 or more
   * @param last last line index, at least {@code first}
   */
  public record Lines(Side side, int first, int last) {
    public Lines {
      Objects.requireNonNull(side, "side");
      if (first < 0 || last < first) {
        throw new IllegalArgumentException(
            "first must be 0 or more and last at least first, were " + first + " and " + last);
      }
    }
  }

  /**
   * A slice {@code [from, to)} of the removed run {@code [runFrom, runTo)} shown by one
   * removed-lines block in unified diff.
   */
  private record DeletedChunk(int from, int to, int runFrom, int runTo) {}

  /** Unique key pairing two blocks at the same side-by-side row and ordinal. */
  private record PairKey(int oldInsert, int newInsert, int ordinal) {}

  /** Identifies one mark block on a side by its insert line and 0-based ordinal at that line. */
  private record BlockSlot(int insertLine, int ordinal) {}

  /** One collapsible unchanged run between (or outside) hunks, exclusive of end indices. */
  record Region(int oldFrom, int oldTo, int newFrom, int newTo) {
    int count() {
      return newTo - newFrom;
    }
  }

  /**
   * App-held state of a {@link DiffView}: the two read-only {@link CodeEditor.State}s, their
   * coupled {@link ScrollState}s, which unchanged regions are expanded, and the current change
   * index for F7 / Shift+F7 navigation. Keep one per diff view and reuse it across rebuilds or when
   * switching between {@link #sideBySide} and {@link #unified}.
   */
  public static final class State {
    private final CodeEditor.State oldEditor = new CodeEditor.State().readOnly(true);
    private final CodeEditor.State newEditor = new CodeEditor.State().readOnly(true);
    private final ScrollState oldScroll = new ScrollState();

    {
      oldEditor.diff = true;
      newEditor.diff = true;
    }

    private final ScrollState newScroll = new ScrollState();
    // Kept by range: when marks split or merge regions, the lines expanded stay open.
    private final List<Region> expanded = new ArrayList<>();
    private int currentChange = -1;
    private boolean sideBySide = true;
    private App<?> app;

    // Cached diff and normalized line slices for the last pair of texts.
    private String lastOld;
    private String lastNew;
    private String oldDisplayed = "";
    private String newDisplayed = "";
    private String[] oldLines = new String[0];
    private String[] newLines = new String[0];
    private int[] oldStarts = {0};
    private int[] newStarts = {0};
    private Diff diff = new Diff(0, 0, List.of());
    private List<Region> regions = List.of();
    private boolean regionsDirty = true;

    // Cached highlighter start states per old line, for unified deleted-line blocks.
    private Highlighter cachedHighlighter;
    private String cachedHighlighterOld;
    private Object[] oldHighlightStates;

    // The widest old line of each split removed run, by its first old line, for unified blocks.
    private final Map<Integer, String> widestRemoved = new HashMap<>();

    // Line action for unified removed rows, and its active drag-selection preview.
    private LineAction lineAction;
    int oldDragAnchor = -1;
    Lines oldDragLines;

    private record LineAction(Icon symbol, String label, Consumer<Lines> onPress) {}

    /**
     * Sets the marks of layer {@code layer} on {@code side}, computed for {@code text}, in place
     * of that layer's marks before ({@link CodeEditor.State#markup}). Marks of all app layers show
     * together: {@link CodeEditor.Diagnostic}s, gutter {@link CodeEditor.Marker}s, {@link
     * CodeEditor.Highlight}s (including {@link CodeEditor.Highlight.Tone#NOTE}), {@link
     * CodeEditor.Change}s, {@link CodeEditor.InlayHint}s and {@link CodeEditor.Block}s between
     * lines.
     *
     * <p>Side by side, each {@link CodeEditor.Block} on one side pairs with a matching block on
     * the same row of the other side, or gets an empty paired spacer there (splitting any hunk
     * filler around it), so both sides stay aligned as blocks grow and shrink. Lines carrying any
     * mark count like changed lines when unchanged regions collapse, so marks and threads are
     * never hidden in a collapsed region.
     *
     * <p>In {@link #unified}, {@link Side#NEW} marks apply directly to the editor, and {@link
     * Side#OLD} marks on unchanged lines map to their matching new lines. On removed lines, a
     * {@link CodeEditor.Block} splits the removed-lines block right below its line, {@link
     * CodeEditor.Highlight.Tone#NOTE} tints the row and draws the gutter stripe, {@link
     * CodeEditor.Highlight.Tone#OCCURRENCE} highlights its characters, and {@link
     * CodeEditor.Marker}s show in the gutter icon lane; diagnostics, inlay hints and change bars
     * are not shown on removed lines.
     *
     * <p>The layer name {@code "diff"} is reserved for the diff view's own change bars and
     * scrollbar ticks.
     *
     * @param side which side of the diff the marks belong to
     * @param layer app-chosen layer name, not {@code "diff"}
     * @param text revision the marks were computed for
     * @param marks marks to attach, or an empty list to clear {@code layer}
     * @throws IllegalArgumentException if {@code layer} is {@code "diff"}
     */
    public State markup(
        Side side, String layer, String text, List<? extends CodeEditor.Mark> marks) {
      CodeEditor.State target = editor(side);
      checkLayer(layer);
      target.markup(layer, text, marks);
      regionsDirty = true;
      return this;
    }

    /**
     * Clears the marks of layer {@code layer} on {@code side} ({@link CodeEditor.State#noMarkup}).
     *
     * @param side which side of the diff to clear
     * @param layer app-chosen layer name, not {@code "diff"}
     * @throws IllegalArgumentException if {@code layer} is {@code "diff"}
     */
    public State noMarkup(Side side, String layer) {
      CodeEditor.State target = editor(side);
      checkLayer(layer);
      target.noMarkup(layer);
      regionsDirty = true;
      return this;
    }

    /**
     * Offers an action on any line of either side from the gutter and the editor's context menu,
     * such as starting a review comment ({@link CodeEditor.State#lineAction}). Hovering a line's
     * gutter cell reveals {@code symbol} left of the line number with {@code label} as its tooltip
     * and accessible name; pressing or dragging over a side's gutter reports an inclusive 0-based
     * {@link Lines} range carrying that {@link Side} (a drag stays on the side it started on, and
     * in {@link #unified} a drag over removed lines stays within the same removed run; Escape
     * cancels). Because the keyboard cannot reach removed lines in {@link #unified}, the context
     * menu item acts on {@link Side#NEW} lines only. Passing a null {@code symbol} or {@code
     * onPress} removes it from both sides.
     */
    public State lineAction(Icon symbol, String label, Consumer<Lines> onPress) {
      if (symbol == null || onPress == null) {
        lineAction = null;
        oldDragLines = null;
        oldDragAnchor = -1;
        oldEditor.lineAction(null, null, null);
        newEditor.lineAction(null, null, null);
        return this;
      }
      Objects.requireNonNull(label, "label");
      lineAction = new LineAction(symbol, label, onPress);
      oldEditor.lineAction(
          symbol, label, lines -> onPress.accept(new Lines(Side.OLD, lines.first(), lines.last())));
      newEditor.lineAction(
          symbol, label, lines -> onPress.accept(new Lines(Side.NEW, lines.first(), lines.last())));
      return this;
    }

    private CodeEditor.State editor(Side side) {
      return Objects.requireNonNull(side, "side") == Side.OLD ? oldEditor : newEditor;
    }

    private static void checkLayer(String layer) {
      Objects.requireNonNull(layer, "layer");
      if (DIFF_LAYER.equals(layer)) {
        throw new IllegalArgumentException("layer \"" + DIFF_LAYER + "\" is reserved");
      }
    }

    /** The 0-based index of the current change hunk, or -1 before navigating or when unchanged. */
    public int currentChange() {
      return currentChange;
    }

    /** The number of change hunks in the last built diff. */
    public int changeCount() {
      return diff.hunks().size();
    }

    /** Moves to the next change hunk, wrapping to the first after the last. */
    public void nextChange() {
      stepChange(app, false);
    }

    /** Moves to the previous change hunk, wrapping to the last before the first. */
    public void previousChange() {
      stepChange(app, true);
    }

    private void expand(Region region) {
      expanded.add(region);
    }

    private void sync(String oldText, String newText) {
      if (oldText.equals(lastOld) && newText.equals(lastNew)) {
        return;
      }
      lastOld = oldText;
      lastNew = newText;
      oldDisplayed = CodeEditor.displayed(oldText);
      newDisplayed = CodeEditor.displayed(newText);
      oldLines = Diff.splitLines(oldDisplayed);
      newLines = Diff.splitLines(newDisplayed);
      oldStarts = lineStarts(oldLines);
      newStarts = lineStarts(newLines);
      diff = Diff.diffLines(oldLines, newLines);
      regionsDirty = true;
      expanded.clear();
      widestRemoved.clear();
      oldDragLines = null;
      oldDragAnchor = -1;
      currentChange = -1;
      cachedHighlighterOld = null;
      oldHighlightStates = null;
    }

    private void ensureRegions(List<CodeEditor.Mark> oldMarks, List<CodeEditor.Mark> newMarks) {
      if (!regionsDirty) {
        return;
      }
      Set<Integer> oldMarked = markedLines(oldMarks, oldDisplayed, oldStarts);
      Set<Integer> newMarked = markedLines(newMarks, newDisplayed, newStarts);
      regions = collapsibleRegions(diff, oldMarked, newMarked);
      regionsDirty = false;
    }

    private Object[] oldStartStates(Highlighter highlighter) {
      if (highlighter == null || oldLines.length == 0) {
        return new Object[0];
      }
      if (Objects.equals(cachedHighlighter, highlighter)
          && Objects.equals(cachedHighlighterOld, oldDisplayed)
          && oldHighlightStates != null) {
        return oldHighlightStates;
      }
      Object[] states = new Object[oldLines.length];
      Object cur = null;
      for (int i = 0; i < oldLines.length; i++) {
        states[i] = cur;
        cur = highlighter.line(oldLines[i], cur, new Spans(oldLines[i].length()));
      }
      cachedHighlighter = highlighter;
      cachedHighlighterOld = oldDisplayed;
      oldHighlightStates = states;
      return states;
    }

    private void stepChange(App<?> app, boolean back) {
      List<Diff.Hunk> hunks = diff.hunks();
      if (app == null || hunks.isEmpty()) { // before the view's first build
        return;
      }
      int count = hunks.size();
      if (currentChange < 0 || currentChange >= count) {
        currentChange = back ? count - 1 : 0;
      } else {
        currentChange = back ? (currentChange + count - 1) % count : (currentChange + 1) % count;
      }
      Diff.Hunk hunk = hunks.get(currentChange);
      int oldCount = hunk.oldEnd() - hunk.oldStart();
      int newCount = hunk.newEnd() - hunk.newStart();
      // The caret goes to the change's first line on each side that has lines of it.
      if (newCount > 0) {
        selectEditor(app, newEditor, lineStart(newStarts, hunk.newStart()));
      }
      if (sideBySide && oldCount > 0) {
        selectEditor(app, oldEditor, lineStart(oldStarts, hunk.oldStart()));
      }
      // The change's first row goes to the top, a line or, in unified, the removed lines' block,
      // which lies after line newStart - 1 and before line newStart; the partner side follows.
      boolean scrollOld = sideBySide && newCount == 0;
      El field = (scrollOld ? oldEditor : newEditor).editor();
      if (field == null) {
        return;
      }
      // From the line's start, where the caret and the change's first words are.
      Text text = field.text();
      double y;
      if (scrollOld) {
        y = text.lineToOffset(hunk.oldStart());
      } else if (sideBySide || oldCount == 0) {
        y = text.lineToOffset(hunk.newStart());
      } else if (hunk.newStart() == 0) {
        y = 0; // the removed lines come first
      } else {
        // One line height below the top of line newStart - 1, whatever blocks follow it; below
        // line 0 the offsets step by whole line heights.
        y = text.lineToOffset(hunk.newStart() - 1.0) + text.lineToOffset(0) - text.lineToOffset(-1);
      }
      app.scrollTo(field, 0, Math.max(0, y));
    }

    private static void selectEditor(App<?> app, CodeEditor.State editorState, int offset) {
      El editorEl = editorState.editor();
      if (editorEl != null) {
        CodeEditor.goTo(app, editorState, editorEl, offset);
      }
    }
  }

  /**
   * Side-by-side diff view: original text on the left, modified text on the right, with exact
   * filler blocks keeping hunks aligned, bidirectional scroll coupling by hunk, collapsed unchanged
   * regions, intraline word highlights, gutter change bars, scrollbar ticks, and F7 / Shift+F7
   * change navigation.
   *
   * @param app the app this view is in, which its navigation and find bar work through
   * @param oldText original text
   * @param newText modified text
   * @param state app-held diff state
   * @param highlighter syntax highlighter for both panes, or null for plain text
   */
  public static El sideBySide(
      App<?> app, String oldText, String newText, State state, Highlighter highlighter) {
    Objects.requireNonNull(app, "app");
    Objects.requireNonNull(oldText, "oldText");
    Objects.requireNonNull(newText, "newText");
    Objects.requireNonNull(state, "state");
    Theme theme = Theme.CURRENT.get();
    Texts texts = Texts.CURRENT.get();
    state.sync(oldText, newText);
    state.sideBySide = true;
    state.app = app;
    state.oldEditor.oldNumbers(null);
    state.newEditor.oldNumbers(null);
    state.oldEditor.minMarkerSlots(0);
    state.newEditor.minMarkerSlots(0);
    state.oldDragLines = null;

    List<CodeEditor.Mark> oldMarks = state.oldEditor.layerMarks(state.oldDisplayed, DIFF_LAYER);
    List<CodeEditor.Mark> newMarks = state.newEditor.layerMarks(state.newDisplayed, DIFF_LAYER);
    state.ensureRegions(oldMarks, newMarks);

    Diff diff = state.diff;
    List<Region> collapsed = activeCollapsed(state);

    // Build markup Change bars + scrollbar ticks for both panes.
    List<CodeEditor.Change> oldChanges = new ArrayList<>();
    List<CodeEditor.Change> newChanges = new ArrayList<>();
    Map<Integer, Integer> oldLineTints = new HashMap<>();
    Map<Integer, Integer> newLineTints = new HashMap<>();
    Map<Integer, List<Diff.Span>> oldWordSpans = new HashMap<>();
    Map<Integer, List<Diff.Span>> newWordSpans = new HashMap<>();
    Theme.Diff diffColors = theme.diff();

    for (Diff.Hunk h : diff.hunks()) {
      switch (h.kind()) {
        case ADDED -> {
          addChangeRange(
              newChanges, state.newStarts, h.newStart(), h.newEnd(), CodeEditor.Change.Kind.ADDED);
          for (int l = h.newStart(); l < h.newEnd(); l++) {
            newLineTints.put(l, diffColors.added());
          }
        }
        case DELETED -> {
          addChangeRange(
              oldChanges,
              state.oldStarts,
              h.oldStart(),
              h.oldEnd(),
              CodeEditor.Change.Kind.DELETED);
          for (int l = h.oldStart(); l < h.oldEnd(); l++) {
            oldLineTints.put(l, diffColors.removed());
          }
        }
        case MODIFIED -> {
          addChangeRange(
              oldChanges,
              state.oldStarts,
              h.oldStart(),
              h.oldEnd(),
              CodeEditor.Change.Kind.MODIFIED);
          addChangeRange(
              newChanges,
              state.newStarts,
              h.newStart(),
              h.newEnd(),
              CodeEditor.Change.Kind.MODIFIED);
          for (int l = h.oldStart(); l < h.oldEnd(); l++) {
            oldLineTints.put(l, diffColors.modified());
          }
          for (int l = h.newStart(); l < h.newEnd(); l++) {
            newLineTints.put(l, diffColors.modified());
          }
          for (Diff.LineChange lc : h.lineChanges()) {
            if (!lc.oldSpans().isEmpty()) {
              oldWordSpans.put(lc.oldLine(), lc.oldSpans());
            }
            if (!lc.newSpans().isEmpty()) {
              newWordSpans.put(lc.newLine(), lc.newSpans());
            }
          }
        }
      }
    }
    setDiffMarkup(state.oldEditor, state.oldDisplayed, oldChanges);
    setDiffMarkup(state.newEditor, state.newDisplayed, newChanges);

    // Assemble collapsed regions, filler blocks and paired spacers for review blocks on each side.
    List<El.Block> oldBlocks = new ArrayList<>();
    List<El.Block> newBlocks = new ArrayList<>();
    Map<El.Block, Region> oldCollapsedMap = new HashMap<>();
    Map<El.Block, Region> newCollapsedMap = new HashMap<>();

    for (Region r : collapsed) {
      El.Block ob = El.Block.replace(r.oldFrom(), r.oldTo() - 1).lines(1);
      El.Block nb = El.Block.replace(r.newFrom(), r.newTo() - 1).lines(1);
      oldBlocks.add(ob);
      newBlocks.add(nb);
      oldCollapsedMap.put(ob, r);
      newCollapsedMap.put(nb, r);
    }

    Map<Integer, Integer> oldBlockCounts =
        blockCounts(oldMarks, state.oldDisplayed, state.oldStarts);
    Map<Integer, Integer> newBlockCounts =
        blockCounts(newMarks, state.newDisplayed, state.newStarts);
    Map<BlockSlot, PairKey> oldPairs = new HashMap<>();
    Map<BlockSlot, PairKey> newPairs = new HashMap<>();

    if (diff.oldLineCount() == 0 && diff.newLineCount() == 0) {
      pairRow(1, 1, oldBlockCounts, newBlockCounts, oldPairs, newPairs, oldBlocks, newBlocks);
    } else {
      int oldPos = 0;
      int newPos = 0;
      for (Diff.Hunk h : diff.hunks()) {
        pairMatchedRows(
            oldPos,
            newPos,
            h.oldStart() - oldPos,
            oldBlockCounts,
            newBlockCounts,
            oldPairs,
            newPairs,
            oldBlocks,
            newBlocks);
        int oldCount = h.oldEnd() - h.oldStart();
        int newCount = h.newEnd() - h.newStart();
        if (diff.oldLineCount() == 0) {
          // The empty side's one line stands beside new line 0, then filler for the rest.
          oldLineTints.put(0, diffColors.filler());
          pairRow(1, 1, oldBlockCounts, newBlockCounts, oldPairs, newPairs, oldBlocks, newBlocks);
          addSplitFiller(oldBlocks, 1, 1, h.newEnd(), newBlockCounts, newPairs, true);
        } else if (diff.newLineCount() == 0) {
          newLineTints.put(0, diffColors.filler());
          pairRow(1, 1, oldBlockCounts, newBlockCounts, oldPairs, newPairs, oldBlocks, newBlocks);
          addSplitFiller(newBlocks, 1, 1, h.oldEnd(), oldBlockCounts, oldPairs, false);
        } else {
          int shared = Math.min(oldCount, newCount);
          pairMatchedRows(
              h.oldStart(),
              h.newStart(),
              shared,
              oldBlockCounts,
              newBlockCounts,
              oldPairs,
              newPairs,
              oldBlocks,
              newBlocks);
          if (newCount > oldCount) {
            addSplitFiller(
                oldBlocks,
                h.oldEnd(),
                h.newStart() + shared,
                h.newEnd(),
                newBlockCounts,
                newPairs,
                true);
          } else if (oldCount > newCount) {
            addSplitFiller(
                newBlocks,
                h.newEnd(),
                h.oldStart() + shared,
                h.oldEnd(),
                oldBlockCounts,
                oldPairs,
                false);
          }
        }
        oldPos = h.oldEnd();
        newPos = h.newEnd();
      }
      pairMatchedRows(
          oldPos,
          newPos,
          diff.oldLineCount() - oldPos,
          oldBlockCounts,
          newBlockCounts,
          oldPairs,
          newPairs,
          oldBlocks,
          newBlocks);
    }

    state.oldEditor.extraBlocks(new CodeEditor.State.ExtraBlocks(
        oldBlocks,
        block -> {
          Region region = oldCollapsedMap.get(block);
          if (region != null) {
            return collapsedRow(theme, texts, state, region);
          }
          return block.pair() != null ? spacerBlock(block.pair()) : fillerBlock(theme);
        },
        block -> {
          Region region = oldCollapsedMap.get(block);
          if (region != null) {
            state.expand(region);
          }
        },
        block -> {
          Region region = oldCollapsedMap.get(block);
          return region != null ? collapsedGutter(theme, texts, state, region) : null;
        },
        oldPairs.isEmpty()
            ? null
            : (insertLine, ordinal) -> oldPairs.get(new BlockSlot(insertLine, ordinal))));
    state.newEditor.extraBlocks(new CodeEditor.State.ExtraBlocks(
        newBlocks,
        block -> {
          Region region = newCollapsedMap.get(block);
          if (region != null) {
            return collapsedRow(theme, texts, state, region);
          }
          return block.pair() != null ? spacerBlock(block.pair()) : fillerBlock(theme);
        },
        block -> {
          Region region = newCollapsedMap.get(block);
          if (region != null) {
            state.expand(region);
          }
        },
        block -> {
          Region region = newCollapsedMap.get(block);
          return region != null ? collapsedGutter(theme, texts, state, region) : null;
        },
        newPairs.isEmpty()
            ? null
            : (insertLine, ordinal) -> newPairs.get(new BlockSlot(insertLine, ordinal))));

    // Couple vertical & horizontal scrolling by hunk anchors.
    state.oldScroll.scrollWith(state.newScroll, scrollAnchors(diff));

    El left = tinted(
            CodeEditor.codeEditor(app, oldText, state.oldEditor, highlighter, null)
                .key("old")
                .label(texts.original())
                .radius(0)
                .border(0, 0, 0, 0, 0)
                .shadow()
                .whenFocused(null)
                .grow(1)
                .w(0)
                .hFull()
                .scrollState(state.oldScroll),
            oldLineTints,
            oldWordSpans,
            diffColors.modifiedWord())
        .onKeyFirst(k -> diffKey(app, state, k));

    El right = tinted(
            CodeEditor.codeEditor(app, newText, state.newEditor, highlighter, null)
                .key("new")
                .label(texts.modified())
                .radius(0)
                .border(0, 0, 0, 0, 0)
                .shadow()
                .whenFocused(null)
                .grow(1)
                .w(0)
                .hFull()
                .scrollState(state.newScroll),
            newLineTints,
            newWordSpans,
            diffColors.modifiedWord())
        .onKeyFirst(k -> diffKey(app, state, k));

    float stroke = theme.size().stroke();
    return El.row()
        .key("diff-side-by-side")
        .border(stroke, theme.border().control())
        .radius(theme.radius().medium())
        .clip()
        .whenFocusWithin(e -> e.border(stroke, theme.accent().edge()))
        .child(left)
        .child(El.el().w(stroke).hFull().shrink(0).fill(theme.border().divider()))
        .child(right);
  }

  /**
   * Tints a side's changed lines under the editor's own marks, so a noted line keeps its tint,
   * and its changed words over them.
   */
  private static El tinted(
      El editor,
      Map<Integer, Integer> lineTints,
      Map<Integer, List<Diff.Span>> wordSpans,
      int wordColor) {
    Decorator own = editor.decorator();
    return editor.decorate((line, start, text, out) -> {
      Integer bg = lineTints.get(line);
      if (bg != null) {
        out.lineBackground(bg);
      }
      if (own != null) {
        own.line(line, start, text, out);
      }
      List<Diff.Span> spans = wordSpans.get(line);
      if (spans != null) {
        for (Diff.Span span : spans) {
          out.background(span.from(), span.to(), wordColor);
        }
      }
    });
  }

  /**
   * Unified diff view: a single read-only {@link CodeEditor} displaying the new text, with deleted
   * lines shown inline as read-only inserted blocks, a second gutter column showing old line
   * numbers (deleted-line blocks show old numbers and no new number), collapsed unchanged regions,
   * intraline word highlights, gutter change bars, scrollbar ticks, and F7 / Shift+F7 change
   * navigation.
   *
   * @param app the app this view is in, which its navigation and find bar work through
   * @param oldText original text
   * @param newText modified text
   * @param state app-held diff state
   * @param highlighter syntax highlighter for both added and deleted lines, or null for plain text
   */
  public static El unified(
      App<?> app, String oldText, String newText, State state, Highlighter highlighter) {
    Objects.requireNonNull(app, "app");
    Objects.requireNonNull(oldText, "oldText");
    Objects.requireNonNull(newText, "newText");
    Objects.requireNonNull(state, "state");
    Theme theme = Theme.CURRENT.get();
    Texts texts = Texts.CURRENT.get();
    state.sync(oldText, newText);
    state.sideBySide = false;
    state.app = app;
    state.oldScroll.scrollWith(null, List.of());

    List<CodeEditor.Mark> oldMarks = state.oldEditor.layerMarks(state.oldDisplayed, DIFF_LAYER);
    List<CodeEditor.Mark> newMarks = state.newEditor.layerMarks(state.newDisplayed, DIFF_LAYER);
    state.ensureRegions(oldMarks, newMarks);

    Diff diff = state.diff;
    List<Region> collapsed = activeCollapsed(state);
    Theme.Diff diffColors = theme.diff();

    // Map each new line index -> 1-based old line number string ("" for added/modified new lines).
    int[] newToOld = mapNewToOldLines(diff);
    int[] oldToNew = mapOldToNewLines(diff);
    int maxOldLine = Math.max(1, diff.oldLineCount());
    int maxNewLine = Math.max(1, diff.newLineCount());
    state.newEditor.oldNumbers(new CodeEditor.State.OldNumbers(
        maxOldLine,
        maxNewLine,
        line -> line >= 0 && line < newToOld.length && newToOld[line] >= 0
            ? String.valueOf(newToOld[line] + 1)
            : ""));

    List<CodeEditor.Mark> newDiffMarks = new ArrayList<>();
    List<Scrollbars.Mark> deletedScrollMarks = new ArrayList<>();
    Map<Integer, Integer> newLineTints = new HashMap<>();
    Map<Integer, List<Diff.Span>> newWordSpans = new HashMap<>();
    Map<Integer, List<Diff.Span>> oldWordSpans = new HashMap<>();

    UnifiedOldMarks unifiedOld = classifyOldMarks(state, oldMarks, oldToNew, newDiffMarks);

    List<El.Block> blocks = new ArrayList<>();
    Map<El.Block, Region> collapsedMap = new HashMap<>();
    Map<El.Block, DeletedChunk> deletedMap = new IdentityHashMap<>();
    Map<El.Block, Supplier<El>> removedBlocks = new IdentityHashMap<>();

    for (Region r : collapsed) {
      El.Block nb = El.Block.replace(r.newFrom(), r.newTo() - 1).lines(1);
      blocks.add(nb);
      collapsedMap.put(nb, r);
    }

    for (Diff.Hunk h : diff.hunks()) {
      switch (h.kind()) {
        case ADDED -> {
          addChangeRange(
              newDiffMarks,
              state.newStarts,
              h.newStart(),
              h.newEnd(),
              CodeEditor.Change.Kind.ADDED);
          for (int l = h.newStart(); l < h.newEnd(); l++) {
            newLineTints.put(l, diffColors.added());
          }
        }
        case DELETED -> {
          addDeletedBlocks(
              blocks,
              deletedMap,
              removedBlocks,
              unifiedOld.blocks(),
              h.oldStart(),
              h.oldEnd(),
              h.newStart(),
              diff.newLineCount() == 0);
          int at = lineStart(
              state.newStarts, Math.clamp(h.newStart(), 0, Math.max(0, diff.newLineCount() - 1)));
          deletedScrollMarks.add(new Scrollbars.Mark(at, at, theme.status().danger()));
        }
        case MODIFIED -> {
          addDeletedBlocks(
              blocks,
              deletedMap,
              removedBlocks,
              unifiedOld.blocks(),
              h.oldStart(),
              h.oldEnd(),
              h.newStart(),
              false);
          addChangeRange(
              newDiffMarks,
              state.newStarts,
              h.newStart(),
              h.newEnd(),
              CodeEditor.Change.Kind.ADDED);
          for (int l = h.newStart(); l < h.newEnd(); l++) {
            newLineTints.put(l, diffColors.added());
          }
          for (Diff.LineChange lc : h.lineChanges()) {
            if (!lc.oldSpans().isEmpty()) {
              oldWordSpans.put(lc.oldLine(), lc.oldSpans());
            }
            if (!lc.newSpans().isEmpty()) {
              newWordSpans.put(lc.newLine(), lc.newSpans());
            }
          }
        }
      }
    }
    setDiffMarkup(state.newEditor, state.newDisplayed, newDiffMarks);

    int markerSlots = unifiedMarkerSlots(state.newDisplayed, newMarks, newDiffMarks, unifiedOld);
    state.newEditor.minMarkerSlots(markerSlots);

    Object[] oldStartStates =
        deletedMap.isEmpty() ? new Object[0] : state.oldStartStates(highlighter);
    float oldColW = CodeEditor.numberWidth(theme, maxOldLine);
    float newColW = CodeEditor.numberWidth(theme, maxNewLine);

    state.newEditor.extraBlocks(new CodeEditor.State.ExtraBlocks(
        blocks,
        block -> {
          Region region = collapsedMap.get(block);
          if (region != null) {
            return collapsedRow(theme, texts, state, region);
          }
          DeletedChunk chunk = deletedMap.get(block);
          if (chunk != null) {
            return deletedLinesBlock(
                theme,
                state,
                chunk,
                highlighter,
                oldStartStates,
                oldWordSpans,
                unifiedOld.notes(),
                unifiedOld.occurrences());
          }
          Supplier<El> removedBlock = removedBlocks.get(block);
          return removedBlock != null ? removedBlock.get() : fillerBlock(theme);
        },
        block -> {
          Region region = collapsedMap.get(block);
          if (region != null) {
            state.expand(region);
          }
        },
        block -> {
          Region region = collapsedMap.get(block);
          if (region != null) {
            return collapsedGutter(theme, texts, state, region);
          }
          DeletedChunk chunk = deletedMap.get(block);
          return chunk != null
              ? deletedLinesGutter(
                  theme,
                  state,
                  chunk,
                  oldColW,
                  newColW,
                  markerSlots,
                  unifiedOld.markers(),
                  unifiedOld.notes())
              : null;
        },
        null));

    El editor = tinted(
            CodeEditor.codeEditor(app, newText, state.newEditor, highlighter, null)
                .key("diff-unified")
                .label(texts.modified())
                .scrollState(state.newScroll),
            newLineTints,
            newWordSpans,
            diffColors.addedWord())
        .onKeyFirst(k -> diffKey(app, state, k));
    if (!deletedScrollMarks.isEmpty()) {
      editor.scrollMarks(deletedScrollMarks);
    }
    return editor;
  }

  private static boolean diffKey(App<?> app, State state, Key k) {
    if (!k.name().equals("F7") || (k.modifiers() & ~Key.SHIFT) != 0) {
      return false;
    }
    if (state.diff.hunks().isEmpty()) {
      return false;
    }
    state.stepChange(app, k.modifiers() == Key.SHIFT);
    return true;
  }

  private static void setDiffMarkup(
      CodeEditor.State editorState, String displayed, List<? extends CodeEditor.Mark> marks) {
    if (marks.isEmpty()) {
      editorState.noMarkup(DIFF_LAYER);
    } else {
      editorState.markup(DIFF_LAYER, displayed, marks);
    }
  }

  private static void addChangeRange(
      List<? super CodeEditor.Change> out,
      int[] starts,
      int lineStart,
      int lineEndExclusive,
      CodeEditor.Change.Kind kind) {
    if (lineEndExclusive <= lineStart) {
      return;
    }
    // To the next line's start, after the line feed, so even empty changed lines have to > from.
    int from = lineStart(starts, lineStart);
    out.add(new CodeEditor.Change(from, lineStart(starts, lineEndExclusive), kind));
  }

  /** Where each of these lines starts in their text, and one past the text's end after them. */
  private static int[] lineStarts(String[] lines) {
    int[] starts = new int[lines.length + 1];
    for (int i = 0; i < lines.length; i++) {
      starts[i + 1] = starts[i] + lines[i].length() + 1;
    }
    return starts;
  }

  /** Where {@code line} starts, or the text's end for a line past the last. */
  private static int lineStart(int[] starts, int line) {
    int end = Math.max(0, starts[starts.length - 1] - 1);
    return Math.min(starts[Math.clamp(line, 0, starts.length - 1)], end);
  }

  /**
   * Computes collapsible unchanged regions from {@code diff}, keeping {@link #CONTEXT_LINES} lines
   * around every hunk and skipping runs shorter than {@link #MIN_COLLAPSE}.
   */
  static List<Region> collapsibleRegions(Diff diff) {
    return collapsibleRegions(diff, Set.of(), Set.of());
  }

  /**
   * Computes collapsible unchanged regions from {@code diff}, treating lines in {@code oldMarked}
   * or {@code newMarked} like changed lines so marks and review threads stay visible with context.
   */
  static List<Region> collapsibleRegions(
      Diff diff, Set<Integer> oldMarked, Set<Integer> newMarked) {
    List<Diff.Hunk> hunks = diff.hunks();
    if (hunks.isEmpty()) {
      return List.of();
    }
    List<Region> out = new ArrayList<>();
    int oldPos = 0;
    int newPos = 0;
    for (int i = 0; i < hunks.size(); i++) {
      Diff.Hunk h = hunks.get(i);
      int topContext = i == 0 ? 0 : CONTEXT_LINES;
      int bottomContext = CONTEXT_LINES;
      addUnchangedRegions(
          out,
          oldPos,
          h.oldStart(),
          newPos,
          h.newStart(),
          topContext,
          bottomContext,
          oldMarked,
          newMarked);
      oldPos = h.oldEnd();
      newPos = h.newEnd();
    }
    addUnchangedRegions(
        out,
        oldPos,
        diff.oldLineCount(),
        newPos,
        diff.newLineCount(),
        CONTEXT_LINES,
        0,
        oldMarked,
        newMarked);
    return List.copyOf(out);
  }

  private static void addUnchangedRegions(
      List<Region> out,
      int oldFrom,
      int oldTo,
      int newFrom,
      int newTo,
      int topContext,
      int bottomContext,
      Set<Integer> oldMarked,
      Set<Integer> newMarked) {
    if (oldMarked.isEmpty() && newMarked.isEmpty()) {
      addRegion(out, oldFrom, oldTo, newFrom, newTo, topContext, bottomContext);
      return;
    }
    int count = newTo - newFrom;
    if (count <= 0 || oldTo - oldFrom != count) {
      return;
    }
    int segStart = 0;
    int segTop = topContext;
    for (int i = 0; i < count; i++) {
      if (oldMarked.contains(oldFrom + i) || newMarked.contains(newFrom + i)) {
        addRegion(
            out,
            oldFrom + segStart,
            oldFrom + i,
            newFrom + segStart,
            newFrom + i,
            segTop,
            CONTEXT_LINES);
        segStart = i + 1;
        segTop = CONTEXT_LINES;
      }
    }
    addRegion(out, oldFrom + segStart, oldTo, newFrom + segStart, newTo, segTop, bottomContext);
  }

  private static void addRegion(
      List<Region> out,
      int oldFrom,
      int oldTo,
      int newFrom,
      int newTo,
      int topContext,
      int bottomContext) {
    int cOldFrom = oldFrom + topContext;
    int cOldTo = oldTo - bottomContext;
    int cNewFrom = newFrom + topContext;
    int cNewTo = newTo - bottomContext;
    if (cNewTo - cNewFrom >= MIN_COLLAPSE && cOldTo - cOldFrom == cNewTo - cNewFrom) {
      out.add(new Region(cOldFrom, cOldTo, cNewFrom, cNewTo));
    }
  }

  /** 0-based line indices touched by any mark in {@code marks}. */
  private static Set<Integer> markedLines(
      List<CodeEditor.Mark> marks, String displayed, int[] starts) {
    if (marks.isEmpty() || starts.length <= 1) {
      return Set.of();
    }
    int len = displayed.length();
    Set<Integer> lines = new HashSet<>();
    for (CodeEditor.Mark mark : marks) {
      switch (mark) {
        case CodeEditor.Diagnostic d -> addRangeLines(lines, starts, len, d.from(), d.to());
        case CodeEditor.Highlight h -> addRangeLines(lines, starts, len, h.from(), h.to());
        case CodeEditor.Change c -> addRangeLines(lines, starts, len, c.from(), c.to());
        case CodeEditor.Marker m -> addPointLine(lines, starts, len, m.at());
        case CodeEditor.InlayHint h -> addPointLine(lines, starts, len, h.at());
        case CodeEditor.Block b -> addPointLine(lines, starts, len, b.at());
      }
    }
    return lines;
  }

  private static void addPointLine(Set<Integer> lines, int[] starts, int len, int at) {
    if (at >= 0 && at <= len) {
      lines.add(lineOf(starts, at));
    }
  }

  private static void addRangeLines(Set<Integer> lines, int[] starts, int len, int from, int to) {
    if (from == to) {
      addPointLine(lines, starts, len, from);
      return;
    }
    if (from > to || from > len || to <= 0) {
      return;
    }
    int first = lineOf(starts, Math.max(0, from));
    int last = lineOf(starts, Math.min(len, to - 1));
    for (int line = first; line <= last; line++) {
      lines.add(line);
    }
  }

  /** Number of {@link CodeEditor.Block} marks at each 1-based insert line ({@code line + 1}). */
  private static Map<Integer, Integer> blockCounts(
      List<CodeEditor.Mark> marks, String displayed, int[] starts) {
    if (marks.isEmpty()) {
      return Map.of();
    }
    int len = displayed.length();
    Map<Integer, Integer> counts = null;
    for (CodeEditor.Mark mark : marks) {
      if (mark instanceof CodeEditor.Block b && b.at() <= len) {
        int insertLine = lineOf(starts, b.at()) + 1;
        if (counts == null) {
          counts = new HashMap<>();
        }
        counts.merge(insertLine, 1, Integer::sum);
      }
    }
    return counts == null ? Map.of() : counts;
  }

  /** 0-based line index of {@code offset} in {@code [0, displayed.length()]}. */
  private static int lineOf(int[] starts, int offset) {
    if (starts.length <= 1) {
      return 0;
    }
    int idx = Arrays.binarySearch(starts, offset);
    return idx >= 0 ? Math.min(idx, starts.length - 2) : -idx - 2;
  }

  /**
   * Pairs mark blocks and spacers across {@code count} 1-to-1 matched lines starting at {@code
   * oldFrom} and {@code newFrom}.
   */
  private static void pairMatchedRows(
      int oldFrom,
      int newFrom,
      int count,
      Map<Integer, Integer> oldBlockCounts,
      Map<Integer, Integer> newBlockCounts,
      Map<BlockSlot, PairKey> oldPairs,
      Map<BlockSlot, PairKey> newPairs,
      List<El.Block> oldBlocks,
      List<El.Block> newBlocks) {
    if (oldBlockCounts.isEmpty() && newBlockCounts.isEmpty()) {
      return;
    }
    for (int i = 0; i < count; i++) {
      pairRow(
          oldFrom + i + 1,
          newFrom + i + 1,
          oldBlockCounts,
          newBlockCounts,
          oldPairs,
          newPairs,
          oldBlocks,
          newBlocks);
    }
  }

  /** Pairs mark blocks at one matched row, adding empty spacers on whichever side has fewer. */
  private static void pairRow(
      int oldInsert,
      int newInsert,
      Map<Integer, Integer> oldBlockCounts,
      Map<Integer, Integer> newBlockCounts,
      Map<BlockSlot, PairKey> oldPairs,
      Map<BlockSlot, PairKey> newPairs,
      List<El.Block> oldBlocks,
      List<El.Block> newBlocks) {
    int oCount = oldBlockCounts.getOrDefault(oldInsert, 0);
    int nCount = newBlockCounts.getOrDefault(newInsert, 0);
    if (oCount == 0 && nCount == 0) {
      return;
    }
    int total = Math.max(oCount, nCount);
    for (int ord = 0; ord < total; ord++) {
      PairKey key = new PairKey(oldInsert, newInsert, ord);
      if (ord < oCount) {
        oldPairs.put(new BlockSlot(oldInsert, ord), key);
      } else {
        oldBlocks.add(El.Block.insert(oldInsert).paired(key));
      }
      if (ord < nCount) {
        newPairs.put(new BlockSlot(newInsert, ord), key);
      } else {
        newBlocks.add(El.Block.insert(newInsert).paired(key));
      }
    }
  }

  /**
   * Adds filler blocks at {@code fillerInsert} on the shorter side for lines {@code [fromLine,
   * toLine)} of the longer side, splitting the filler around empty paired spacers wherever the
   * longer side has {@link CodeEditor.Block} marks.
   */
  private static void addSplitFiller(
      List<El.Block> fillerBlocks,
      int fillerInsert,
      int fromLine,
      int toLine,
      Map<Integer, Integer> otherBlockCounts,
      Map<BlockSlot, PairKey> otherPairs,
      boolean fillerOnOld) {
    int run = 0;
    for (int line = fromLine; line < toLine; line++) {
      run++;
      int otherInsert = line + 1;
      int count = otherBlockCounts.getOrDefault(otherInsert, 0);
      if (count > 0) {
        fillerBlocks.add(El.Block.insert(fillerInsert).lines(run));
        run = 0;
        for (int ord = 0; ord < count; ord++) {
          PairKey key = fillerOnOld
              ? new PairKey(fillerInsert, otherInsert, ord)
              : new PairKey(otherInsert, fillerInsert, ord);
          otherPairs.put(new BlockSlot(otherInsert, ord), key);
          fillerBlocks.add(El.Block.insert(fillerInsert).paired(key));
        }
      }
    }
    if (run > 0) {
      fillerBlocks.add(El.Block.insert(fillerInsert).lines(run));
    }
  }

  /** The regions to collapse: each region without the lines the app's user expanded. */
  private static List<Region> activeCollapsed(State state) {
    if (state.regions.isEmpty()) {
      return List.of();
    }
    List<Region> expanded =
        state.expanded.stream().sorted(Comparator.comparingInt(Region::newFrom)).toList();
    List<Region> active = new ArrayList<>();
    for (Region r : state.regions) {
      int from = r.newFrom();
      for (Region e : expanded) {
        if (e.newFrom() < r.newTo() && from < e.newTo()) {
          addCollapsed(active, r, from, e.newFrom());
          from = e.newTo();
        }
      }
      addCollapsed(active, r, from, r.newTo());
    }
    return active;
  }

  /** Adds new lines {@code from} to {@code to} of {@code region} if they are worth collapsing. */
  private static void addCollapsed(List<Region> out, Region region, int from, int to) {
    if (to - from >= MIN_COLLAPSE) {
      int shift = region.oldFrom() - region.newFrom();
      out.add(new Region(from + shift, to + shift, from, to));
    }
  }

  /**
   * Maps each 0-based line in the new text to its corresponding 0-based line in the old text, or
   * {@code -1} for lines inside {@link Diff.Kind#ADDED} or {@link Diff.Kind#MODIFIED} hunks.
   */
  private static int[] mapNewToOldLines(Diff diff) {
    int[] map = new int[diff.newLineCount()];
    int oldPos = 0;
    int newPos = 0;
    for (Diff.Hunk h : diff.hunks()) {
      int unchanged = h.newStart() - newPos;
      for (int i = 0; i < unchanged; i++) {
        map[newPos + i] = oldPos + i;
      }
      for (int l = h.newStart(); l < h.newEnd(); l++) {
        map[l] = -1;
      }
      oldPos = h.oldEnd();
      newPos = h.newEnd();
    }
    for (int i = 0; newPos + i < map.length; i++) {
      map[newPos + i] = oldPos + i;
    }
    return map;
  }

  /**
   * Maps each 0-based line in the old text to its corresponding 0-based line in the new text, or
   * {@code -1} for lines inside {@link Diff.Kind#DELETED} or {@link Diff.Kind#MODIFIED} hunks.
   */
  private static int[] mapOldToNewLines(Diff diff) {
    int[] map = new int[diff.oldLineCount()];
    int oldPos = 0;
    int newPos = 0;
    for (Diff.Hunk h : diff.hunks()) {
      int unchanged = h.oldStart() - oldPos;
      for (int i = 0; i < unchanged; i++) {
        map[oldPos + i] = newPos + i;
      }
      for (int l = h.oldStart(); l < h.oldEnd(); l++) {
        map[l] = -1;
      }
      oldPos = h.oldEnd();
      newPos = h.newEnd();
    }
    for (int i = 0; oldPos + i < map.length; i++) {
      map[oldPos + i] = newPos + i;
    }
    return map;
  }

  /** {@link Side#OLD} marks on removed lines in {@link #unified}, by old line. */
  private record UnifiedOldMarks(
      Map<Integer, List<CodeEditor.Block>> blocks,
      Set<Integer> notes,
      Map<Integer, List<Diff.Span>> occurrences,
      Map<Integer, List<CodeEditor.Marker>> markers) {}

  /** Receives a range of the new text. */
  @FunctionalInterface
  private interface RangeConsumer {
    void accept(int from, int to);
  }

  /**
   * Sorts the {@link Side#OLD} marks for {@link #unified}: those on unchanged lines move to the
   * same place in the new text, into {@code translatedOut}; those on removed lines are grouped by
   * old line for the removed-lines blocks and their gutter.
   */
  private static UnifiedOldMarks classifyOldMarks(
      State state,
      List<CodeEditor.Mark> oldMarks,
      int[] oldToNew,
      List<CodeEditor.Mark> translatedOut) {
    Map<Integer, List<CodeEditor.Block>> blocks = new HashMap<>();
    Set<Integer> notes = new HashSet<>();
    Map<Integer, List<Diff.Span>> occurrences = new HashMap<>();
    Map<Integer, List<CodeEditor.Marker>> markers = new HashMap<>();
    for (CodeEditor.Mark mark : oldMarks) {
      switch (mark) {
        case CodeEditor.Marker m ->
          translatePoint(
              state,
              oldToNew,
              m.at(),
              at -> translatedOut.add(
                  new CodeEditor.Marker(at, m.symbol(), m.tone(), m.label(), m.onPress())),
              line -> markers.computeIfAbsent(line, k -> new ArrayList<>()).add(m));
        case CodeEditor.Block b ->
          translatePoint(
              state,
              oldToNew,
              b.at(),
              at -> translatedOut.add(new CodeEditor.Block(at, b.element())),
              line -> blocks.computeIfAbsent(line, k -> new ArrayList<>()).add(b));
        case CodeEditor.Highlight h ->
          translateRange(
              state,
              oldToNew,
              h.from(),
              h.to(),
              (from, to) -> translatedOut.add(new CodeEditor.Highlight(from, to, h.tone())),
              line -> {
                if (h.tone() == CodeEditor.Highlight.Tone.NOTE) {
                  notes.add(line);
                } else {
                  addOccurrenceSpan(state, line, h.from(), h.to(), occurrences);
                }
              });
        // shortcut: inlay hints, diagnostics and app change bars on removed lines are not shown;
        // draw them in the removed-lines block once an app puts them there.
        case CodeEditor.InlayHint h ->
          translatePoint(
              state,
              oldToNew,
              h.at(),
              at -> translatedOut.add(new CodeEditor.InlayHint(at, h.affinity(), h.text())),
              null);
        case CodeEditor.Diagnostic d -> // without fixes: the view is read-only
          translateRange(
              state,
              oldToNew,
              d.from(),
              d.to(),
              (from, to) ->
                  translatedOut.add(new CodeEditor.Diagnostic(from, to, d.severity(), d.message())),
              null);
        case CodeEditor.Change c ->
          translateRange(
              state,
              oldToNew,
              c.from(),
              c.to(),
              (from, to) -> translatedOut.add(new CodeEditor.Change(from, to, c.kind())),
              null);
      }
    }
    return new UnifiedOldMarks(blocks, notes, occurrences, markers);
  }

  private static int translateOffset(State state, int oldLine, int newLine, int oldOffset) {
    int col = oldOffset - state.oldStarts[oldLine];
    return Math.clamp((long) state.newStarts[newLine] + col, 0, state.newDisplayed.length());
  }

  /**
   * Passes old offset {@code at} moved to the new text to {@code onUnchanged}, or its old line to
   * {@code onRemoved} (when not null) where that line was removed.
   */
  private static void translatePoint(
      State state, int[] oldToNew, int at, IntConsumer onUnchanged, IntConsumer onRemoved) {
    if (oldToNew.length == 0) { // an empty old text: only beside an empty new one
      if (at == 0 && state.newDisplayed.isEmpty()) {
        onUnchanged.accept(0);
      }
      return;
    }
    if (at < 0 || at > state.oldDisplayed.length()) {
      return;
    }
    int oldLine = lineOf(state.oldStarts, at);
    int newLine = oldToNew[oldLine];
    if (newLine >= 0) {
      onUnchanged.accept(translateOffset(state, oldLine, newLine, at));
    } else if (onRemoved != null) {
      onRemoved.accept(oldLine);
    }
  }

  /**
   * Passes the parts of old range {@code [from, to)} on unchanged lines, moved to the new text, to
   * {@code onUnchanged}, and each removed old line it touches to {@code onRemoved} (when not null).
   */
  private static void translateRange(
      State state,
      int[] oldToNew,
      int from,
      int to,
      RangeConsumer onUnchanged,
      IntConsumer onRemoved) {
    from = Math.max(0, from);
    to = Math.min(state.oldDisplayed.length(), to);
    if (to <= from) {
      translatePoint(state, oldToNew, from, at -> onUnchanged.accept(at, at), onRemoved);
      return;
    }
    int runFrom = -1;
    int runTo = -1;
    int last = lineOf(state.oldStarts, to - 1);
    for (int line = lineOf(state.oldStarts, from); line <= last; line++) {
      int newLine = oldToNew[line];
      if (newLine < 0) {
        if (onRemoved != null) {
          onRemoved.accept(line);
        }
        continue;
      }
      int lineFrom = translateOffset(state, line, newLine, Math.max(from, state.oldStarts[line]));
      if (lineFrom != runTo) { // lines that follow each other in the new text make one range
        if (runFrom >= 0) {
          onUnchanged.accept(runFrom, runTo);
        }
        runFrom = lineFrom;
      }
      runTo = translateOffset(state, line, newLine, Math.min(to, state.oldStarts[line + 1]));
    }
    if (runFrom >= 0) {
      onUnchanged.accept(runFrom, runTo);
    }
  }

  private static void addOccurrenceSpan(
      State state, int oldLine, int rawFrom, int rawTo, Map<Integer, List<Diff.Span>> out) {
    int start = state.oldStarts[oldLine];
    int lineLen = state.oldLines[oldLine].length();
    int from = Math.max(0, rawFrom - start);
    int to = Math.min(lineLen, rawTo - start);
    if (to > from) {
      out.computeIfAbsent(oldLine, k -> new ArrayList<>()).add(new Diff.Span(from, to));
    }
  }

  /** The most app markers on any new or removed line, so removed rows line up with the others. */
  private static int unifiedMarkerSlots(
      String newDisplayed,
      List<CodeEditor.Mark> newMarks,
      List<CodeEditor.Mark> newDiffMarks,
      UnifiedOldMarks unifiedOld) {
    List<CodeEditor.Marker> markers = new ArrayList<>();
    for (List<CodeEditor.Mark> marks : List.of(newMarks, newDiffMarks)) {
      for (CodeEditor.Mark mark : marks) {
        if (mark instanceof CodeEditor.Marker marker) {
          markers.add(marker);
        }
      }
    }
    int max = CodeEditor.maxMarkersPerLine(newDisplayed, markers);
    for (List<CodeEditor.Marker> onLine : unifiedOld.markers().values()) {
      max = Math.max(max, onLine.size());
    }
    return max;
  }

  /**
   * Builds strictly increasing {@link ScrollState.Anchor} pairs aligning every unchanged run and
   * modified hunk start between the old and new panes.
   */
  static List<ScrollState.Anchor> scrollAnchors(Diff diff) {
    if (diff.oldLineCount() == 0 && diff.newLineCount() == 0) {
      return List.of();
    }
    // An empty side shows one line and after it a filler for the rest of the other side.
    if (diff.oldLineCount() == 0) {
      return List.of(new ScrollState.Anchor(0, 0), new ScrollState.Anchor(1, diff.newLineCount()));
    }
    if (diff.newLineCount() == 0) {
      return List.of(new ScrollState.Anchor(0, 0), new ScrollState.Anchor(diff.oldLineCount(), 1));
    }
    List<ScrollState.Anchor> anchors = new ArrayList<>();
    int oldPos = 0;
    int newPos = 0;
    for (Diff.Hunk h : diff.hunks()) {
      if (h.oldStart() > oldPos && h.newStart() > newPos) {
        addAnchor(anchors, oldPos, newPos);
        if (h.oldStart() - 1 > oldPos) {
          addAnchor(anchors, h.oldStart() - 1, h.newStart() - 1);
        }
      }
      // The rows both sides have align one to one; only the last of them and the filler after
      // it stretch against the longer side's rest.
      int shared = Math.min(h.oldEnd() - h.oldStart(), h.newEnd() - h.newStart());
      if (shared > 0) {
        addAnchor(anchors, h.oldStart(), h.newStart());
        addAnchor(anchors, h.oldStart() + shared - 1, h.newStart() + shared - 1);
      }
      oldPos = h.oldEnd();
      newPos = h.newEnd();
    }
    if (oldPos < diff.oldLineCount() && newPos < diff.newLineCount()) {
      addAnchor(anchors, oldPos, newPos);
      if (diff.oldLineCount() - 1 > oldPos) {
        addAnchor(anchors, diff.oldLineCount() - 1, diff.newLineCount() - 1);
      }
    }
    addAnchor(anchors, diff.oldLineCount(), diff.newLineCount());
    return List.copyOf(anchors);
  }

  private static void addAnchor(List<ScrollState.Anchor> anchors, int oldLine, int newLine) {
    if (!anchors.isEmpty()) {
      ScrollState.Anchor prev = anchors.getLast();
      if (oldLine <= prev.line() || newLine <= prev.otherLine()) {
        return;
      }
    }
    anchors.add(new ScrollState.Anchor(oldLine, newLine));
  }

  /** Filler block occupying the exact height of lines only present on the opposite side. */
  private static El fillerBlock(Theme theme) {
    return El.el().wFull().hFull().fill(theme.diff().filler());
  }

  /** Empty paired spacer keeping the opposite side aligned with a review block. */
  private static El spacerBlock(Object pair) {
    PairKey key = (PairKey) pair;
    String name = "spacer-" + key.oldInsert() + "-" + key.newInsert()
        + (key.ordinal() == 0 ? "" : "-" + key.ordinal());
    return El.el().key(name);
  }

  /** Clickable 1-line bar replacing a collapsed unchanged region. */
  private static El collapsedRow(Theme theme, Texts texts, State state, Region region) {
    String label = texts.unchangedLines(region.count());
    float stroke = theme.size().stroke();
    int hover = Theme.over(theme.surface().band(), theme.surface().hover());
    return El.row()
        .key("unchanged-" + region.newFrom())
        .role("button")
        .label(label)
        .wFull()
        .hFull()
        .align(Style.Align.CENTER)
        .gap(theme.space().s())
        .padding(0, theme.space().m())
        .fill(theme.surface().band())
        .border(stroke, 0, stroke, 0, theme.border().subtle())
        .cursor(Cursor.POINTER)
        .keepFocus()
        .onPress(() -> state.expand(region))
        .whenHovered(e -> e.fill(hover))
        .child(Icon.icon(
            Symbol.CHEVRON_DOWN, Icon.Variant.MUTED, theme.type().small().size()))
        .child(Label.label(Label.Variant.SMALL, label));
  }

  /** Gutter strip aligned with a collapsed unchanged region bar. */
  private static El collapsedGutter(Theme theme, Texts texts, State state, Region region) {
    float stroke = theme.size().stroke();
    int hover = Theme.over(theme.surface().band(), theme.surface().hover());
    return El.row()
        .key("unchanged-gutter-" + region.newFrom())
        .role("button")
        .label(texts.unchangedLines(region.count()))
        .wFull()
        .hFull()
        .align(Style.Align.CENTER)
        .justify(Style.Content.CENTER)
        .fill(theme.surface().band())
        .border(stroke, 0, stroke, 0, theme.border().subtle())
        .cursor(Cursor.POINTER)
        .keepFocus()
        .onPress(() -> state.expand(region))
        .whenHovered(e -> e.fill(hover))
        .child(Icon.icon(
            Symbol.MORE_HORIZONTAL, Icon.Variant.MUTED, theme.type().small().size()));
  }

  /**
   * Splits a removed run {@code [oldStart, oldEnd)} into consecutive blocks of at most {@link
   * #MAX_DELETED_BLOCK_LINES} lines and right after every old line carrying a {@link
   * CodeEditor.Block}, placing those blocks in order between chunks at the same insert line. When
   * the new side is empty, the first chunk replaces its single empty line and later chunks and
   * blocks stack after it.
   */
  private static void addDeletedBlocks(
      List<El.Block> blocks,
      Map<El.Block, DeletedChunk> deletedMap,
      Map<El.Block, Supplier<El>> removedBlocks,
      Map<Integer, List<CodeEditor.Block>> blocksByOldLine,
      int oldStart,
      int oldEnd,
      int newStart,
      boolean emptyNew) {
    int from = oldStart;
    boolean firstChunk = true;
    while (from < oldEnd) {
      int limit = Math.min(from + MAX_DELETED_BLOCK_LINES, oldEnd);
      int to = limit;
      List<CodeEditor.Block> atEnd = null;
      if (!blocksByOldLine.isEmpty()) {
        for (int line = from; line < limit; line++) {
          List<CodeEditor.Block> onLine = blocksByOldLine.get(line);
          if (onLine != null && !onLine.isEmpty()) {
            to = line + 1;
            atEnd = onLine;
            break;
          }
        }
      }
      int count = to - from;
      El.Block block = emptyNew
          ? (firstChunk
              ? El.Block.replace(0, 0).lines(count)
              : El.Block.insert(1).lines(count))
          : El.Block.insert(newStart).lines(count);
      blocks.add(block);
      deletedMap.put(block, new DeletedChunk(from, to, oldStart, oldEnd));
      firstChunk = false;
      if (atEnd != null) {
        int insertLine = emptyNew ? 1 : newStart;
        String prefix = "removed-block-" + (to - 1) + "-";
        for (CodeEditor.Block markBlock : atEnd) {
          El.Block extra = El.Block.insert(insertLine).pinned(true);
          blocks.add(extra);
          removedBlocks.put(extra, () -> {
            El element = markBlock.element().get();
            // By old line too: all removed lines insert at one line, where the app's keys collide.
            // One without a key gets the field's own key by its place at that line.
            return element.key() != null ? element.key(prefix + element.key()) : element;
          });
        }
      }
      from = to;
    }
  }

  /**
   * Read-only inserted block showing deleted lines {@code [fromLine, toLine)} in the unified diff
   * view.
   */
  private static El deletedLinesBlock(
      Theme theme,
      State state,
      DeletedChunk chunk,
      Highlighter highlighter,
      Object[] oldStartStates,
      Map<Integer, List<Diff.Span>> oldWordSpans,
      Set<Integer> removedNotes,
      Map<Integer, List<Diff.Span>> removedOccurrences) {
    Theme.TextStyle mono = theme.type().mono();
    Theme.Diff diffColors = theme.diff();
    El container =
        El.col().key("deleted-" + chunk.from()).wFull().hFull().fill(diffColors.removed());
    for (int lineIdx = chunk.from(); lineIdx < chunk.to(); lineIdx++) {
      String raw = lineIdx < state.oldLines.length ? state.oldLines[lineIdx] : "";
      List<Diff.Span> spans = oldWordSpans.get(lineIdx);
      List<Diff.Span> occSpans = removedOccurrences.get(lineIdx);
      Object startState = lineIdx < oldStartStates.length ? oldStartStates[lineIdx] : null;
      // From basis 0, so blank rows are as tall as the others and as their gutter rows.
      El row = El.row().wFull().basis(0).grow(1).align(Style.Align.CENTER);
      boolean noted = removedNotes.contains(lineIdx)
          || (state.oldDragLines != null
              && lineIdx >= state.oldDragLines.first()
              && lineIdx <= state.oldDragLines.last());
      if (noted) {
        row.fill(theme.code().note());
      }
      if (!raw.isEmpty()) {
        Expanded expanded = expandTabs(raw);
        El textEl = El.text(expanded.text())
            .font(mono.font().get())
            .fontSize(mono.size())
            .color(theme.text().primary())
            .wrapText(false);
        if (highlighter != null
            || (spans != null && !spans.isEmpty())
            || (occSpans != null && !occSpans.isEmpty())) {
          Object key = new DeletedHighlightKey(
              highlighter,
              startState,
              spans == null ? List.of() : spans,
              diffColors.removedWord(),
              occSpans == null ? List.of() : occSpans,
              theme.code().occurrence());
          textEl.highlight(key, (lineText, st, out) -> {
            if (highlighter != null) {
              highlighter.line(expanded.text(), startState, out);
            }
            if (occSpans != null) {
              for (Diff.Span s : occSpans) {
                int from = expanded.map(s.from());
                int to = expanded.map(s.to());
                if (to > from) {
                  out.background(from, to, theme.code().occurrence());
                }
              }
            }
            if (spans != null) {
              for (Diff.Span s : spans) {
                int from = expanded.map(s.from());
                int to = expanded.map(s.to());
                if (to > from) {
                  out.background(from, to, diffColors.removedWord());
                }
              }
            }
            return null;
          });
        }
        row.child(textEl);
      }
      container.child(row);
    }
    if (chunk.from() > chunk.runFrom() || chunk.to() < chunk.runTo()) {
      // Every chunk of a split run as wide as its widest line, so the tint stays whole sideways.
      container.child(El.el().w(widestWidth(theme, state, chunk)).h(0).shrink(0));
    }
    return container;
  }

  /** Width of the widest line of the removed run of {@code chunk}, tabs expanded. */
  private static float widestWidth(Theme theme, State state, DeletedChunk chunk) {
    // shortcut: picks the line with the most columns, not the widest shaped one (combining marks,
    // wide glyphs); measure each line's width if such runs lose their tint sideways.
    String widest = state.widestRemoved.computeIfAbsent(chunk.runFrom(), from -> {
      String found = "";
      int foundColumns = -1;
      for (int lineIdx = from;
          lineIdx < chunk.runTo() && lineIdx < state.oldLines.length;
          lineIdx++) {
        String raw = state.oldLines[lineIdx];
        int columns = 0;
        for (int i = 0; i < raw.length(); i++) {
          columns += raw.charAt(i) == '\t' ? TAB_SIZE - columns % TAB_SIZE : 1;
        }
        if (columns > foundColumns) {
          found = raw;
          foundColumns = columns;
        }
      }
      return expandTabs(found).text();
    });
    return CodeEditor.monoWidth(theme, widest);
  }

  private record DeletedHighlightKey(
      Highlighter highlighter,
      Object startState,
      List<Diff.Span> spans,
      int wordColor,
      List<Diff.Span> occurrences,
      int occurrenceColor) {}

  private record RemovedRow(int line, float top, float bottom) {}

  /**
   * Gutter rows for deleted lines of {@code chunk} in unified diff, matching {@link CodeEditor}'s
   * gutter lanes: diagnostic slot, app markers, line action, old number, blank new number, red bar,
   * control slot, and optional note stripe.
   */
  private static El deletedLinesGutter(
      Theme theme,
      State state,
      DeletedChunk chunk,
      float oldColW,
      float newColW,
      int markerSlots,
      Map<Integer, List<CodeEditor.Marker>> removedMarkers,
      Set<Integer> removedNotes) {
    Theme.TextStyle mono = theme.type().mono();
    float bar = theme.size().indicator();
    float s = theme.space().s();
    boolean hasAction = state.lineAction != null;
    int slots = markerSlots + (hasAction ? 1 : 0);
    El column = El.col().key("deleted-gutter-" + chunk.from()).wFull().hFull();
    for (int lineIdx = chunk.from(); lineIdx < chunk.to(); lineIdx++) {
      El row = El.row()
          .key("old-line-" + lineIdx)
          .align(Style.Align.CENTER)
          .gap(s)
          .padding(0, s, 0, s + bar)
          .wFull()
          .grow(1)
          .child(El.el().w(mono.size()).shrink(0));
      El action = hasAction ? removedLineAction(theme, state, chunk, lineIdx) : null;
      if (slots > 0) {
        int placed = 0;
        List<CodeEditor.Marker> lineMarkers = removedMarkers.get(lineIdx);
        if (lineMarkers != null) {
          for (CodeEditor.Marker m : lineMarkers) {
            placed++;
            row.child(CodeEditor.appMarker(theme, m, placed).w(mono.size()).shrink(0));
            if (placed == markerSlots) {
              break;
            }
          }
        }
        if (action != null) {
          row.child(action);
          placed++;
        }
        for (int i = placed; i < slots; i++) {
          row.child(El.el().w(mono.size()).shrink(0));
        }
      }
      row.child(El.text(String.valueOf(lineIdx + 1))
              .font(mono.font().get())
              .fontSize(mono.size())
              .color(theme.text().muted())
              .textAlign(Style.Align.END)
              .w(oldColW)
              .shrink(0))
          .child(El.el().w(newColW).shrink(0))
          .child(El.el()
              .key("change")
              .w(bar)
              .shrink(0)
              .fill(theme.status().danger())
              .alignSelf(Style.Align.STRETCH))
          .child(El.el().w(mono.size()).shrink(0));
      if (action != null) {
        row.whenHovered(e -> action.opacity(1));
      }
      if (removedNotes.contains(lineIdx)) {
        El stripe = El.el()
            .key("note")
            .w(bar)
            .fill(Colors.alpha(theme.code().note(), 1))
            .inert();
        Style at = stripe.style();
        at.position = Style.Position.ABSOLUTE;
        at.right = Len.ZERO;
        at.top = Len.ZERO;
        at.bottom = Len.ZERO;
        row.child(stripe);
      }
      column.child(row);
    }
    return column;
  }

  /**
   * The gutter line-action button of a removed row in unified diff: hidden until its row is
   * hovered (or while its drag runs), reporting {@link Side#OLD} lines within the same removed run.
   */
  private static El removedLineAction(Theme theme, State state, DeletedChunk chunk, int lineIdx) {
    State.LineAction action = state.lineAction;
    float size = theme.type().mono().size();
    El button = El.row()
        .key("action-old-line-" + lineIdx)
        .role("button")
        .label(action.label())
        .size(size, size)
        .align(Style.Align.CENTER)
        .justify(Style.Content.CENTER)
        .shrink(0)
        .radius(theme.radius().small())
        .cursor(Cursor.POINTER)
        .opacity(state.oldDragLines != null && state.oldDragAnchor == lineIdx ? 1 : 0)
        .keepFocus()
        .whenHovered(e -> e.fill(theme.surface().hover()))
        .child(Icon.icon(action.symbol(), Icon.Variant.MUTED, size));
    button.onPress(() -> {
      State.LineAction current = state.lineAction;
      if (current != null) {
        current.onPress().accept(new Lines(Side.OLD, lineIdx, lineIdx));
      }
    });
    button.onDrag(drag -> {
      State.LineAction current = state.lineAction;
      if (current == null || drag.phase() == Drag.Phase.CANCEL) {
        state.oldDragLines = null;
        return;
      }
      Lines lines = removedDragLines(state, chunk.runFrom(), chunk.runTo(), lineIdx, drag);
      if (drag.phase() == Drag.Phase.END) {
        Lines done = lines != null ? lines : state.oldDragLines;
        state.oldDragLines = null;
        current.onPress().accept(done != null ? done : new Lines(Side.OLD, lineIdx, lineIdx));
        return;
      }
      state.oldDragAnchor = lineIdx;
      if (lines != null) {
        state.oldDragLines = lines;
      }
    });
    return Tooltip.tooltip(button, action.label());
  }

  /**
   * The OLD lines from {@code anchor} to the removed row under a drag step within the same removed
   * run {@code [runFrom, runTo)}, found among the laid-out gutter cells, or null when absent.
   */
  private static Lines removedDragLines(
      State state, int runFrom, int runTo, int anchor, Drag drag) {
    El editor = state.newEditor.editor();
    if (editor == null || Double.isNaN(drag.y())) {
      return null;
    }
    El gutter = null;
    for (El child : editor.children()) {
      if ("gutter".equals(child.key())) {
        gutter = child;
      }
    }
    if (gutter == null) {
      return null;
    }
    List<RemovedRow> rows = new ArrayList<>();
    double y = Double.NaN;
    for (El blockCell : gutter.children()) {
      if (blockCell.key() == null || !blockCell.key().startsWith("deleted-gutter-")) {
        continue;
      }
      for (El row : blockCell.children()) {
        if (row.key() == null || !row.key().startsWith("old-line-")) {
          continue;
        }
        int line = Integer.parseInt(row.key().substring("old-line-".length()));
        if (line < runFrom || line >= runTo) {
          continue;
        }
        float rowTop = blockCell.y() + row.y();
        rows.add(new RemovedRow(line, rowTop, rowTop + row.height()));
        if (line == anchor) {
          for (El child : row.children()) {
            if (("action-old-line-" + anchor).equals(child.key())) {
              y = rowTop + child.y() + drag.y();
            }
          }
        }
      }
    }
    if (Double.isNaN(y) || rows.isEmpty()) {
      return null;
    }
    rows.sort(Comparator.comparingInt(RemovedRow::line));
    int first = anchor;
    int last = anchor;
    boolean found = false;
    for (int i = 0; i < rows.size(); i++) {
      RemovedRow row = rows.get(i);
      // Past the run's ends counts as its end rows; over a card between rows, as no row.
      boolean under = (i == 0 || y >= row.top()) && (i + 1 == rows.size() || y < row.bottom());
      found |= under;
      if (under || row.line() == anchor) {
        first = Math.min(first, row.line());
        last = Math.max(last, row.line());
      }
    }
    return found ? new Lines(Side.OLD, first, last) : null;
  }

  private record Expanded(String text, int[] offsets) {
    int map(int rawIndex) {
      if (offsets == null) {
        return rawIndex;
      }
      return offsets[Math.clamp(rawIndex, 0, offsets.length - 1)];
    }
  }

  /** Expands tabs to {@link #TAB_SIZE}-column stops so deleted lines align with editor lines. */
  private static Expanded expandTabs(String raw) {
    if (raw.indexOf('\t') < 0) {
      return new Expanded(raw, null);
    }
    StringBuilder sb = new StringBuilder(raw.length() + 8);
    int[] offsets = new int[raw.length() + 1];
    int col = 0;
    for (int i = 0; i < raw.length(); i++) {
      offsets[i] = sb.length();
      char c = raw.charAt(i);
      if (c == '\t') {
        int spaces = TAB_SIZE - (col % TAB_SIZE);
        for (int s = 0; s < spaces; s++) {
          sb.append(' ');
        }
        col += spaces;
      } else {
        sb.append(c);
        col++;
      }
    }
    offsets[raw.length()] = sb.length();
    return new Expanded(sb.toString(), offsets);
  }
}
