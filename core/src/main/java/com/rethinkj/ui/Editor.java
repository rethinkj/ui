package com.rethinkj.ui;

import java.text.BreakIterator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntPredicate;

/**
 * The editing state of one {@link El#editable} element, which {@link App} keeps by the element's
 * identity across rebuilds, as it keeps scroll offsets: the caret and the anchor of the selection,
 * the horizontal scroll, the undo history and the composition of an input method in progress, and
 * for a {@link El#multiline multi-line} field where its lines start ({@link Lines}).
 *
 * <p>The string belongs to the app. An edit computes the new string and hands it to the element's
 * handler; the next tree the view builds shows what the app made of it. {@link #attach} compares
 * that with what the edit proposed: the same, and the caret is where the edit put it; the string
 * from before the edit, and the edit is undone, caret and history included; something else, and
 * the caret keeps its distance from the end of the text after it, or from the start of the text
 * before it, whichever is unchanged.
 *
 * <p>Positions are indexes into the Java string. The caret only stops at grapheme boundaries
 * ({@link BreakIterator#getCharacterInstance}), so it never splits a surrogate pair, a letter from
 * its combining mark or an emoji sequence, and Backspace removes a whole grapheme. Words come from
 * {@link BreakIterator#getWordInstance}: a segment with a letter or a digit is a word; in a field of
 * {@link El#codeWords}, a run of letters, digits and {@code _} is. The JDK's
 * grapheme iterator reads its whole text when it is set, so a multi-line field asks it about the
 * line of a position alone, which a line feed bounds on both sides.
 *
 * <p>A single-line field turns line breaks into spaces, and Home and End go to the ends of the
 * text. A multi-line field keeps them as line feeds, turning a carriage return with its line feed,
 * a carriage return alone and the separators U+2028 and U+2029 into one ({@link #multiLine}), in
 * typed and pasted text and in the app's string, which the field shows and edits so: the first
 * edit hands the app the string with line feeds only.
 *
 * <p>The history holds edits, not states: where an edit replaced what by what, with the caret and
 * the anchor before and after, so that the steps of a long text do not each hold a copy of it.
 * Typing and deleting in a run grow one step; undo and redo replace back.
 *
 * <p>A {@link El#masked masked} field edits its real string as any other, but copies and cuts
 * nothing, and takes its whole string as one word, so that the keys and presses that go by words
 * do not show where its words are. The string, the steps of the undo history and the
 * composition stay in memory as they are, as the app's state does: masking hides the string from
 * the screen, the clipboard, the tree dump and the fonts, not from the process.
 */
final class Editor {

  /** The most steps that undo goes back. */
  static final int HISTORY = 100;

  /** How far the caret stays from the edges when the text scrolls, in logical pixels. */
  static final float MARGIN = 4;

  /**
   * Whether Ctrl+Right and Ctrl+Delete go to the end of a word, as on Linux, rather than to the
   * start of the next one, as on Windows.
   */
  static final boolean WORD_ENDS = !System.getProperty("os.name", "").startsWith("Windows");

  /** Edits that coalesce into one step of the history while they follow each other. */
  private enum Run {
    TYPING,
    DELETING
  }

  private record State(String text, int caret, int anchor) {}

  /**
   * A step of the history: {@code removed} at {@code at} replaced by {@code inserted}, with the
   * caret and the anchor before and after.
   */
  private record Step(
      int at,
      String removed,
      String inserted,
      int caretBefore,
      int anchorBefore,
      int caretAfter,
      int anchorAfter) {}

  private El el; // in the current tree, for its handler and settings
  private String text;
  private int caret;
  private int anchor;
  private Lines lines; // of text, in a multi-line field; null otherwise
  // The state before the first edit that no tree has answered yet, the history as it was then, and
  // what the last edit proposed.
  private State before;
  private ArrayDeque<Step> undoBefore;
  private ArrayDeque<Step> redoBefore;
  private String proposed;
  private ArrayDeque<Step> undo = new ArrayDeque<>();
  private ArrayDeque<Step> redo = new ArrayDeque<>();
  private Run run;
  private String composition = "";
  private int compositionCursor;
  // What the pointer selects by while it is down: 1 graphemes, 2 words, 3 everything or, in a
  // multi-line field, lines, and 4 everything; and the range the press selected, which a drag by
  // words or lines keeps.
  private int unit = 1;
  private int pressFrom;
  private int pressTo;
  private float scroll; // in logical pixels
  // A multi-line field's scroll when it last left the tree, and whether it comes back to it now
  // (a FieldState keeps the editor across that).
  private double leftX;
  private double leftY;
  private boolean returning;
  private boolean changed;
  // A multi-line field: whether the caret has moved since it was last shown, which scrolls it into
  // view; the x Up and Down keep, in physical pixels, NaN until they move; whether Tab moves the
  // focus after Escape, or after Control+M until the next Control+M.
  private boolean reveal;
  // App.select asked: a multi-line field scrolls the selection into view also without the focus.
  private boolean asked;
  // The caret and anchor the element's onSelect handler was told last; -1 before the first.
  private int toldCaret = -1;
  private int toldAnchor = -1;
  private float goal = Float.NaN;
  private static final List<String> MODIFIER_KEYS = List.of("Shift", "Control", "Alt", "Meta");
  private boolean escaped;
  private boolean tabMovesFocus;
  // The app's last string and what the field made of it, and the string with the composition.
  private String raw;
  private String normalized;
  private String shownFor;
  private String shownComposition;
  private int shownStart;
  private int shownEnd;
  private String shownText;
  private Lines shownLines;

  /**
   * A new editor with the caret at the end of the element's string, or at its start in a
   * multi-line field, whose text may be long.
   */
  Editor(El el) {
    this.el = el;
    boolean multi = el.text().multiline();
    this.text = multi ? normalized(el.text().value()) : el.text().value();
    this.caret = multi ? 0 : text.length();
    this.anchor = caret;
    this.lines = multi ? Lines.of(text) : null;
  }

  // ---- The element

  /**
   * Takes the element of a new tree: its handler and settings, and its string, which is the app's
   * answer to the edits since the last tree. Then shows the composition in its text.
   */
  void attach(El el) {
    this.el = el;
    Text t = el.text();
    if (!t.multiline()) {
      lines = null;
      answer(t.value());
      t.compose(composition.isEmpty() ? null : shown());
      return;
    }
    String value = t.value();
    answer(value == proposed || value == text ? value : normalized(value));
    t.edited(text, lines());
    showComposition();
  }

