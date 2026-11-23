package com.rethinkj.ui.editor;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Cursor;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

  /**
   * A slice {@code [from, to)} of the removed run {@code [runFrom, runTo)} shown by one
   * removed-lines block in unified diff.
   */
  private record DeletedChunk(int from, int to, int runFrom, int runTo) {}

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
    private final Set<Integer> expanded = new HashSet<>();
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

    // Cached highlighter start states per old line, for unified deleted-line blocks.
    private Highlighter cachedHighlighter;
    private String cachedHighlighterOld;
    private Object[] oldHighlightStates;

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

    private void expand(int regionKey) {
      expanded.add(regionKey);
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
      regions = collapsibleRegions(diff);
      expanded.clear();
      currentChange = -1;
      cachedHighlighterOld = null;
      oldHighlightStates = null;
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

    // Assemble filler blocks and collapsed unchanged region blocks for each side.
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
    for (Diff.Hunk h : diff.hunks()) {
      int oldCount = h.oldEnd() - h.oldStart();
      int newCount = h.newEnd() - h.newStart();
      if (newCount > oldCount) {
        if (diff.oldLineCount() == 0) { // the empty side's one line, then filler for the rest
          if (newCount > 1) {
            oldBlocks.add(El.Block.insert(1).lines(newCount - 1));
          }
          oldLineTints.put(0, diffColors.filler());
        } else {
          oldBlocks.add(El.Block.insert(h.oldEnd()).lines(newCount - oldCount));
        }
      } else if (oldCount > newCount) {
        if (diff.newLineCount() == 0) {
          if (oldCount > 1) {
            newBlocks.add(El.Block.insert(1).lines(oldCount - 1));
          }
          newLineTints.put(0, diffColors.filler());
        } else {
          newBlocks.add(El.Block.insert(h.newEnd()).lines(oldCount - newCount));
        }
      }
    }

    state.oldEditor.extraBlocks(new CodeEditor.State.ExtraBlocks(
        oldBlocks,
        block -> {
          Region region = oldCollapsedMap.get(block);
          return region != null ? collapsedRow(theme, texts, state, region) : fillerBlock(theme);
        },
        block -> {
          Region region = oldCollapsedMap.get(block);
          if (region != null) {
            state.expand(region.newFrom());
          }
        },
        block -> {
          Region region = oldCollapsedMap.get(block);
          return region != null ? collapsedGutter(theme, texts, state, region) : null;
        }));
    state.newEditor.extraBlocks(new CodeEditor.State.ExtraBlocks(
        newBlocks,
        block -> {
          Region region = newCollapsedMap.get(block);
          return region != null ? collapsedRow(theme, texts, state, region) : fillerBlock(theme);
        },
        block -> {
          Region region = newCollapsedMap.get(block);
          if (region != null) {
            state.expand(region.newFrom());
          }
        },
        block -> {
          Region region = newCollapsedMap.get(block);
          return region != null ? collapsedGutter(theme, texts, state, region) : null;
        }));

    // Couple vertical & horizontal scrolling by hunk anchors.
    state.oldScroll.scrollWith(state.newScroll, scrollAnchors(diff));

    El left = CodeEditor.codeEditor(app, oldText, state.oldEditor, highlighter, null)
        .key("old")
        .label(texts.original())
        .radius(0)
        .border(0, 0, 0, 0, 0)
        .shadow()
        .whenFocused(null)
        .grow(1)
        .w(0)
        .hFull()
        .scrollState(state.oldScroll)
        .alsoDecorate((line, start, text, out) -> {
          Integer bg = oldLineTints.get(line);
          if (bg != null) {
            out.lineBackground(bg);
          }
          List<Diff.Span> spans = oldWordSpans.get(line);
          if (spans != null) {
            for (Diff.Span s : spans) {
              out.background(s.from(), s.to(), diffColors.modifiedWord());
            }
          }
        })
        .onKeyFirst(k -> diffKey(app, state, k));

    El right = CodeEditor.codeEditor(app, newText, state.newEditor, highlighter, null)
        .key("new")
        .label(texts.modified())
        .radius(0)
        .border(0, 0, 0, 0, 0)
        .shadow()
        .whenFocused(null)
        .grow(1)
        .w(0)
        .hFull()
        .scrollState(state.newScroll)
        .alsoDecorate((line, start, text, out) -> {
          Integer bg = newLineTints.get(line);
          if (bg != null) {
            out.lineBackground(bg);
          }
          List<Diff.Span> spans = newWordSpans.get(line);
          if (spans != null) {
            for (Diff.Span s : spans) {
              out.background(s.from(), s.to(), diffColors.modifiedWord());
            }
          }
        })
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
            state.expand(region.newFrom());
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
        }));

    El editor = CodeEditor.codeEditor(app, newText, state.newEditor, highlighter, null)
        .key("diff-unified")
        .label(texts.modified())
        .scrollState(state.newScroll)
        .alsoDecorate((line, start, text, out) -> {
          Integer bg = newLineTints.get(line);
          if (bg != null) {
            out.lineBackground(bg);
          }
          List<Diff.Span> spans = newWordSpans.get(line);
          if (spans != null) {
            for (Diff.Span s : spans) {
              out.background(s.from(), s.to(), diffColors.addedWord());
            }
          }
        })
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
      editorState.noMarkup("diff");
    } else {
      editorState.markup("diff", displayed, changes);
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
      addRegion(out, oldPos, h.oldStart(), newPos, h.newStart(), topContext, bottomContext);
      oldPos = h.oldEnd();
      newPos = h.newEnd();
    }
    addRegion(out, oldPos, diff.oldLineCount(), newPos, diff.newLineCount(), CONTEXT_LINES, 0);
    return List.copyOf(out);
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

  private static List<Region> activeCollapsed(State state) {
    if (state.regions.isEmpty()) {
      return List.of();
    }
    List<Region> active = new ArrayList<>();
    for (Region r : state.regions) {
      if (!state.expanded.contains(r.newFrom())) {
        active.add(r);
      }
    }
    return active;
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
        .onPress(() -> state.expand(region.newFrom()))
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
        .onPress(() -> state.expand(region.newFrom()))
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
    String widest = "";
    for (int lineIdx = chunk.runFrom(); lineIdx < chunk.runTo(); lineIdx++) {
      String raw = lineIdx < state.oldLines.length ? state.oldLines[lineIdx] : "";
      String text = expandTabs(raw).text();
      if (text.length() > widest.length()) {
        widest = text;
      }
    }
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
