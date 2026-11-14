package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Decorator;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Scrollbars;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.Work;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Find and replace in a multi-line text field, such as a {@link CodeEditor}, which has it from
 * the start, or a {@link TextField#textArea}: a bar along the top of the field with the text to
 * find, the number of matches, switches for case, whole words, regular expressions and the
 * selection, and below it the replacement; or a field for the line to go to.
 *
 * <pre>{@code
 * El area = TextField.textArea(t, s.text, "Notes", v -> s.text = v).key("notes");
 * Find.find(t, app, area, s.find);
 * }</pre>
 *
 * <p>Control+F opens it with the selected text, or the word at the caret, as the text to find,
 * Control+H with the replacement too, Control+G with the line to go to; Escape closes it and gives
 * the focus back to the field, with the last match selected. While it shows, every match in view
 * has {@code code().match()} behind it and the current one, the selection, {@code
 * code().current()} with a line of {@code accent().edge()} under it. Enter and F3 go to the next
 * match, Shift+Enter and Shift+F3 to the previous,
 * round at the ends, F3 also while the bar is closed. Alt+C, Alt+W, Alt+R and Alt+L switch case,
 * whole words, regular expressions and the search in the selection, which is the selection of
 * several lines the bar opened with. In the replacement Enter replaces the current match and goes
 * to the next; Control+Alt+Enter replaces all, as one step of the undo history; a regular
 * expression's replacement takes {@code $1} and {@code ${name}} for its groups. A read-only field
 * has no replacement.
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
   * {@link CodeEditor.State#find()} holds one.
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
      return inSelection && scopeTo > scopeFrom;
    }

    /** Closes the bar; the field keeps its selection. */
    public void close() {
      open = false;
      cancel();
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
      return new Search.Query(query, matchCase, wholeWord, regex);
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
        error = e.getDescription() + (e.getIndex() >= 0 ? " at " + (e.getIndex() + 1) : "");
        return null;
      }
    }

    private boolean fresh(String text) {
      return text.equals(searched) && target(text.length()).equals(searchedFor);
    }
  }

  /**
   * Gives a multi-line field its find bar: the bar while {@code state} is open, the marks of the
   * matches and the keys that open it and go from match to match. The field's own handlers go on
   * as before: its keys, decorator and selection handler run after the bar's.
   *
   * @param app the app the field is in, which selects and replaces ({@link App#select}, {@link
   *     App#edit})
   * @param field a {@link El#multiline} field
   * @return the field
   */
  public static El find(Theme t, App<?> app, El field, State state) {
    String text = field.text().string();
    // A view may make the field read-only after this call, as after CodeEditor's: the last build's
    // field says so too, and the bar only shows after a handler that ran on that build.
    boolean editable = field.onEdit() != null
        && !field.isReadOnly()
        && (state.field == null || !state.field.isReadOnly());
    if (!editable && state.mode == Mode.REPLACE) {
      state.mode = Mode.FIND;
    }
    state.field = field;
    state.app = app;
    state.caret = Math.min(state.caret, text.length());
    state.anchor = Math.min(state.anchor, text.length());
    El.SelectHandler ownSelect = field.onSelect();
    field.onSelect((caret, anchor) -> {
      state.caret = caret;
      state.anchor = anchor;
      if (ownSelect != null) {
        ownSelect.select(caret, anchor);
      }
    });
    if (state.jump != null && !text.equals(state.jumpText)) {
      state.jump.cancel(); // its offsets are those of a text that is gone
      state.jump = null;
      state.jumpText = null;
    }
    Predicate<Key> own = field.onKey();
    field.onKey(k -> fieldKey(app, state, k) || own != null && own.test(k));
    Pattern pattern = state.open && state.mode != Mode.LINE ? state.pattern() : null;
    if (pattern != null) {
      search(app, state, pattern, text);
    } else if (!state.open) {
      state.cancel();
    }
    Decorator decorator = field.decorator();
    if (state.open && state.mode != Mode.LINE) {
      field.decorate(marks(t, state, pattern, text, decorator));
    }
    state.findField = null;
    state.lineField = null;
    if (!state.open) {
      return field;
    }
    return field.overlay(bar(t, app, state, text, editable));
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
    if (state.field == null || parseLine(line) == null) {
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
    String text = field.text().string();
    int s = Math.min(state.caret, state.anchor);
    int e = Math.max(state.caret, state.anchor);
    boolean wasOpen = state.open;
    int origin = s;
    if (mode != Mode.LINE) {
      int feed = text.indexOf('\n', s);
      boolean oneLine = feed < 0 || feed >= e;
      // A selection in a line is the text to find, unless it is a match the bar found; the word at
      // the caret is, when the bar opens.
      boolean found = wasOpen && state.fresh(text) && state.matches.indexOf(s, e) >= 0;
      String chosen = null;
      if (s < e && oneLine && !found) {
        chosen = text.substring(s, e);
      } else if (s == e && !wasOpen) {
        int from = Completion.wordStart(text, s);
        int to = wordEnd(text, s);
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
    state.origin = origin;
    state.mode =
        mode == Mode.REPLACE && (field.onEdit() == null || field.isReadOnly()) ? Mode.FIND : mode;
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

  /** The keys of the field the bar belongs to; whether one was the bar's. */
  private static boolean fieldKey(App<?> app, State state, Key k) {
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
        if (mods != Key.CONTROL) {
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
          if (state.scopeTo <= state.scopeFrom) {
            return true;
          }
          state.inSelection = !state.inSelection;
        }
        default -> {
          return false;
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
    if (fieldKey(app, state, k)) {
      return true;
    }
    // The editor's own commands act on the editor, not on the bar's fields.
    return mods != 0 && CodeEditor.command(k) != null;
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
    String text = field.text().string();
    For target = state.target(text.length());
    int s = Math.min(state.caret, state.anchor);
    int e = Math.max(state.caret, state.anchor);
    int from = direction == 0 ? state.origin : direction > 0 ? e : s;
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
      } catch (Search.Stopped stopped) {
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
                  && state.field.text().string().equals(text)) {
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
    if (field == null || pattern == null) {
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
          : Search.replacement(
              state.search(),
              pattern,
              text,
              s,
              e,
              target.to(),
              state.replacement,
              Search.deadline(SYNC_MILLIS));
    } catch (Search.Stopped stopped) {
      state.replaceError = "Replacing took too long";
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
      } catch (Search.Stopped stopped) {
        next = null;
      }
      app.edit(field, s, e, with, s + with.length());
      if (state.inSelection()) {
        state.scopeTo += delta;
      }
      if (next != null && !(next[0] == s && next[1] == e)) {
        int shift = next[0] >= e ? delta : 0;
        select(state, new int[] {next[0] + shift, next[1] + shift});
      } else {
        state.caret = state.anchor = s + with.length();
      }
      return;
    }
    go(state, 1);
  }

  /** Replaces every match, as one step of the history. */
  static void replaceAll(App<?> app, State state) {
    El field = state.field;
    Pattern pattern = state.pattern();
    if (field == null || pattern == null) {
      return;
    }
    String text = field.text().string();
    For target = state.target(text.length());
    Search.Replaced replaced;
    state.replaceError = null;
    try {
      replaced = Search.replaceAll(
          state.search(),
          pattern,
          text,
          target.from(),
          target.to(),
          state.replacement,
          Search.deadline(BACKGROUND_MILLIS));
    } catch (Search.Stopped stopped) {
      state.replaceError = "Replacing took too long";
      return;
    } catch (IllegalArgumentException | IndexOutOfBoundsException wrong) {
      state.replaceError = wrong.getMessage();
      return;
    }
    if (replaced == null) {
      return;
    }
    int end = replaced.from() + replaced.text().length();
    app.edit(field, replaced.from(), replaced.to(), replaced.text(), end);
    if (state.inSelection()) {
      state.scopeTo += replaced.text().length() - (replaced.to() - replaced.from());
    }
    state.caret = state.anchor = end;
  }

  /** Goes to the line, and column, the line field holds, and closes the bar. */
  private static void jumpToLine(State state) {
    El field = state.field;
    int[] at = field == null ? null : parseLine(state.line);
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
      Theme t, State state, Pattern pattern, String text, Decorator own) {
    Theme.Code code = t.code();
    int edge = t.accent().edge();
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

  /** What the count says. */
  static String count(State state, String text) {
    if (state.query.isEmpty() || state.error != null) {
      return "";
    }
    if (!state.fresh(text)) {
      return "Searching…";
    }
    Search.Matches m = state.matches;
    if (m.stopped() && m.count() == 0) {
      return "Too slow";
    }
    if (m.count() == 0) {
      return "No results";
    }
    String total = m.complete() ? String.valueOf(m.count()) : m.count() + "+";
    int i = m.indexOf(Math.min(state.caret, state.anchor), Math.max(state.caret, state.anchor));
    if (i >= 0) {
      return (i + 1) + " of " + total;
    }
    return total + (m.count() == 1 && m.complete() ? " result" : " results");
  }

  /** The bar: the field to find with its switches, the count and the buttons; the replacement. */
  private static El bar(Theme t, App<?> app, State state, String text, boolean editable) {
    El panel = col()
        .padding(t.space().xs())
        .gap(t.space().xs())
        .radius(t.radius().medium())
        .fill(t.surface().raised())
        .border(t.size().stroke(), t.border().control())
        .shadow(t.elevation().popup());
    // Over the top right corner of the field, clear of its scrollbar: the root's padding.
    Scrollbars bars = t.scrollbars();
    El bar = row()
        .key("find")
        .role("search")
        .label("Find")
        .padding(0, bars.thickness() + 2 * bars.margin(), 0, 0)
        .motion(t.motion().fade())
        .side(Side.TOP, Style.Align.END, -(barHeight(t, state) + t.space().xs()))
        .onKey(k -> barKey(app, state, k))
        .child(panel);
    if (state.mode == Mode.LINE) {
      panel.child(lineRow(t, state, text));
      return bar;
    }
    float lead = t.size().control();
    El toggle = editable
        ? tool(
                t,
                state.mode == Mode.REPLACE ? Symbol.CHEVRON_DOWN : Symbol.CHEVRON_RIGHT,
                "Toggle replace",
                "Toggle replace (Ctrl+H)",
                null,
                () -> {
                  state.mode = state.mode == Mode.REPLACE ? Mode.FIND : Mode.REPLACE;
                })
            .key("toggle-replace")
        : null;
    El query = queryField(t, state);
    String said = count(state, text);
    El count = label(t, Label.Variant.MUTED, said).key("count").shrink(0).fontFeatures("+tnum");
    count.style().minWidth = Len.px(3 * t.size().control());
    boolean searchable = !state.query.isEmpty() && state.error == null;
    El findRow = row().gap(t.space().xs()).align(Style.Align.CENTER);
    if (toggle != null) {
      findRow.child(toggle);
    }
    findRow
        .child(query)
        .child(count)
        .child(tool(
                t,
                Symbol.SELECT_ALL_ON,
                "Find in selection",
                "Find in selection (Alt+L)",
                state.inSelection(),
                state.scopeTo > state.scopeFrom
                    ? () -> {
                      state.inSelection = !state.inSelection;
                      changed(state);
                    }
                    : null)
            .key("in-selection"))
        .child(tool(
                t,
                Symbol.ARROW_UP,
                "Previous match",
                "Previous match (Shift+Enter)",
                null,
                searchable ? () -> go(state, -1) : null)
            .key("previous"))
        .child(tool(
                t,
                Symbol.ARROW_DOWN,
                "Next match",
                "Next match (Enter)",
                null,
                searchable ? () -> go(state, 1) : null)
            .key("next"))
        .child(tool(t, Symbol.DISMISS, "Close", "Close (Escape)", null, state::close)
            .key("close"));
    panel.child(findRow);
    state.replaceField = null;
    if (state.mode == Mode.REPLACE) {
      El replace = TextField.textField(t, state.replacement, "Replace", v -> {
            state.replacement = v;
            state.replaceError = null;
          })
          .key("replace")
          .label("Replace")
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
        replace = Tooltip.tooltip(t, TextField.invalid(t, replace), state.replaceError);
      }
      panel.child(row()
          .gap(t.space().xs())
          .align(Style.Align.CENTER)
          .padding(0, 0, 0, lead + t.space().xs())
          .child(replace)
          .child(Button.button(
                  t,
                  Button.Variant.SECONDARY,
                  "Replace",
                  searchable ? () -> replace(app, state) : null)
              .key("replace-one"))
          .child(Button.button(
                  t,
                  Button.Variant.SECONDARY,
                  "Replace all",
                  searchable ? () -> replaceAll(app, state) : null)
              .key("replace-all")));
    }
    return bar;
  }

  /** The height of the bar, to lay it over the field's top. */
  static float barHeight(Theme t, State state) {
    int rows = state.mode == Mode.REPLACE ? 2 : 1;
    return rows * t.size().control()
        + (rows - 1) * t.space().xs()
        + 2 * (t.space().xs() + t.size().stroke());
  }

  /** The field to find, with the switches of case, words and regular expressions inside. */
  private static El queryField(Theme t, State state) {
    El field = TextField.textField(t, state.query, "Find", v -> {
          state.query = v;
          changed(state);
        })
        .key("find-field")
        .label("Find")
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
    float inset = t.space().xs();
    float size = FieldActions.size(t);
    if (state.error != null) {
      TextField.invalid(t, field);
    }
    El switches = row()
        .gap(0)
        .child(tool(
                t,
                Symbol.TEXT_CASE_TITLE,
                "Match case",
                "Match case (Alt+C)",
                state.matchCase,
                () -> {
                  state.matchCase = !state.matchCase;
                  changed(state);
                })
            .key("match-case")
            .size(size, size))
        .child(tool(
                t,
                Symbol.TEXT_WHOLE_WORD,
                "Whole word",
                "Whole word (Alt+W)",
                state.wholeWord,
                () -> {
                  state.wholeWord = !state.wholeWord;
                  changed(state);
                })
            .key("whole-word")
            .size(size, size))
        .child(tool(
                t,
                Symbol.TEXT_ASTERISK,
                "Regular expression",
                "Regular expression (Alt+R)",
                state.regex,
                () -> {
                  state.regex = !state.regex;
                  changed(state);
                })
            .key("regex")
            .size(size, size));
    El box = FieldActions.embed(t, field, switches, 3 * size + 2 * inset)
        .whenFocusWithin(e -> field.border(
            t.size().stroke(),
            state.error != null ? t.status().danger() : t.accent().color()));
    return state.error != null ? Tooltip.tooltip(t, box, state.error) : box;
  }

  /** The field of the line to go to and the lines there are. */
  private static El lineRow(Theme t, State state, String text) {
    El field = TextField.textField(t, state.line, "Line or line:column", v -> state.line = v)
        .key("line-field")
        .label("Go to line")
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
      TextField.invalid(t, field);
    }
    int lines = 1;
    for (int i = text.indexOf('\n'); i >= 0; i = text.indexOf('\n', i + 1)) {
      lines++;
    }
    return row()
        .gap(t.space().xs())
        .align(Style.Align.CENTER)
        .child(field)
        .child(label(t, Label.Variant.MUTED, "1 to " + lines)
            .key("count")
            .shrink(0)
            .fontFeatures("+tnum")
            .margin(0, t.space().s(), 0, t.space().s()))
        .child(tool(t, Symbol.DISMISS, "Close", "Close (Escape)", null, state::close)
            .key("close"));
  }

  /**
   * A small square button with an icon in the bar: a switch when {@code checked} is not null. It
   * leaves the focus where it is when pressed, and Tab reaches it.
   */
  private static El tool(
      Theme t, Symbol symbol, String label, String tip, Boolean checked, Runnable onPress) {
    El button = FieldActions.button(t, symbol, label, checked, onPress)
        .size(t.size().control(), t.size().control())
        .shrink(0);
    if (onPress == null) {
      return button;
    }
    button.focusable().focusRing(t.accent().edge());
    return Tooltip.tooltip(t, button, tip);
  }

  /** The end of the word that starts at or before {@code caret}. */
  static int wordEnd(String text, int caret) {
    int at = caret;
    while (at < text.length()) {
      int c = text.codePointAt(at);
      if (!Completion.word(c)) {
        break;
      }
      at += Character.charCount(c);
    }
    return at;
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