  /** The app's string with line feeds only; the same object while the app's string is. */
  private String normalized(String value) {
    if (value != raw) {
      raw = value;
      normalized = multiLine(value);
    }
    return normalized;
  }

  private boolean multiline() {
    return el.text().multiline();
  }

  /** The lines of the text, made when a multi-line field first needs them. */
  private Lines lines() {
    if (lines == null) {
      lines = Lines.of(text);
    }
    return lines;
  }

  /** For tests: the lines of a multi-line field's text, and how many the field shaped. */
  Lines lineIndex() {
    return lines;
  }

  /** Whether an edit waits for the app's answer, the string of the next tree. */
  boolean answerPending() {
    return proposed != null;
  }

  /**
   * Takes the app's answer to the edits since the last answer: the string the view now gives the
   * element, as {@link #attach} describes. {@link App} builds the view again between two edits
   * that reach a field in the same frame, so that the second builds on what the app made of the
   * first.
   */
  private void answer(String value) {
    if (proposed != null) {
      // The answer is usually the proposal itself, which the first comparison finds at once.
      if (!value.equals(proposed) && value.equals(before.text)) {
        // Refused: as if the edits had not happened.
        caret = before.caret;
        anchor = before.anchor;
        undo = undoBefore;
        redo = redoBefore;
        run = null;
        relined(value);
      } else if (!value.equals(proposed)) {
        // Changed: one step from before the edits to what the app made of them.
        caret = map(caret, proposed, value);
        anchor = map(anchor, proposed, value);
        undo = undoBefore;
        redo = new ArrayDeque<>();
        push(undo, diff(before, value, caret, anchor));
        run = null;
        relined(value);
      }
      before = null;
      undoBefore = null;
      redoBefore = null;
      proposed = null;
    } else if (!value.equals(text)) {
      // Changed by the app on its own: the history no longer leads to it.
      caret = map(caret, text, value);
      anchor = map(anchor, text, value);
      undo.clear();
      redo.clear();
      run = null;
      relined(value);
    }
    text = value;
    caret = snap(caret);
    anchor = snap(anchor);
  }

  /** The lines are found again in a string that no edit led to. */
  private void relined(String value) {
    if (lines != null) {
      lines = Lines.of(value);
      reveal = true;
    }
  }

  /** The step from a state to a string that differs from its text in one place. */
  private static Step diff(State from, String to, int caretAfter, int anchorAfter) {
    String a = from.text;
    int prefix = 0;
    int shorter = Math.min(a.length(), to.length());
    while (prefix < shorter && a.charAt(prefix) == to.charAt(prefix)) {
      prefix++;
    }
    int suffix = 0;
    while (suffix < shorter - prefix
        && a.charAt(a.length() - 1 - suffix) == to.charAt(to.length() - 1 - suffix)) {
      suffix++;
    }
    return new Step(
        prefix,
        a.substring(prefix, a.length() - suffix),
        to.substring(prefix, to.length() - suffix),
        from.caret,
        from.anchor,
        caretAfter,
        anchorAfter);
  }

  /** Whether the element takes edits: it has a handler and is not read-only. */
  boolean editable() {
    return el.onEdit() != null && !el.isReadOnly();
  }

  /** The string with the composition in place of the selection. */
  private String shown() {
    return text.substring(0, start()) + composition + text.substring(end());
  }

  /**
   * Shows the composition in a multi-line field's text, with the lines of the string it makes,
   * which are made again only when text, selection or composition change.
   */
  private void showComposition() {
    Text t = el.text();
    if (composition.isEmpty()) {
      shownFor = null;
      t.compose(null, null);
      return;
    }
    if (shownFor != text
        || shownComposition != composition
        || shownStart != start()
        || shownEnd != end()) {
      shownFor = text;
      shownComposition = composition;
      shownStart = start();
      shownEnd = end();
      shownText = shown();
      shownLines = lines().copy();
      shownLines.edit(start(), end() - start(), composition);
    }
    t.compose(shownText, shownLines);
  }

  /** The first and the last position of the selection; equal when nothing is selected. */
  int start() {
    return Math.min(caret, anchor);
  }

  int end() {
    return Math.max(caret, anchor);
  }

  int caret() {
    return caret;
  }

  int anchor() {
    return anchor;
  }

  String text() {
    return text;
  }

  String composition() {
    return composition;
  }

  /**
   * Tells the element's {@link El#onSelect} handler the caret and the anchor if they changed since
   * it was last told.
   *
   * @return whether it was told
   */
  boolean tell() {
    if (caret == toldCaret && anchor == toldAnchor) {
      return false;
    }
    toldCaret = caret;
    toldAnchor = anchor;
    El.SelectHandler handler = el.onSelect();
    if (handler == null) {
      return false;
    }
    handler.select(caret, anchor);
    return true;
  }

  /**
   * An edit the app asks for ({@link App#edit}): {@code from} to {@code to} replaced by {@code
   * inserted}, with the caret at {@code newCaret} of the new string after it, as one step of the
   * history, handed to the app as typed text is. Nothing in a field that takes no edits.
   */
  void request(int from, int to, String inserted, int newCaret) {
    if (!editable()) {
      return;
    }
    composition = "";
    from = Math.clamp(from, 0, text.length());
    to = Math.clamp(to, from, text.length());
    inserted = fit(fitLines(inserted), from, to);
    int length = text.length() - (to - from) + inserted.length();
    newCaret = Math.clamp(newCaret, 0, length);
    run = null;
    edit(from, to - from, inserted, newCaret, newCaret, null, false);
  }

  /** A selection the app asks for ({@link App#select}), which scrolls into view as a key's does. */
  void select(int newCaret, int newAnchor) {
    composition = "";
    anchor = snap(newAnchor);
    caret = snap(newCaret);
    run = null;
    goal = Float.NaN;
    changed = true;
    reveal = true;
    asked = true;
  }

