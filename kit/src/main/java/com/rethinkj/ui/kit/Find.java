package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.App;
import com.rethinkj.ui.CodeWords;
import com.rethinkj.ui.Decorator;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Scrollbars;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.TextEdit;
import com.rethinkj.ui.Work;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Find and replace in a multi-line text field or a {@link El#selectionScope selection scope}, such
 * as a code editor ({@code CodeEditor}), which has it from the start, a {@link TextField#textArea},
 * {@link Markdown#markdown}, or a virtual list of messages marked {@link El#selectionScope()}: a
 * bar along the top of the field or scope with the text to find, the number of matches, switches
 * for case, whole words, regular expressions and (in a field) the selection, and below it the
 * replacement; or a field for the line to go to.
 *
 * <pre>{@code
 * El area = TextField.textArea(state.text, "Notes", value -> state.text = value)
 *     .key("notes");
 * Find.find(app, area, state.find);
 * }</pre>
 *
 * <p>Control+F opens it with the selected text, or the word at the caret, as the text to find,
 * Control+H with the replacement too, Control+G with the line to go to; Escape closes it and gives
 * the focus back to the field, with the last match selected. A match the bar goes to scrolls into
 * view below it, also in the first lines, for which the field scrolls above its first row while
 * the bar shows ({@link El#coveredTop}). While it shows, every match in view
 * has {@code code().match()} behind it and the current one, the selection, {@code
 * code().current()} with a line of {@code accent().edge()} under it. Enter and F3 go to the next
 * match, Shift+Enter and Shift+F3 to the previous,
 * round at the ends, F3 also while the bar is closed. Alt+C, Alt+W, Alt+R and Alt+L switch case,
 * whole words, regular expressions and the search in the selection, which is the selection of
 * several lines the bar opened with. In the replacement Enter replaces the current match and goes
 * to the next; Control+Alt+Enter replaces all, as one step of the undo history; a regular
 * expression's replacement takes {@code $1} and {@code ${name}} for its groups. A read-only field
 * has no replacement; a selection scope is read-only and has no replacement, find-in-selection
 * switch or go-to-line, and searches across all its read-only fields in order, including virtual
 * list rows not built yet.
 *
 * <p>The matches are counted up to {@link #LIMIT}. A text of up to {@link #SYNC_CHARS}
 * characters is searched whole whenever the text or the query changes, which takes a few
 * milliseconds at most; a longer one marks the matches of the lines in view at once and is
 * searched on another thread ({@link App#background}), whose answer the next search cancels; a
 * jump to a match far away does the same when it takes longer than a frame. A search that runs
 * past its time, such as a regular expression that backtracks without end, stops and says so.
 *
 * <p>In a tree dump the bar is {@code role=search} with the key {@code find}, its fields {@code
 * label="Find"}, {@code "Replace"} and {@code "Go to line"}, its switches {@code role=button}
 * with {@code checked} or {@code checked=false}, and the count a text with the key {@code count},
 * such as {@code "3 of 17"} or {@code "No results"}.
 */
public final class Find {

  private Find() {}

  /** The most matches counted; more show as {@code 10000+}. */
  public static final int LIMIT = 10_000;

  /** The longest text searched whole on the app's thread. */
  public static final int SYNC_CHARS = 200_000;

  // How long a search on the app's thread may take, and one on another thread.
  static final long SYNC_MILLIS = 10;
  static final long BACKGROUND_MILLIS = 2_000;

  enum Mode {
    FIND,
    REPLACE,
    LINE
  }

  /** What a search looks for in which part of the text. */
  private record For(Search.Query query, int from, int to) {}

  /**
   * What the app keeps of a field's find bar, as a {@link Menu.State}: whether it is open and with
   * what, the switches, and the last search's matches. Keep one per field, such as one per tab;
   * a code editor's state holds one.
   */
  public static final class State {
    boolean open;
    Mode mode = Mode.FIND;
    String query = "";
    String replacement = "";
    String line = "";
    boolean matchCase;
    boolean wholeWord;
    boolean regex;
    boolean inSelection;
    // The selection of several lines the bar opened with, which a search in the selection takes.
    int scopeFrom;
    int scopeTo;
    int caret;
    int anchor;
    int origin; // where typing in the field searches from
    int opened; // counts the openings, so that each takes the focus
    boolean selectQuery; // the field selects its text once it is there
    String error; // the pattern's, or a replacement's
    String replaceError;
    // The last search: for which text and query, and its matches.
    String searched;
    For searchedFor;
    Search.Matches matches = Search.Matches.NONE;
    boolean slow; // the lines in view took too long to search: no more marks for this query
    Work work;
    For working;
    String workingText;
    Work jump;
    String jumpText; // the text the jump searches
    // The elements of the last build, for the handlers.
    El field;
    El findField;
    El replaceField; // while the replacement's row shows: Tab goes there from the field to find
    El lineField;
    App<?> app;
    Predicate<Key> commands = k -> false;
    Texts texts = Texts.ENGLISH;

    /** Whether the bar shows. */
    public boolean isOpen() {
      return open;
    }

    /** The text to find. */
    public String query() {
      return query;
    }

    /** The text that replaces a match. */
    public String replacement() {
      return replacement;
    }

    public boolean matchCase() {
      return matchCase;
    }

    public boolean wholeWord() {
      return wholeWord;
    }

    public boolean regex() {
      return regex;
    }

    /** Whether the search keeps to the selection the bar opened with. */
    public boolean inSelection() {
      return !isScope() && inSelection && scopeTo > scopeFrom;
    }

    /** Closes the bar; the field or scope keeps its selection. */
    public void close() {
      open = false;
      cancel();
    }

    private boolean isScope() {
      return field != null && field.isSelectionScope();
    }

    private String text() {
      return isScope() ? app.scopeText(field) : field.text().string();
    }

    void cancel() {
      if (work != null) {
        work.cancel();
        work = null;
        working = null;
        workingText = null;
      }
      if (jump != null) {
        jump.cancel();
        jump = null;
        jumpText = null;
      }
    }

    private Search.Query search() {
      String words = field == null || isScope() ? "" : field.text().wordCharacters();
      return new Search.Query(query, matchCase, wholeWord, regex, words);
    }

    private For target(int length) {
      boolean scoped = inSelection();
      int from = scoped ? Math.min(scopeFrom, length) : 0;
      int to = scoped ? Math.min(scopeTo, length) : length;
      return new For(search(), from, to);
    }

    /** The pattern, or null with {@link #error} set; null too for an empty query. */
    private Pattern pattern() {
      error = null;
      try {
        return search().compile();
      } catch (PatternSyntaxException e) {
        error = patternError(texts, e);
        return null;
      }
    }

    private boolean fresh(String text) {
      return text.equals(searched) && target(text.length()).equals(searchedFor);
    }
  }

  /**
   * Where {@code needle} next occurs in {@code text}, matching case: the first occurrence that
   * starts at or after {@code from}, or else the first one before it, as {@code {start, end}}.
   * This is how a code editor's Control+D goes from one occurrence of the selection to the next.
   *
   * @param wholeWord whether only an occurrence that is a whole word counts, a word being letters,
   *     digits, {@code _} and {@code wordCharacters}
   * @param wordCharacters the characters the field's language adds to a word, such as {@code "$"}
   * @return the occurrence, or an empty array if there is none or the search took longer than a
   *     frame
   */
  public static int[] next(
      String text, String needle, boolean wholeWord, String wordCharacters, int from) {
    if (needle.isEmpty()) {
      return new int[0];
    }
    Search.Query query = new Search.Query(needle, true, wholeWord, false, wordCharacters);
    try {
      int[] next =
          Search.next(query.compile(), text, 0, text.length(), from, Search.deadline(SYNC_MILLIS));
      return next == null ? new int[0] : next;
    } catch (Search.Stopped _) {
      return new int[0];
    }
  }

  /**
   * Gives a multi-line field or a {@link El#selectionScope selection scope} its find bar: the bar
   * while {@code state} is open, the marks of the matches and the keys that open it and go from
   * match to match. The element's own handlers go on as before: its keys, decorator and (for a
   * field) selection handler run after the bar's.
   *
   * @param app the app the field or scope is in, which reads scope text, selects and replaces
   *     ({@link App#scopeText}, {@link App#select}, {@link App#edit})
   * @param field a {@link El#multiline} field or an element marked {@link El#selectionScope()}
   * @return the field or scope
   */
  public static El find(App<?> app, El field, State state) {
    return find(app, field, state, k -> false);
  }

  /**
   * Gives a multi-line field or {@link El#selectionScope selection scope} with commands of its own
   * its find bar, as {@link #find(App, El, State)} does. A key with a modifier that {@code
   * commands} takes, such as a code editor's Control+D, does nothing while the focus is in the bar,
   * so that it does not act on the text out of sight.
   *
   * @param commands whether a key is one of the field's or scope's commands
   */
  public static El find(App<?> app, El field, State state, Predicate<Key> commands) {
    Theme theme = Theme.CURRENT.get();
    boolean scope = field.isSelectionScope();
    state.commands = commands;
    // A view may make the field read-only after this call, as after a code editor's: the last
    // build's field says so too, and the bar only shows after a handler that ran on that build.
    boolean editable = !scope
        && field.onEdit() != null
        && !field.isReadOnly()
        && (state.field == null || !state.field.isReadOnly());
    if (!editable && state.mode == Mode.REPLACE) {
      state.mode = Mode.FIND;
    }
    if (scope && state.mode == Mode.LINE) {
      state.mode = Mode.FIND;
    }
    state.field = field;
    state.app = app;
    state.texts = Texts.CURRENT.get();
    String text = scope && !state.open
        ? null
        : scope ? app.scopeText(field) : field.text().string();
    if (text != null) {
      state.caret = Math.min(state.caret, text.length());
      state.anchor = Math.min(state.anchor, text.length());
    }
    if (!scope) {
      El.SelectHandler ownSelect = field.onSelect();
      field.onSelect((caret, anchor) -> {
        state.caret = caret;
        state.anchor = anchor;
        if (ownSelect != null) {
          ownSelect.select(caret, anchor);
        }
      });
    }
    if (state.jump != null && (text == null || !text.equals(state.jumpText))) {
      state.jump.cancel(); // its offsets are those of a text that is gone
      state.jump = null;
      state.jumpText = null;
    }
    field.onKeyFirst(k -> fieldKey(state, k));
    Pattern pattern = state.open && state.mode != Mode.LINE ? state.pattern() : null;
    if (pattern != null) {
      search(app, state, pattern, text);
      if (!scope && state.fresh(text) && state.matches.count() > 0) {
        Search.Matches found = state.matches;
        int count = found.count();
        int[] starts = found.starts();
        int[] ends = found.ends();
        int color = theme.accent().edge();
        List<Scrollbars.Mark> ticks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
          ticks.add(new Scrollbars.Mark(starts[i], ends[i], color));
        }
        field.scrollMarks(ticks);
      }
    } else if (!state.open) {
      state.cancel();
    }
    Decorator decorator = field.decorator();
    if (state.open && state.mode != Mode.LINE) {
      field.decorate(marks(theme, state, pattern, text, decorator));
    }
    state.findField = null;
    state.lineField = null;
    if (!state.open) {
      return field;
    }
    // A match comes into view below the bar, also in the first line, a step xs clear of it.
    // shortcut: the whole width counts as covered, so a match left of the bar scrolls down too;
    // ask the bar's left edge when that should stay put.
    // shortcut: in a scope a match in its first lines, which cannot scroll above the top, can
    // stay under the bar; scroll a scope's list above its first row when that matters.
    field.coveredTop(barHeight(theme, state) + 2 * theme.space().xs());
    return field.overlay(bar(theme, app, state, text, editable, scope));
  }

  /** Opens the bar to find, with the selected text or the word at the caret. */
  public static void open(State state) {
    open(state, Mode.FIND);
  }

  /** Opens the bar with the replacement, or to find in a field that takes no edits. */
  public static void openReplace(State state) {
    open(state, Mode.REPLACE);
  }

  /** Opens the bar to go to a line. */
  public static void goToLine(State state) {
    open(state, Mode.LINE);
  }

  /**
   * Goes to a line, and column, of the field, as the bar's line field does with {@code 42} or
   * {@code 42:7}, from a command palette, say; whether {@code line} is one. The field is the one
   * the bar was last built for.
   */
  public static boolean goToLine(State state, String line) {
    if (state.field == null || state.isScope() || parseLine(line) == null) {
      return false;
    }
    state.line = line;
    jumpToLine(state);
    return true;
  }

  /** Goes to the next match after the selection, round to the first; the last query if closed. */
  public static void next(State state) {
    go(state, 1);
  }

  /** Goes to the match before the selection, round to the last. */
  public static void previous(State state) {
    go(state, -1);
  }

  private static void open(State state, Mode mode) {
    El field = state.field;
    if (field == null) {
      return;
    }
    boolean scope = state.isScope();
    if (scope && mode == Mode.LINE) {
      return;
    }
    String text = state.text();
    int caret = Math.min(state.caret, text.length());
    int anchor = Math.min(state.anchor, text.length());
    int s = Math.min(caret, anchor);
    int e = Math.max(caret, anchor);
    boolean wasOpen = state.open;
    int origin = scope ? (s < e ? s : 0) : s;
    if (mode != Mode.LINE) {
      if (scope) {
        state.scopeFrom = state.scopeTo = 0;
        state.inSelection = false;
      } else {
        int feed = text.indexOf('\n', s);
        boolean oneLine = feed < 0 || feed >= e;
        // A selection in a line is the text to find, unless it is a match the bar found; the word
        // at
        // the caret is, when the bar opens.
        boolean found = wasOpen && state.fresh(text) && state.matches.indexOf(s, e) >= 0;
        String chosen = null;
        if (s < e && oneLine && !found) {
          chosen = text.substring(s, e);
        } else if (s == e && !wasOpen) {
          int from = CodeWords.start(text, s, field.text().wordCharacters());
          int to = CodeWords.end(text, s, field.text().wordCharacters());
          chosen = from < to ? text.substring(from, to) : null;
          origin = from;
        }
        if (chosen != null) {
          state.query = state.regex ? quote(chosen) : chosen;
        }
        if (s < e && !oneLine) { // the lines a search in the selection keeps to
          state.scopeFrom = s;
          state.scopeTo = e;
        } else if (!wasOpen) {
          state.scopeFrom = state.scopeTo = 0;
          state.inSelection = false;
        }
      }
    }
    state.origin = origin;
    state.mode = mode == Mode.REPLACE && (scope || field.onEdit() == null || field.isReadOnly())
        ? Mode.FIND
        : mode;
    state.open = true;
    state.opened++;
    state.slow = false;
    state.selectQuery = true;
    El shown = mode == Mode.LINE ? state.lineField : state.findField;
    if (wasOpen && shown != null) { // there already: it selects its text now
      state.selectQuery = false;
      String value = mode == Mode.LINE ? state.line : state.query;
      state.app.select(shown, value.length(), 0);
    }
    if (mode != Mode.LINE && !state.query.isEmpty()) {
      go(state, 0);
    }
  }

  /** The keys of the field or scope the bar belongs to; whether one was the bar's. */
  private static boolean fieldKey(State state, Key k) {
    int mods = k.modifiers();
    switch (k.name()) {
      case "F" -> {
        if (mods != Key.CONTROL) {
          return false;
        }
        open(state, Mode.FIND);
      }
      case "H" -> {
        if (mods != Key.CONTROL) {
          return false;
        }
        open(state, Mode.REPLACE);
      }
      case "G" -> {
        if (mods != Key.CONTROL || state.isScope()) {
          return false;
        }
        open(state, Mode.LINE);
      }
      case "F3" -> {
        if (mods != 0 && mods != Key.SHIFT) {
          return false;
        }
        go(state, mods == Key.SHIFT ? -1 : 1);
      }
      case "Escape" -> {
        if (mods != 0 || !state.open) {
          return false;
        }
        state.close();
      }
      default -> {
        return false;
      }
    }
    return true;
  }

  /** The keys anywhere in the bar; whether one was the bar's. */
  private static boolean barKey(App<?> app, State state, Key k) {
    int mods = k.modifiers();
    String name = k.name();
    if (mods == Key.ALT && state.mode != Mode.LINE) {
      switch (name) {
        case "C" -> state.matchCase = !state.matchCase;
        case "W" -> state.wholeWord = !state.wholeWord;
        case "R" -> state.regex = !state.regex;
        case "L" -> {
          if (state.isScope()) {
            return false;
          }
          if (state.scopeTo <= state.scopeFrom) {
            return true;
          }
          state.inSelection = !state.inSelection;
        }
        default -> {
          return state.commands.test(k); // such as the editor's Alt+Up, which moves lines
        }
      }
      changed(state);
      return true;
    }
    if ((name.equals("Enter") || name.equals("NumpadEnter"))
        && mods == (Key.CONTROL | Key.ALT)
        && state.mode == Mode.REPLACE) {
      replaceAll(app, state);
      return true;
    }
    if (fieldKey(state, k)) {
      return true;
    }
    // The field's own commands act on the field, not on the bar's fields.
    return mods != 0 && state.commands.test(k);
  }

  /** The query or a switch changed: the search starts again from where the bar opened. */
  private static void changed(State state) {
    state.slow = false;
    state.cancel();
    if (!state.query.isEmpty() && state.pattern() != null) {
      go(state, 0);
    }
  }

  /** Searches the text whole if it is short, else starts a search on another thread. */
  private static void search(App<?> app, State state, Pattern pattern, String text) {
    if (state.fresh(text)) {
      return;
    }
    For target = state.target(text.length());
    if (text.length() <= SYNC_CHARS) {
      state.cancel();
      state.matches = Search.scan(
          pattern, text, target.from(), target.to(), LIMIT, Search.deadline(SYNC_MILLIS));
      state.searched = text;
      state.searchedFor = target;
      return;
    }
    if (state.work != null && target.equals(state.working) && text.equals(state.workingText)) {
      return; // on its way
    }
    if (state.work != null) {
      state.work.cancel();
    }
    state.working = target;
    state.workingText = text;
    state.work = app.background(
        "Find",
        () -> Search.scan(
            pattern, text, target.from(), target.to(), LIMIT, Search.deadline(BACKGROUND_MILLIS)),
        matches -> {
          state.work = null;
          state.working = null;
          state.workingText = null;
          state.matches = matches;
          state.searched = text;
          state.searchedFor = target;
        },
        failure -> {
          state.work = null;
          state.working = null;
          state.workingText = null;
        });
  }

  /**
   * Goes to a match: the next after the selection (1), the one before it (-1) or the first from
   * where the bar opened (0), round at the ends.
   */
  private static void go(State state, int direction) {
    El field = state.field;
    if (field == null || state.query.isEmpty()) {
      return;
    }
    Pattern pattern = state.pattern();
    if (pattern == null) {
      return;
    }
    String text = state.text();
    For target = state.target(text.length());
    int caret = Math.min(state.caret, text.length());
    int anchor = Math.min(state.anchor, text.length());
    int s = Math.min(caret, anchor);
    int e = Math.max(caret, anchor);
    int from = direction == 0 ? Math.min(state.origin, text.length()) : direction > 0 ? e : s;
    int[] hit = null;
    int floor = target.from();
    if (state.fresh(text)) {
      Search.Matches m = state.matches;
      if (direction >= 0) {
        int i = m.firstStartingFrom(from);
        hit = i < m.count() ? match(m, i) : m.complete() && m.count() > 0 ? match(m, 0) : null;
      } else {
        int i = m.firstStartingFrom(from) - 1;
        hit = i >= 0 ? match(m, i) : m.complete() && m.count() > 0 ? match(m, m.count() - 1) : null;
        floor = m.count() > 0 ? m.starts()[m.count() - 1] : floor;
      }
      if (hit == null && m.complete()) {
        return; // no match
      }
    }
    if (hit == null) {
      int start = floor;
      try {
        hit = direct(pattern, text, target, direction, from, start, SYNC_MILLIS);
      } catch (Search.Stopped _) {
        // Too long for a frame: the rest on another thread, whose answer selects.
        if (state.jump != null) {
          state.jump.cancel();
        }
        state.jumpText = text;
        state.jump = state.app.background(
            "Find the next match",
            () -> direct(pattern, text, target, direction, from, start, BACKGROUND_MILLIS),
            found -> {
              state.jump = null;
              state.jumpText = null;
              if (found != null
                  && state.field != null
                  && state.text().equals(text)
                  && target.equals(state.target(text.length()))) { // same query and words
                select(state, found);
              }
            },
            failure -> {
              state.jump = null;
              state.jumpText = null;
            });
        return;
      }
    }
    if (hit != null) {
      select(state, hit);
    }
  }

  private static int[] direct(
      Pattern pattern, String text, For target, int direction, int from, int floor, long millis) {
    long deadline = Search.deadline(millis);
    return direction >= 0
        ? Search.next(pattern, text, target.from(), target.to(), from, deadline)
        : Search.previous(pattern, text, target.from(), target.to(), from, floor, deadline);
  }

  private static int[] match(Search.Matches m, int i) {
    return new int[] {m.starts()[i], m.ends()[i]};
  }

  private static void select(State state, int[] match) {
    state.app.select(state.field, match[1], match[0]);
    state.caret = match[1];
    state.anchor = match[0];
  }

  /** Replaces the selection if it is a match and goes to the next match. */
  static void replace(App<?> app, State state) {
    El field = state.field;
    Pattern pattern = state.pattern();
    if (field == null || state.isScope() || pattern == null) {
      return;
    }
    String text = field.text().string();
    int s = Math.min(state.caret, state.anchor);
    int e = Math.max(state.caret, state.anchor);
    state.replaceError = null;
    For target = state.target(text.length());
    String with;
    try {
      // Only a match inside the part searched: never text outside a search in the selection.
      with = s < target.from()
          ? null
          : Texts.CURRENT.with(
              state.texts,
              () -> Search.replacement( // its messages: the bar's
                  state.search(),
                  pattern,
                  text,
                  s,
                  e,
                  target.to(),
                  state.replacement,
                  Search.deadline(SYNC_MILLIS)));
    } catch (Search.Stopped _) {
      state.replaceError = state.texts.replacingTookTooLong();
      return;
    } catch (IllegalArgumentException | IndexOutOfBoundsException wrong) {
      state.replaceError = wrong.getMessage();
      return;
    }
    if (with != null) {
      int delta = with.length() - (e - s);
      int[] next;
      try {
        next =
            Search.next(pattern, text, target.from(), target.to(), e, Search.deadline(SYNC_MILLIS));
      } catch (Search.Stopped _) {
        next = null;
      }
      app.edit(field, s, e, with, s + with.length());
      if (state.inSelection()) {
        state.scopeTo += delta;
      }
      if (next != null && !(next[0] == s && next[1] == e)) {
        int shift = next[0] >= e ? delta : 0;
        select(state, new int[] {next[0] + shift, next[1] + shift});
      } // else onSelect tells the caret once the app took the edit
      return;
    }
    go(state, 1);
  }

  /** Replaces every match, as one step of the history. */
  static void replaceAll(App<?> app, State state) {
    El field = state.field;
    Pattern pattern = state.pattern();
    if (field == null || state.isScope() || pattern == null) {
      return;
    }
    String text = field.text().string();
    For target = state.target(text.length());
    List<TextEdit> edits;
    state.replaceError = null;
    try {
      edits = Texts.CURRENT.with(
          state.texts,
          () -> Search.replaceAll(
              state.search(),
              pattern,
              text,
              target.from(),
              target.to(),
              state.replacement,
              Search.deadline(BACKGROUND_MILLIS)));
    } catch (Search.Stopped _) {
      state.replaceError = state.texts.replacingTookTooLong();
      return;
    } catch (IllegalArgumentException | IndexOutOfBoundsException wrong) {
      state.replaceError = wrong.getMessage();
      return;
    }
    if (edits.isEmpty()) {
      return;
    }
    int delta = 0;
    for (TextEdit e : edits) {
      delta += e.text().length() - (e.to() - e.from());
    }
    int end = edits.getLast().to() + delta;
    app.edit(field, edits, end);
    if (state.inSelection()) {
      state.scopeTo += delta;
    }
  }

  /** Goes to the line, and column, the line field holds, and closes the bar. */
  private static void jumpToLine(State state) {
    El field = state.field;
    int[] at = field == null || state.isScope() ? null : parseLine(state.line);
    if (at == null) {
      return;
    }
    String text = field.text().string();
    int start = 0;
    for (int line = 1; line < at[0]; line++) {
      int feed = text.indexOf('\n', start);
      if (feed < 0) {
        break;
      }
      start = feed + 1;
    }
    int end = text.indexOf('\n', start);
    end = end < 0 ? text.length() : end;
    int position = Math.min(start + Math.max(at[1], 1) - 1, end);
    state.app.select(field, position, position);
    state.caret = state.anchor = position;
    state.close();
  }

  /** {line, column}, both from 1, of {@code 42} or {@code 42:7}; null for anything else. */
  static int[] parseLine(String text) {
    java.util.regex.Matcher m =
        Pattern.compile("\\s*(\\d{1,9})\\s*(?:[:,]\\s*(\\d{1,9})\\s*)?").matcher(text);
    if (!m.matches()) {
      return null;
    }
    int line = Integer.parseInt(m.group(1));
    int column = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
    return line < 1 ? null : new int[] {line, column};
  }

  /** The matches in a line: the current one stronger, and the selection searched in. */
  private static Decorator marks(
      Theme theme, State state, Pattern pattern, String text, Decorator own) {
    Theme.Code code = theme.code();
    int edge = theme.accent().edge();
    boolean fresh = pattern != null && state.fresh(text);
    For target = state.target(text.length());
    int s = Math.min(state.caret, state.anchor);
    int e = Math.max(state.caret, state.anchor);
    return (line, start, string, out) -> {
      int end = start + string.length();
      if (state.inSelection() && target.to() > start && target.from() <= end) {
        out.background(
            Math.max(target.from(), start) - start,
            Math.min(target.to(), end + 1) - start,
            code.line());
      }
      if (pattern != null) {
        Search.Matches m = state.matches;
        if (fresh) {
          for (int i = m.firstEndingAfter(start); i < m.count() && m.starts()[i] <= end; i++) {
            mark(out, start, end, m.starts()[i], m.ends()[i], s, e, code, edge);
          }
        }
        boolean beyond = fresh && m.more() && (m.count() == 0 || start >= m.ends()[m.count() - 1]);
        if ((!fresh || beyond) && !state.slow) {
          int from = Math.max(start, target.from());
          int to = Math.min(end, target.to());
          if (from < to) {
            Search.Matches here = Search.scan(pattern, text, from, to, LIMIT, Search.deadline(1));
            state.slow |= here.stopped();
            for (int i = 0; i < here.count(); i++) {
              mark(out, start, end, here.starts()[i], here.ends()[i], s, e, code, edge);
            }
          }
        }
      }
      if (own != null) {
        own.line(line, start, string, out);
      }
    };
  }

  /** A match's background, the current one's stronger and underlined in the accent's edge. */
  private static void mark(
      com.rethinkj.ui.Spans out,
      int start,
      int end,
      int from,
      int to,
      int s,
      int e,
      Theme.Code code,
      int edge) {
    int a = Math.max(from, start) - start;
    int b = Math.min(to, end + 1) - start;
    if (b <= a) {
      return;
    }
    boolean current = from == s && to == e;
    out.background(a, b, current ? code.current() : code.match());
    if (current) {
      out.underline(a, Math.min(b, end - start), edge);
    }
  }

  /** Formats a regular-expression syntax error in the view's texts. */
  static String patternError(Texts texts, PatternSyntaxException e) {
    return e.getIndex() >= 0
        ? texts.patternError(e.getDescription(), e.getIndex() + 1)
        : e.getDescription();
  }

  /** What the count says, shared with {@link GridFind}. */
  static String formatCount(
      Texts texts,
      boolean empty,
      String error,
      boolean searching,
      boolean stopped,
      int count,
      boolean complete,
      int currentIndex) {
    if (empty || error != null) {
      return "";
    }
    if (searching) {
      return texts.searching();
    }
    if (stopped && count == 0) {
      return texts.tooSlow();
    }
    if (count == 0 && complete) {
      return texts.noResults();
    }
    String total = complete ? String.valueOf(count) : count + "+";
    if (currentIndex >= 0) {
      return texts.match(currentIndex + 1, total);
    }
    return texts.results(total, count == 1 && complete);
  }

  /** What the count says. */
  static String count(State state, String text) {
    Search.Matches m = state.matches;
    int current =
        m.indexOf(Math.min(state.caret, state.anchor), Math.max(state.caret, state.anchor));
    return formatCount(
        state.texts,
        state.query.isEmpty(),
        state.error,
        !state.fresh(text),
        m.stopped(),
        m.count(),
        m.complete(),
        current);
  }

  /** The popup surface holding a find bar's rows. */
  static El panel(Theme theme) {
    return Popup.surface(theme, col())
        .padding(theme.space().xs())
        .gap(theme.space().xs());
  }

  /** The floating find bar container, positioned at the top right clear of the vertical scrollbar. */
  static El bar(Theme theme, Texts texts, float topInset, Predicate<Key> onKey, El panel) {
    Scrollbars bars = theme.scrollbars();
    return row()
        .key("find")
        .role("search")
        .label(texts.find())
        .margin(0, bars.strip(), 0, 0)
        .motion(theme.motion().fade())
        .side(Side.TOP, Style.Align.END, -topInset)
        .onKey(onKey)
        .child(panel);
  }

  /** The tabular-numeral match count label, spaced a step m after the query field. */
  static El countLabel(Theme theme, String text) {
    El count = label(Label.Variant.SMALL, text).key("count").shrink(0).fontFeatures("+tnum");
    count.style().minWidth = Len.px(2 * theme.size().control());
    // The rows' gap is xs; the field stands a step m apart from what follows it.
    float apart = theme.space().m() - theme.space().xs();
    count.margin(0, 0, 0, apart);
    return count;
  }

  /** The bar: the field to find with its switches, the count and the buttons; the replacement. */
  private static El bar(
      Theme theme, App<?> app, State state, String text, boolean editable, boolean scope) {
    Texts texts = state.texts;
    El panel = panel(theme);
    El bar = bar(
        theme,
        texts,
        barHeight(theme, state) + theme.space().xs(),
        k -> barKey(app, state, k),
        panel);
    if (state.mode == Mode.LINE) {
      panel.child(lineRow(theme, state, text));
      return bar;
    }
    float lead = theme.size().control();
    El toggle = editable
        ? tool(
                theme,
                state.mode == Mode.REPLACE ? Symbol.CHEVRON_DOWN : Symbol.CHEVRON_RIGHT,
                texts.toggleReplace(),
                texts.keys("Ctrl", "H"),
                null,
                () -> {
                  state.mode = state.mode == Mode.REPLACE ? Mode.FIND : Mode.REPLACE;
                })
            .key("toggle-replace")
        : null;
    El query = queryField(theme, state);
    El count = countLabel(theme, count(state, text));
    float apart = theme.space().m() - theme.space().xs();
    boolean searchable = !state.query.isEmpty() && state.error == null;
    El findRow = row().gap(theme.space().xs()).align(Style.Align.CENTER);
    if (toggle != null) {
      findRow.child(toggle);
    }
    findRow.child(query).child(count);
    if (!scope) {
      findRow.child(tool(
              theme,
              Symbol.SELECT_ALL_ON,
              texts.findInSelection(),
              texts.keys("Alt", "L"),
              state.inSelection(),
              state.scopeTo > state.scopeFrom
                  ? () -> {
                    state.inSelection = !state.inSelection;
                    changed(state);
                  }
                  : null)
          .key("in-selection"));
    }
    findRow
        .child(tool(
                theme,
                Symbol.ARROW_UP,
                texts.previousMatch(),
                texts.keys("Shift", "Enter"),
                null,
                searchable ? () -> go(state, -1) : null)
            .key("previous"))
        .child(tool(
                theme,
                Symbol.ARROW_DOWN,
                texts.nextMatch(),
                texts.keys("Enter"),
                null,
                searchable ? () -> go(state, 1) : null)
            .key("next"))
        .child(tool(theme, Symbol.DISMISS, texts.close(), texts.keys("Escape"), null, state::close)
            .key("close"));
    panel.child(findRow);
    state.replaceField = null;
    if (state.mode == Mode.REPLACE) {
      El replace = TextField.textField(state.replacement, texts.replace(), v -> {
            state.replacement = v;
            state.replaceError = null;
          })
          .key("replace")
          .label(texts.replace())
          .onKey(k -> {
            if (k.name().equals("Tab") && k.modifiers() == Key.SHIFT && state.findField != null) {
              app.focus(state.findField); // back, past the switches, as it came
              return true;
            }
            boolean enter =
                (k.name().equals("Enter") || k.name().equals("NumpadEnter")) && k.modifiers() == 0;
            if (enter) {
              replace(app, state);
            }
            return enter;
          });
      state.replaceField = replace;
      if (state.replaceError != null) {
        replace = Tooltip.tooltip(
            TextField.invalid(replace),
            state.replaceError,
            Tooltip.options().kind(Callout.Kind.DANGER));
      }
      panel.child(row()
          .gap(theme.space().xs())
          .align(Style.Align.CENTER)
          .padding(0, 0, 0, lead + theme.space().xs())
          .child(replace)
          .child(Button.button(
                  Button.Variant.SECONDARY,
                  texts.replace(),
                  searchable ? () -> replace(app, state) : null)
              .key("replace-one")
              .margin(0, 0, 0, apart))
          .child(Button.button(
                  Button.Variant.SECONDARY,
                  texts.replaceAll(),
                  searchable ? () -> replaceAll(app, state) : null)
              .key("replace-all")));
    }
    return bar;
  }

  /** The height of a find bar with {@code rows} rows, to lay it over its host's top. */
  static float barHeight(Theme theme, int rows) {
    return rows * theme.size().control()
        + (rows - 1) * theme.space().xs()
        + 2 * (theme.space().xs() + theme.size().stroke());
  }

  /** The height of the bar, to lay it over the field's top. */
  static float barHeight(Theme theme, State state) {
    return barHeight(theme, state.mode == Mode.REPLACE ? 2 : 1);
  }

  /**
   * Embeds the case, optional whole-word, and regular-expression switches inside {@code field} and
   * marks it invalid with a danger tooltip when {@code error} is non-null.
   */
  static El queryField(
      Theme theme,
      Texts texts,
      El field,
      String error,
      boolean matchCase,
      Runnable onMatchCase,
      Boolean wholeWord,
      Runnable onWholeWord,
      boolean regex,
      Runnable onRegex) {
    float inset = theme.space().xs();
    float size = FieldActions.size(theme);
    if (error != null) {
      TextField.invalid(field);
    }
    El switches = row()
        .gap(0)
        .child(tool(
                theme,
                Symbol.TEXT_CASE_TITLE,
                texts.matchCase(),
                texts.keys("Alt", "C"),
                matchCase,
                onMatchCase)
            .key("match-case")
            .size(size, size));
    int count = 2;
    if (onWholeWord != null) {
      switches.child(tool(
              theme,
              Symbol.TEXT_WHOLE_WORD,
              texts.wholeWord(),
              texts.keys("Alt", "W"),
              wholeWord,
              onWholeWord)
          .key("whole-word")
          .size(size, size));
      count = 3;
    }
    switches.child(tool(
            theme,
            Symbol.TEXT_PERIOD_ASTERISK,
            texts.regularExpression(),
            texts.keys("Alt", "R"),
            regex,
            onRegex)
        .key("regex")
        .size(size, size));
    El box = FieldActions.embed(theme, field, switches, count * size + 2 * inset)
        .whenFocusWithin(e -> {
          int c = error != null ? theme.status().danger() : theme.accent().color();
          TextField.edge(theme, field, c, c);
        });
    return error != null
        ? Tooltip.tooltip(box, error, Tooltip.options().kind(Callout.Kind.DANGER))
        : box;
  }

  /** The field to find, with the switches of case, words and regular expressions inside. */
  private static El queryField(Theme theme, State state) {
    Texts texts = state.texts;
    El field = TextField.textField(state.query, texts.find(), v -> {
          state.query = v;
          changed(state);
        })
        .key("find-field")
        .label(texts.find())
        .autofocus(state.opened)
        .onSelect((caret, anchor) -> {
          if (state.selectQuery && state.findField != null) {
            state.selectQuery = false;
            state.app.select(state.findField, state.query.length(), 0);
          }
        })
        .onKey(k -> {
          // With a replacement to type, Tab goes on to it, as in editors; the switches and
          // buttons between them stay for the pointer and their keys, and Tab from there.
          if (k.name().equals("Tab") && k.modifiers() == 0 && state.replaceField != null) {
            state.app.focus(state.replaceField);
            return true;
          }
          boolean enter = (k.name().equals("Enter") || k.name().equals("NumpadEnter"))
              && (k.modifiers() == 0 || k.modifiers() == Key.SHIFT);
          if (enter) {
            go(state, k.modifiers() == Key.SHIFT ? -1 : 1);
          }
          return enter;
        });
    state.findField = field;
    return queryField(
        theme,
        texts,
        field,
        state.error,
        state.matchCase,
        () -> {
          state.matchCase = !state.matchCase;
          changed(state);
        },
        state.wholeWord,
        () -> {
          state.wholeWord = !state.wholeWord;
          changed(state);
        },
        state.regex,
        () -> {
          state.regex = !state.regex;
          changed(state);
        });
  }

  /** The field of the line to go to and the lines there are. */
  private static El lineRow(Theme theme, State state, String text) {
    Texts texts = state.texts;
    El field = TextField.textField(state.line, texts.lineOrColumn(), v -> state.line = v)
        .key("line-field")
        .label(texts.goToLine())
        .autofocus(state.opened)
        .onSelect((caret, anchor) -> {
          if (state.selectQuery && state.lineField != null) {
            state.selectQuery = false;
            state.app.select(state.lineField, state.line.length(), 0);
          }
        })
        .onKey(k -> {
          boolean enter =
              (k.name().equals("Enter") || k.name().equals("NumpadEnter")) && k.modifiers() == 0;
          if (enter) {
            jumpToLine(state);
          }
          return enter;
        });
    state.lineField = field;
    if (!state.line.isBlank() && parseLine(state.line) == null) {
      TextField.invalid(field);
    }
    int lines = 1;
    for (int i = text.indexOf('\n'); i >= 0; i = text.indexOf('\n', i + 1)) {
      lines++;
    }
    return row()
        .gap(theme.space().xs())
        .align(Style.Align.CENTER)
        .child(field)
        .child(label(Label.Variant.MUTED, texts.range("1", Integer.toString(lines)))
            .key("count")
            .shrink(0)
            .fontFeatures("+tnum")
            .margin(0, theme.space().s(), 0, theme.space().s()))
        .child(tool(theme, Symbol.DISMISS, texts.close(), texts.keys("Escape"), null, state::close)
            .key("close"));
  }

  /**
   * A small square button with an icon in the bar: a switch when {@code checked} is not null. It
   * leaves the focus where it is when pressed, and Tab reaches it; its tooltip names its keys.
   */
  static El tool(
      Theme theme, Symbol symbol, String label, String keys, Boolean checked, Runnable onPress) {
    El button = FieldActions.button(theme, symbol, label, checked, onPress)
        .size(theme.size().control(), theme.size().control())
        .shrink(0);
    if (onPress == null) {
      return button;
    }
    button.focusable().focusRing(theme.accent().edge());
    return Tooltip.tooltip(button, label + " (" + keys + ")");
  }

  /** The text as a regular expression that finds it. */
  static String quote(String text) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) {
        out.append('\\');
      }
      out.append(c);
    }
    return out.toString();
  }
}
