package com.rethinkj.ui.editor;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.Decorator;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.ScrollState;
import com.rethinkj.ui.Scrollbars;
import com.rethinkj.ui.Spans;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Texts;
import com.rethinkj.ui.kit.Theme;
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
     * <p>{@link #unified} shows only the {@link Side#NEW} side's marks.
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
     * {@link Lines} range carrying that {@link Side} (a drag stays on the side it started on;
     * Escape cancels). {@link #unified} offers it on the new side only. Passing a null {@code
     * symbol} or {@code onPress} removes it from both sides.
     */
    public State lineAction(Icon symbol, String label, Consumer<Lines> onPress) {
      if (symbol == null || onPress == null) {
        oldEditor.lineAction(null, null, null);
        newEditor.lineAction(null, null, null);
        return this;
      }
      Objects.requireNonNull(label, "label");
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
      double line;
      if (scrollOld) {
        line = hunk.oldStart();
      } else if (sideBySide || oldCount == 0) {
        line = hunk.newStart();
      } else if (hunk.newStart() == 0) {
        line = -oldCount;
      } else {
        line = hunk.newStart() - 1 + 1.0 / (oldCount + 1);
      }
      // From the line's start, where the caret and the change's first words are.
      app.scrollTo(field, 0, Math.max(0, field.text().lineToOffset(line)));
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
    int maxOldLine = Math.max(1, diff.oldLineCount());
    int maxNewLine = Math.max(1, diff.newLineCount());
    state.newEditor.oldNumbers(new CodeEditor.State.OldNumbers(
        maxOldLine,
        maxNewLine,
        line -> line >= 0 && line < newToOld.length && newToOld[line] >= 0
            ? String.valueOf(newToOld[line] + 1)
            : ""));

    List<CodeEditor.Change> newChanges = new ArrayList<>();
    List<Scrollbars.Mark> deletedScrollMarks = new ArrayList<>();
    Map<Integer, Integer> newLineTints = new HashMap<>();
    Map<Integer, List<Diff.Span>> newWordSpans = new HashMap<>();
    Map<Integer, List<Diff.Span>> oldWordSpans = new HashMap<>();

    List<El.Block> blocks = new ArrayList<>();
    Map<El.Block, Region> collapsedMap = new HashMap<>();
    Map<El.Block, DeletedChunk> deletedMap = new IdentityHashMap<>();

    for (Region r : collapsed) {
      El.Block nb = El.Block.replace(r.newFrom(), r.newTo() - 1).lines(1);
      blocks.add(nb);
      collapsedMap.put(nb, r);
    }

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
          addDeletedBlocks(
              blocks, deletedMap, h.oldStart(), h.oldEnd(), h.newStart(), diff.newLineCount() == 0);
          int at = lineStart(
              state.newStarts, Math.clamp(h.newStart(), 0, Math.max(0, diff.newLineCount() - 1)));
          deletedScrollMarks.add(new Scrollbars.Mark(at, at, theme.status().danger()));
        }
        case MODIFIED -> {
          addDeletedBlocks(blocks, deletedMap, h.oldStart(), h.oldEnd(), h.newStart(), false);
          addChangeRange(
              newChanges, state.newStarts, h.newStart(), h.newEnd(), CodeEditor.Change.Kind.ADDED);
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
    setDiffMarkup(state.newEditor, state.newDisplayed, newChanges);

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
          return chunk != null
              ? deletedLinesBlock(theme, state, chunk, highlighter, oldStartStates, oldWordSpans)
              : fillerBlock(theme);
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
              ? deletedLinesGutter(theme, chunk.from(), chunk.to(), oldColW, newColW)
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
      CodeEditor.State editorState, String displayed, List<CodeEditor.Change> changes) {
    if (changes.isEmpty()) {
      editorState.noMarkup(DIFF_LAYER);
    } else {
      editorState.markup(DIFF_LAYER, displayed, changes);
    }
  }

  private static void addChangeRange(
      List<CodeEditor.Change> out,
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
   * #MAX_DELETED_BLOCK_LINES} lines so layout builds only the chunks in view. When the new side is
   * empty, the first chunk replaces its single empty line and later chunks stack after it.
   */
  private static void addDeletedBlocks(
      List<El.Block> blocks,
      Map<El.Block, DeletedChunk> deletedMap,
      int oldStart,
      int oldEnd,
      int newStart,
      boolean emptyNew) {
    for (int from = oldStart; from < oldEnd; from += MAX_DELETED_BLOCK_LINES) {
      int to = Math.min(from + MAX_DELETED_BLOCK_LINES, oldEnd);
      int count = to - from;
      El.Block block = emptyNew
          ? (from == oldStart
              ? El.Block.replace(0, 0).lines(count)
              : El.Block.insert(1).lines(count))
          : El.Block.insert(newStart).lines(count);
      blocks.add(block);
      deletedMap.put(block, new DeletedChunk(from, to, oldStart, oldEnd));
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
      Map<Integer, List<Diff.Span>> oldWordSpans) {
    Theme.TextStyle mono = theme.type().mono();
    Theme.Diff diffColors = theme.diff();
    El container =
        El.col().key("deleted-" + chunk.from()).wFull().hFull().fill(diffColors.removed());
    for (int lineIdx = chunk.from(); lineIdx < chunk.to(); lineIdx++) {
      String raw = lineIdx < state.oldLines.length ? state.oldLines[lineIdx] : "";
      List<Diff.Span> spans = oldWordSpans.get(lineIdx);
      Object startState = lineIdx < oldStartStates.length ? oldStartStates[lineIdx] : null;
      El row = El.row().wFull().grow(1).align(Style.Align.CENTER);
      if (!raw.isEmpty()) {
        Expanded expanded = expandTabs(raw);
        El textEl = El.text(expanded.text())
            .font(mono.font().get())
            .fontSize(mono.size())
            .color(theme.text().primary())
            .wrapText(false);
        if (highlighter != null || (spans != null && !spans.isEmpty())) {
          Object key = new DeletedHighlightKey(
              highlighter, startState, spans == null ? List.of() : spans, diffColors.removedWord());
          textEl.highlight(key, (lineText, st, out) -> {
            if (highlighter != null) {
              highlighter.line(expanded.text(), startState, out);
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
    if (chunk.runTo() - chunk.runFrom() > MAX_DELETED_BLOCK_LINES) {
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
      Highlighter highlighter, Object startState, List<Diff.Span> spans, int wordColor) {}

  /**
   * Gutter rows for deleted lines {@code [fromLine, toLine)} in unified diff: old numbers, blank
   * new number, red bar.
   */
  private static El deletedLinesGutter(
      Theme theme, int fromLine, int toLine, float oldColW, float newColW) {
    Theme.TextStyle mono = theme.type().mono();
    float bar = theme.size().indicator();
    float s = theme.space().s();
    El column = El.col().key("deleted-gutter-" + fromLine).wFull().hFull();
    for (int lineIdx = fromLine; lineIdx < toLine; lineIdx++) {
      El row = El.row()
          .key("old-line-" + lineIdx)
          .align(Style.Align.CENTER)
          .gap(s)
          .padding(0, s, 0, s + bar)
          .wFull()
          .grow(1)
          .child(El.el().w(mono.size()).shrink(0))
          .child(El.text(String.valueOf(lineIdx + 1))
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
      column.child(row);
    }
    return column;
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