  /**
   * The caret's rectangle as drawn, in logical pixels from the top left of the element's border
   * box, as {x, y, width, height}; null before layout.
   */
  float[] caretBox() {
    Text t = el.text();
    if (!t.laidOut()) {
      return null;
    }
    Style s = el.style();
    float scale = t.scale();
    float top = s.borderTop.kind() == Len.Kind.PX ? s.borderTop.value() : 0;
    top += s.paddingTop.kind() == Len.Kind.PX ? s.paddingTop.value() : 0;
    float left = s.borderLeft.kind() == Len.Kind.PX ? s.borderLeft.value() : 0;
    left += s.paddingLeft.kind() == Len.Kind.PX ? s.paddingLeft.value() : 0;
    int shownCaret = composition.isEmpty() ? caret : start() + compositionCursor;
    float rowTop = t.multiline() ? caretTopOffset() : t.lineTop(t.lineOf(shownCaret)) / scale;
    return new float[] {
      left + caretOffset(), top + rowTop, caretWidth(scale) / scale, t.lineHeight() / scale
    };
  }

  /** Whether something changed since the last call; a change keeps the caret on for a while. */
  boolean takeChanged() {
    boolean was = changed;
    changed = false;
    return was;
  }

  /** The element gained the focus: a multi-line field scrolls its caret into view. */
  void focusGained() {
    reveal = true;
  }

  /** The element lost the focus: a composition ends without being committed. */
  void blur() {
    composition = "";
    run = null;
    unit = 1;
    escaped = false;
    changed = true;
  }

  /**
   * Its field left the tree, or fades out of it: the element draws no caret, selection or
   * composition any more.
   */
  void leave() {
    blur();
    el.text().dropComposition();
    place(false, false);
    leftX = el.scrollX();
    leftY = el.scrollY();
  }

  /** The field it was attached to last. */
  El element() {
    return el;
  }

  /**
   * The editor comes back into the tree with a field of a new tree, as a {@link FieldState} brings
   * it: the field scrolls to where it was when it left, or to the start the first time.
   */
  void returned() {
    returning = true;
  }

  // ---- Keys

  /**
   * Acts on a key for the focused element.
   *
   * @return whether the key was the field's: its own keys, and keys that type, whose text comes
   *     separately; Enter, Escape, Tab and shortcuts with Control, Alt or Meta that the field does
   *     not know go on to the handlers. A multi-line field takes Enter, which breaks the line, but
   *     not Control+Enter, and Tab only when it {@link El#tabIndents indents} with it
   */
  boolean key(Key key, Clipboard clipboard) {
    String name = key.name();
    int modifiers = key.modifiers();
    boolean shift = (modifiers & Key.SHIFT) != 0;
    int other = modifiers & ~Key.SHIFT;
    boolean afterEscape = escaped;
    // A modifier's own key, which comes before the key it modifies, keeps Escape's effect, so that
    // Escape and then Shift+Tab move the focus back.
    if (!MODIFIER_KEYS.contains(name)) {
      escaped = false;
    }
    if (!composition.isEmpty()) {
      return !name.equals("Tab"); // the input method has the keys while it composes
    }
    boolean word = other == Key.CONTROL;
    boolean plain = other == 0 || word;
    if (multiline()) {
      Boolean taken = lineKey(name, modifiers, shift, other, afterEscape);
      if (taken != null) {
        return taken;
      }
    }
    switch (name) {
      case "ArrowLeft" -> {
        if (!plain) {
          return false;
        }
        int to = word ? wordStart() : caret != anchor && !shift ? start() : previous(caret);
        moveTo(to, shift);
        return true;
      }
      case "ArrowRight" -> {
        if (!plain) {
          return false;
        }
        int to = word ? wordEnd() : caret != anchor && !shift ? end() : next(caret);
        moveTo(to, shift);
        return true;
      }
      case "Home" -> {
        if (!plain) {
          return false;
        }
        moveTo(word ? 0 : lineStart(caret), shift);
        return true;
      }
      case "End" -> {
        if (!plain) {
          return false;
        }
        moveTo(word ? text.length() : lineEnd(caret), shift);
        return true;
      }
      case "Backspace" -> {
        if (!plain) {
          return false;
        }
        delete(word ? wordStart() : previous(caret));
        return true;
      }
      case "Delete" -> {
        if (modifiers == Key.SHIFT) {
          cut(clipboard);
        } else if (other == 0 || word && !shift) {
          delete(word ? wordEnd() : next(caret));
        } else {
          return false;
        }
        return true;
      }
      case "Insert" -> {
        if (modifiers == Key.CONTROL) {
          copy(clipboard);
        } else if (modifiers == Key.SHIFT) {
          paste(clipboard);
        } else {
          return false;
        }
        return true;
      }
      case "Enter", "NumpadEnter", "Escape", "Tab" -> {
        return false;
      }
      default -> {}
    }
    if (modifiers == Key.CONTROL) {
      switch (name) {
        case "A" -> selectAll();
        case "C" -> copy(clipboard);
        case "X" -> cut(clipboard);
        case "V" -> paste(clipboard);
        case "Z" -> undo();
        case "Y" -> redo();
        default -> {
          return false;
        }
      }
      return true;
    }
    if (modifiers == (Key.CONTROL | Key.SHIFT) && name.equals("Z")) {
      redo();
      return true;
    }
    // A key that types: its text comes with textInput, and nobody else should act on it.
    return other == 0
        && (name.equals("Space")
            || name.codePointCount(0, name.length()) == 1
            || name.startsWith("Numpad"));
  }

  /**
   * The keys of a multi-line field that a single-line one does not have, or has otherwise; null
   * for the others.
   */
  private Boolean lineKey(
      String name, int modifiers, boolean shift, int other, boolean afterEscape) {
    Text t = el.text();
    switch (name) {
      case "Enter", "NumpadEnter" -> {
        if (other != 0) {
          return false; // Control+Enter runs what the app runs with it
        }
        if (editable()) {
          replace("\n", null);
        }
        return true;
      }
      case "ArrowUp", "ArrowDown", "PageUp", "PageDown" -> {
        if (other != 0) {
          return false;
        }
        boolean page = name.startsWith("Page");
        int direction = name.endsWith("Up") ? -1 : 1;
        int rows = page ? t.visibleRows() : 1;
        float x = Float.isNaN(goal) ? t.x(caret) : goal;
        int to = text.isEmpty() ? 0 : t.moveRows(caret, direction * rows, x);
        if (page) { // the view moves by as much, so that the caret stays where it is in it
          el.scrollTo(el.scrollX(), el.scrollY() + direction * rows * t.lineHeight() / t.scale());
        }
        moveTo(to, shift);
        goal = x;
        return true;
      }
      case "Home" -> {
        if (other != 0) {
          return null; // Control+Home: to the start of the text
        }
        int row = text.isEmpty() ? 0 : t.rowStart(caret);
        int to = row;
        if (caret == row && t.startsLine(caret)) {
          int indent = row; // a second Home goes to the first character that is not blank
          while (indent < text.length()
              && (text.charAt(indent) == ' ' || text.charAt(indent) == '\t')) {
            indent++;
          }
          to = indent;
        }
        moveTo(to, shift);
        return true;
      }
      case "End" -> {
        if (other != 0) {
          return null;
        }
        moveTo(text.isEmpty() ? 0 : t.rowEnd(caret), shift);
        return true;
      }
      case "Escape" -> {
        escaped = true; // a Tab right after it moves the focus
        return false;
      }
      case "Tab" -> {
        if (!el.tabIndents()
            || tabMovesFocus
            || afterEscape
            || !editable()
            || (modifiers & ~Key.SHIFT) != 0) {
          return false;
        }
        int first = lines().line(start());
        if (shift || lines.line(end()) > first) {
          indent(shift);
        } else {
          insertTab();
        }
        return true;
      }
      case "M" -> {
        if (modifiers != Key.CONTROL || !el.tabIndents()) {
          return null;
        }
        tabMovesFocus = !tabMovesFocus;
        return true;
      }
      default -> {
        return null;
      }
    }
  }

  private int lineStart(int at) {
    return 0; // a single-line field; a multi-line one goes by rows, see lineKey
  }

  private int lineEnd(int at) {
    return text.length();
  }

  /** A tab, or spaces up to the next tab stop, in place of the selection. */
  private void insertTab() {
    if (!el.tabSpaces()) {
      replace("\t", Run.TYPING);
      return;
    }
    int size = el.text().tabSize();
    int column = 0;
    for (int i = lines().start(lines.line(start())); i < start(); ) {
      int c = text.codePointAt(i);
      column = c == '\t' ? (column / size + 1) * size : column + 1;
      i += Character.charCount(c);
    }
    replace(" ".repeat(size - column % size), Run.TYPING);
  }

  /**
   * Indents the lines of the selection, or of the caret, by a tab or {@link El#tabSize} spaces, or
   * outdents them by as much as one of those, in one step of the history. A selection that ends at
   * the start of a line leaves that line out; indenting leaves empty lines empty, and does nothing
   * where it would make the text longer than {@link El#maxLength}.
   */
  private void indent(boolean out) {
    Lines ls = lines();
    int first = ls.line(start());
    int last = ls.line(end());
    if (last > first && end() == ls.start(last)) {
      last--;
    }
    int size = el.text().tabSize();
    String unit = el.tabSpaces() ? " ".repeat(size) : "\t";
    int from = ls.start(first);
    int to = ls.end(last);
    StringBuilder block = new StringBuilder();
    int delta = 0;
    int newCaret = caret > to ? caret : -1;
    int newAnchor = anchor > to ? anchor : -1;
    for (int l = first; l <= last; l++) {
      int start = ls.start(l);
      int end = ls.end(l);
      int change;
      if (!out) {
        change = end > start ? unit.length() : 0;
        block.append(change > 0 ? unit : "");
        block.append(text, start, end);
      } else {
        int k = 0;
        if (start < end && text.charAt(start) == '\t') {
          k = 1;
        } else {
          while (k < size && start + k < end && text.charAt(start + k) == ' ') {
            k++;
          }
        }
        change = -k;
        block.append(text, start + k, end);
      }
      if (l < last) {
        block.append('\n');
      }
      if (newCaret < 0 && caret <= end) {
        newCaret = moved(caret, start, change, delta);
      }
      if (newAnchor < 0 && anchor <= end) {
        newAnchor = moved(anchor, start, change, delta);
      }
      delta += change;
    }
    if (caret > to) {
      newCaret = caret + delta;
    }
    if (anchor > to) {
      newAnchor = anchor + delta;
    }
    if (delta == 0 && block.length() == to - from) {
      return; // nothing to outdent
    }
    int max = el.maxLength();
    if (max >= 0 && text.codePointCount(0, text.length()) + delta > max) {
      return; // an indent is whole or nothing, and cuts no text
    }
    run = null;
    edit(from, to - from, block.toString(), newCaret, newAnchor, null, false);
  }

  /** Where a position of a line goes when {@code change} characters go in or out at its start. */
  private static int moved(int at, int lineStart, int change, int delta) {
    int column = at - lineStart;
    int moved = change >= 0 ? (column > 0 ? column + change : 0) : Math.max(column + change, 0);
    return lineStart + delta + moved;
  }

  // ---- Edits

  /** Text typed, or committed by an input method: it replaces the selection. */
  boolean type(String typed) {
    boolean composed = !composition.isEmpty();
    composition = "";
    changed |= composed;
    reveal |= composed;
    if (!editable()) {
      return composed;
    }
    return replace(fitLines(typed), Run.TYPING) || composed;
  }

  /** Line breaks as the field keeps them: spaces in a single-line field, line feeds otherwise. */
  private String fitLines(String text) {
    return multiline() ? multiLine(text) : singleLine(text);
  }

  /**
   * Shows the composition of an input method in place of the selection, without changing the
   * string; empty text ends it.
   *
   * @param cursor where the input method's cursor is in {@code text}, in UTF-16 units; -1 for its
   *     end
   */
  boolean compose(String composing, int cursor) {
    if (!editable() || composing.isEmpty() && composition.isEmpty()) {
      return false;
    }
    composition = fitLines(composing);
    compositionCursor = cursor < 0 ? composition.length() : Math.min(cursor, composition.length());
    run = null;
    changed = true;
    reveal = true;
    return true;
  }

  void selectAll() {
    anchor = 0;
    caret = text.length();
    run = null;
    goal = Float.NaN;
    changed = true;
  }

  /** Copies the selection; a masked field copies nothing, and the clipboard keeps what it had. */
  void copy(Clipboard clipboard) {
    if (caret != anchor && !masked()) {
      clipboard.write(text.substring(start(), end()));
    }
  }

  /** Cuts the selection; a masked field does nothing, as it copies nothing. */
  void cut(Clipboard clipboard) {
    if (caret != anchor && editable() && !masked()) {
      copy(clipboard);
      replace("", null);
    }
  }

  void paste(Clipboard clipboard) {
    if (editable()) {
      replace(fitLines(clipboard.read()), null);
    }
  }

  void undo() {
    if (editable() && !undo.isEmpty()) {
      keepHistory();
      Step step = undo.pop();
      // Redo comes back to the caret and selection of now, as undo to those before the edit.
      push(
          redo,
          new Step(
              step.at,
              step.removed,
              step.inserted,
              step.caretBefore,
              step.anchorBefore,
              caret,
              anchor));
      propose(step.at, step.inserted.length(), step.removed, step.caretBefore, step.anchorBefore);
      run = null;
    }
  }

  void redo() {
    if (editable() && !redo.isEmpty()) {
      keepHistory();
      Step step = redo.pop();
      push(
          undo,
          new Step(
              step.at,
              step.removed,
              step.inserted,
              caret,
              anchor,
              step.caretAfter,
              step.anchorAfter));
      propose(step.at, step.removed.length(), step.inserted, step.caretAfter, step.anchorAfter);
      run = null;
    }
  }

  private static void push(ArrayDeque<Step> history, Step step) {
    history.push(step);
    while (history.size() > HISTORY) {
      history.removeLast();
    }
  }

  /** Deletes the selection, or from the caret to {@code to}. */
  private void delete(int to) {
    if (!editable()) {
      return;
    }
    if (caret != anchor) {
      replace("", Run.DELETING);
      return;
    }
    if (to == caret) {
      return;
    }
    int from = Math.min(to, caret);
    edit(from, Math.max(to, caret) - from, "", from, from, Run.DELETING, run == Run.DELETING);
  }

  /**
   * Replaces the selection with text, cut to the maximum length.
   *
   * @param kind what kind of run it continues, or null for a step of its own
   * @return whether it changed something
   */
  private boolean replace(String inserted, Run kind) {
    int from = start();
    int to = end();
    inserted = fit(inserted, from, to);
    if (inserted.isEmpty() && from == to) {
      return false;
    }
    int at = from + inserted.length();
    edit(from, to - from, inserted, at, at, kind, kind != null && kind == run && from == to);
    return true;
  }

  /**
   * Replaces {@code removed} characters at {@code at} by {@code inserted}, as a step of the history
   * of its own or, with {@code coalesce}, as part of the run the last step holds where it adjoins
   * it.
   */
  private void edit(
      int at,
      int removed,
      String inserted,
      int newCaret,
      int newAnchor,
      Run kind,
      boolean coalesce) {
    keepHistory();
    String gone = text.substring(at, at + removed);
    Step top = coalesce ? undo.peek() : null;
    Step merged = null;
    if (top != null && removed == 0 && at == top.at + top.inserted.length()) { // typing on
      merged = new Step(
          top.at,
          top.removed,
          top.inserted + inserted,
          top.caretBefore,
          top.anchorBefore,
          newCaret,
          newAnchor);
    } else if (top != null && inserted.isEmpty() && top.inserted.isEmpty()) {
      if (at + removed == top.at) { // Backspace on
        merged = new Step(
            at, gone + top.removed, "", top.caretBefore, top.anchorBefore, newCaret, newAnchor);
      } else if (at == top.at) { // Delete on
        merged = new Step(
            at, top.removed + gone, "", top.caretBefore, top.anchorBefore, newCaret, newAnchor);
      }
    }
    if (merged != null) {
      undo.pop();
      undo.push(merged);
    } else {
      push(undo, new Step(at, gone, inserted, caret, anchor, newCaret, newAnchor));
    }
    redo.clear();
    propose(at, removed, inserted, newCaret, newAnchor);
    run = kind;
  }

  /**
   * Keeps the history as it is before the first edit, undo or redo that no tree has answered yet,
   * for the app to refuse: each of them changes the history before it proposes the string.
   */
  private void keepHistory() {
    if (before == null) {
      undoBefore = new ArrayDeque<>(undo);
      redoBefore = new ArrayDeque<>(redo);
    }
  }

  /** Hands a new string to the app, which answers with the next tree. */
  private void propose(int at, int removed, String inserted, int newCaret, int newAnchor) {
    if (before == null) {
      before = new State(text, caret, anchor);
    }
    String next = text.substring(0, at) + inserted + text.substring(at + removed);
    if (lines != null) {
      lines.edit(at, removed, inserted);
    }
    proposed = next;
    text = next;
    // An edit can join what it puts in with a mark after it: the caret stays between graphemes.
    caret = snap(newCaret);
    anchor = snap(newAnchor);
    changed = true;
    reveal = true;
    goal = Float.NaN;
    el.onEdit().accept(next);
  }

  /** The longest start of {@code inserted}, in whole graphemes, that the maximum length leaves. */
  private String fit(String inserted, int from, int to) {
    int max = el.maxLength();
    if (max < 0) {
      return inserted;
    }
    int room = max - text.codePointCount(0, text.length()) + text.codePointCount(from, to);
    if (inserted.codePointCount(0, inserted.length()) <= room) {
      return inserted;
    }
    BreakIterator chars = characters(inserted);
    int end = 0;
    for (int b = chars.next(); b != BreakIterator.DONE; b = chars.next()) {
      if (inserted.codePointCount(0, b) > room) {
        break;
      }
      end = b;
    }
    return inserted.substring(0, end);
  }

  /** A single-line field takes each line break as a space. */
  static String singleLine(String text) {
    return text.replace("\r\n", " ")
        .replace('\r', ' ')
        .replace('\n', ' ')
        .replace(' ', ' ')
        .replace(' ', ' ');
  }

  /**
   * A multi-line field takes each line break as a line feed: a carriage return with the line feed
   * after it, a carriage return alone, and the separators U+2028 and U+2029. The same string when
   * it has none.
   */
  static String multiLine(String text) {
    if (text.indexOf('\r') < 0 && text.indexOf(' ') < 0 && text.indexOf(' ') < 0) {
      return text;
    }
    return text.replace("\r\n", "\n").replace('\r', '\n').replace(' ', '\n').replace(' ', '\n');
  }

  // ---- Moves

  private void moveTo(int to, boolean extend) {
    caret = to;
    if (!extend) {
      anchor = to;
    }
    run = null;
    goal = Float.NaN;
    changed = true;
    reveal = true;
  }

  // ---- The pointer

  /**
   * A press: places the caret at {@code boundary}, or selects the word under {@code hit} for a
   * double click and everything for a triple one, or in a multi-line field the line under it with
   * its line feed, and everything for a fourth; with Shift it moves the caret and keeps the
   * anchor.
   */
  void press(int boundary, int hit, int clicks, boolean extend) {
    composition = "";
    run = null;
    goal = Float.NaN;
    changed = true;
    reveal = true;
    if (extend) {
      unit = 1;
      caret = boundary;
      return;
    }
    boolean multi = multiline();
    unit = Math.clamp(clicks, 1, multi ? 4 : 3);
    int[] range = switch (unit) {
      case 1 -> new int[] {boundary, boundary};
      case 2 -> wordAt(hit);
      case 3 -> multi ? lineAt(hit) : new int[] {0, text.length()};
      default -> new int[] {0, text.length()};
    };
    pressFrom = range[0];
    pressTo = range[1];
    anchor = pressFrom;
    caret = pressTo;
  }

  /** The pointer moved while it is down after a {@link #press}: the selection follows it. */
  boolean drag(int boundary, int hit) {
    int oldCaret = caret;
    int oldAnchor = anchor;
    boolean multi = multiline();
    switch (unit) {
      case 1 -> caret = boundary;
      case 2, 3 -> {
        if (unit == 3 && !multi) {
          break;
        }
        int[] range = unit == 2 ? wordAt(hit) : lineAt(hit);
        if (range[0] < pressFrom) {
          anchor = pressTo;
          caret = range[0];
        } else {
          anchor = pressFrom;
          caret = Math.max(range[1], pressTo);
        }
      }
      default -> {}
    }
    boolean moved = caret != oldCaret || anchor != oldAnchor;
    changed |= moved;
    reveal |= moved;
    return moved;
  }

  /** The line of a multi-line field at {@code index}, with its line feed. */
  private int[] lineAt(int index) {
    Lines ls = lines();
    int line = ls.line(Math.clamp(index, 0, text.length()));
    return new int[] {ls.start(line), line + 1 < ls.count() ? ls.start(line + 1) : text.length()};
  }

  /** A position of the shown string as a position of the string. */
  int fromShown(int index) {
    if (composition.isEmpty()) {
      return Math.min(index, text.length());
    }
    int at = start();
    if (index <= at) {
      return index;
    }
    if (index < at + composition.length()) {
      return at;
    }
    return Math.min(index - composition.length() + end() - at, text.length());
  }

  // ---- Drawing

  /**
   * Computes what the element's text draws besides its glyphs, once layout has placed it: the
   * scroll that keeps the caret in view, with {@link #MARGIN} to spare where the text is wider
   * than the field, and the selection, caret and composition in the coordinates of the glyphs.
   * Without the focus the text shows its start, and neither caret nor selection.
   */
  void place(boolean focused, boolean caretOn) {
    if (multiline()) {
      placeLines(focused, caretOn);
      return;
    }
    Text t = el.text();
    if (t.lineCount() == 0) {
      t.decorate(0, List.of(), null, null); // not placed: display: none
      return;
    }
    float scale = t.scale();
    int shownCaret = composition.isEmpty() ? caret : start() + compositionCursor;
    int line = t.lineOf(shownCaret);
    float caretX = t.x(shownCaret);
    float caretWidth = caretWidth(scale);
    float width = t.contentWidth();
    float lineWidth = t.lineWidth(line);
    float s = 0;
    if (focused && lineWidth + caretWidth > width) {
      float margin = Math.min(MARGIN * scale, Math.max(0, (width - caretWidth) / 2));
      s = scroll * scale;
      if (caretX - s < margin) {
        s = caretX - margin;
      }
      if (caretX + caretWidth - s > width - margin) {
        s = caretX + caretWidth - width + margin;
      }
      s = Math.clamp(s, 0, lineWidth + caretWidth + margin - width);
      double grid = scale >= 1.5 ? 4 : 1; // the grid of glyph positions
      s = (float) (Math.round(s * grid) / grid);
    }
    scroll = s / scale;
    float top = t.lineTop(line);
    float height = t.lineHeight();
    List<Text.Box> selection = new ArrayList<>();
    if (focused && composition.isEmpty() && caret != anchor) {
      for (int l = t.lineOf(start()); l <= t.lineOf(end()); l++) {
        float from = t.x(Math.max(start(), t.lineStart(l)));
        float to = t.x(Math.min(end(), t.lineEnd(l)));
        selection.add(new Text.Box(from - s, t.lineTop(l), to - from, height));
      }
    }
    Text.Box underline = null;
    if (!composition.isEmpty()) {
      float from = t.x(start());
      float to = t.x(start() + composition.length());
      float y = t.baseline(line) + Math.round(t.font().descent() * t.size() * scale / 2);
      underline = new Text.Box(from - s, y, to - from, caretWidth);
    }
    Text.Box box = focused ? new Text.Box(caretX - s, top, caretWidth, height) : null;
    t.decorate(s, List.copyOf(selection), box, underline);
    t.showCaret(caretOn);
  }

  /**
   * {@link #place} for a multi-line field, which scrolls as a scroll area: the wheel and the bars
   * move it, and an edit or a move of the caret since the last call scrolls just far enough to
   * show the caret's row whole and the caret with {@link #MARGIN} to spare sideways. Then the rows
   * in view are laid out, and the selection is drawn on those.
   */
  private void placeLines(boolean focused, boolean caretOn) {
    Text t = el.text();
    if (!t.laidOut()) {
      t.decorate(0, List.of(), null, null); // not placed: display: none
      return;
    }
    float scale = t.scale();
    int shownCaret = composition.isEmpty() ? caret : start() + compositionCursor;
    float caretWidth = caretWidth(scale);
    el.measureScroll();
    if (returning) { // not where the field this identity had before scrolled to
      returning = false;
      el.scrollTo(leftX, leftY);
    }
    boolean revealed = (focused || asked) && reveal;
    asked = false;
    if (revealed) {
      reveal = false;
      float x = t.x(shownCaret);
      double top = t.caretTop(shownCaret);
      double sx = el.scrollX() * scale;
      double sy = el.scrollY() * scale;
      float width = t.contentWidth();
      if (!t.wraps()) {
        float margin = Math.min(MARGIN * scale, Math.max(0, (width - caretWidth) / 2));
        if (x - sx < margin) {
          sx = x - margin;
        }
        if (x + caretWidth - sx > width - margin) {
          sx = x + caretWidth - width + margin;
        }
      }
      float lineHeight = t.lineHeight();
      if (top < sy) {
        sy = top;
      } else if (top + lineHeight > sy + t.contentHeight()) {
        sy = top + lineHeight - t.contentHeight();
      }
      el.measureScroll(); // with the caret's line, which may be the widest so far
      el.scrollTo(sx / scale, sy / scale);
    }
    float shift = t.layOut();
    if (shift != 0 && !revealed) { // rows above the view were measured: what is in view stays
      el.measureScroll();
      el.scrollTo(el.scrollX(), el.scrollY() + shift / scale);
    }
    el.measureScroll();
    el.scrollTo(el.scrollX(), el.scrollY()); // within what the rows in view measured
    float viewX = t.viewX();
    List<Text.Box> selection = focused && composition.isEmpty() && caret != anchor
        ? t.selectionBoxes(start(), end(), viewX + t.contentWidth())
        : List.of();
    float lineHeight = t.lineHeight();
    Text.Box underline = null;
    if (!composition.isEmpty()) {
      float from = t.x(start());
      float to = t.rowStart(start()) == t.rowStart(start() + composition.length())
          ? t.x(start() + composition.length())
          : t.x(t.rowEnd(start()));
      float y = t.shownBaseline(start()) + Math.round(t.font().descent() * t.size() * scale / 2);
      underline = new Text.Box(from - viewX, y, to - from, caretWidth);
    }
    Text.Box box = focused
        ? new Text.Box(t.x(shownCaret) - viewX, t.shownTop(shownCaret), caretWidth, lineHeight)
        : null;
    t.decorate(viewX, selection, box, underline);
    t.showCaret(caretOn);
    el.placeRanges();
    el.placeGutter(lines().line(Math.min(caret, text.length())));
  }

  /** The caret's offset from the left of the content box, in logical pixels, as drawn. */
  float caretOffset() {
    Text t = el.text();
    int shownCaret = composition.isEmpty() ? caret : start() + compositionCursor;
    return (t.x(shownCaret) - t.scroll()) / t.scale();
  }

  /**
   * The top of the caret's row from the top of the content box, in logical pixels, as drawn: in a
   * multi-line field after its scroll, 0 in a single-line one, whose line is the box.
   */
  float caretTopOffset() {
    Text t = el.text();
    if (!t.multiline()) {
      return 0;
    }
    int shownCaret = composition.isEmpty() ? caret : start() + compositionCursor;
    return t.shownTop(shownCaret) / t.scale();
  }

  /** The caret's line and column, from 1, the column in UTF-16 units; for the tree dump. */
  int[] lineAndColumn() {
    Lines ls = lines();
    int line = ls.line(caret);
    return new int[] {line + 1, caret - ls.start(line) + 1};
  }

  /** Whole physical pixels, at least one. */
  private static float caretWidth(float scale) {
    return Math.max(1, Math.round(scale));
  }

  // ---- Boundaries

  private boolean masked() {
    return el.text().masked();
  }

  /** Where Ctrl+Left goes from the caret; to the start in a masked field, one word. */
  private int wordStart() {
    return masked()
        ? 0
        : snap(el.isCodeWords() ? codeWordLeft(text, caret) : wordLeft(text, caret));
  }

  /** Where Ctrl+Right goes from the caret; to the end in a masked field. */
  private int wordEnd() {
    if (masked()) {
      return text.length();
    }
    return snapUp(
        el.isCodeWords()
            ? codeWordRight(text, caret, WORD_ENDS)
            : wordRight(text, caret, WORD_ENDS));
  }

  /** The word a double press at {@code hit} selects; all of a masked field. */
  private int[] wordAt(int hit) {
    if (masked()) {
      return new int[] {0, text.length()};
    }
    int[] word = el.isCodeWords() ? codeWord(text, hit) : word(text, hit);
    return new int[] {snap(word[0]), snapUp(word[1])};
  }

  /**
   * The part of the text that grapheme boundaries near {@code index} depend on: all of a
   * single-line field, the line of a multi-line one, which a line feed bounds on both sides.
   */
  private int[] around(int index) {
    if (lines == null) {
      return new int[] {0, text.length()};
    }
    int line = lines.line(index);
    return new int[] {lines.start(line), lines.end(line)};
  }

  /** The grapheme boundary at or before {@code index}. */
  private int snap(int index) {
    index = Math.clamp(index, 0, text.length());
    int[] part = around(index);
    if (part[0] == 0 && part[1] == text.length()) {
      return snap(text, index);
    }
    return part[0] + snap(text.substring(part[0], part[1]), index - part[0]);
  }

  /** The grapheme boundary at or after {@code index}. */
  private int snapUp(int index) {
    int at = snap(index);
    return at == index ? at : next(at);
  }

  /** The grapheme boundary before {@code index}, or 0; a line feed is one. */
  private int previous(int index) {
    if (lines == null || index <= 0) {
      return previous(text, index);
    }
    int[] part = around(index);
    if (index == part[0]) {
      return index - 1; // over the line feed before the line
    }
    return part[0] + previous(text.substring(part[0], part[1]), index - part[0]);
  }

  /** The grapheme boundary after {@code index}, or the end; a line feed is one. */
  private int next(int index) {
    if (lines == null || index >= text.length()) {
      return next(text, index);
    }
    int[] part = around(index);
    if (index == part[1]) {
      return index + 1; // over the line feed after the line
    }
    return part[0] + next(text.substring(part[0], part[1]), index - part[0]);
  }

  private static BreakIterator characters(String text) {
    BreakIterator chars = BreakIterator.getCharacterInstance(Locale.ROOT);
    chars.setText(text);
    return chars;
  }

  /** The grapheme boundary at or before {@code index}. */
  static int snap(String text, int index) {
    index = Math.clamp(index, 0, text.length());
    BreakIterator chars = characters(text);
    return chars.isBoundary(index) ? index : chars.preceding(index);
  }

  /** The grapheme boundary before {@code index}, or 0. */
  static int previous(String text, int index) {
    return index <= 0 ? 0 : characters(text).preceding(index);
  }

  /** The grapheme boundary after {@code index}, or the end. */
  static int next(String text, int index) {
    return index >= text.length() ? text.length() : characters(text).following(index);
  }

  private static BreakIterator words(String text) {
    BreakIterator words = BreakIterator.getWordInstance(Locale.ROOT);
    words.setText(text);
    return words;
  }

  /** Whether a segment between two word boundaries is a word: it has a letter or a digit. */
  private static boolean isWord(String text, int from, int to) {
    return text.substring(from, to).codePoints().anyMatch(Character::isLetterOrDigit);
  }

  /** Where Ctrl+Left goes: the start of the word the caret is in or after. */
  static int wordLeft(String text, int index) {
    BreakIterator words = words(text);
    int at = index;
    while (at > 0) {
      int start = words.preceding(at);
      if (isWord(text, start, words.following(start))) {
        return start;
      }
      at = start;
    }
    return 0;
  }

  /**
   * Where Ctrl+Right goes: the end of the word the caret is in or before ({@code ends}), or the
   * start of the next word.
   */
  static int wordRight(String text, int index, boolean ends) {
    BreakIterator words = words(text);
    int at = index;
    if (!ends && at < text.length()) {
      int end = words.following(at);
      if (isWord(text, words.isBoundary(at) ? at : words.preceding(at), end)) {
        at = end; // out of the word the caret is in
      }
    }
    while (at < text.length()) {
      int start = words.isBoundary(at) ? at : words.preceding(at);
      int end = words.following(at);
      if (isWord(text, start, end)) {
        return ends ? end : at;
      }
      at = end;
    }
    return text.length();
  }

  /** The segment between word boundaries that the grapheme at {@code index} is in. */
  static int[] word(String text, int index) {
    if (text.isEmpty()) {
      return new int[] {0, 0};
    }
    index = Math.clamp(index, 0, text.length() - 1);
    BreakIterator words = words(text);
    int start = words.isBoundary(index) ? index : words.preceding(index);
    return new int[] {start, words.following(index)};
  }

  // ---- The words of code (El#codeWords): runs of letters, digits and '_'

  /**
   * Whether a code point belongs to a word of code; a mark is one where nothing comes before it,
   * and otherwise goes with the code point before it, whatever that is.
   */
  static boolean codeWordChar(int c) {
    if (c == '_' || Character.isLetterOrDigit(c)) {
      return true;
    }
    int type = Character.getType(c);
    return type == Character.NON_SPACING_MARK
        || type == Character.COMBINING_SPACING_MARK
        || type == Character.ENCLOSING_MARK;
  }

  /**
   * Whether the code point at {@code index} extends the grapheme before it, as far as the words of
   * code need to know: a mark, a joiner, a variation selector, an emoji modifier or tag, or what
   * follows a zero width joiner. A scan by code points thus never splits a letter from its mark
   * or an emoji from its modifier, and needs no {@link BreakIterator}.
   */
  private static boolean extending(String text, int index) {
    if (index > 0 && text.codePointBefore(index) == 0x200D) {
      return true;
    }
    int c = text.codePointAt(index);
    int type = Character.getType(c);
    return type == Character.NON_SPACING_MARK
        || type == Character.COMBINING_SPACING_MARK
        || type == Character.ENCLOSING_MARK
        || c == 0x200C
        || c == 0x200D
        || c >= 0x1F3FB && c <= 0x1F3FF
        || c >= 0xE0020 && c <= 0xE007F;
  }

  /** The start of the cluster that the code point at {@code index} belongs to. */
  private static int clusterStart(String text, int index) {
    if (index > 0
        && index < text.length()
        && Character.isLowSurrogate(text.charAt(index))
        && Character.isHighSurrogate(text.charAt(index - 1))) {
      index--;
    }
    while (index > 0 && index < text.length() && extending(text, index)) {
      index -= Character.charCount(text.codePointBefore(index));
    }
    return index;
  }

  /** The end of the cluster that starts at {@code index}. */
  private static int clusterEnd(String text, int index) {
    index += Character.charCount(text.codePointAt(index));
    while (index < text.length() && extending(text, index)) {
      index += Character.charCount(text.codePointAt(index));
    }
    return index;
  }

  /** Whether a cluster that starts with {@code c} is a space or a tab. */
  private static boolean blank(int c) {
    return c == ' ' || c == '\t';
  }

  /** The position before the run of clusters from {@code index} back that start with {@code kind}. */
  private static int backOver(String text, int index, IntPredicate kind) {
    while (index > 0) {
      int start = clusterStart(text, index - 1);
      if (!kind.test(text.codePointAt(start))) {
        break;
      }
      index = start;
    }
    return index;
  }

  /** The position after the run of clusters from {@code index} on that start with {@code kind}. */
  private static int forwardOver(String text, int index, IntPredicate kind) {
    while (index < text.length()) {
      if (!kind.test(text.codePointAt(index))) {
        break;
      }
      index = clusterEnd(text, index);
    }
    return index;
  }

  /** {@link #wordLeft} with the words of code. */
  static int codeWordLeft(String text, int index) {
    IntPredicate word = Editor::codeWordChar;
    return backOver(text, backOver(text, Math.clamp(index, 0, text.length()), word.negate()), word);
  }

  /** {@link #wordRight} with the words of code. */
  static int codeWordRight(String text, int index, boolean ends) {
    int at = Math.clamp(index, 0, text.length());
    IntPredicate word = Editor::codeWordChar;
    return ends
        ? forwardOver(text, forwardOver(text, at, word.negate()), word)
        : forwardOver(text, forwardOver(text, at, word), word.negate());
  }

  /**
   * {@link #word} with the words of code: the run of word characters at {@code index}, a run of
   * spaces and tabs, or else the one character there, such as a point or a line feed.
   */
  static int[] codeWord(String text, int index) {
    if (text.isEmpty()) {
      return new int[] {0, 0};
    }
    index = clusterStart(text, Math.clamp(index, 0, text.length() - 1));
    int c = text.codePointAt(index);
    IntPredicate kind = codeWordChar(c) ? Editor::codeWordChar : blank(c) ? Editor::blank : null;
    if (kind != null) {
      return new int[] {backOver(text, index, kind), forwardOver(text, index, kind)};
    }
    return new int[] {index, clusterEnd(text, index)};
  }

  /**
   * Maps a position from one string to another that differs from it in one place: positions in
   * the common start stay, positions in the common end keep their distance from the end, and
   * positions in between go to the end of the change, at the most.
   */
  static int map(int index, String from, String to) {
    int prefix = 0;
    int shorter = Math.min(from.length(), to.length());
    while (prefix < shorter && from.charAt(prefix) == to.charAt(prefix)) {
      prefix++;
    }
    int suffix = 0;
    while (suffix < shorter - prefix
        && from.charAt(from.length() - 1 - suffix) == to.charAt(to.length() - 1 - suffix)) {
      suffix++;
    }
    if (index <= prefix) {
      return Math.min(index, to.length());
    }
    if (index >= from.length() - suffix) {
      return Math.max(to.length() - (from.length() - index), 0);
    }
    return Math.min(index, to.length() - suffix);
  }
}
