package com.rethinkj.ui;

import java.text.BreakIterator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The editing state of one {@link El#editable} element, which {@link App} keeps by the element's
 * identity across rebuilds, as it keeps scroll offsets: the caret and the anchor of the selection,
 * the horizontal scroll, the undo history and the composition of an input method in progress.
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
 * {@link BreakIterator#getWordInstance}: a segment with a letter or a digit is a word.
 *
 * <p>The editing works on strings and does not assume one line, except where a single-line field
 * turns line breaks into spaces and Home and End go to the ends of the text.
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
  static final boolean WORD_ENDS =
      !System.getProperty("os.name", "").startsWith("Windows");

  /** Edits that coalesce into one step of the history while they follow each other. */
  private enum Run {
    TYPING,
    DELETING
  }

  private record State(String text, int caret, int anchor) {}

  private El el; // in the current tree, for its handler and settings
  private String text;
  private int caret;
  private int anchor;
  // The state before the first edit that no tree has answered yet, and what the last one proposed.
  private State before;
  private String proposed;
  private final ArrayDeque<State> undo = new ArrayDeque<>();
  private final ArrayDeque<State> redo = new ArrayDeque<>();
  private Run run;
  private String composition = "";
  private int compositionCursor;
  // What the pointer selects by while it is down: 1 graphemes, 2 words, 3 everything; and the range
  // the press selected, which a drag by words keeps.
  private int unit = 1;
  private int pressFrom;
  private int pressTo;
  private float scroll; // in logical pixels
  private boolean changed;

  /** A new editor with the caret at the end of the element's string. */
  Editor(El el) {
    this.el = el;
    this.text = el.text().string();
    this.caret = text.length();
    this.anchor = caret;
  }

  // ---- The element

  /**
   * Takes the element of a new tree: its handler and settings, and its string, which is the app's
   * answer to the edits since the last tree. Then shows the composition in its text.
   */
  void attach(El el) {
    this.el = el;
    answer(el.text().string());
    el.text().compose(composition.isEmpty() ? null : shown());
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
      if (value.equals(before.text) && !value.equals(proposed)) {
        // Refused: as if the edits had not happened.
        caret = before.caret;
        anchor = before.anchor;
        if (!undo.isEmpty() && undo.peek().text.equals(value)) {
          undo.pop();
        }
        run = null;
      } else if (!value.equals(proposed)) {
        caret = map(caret, proposed, value);
        anchor = map(anchor, proposed, value);
        run = null;
      }
      before = null;
      proposed = null;
    } else if (!value.equals(text)) {
      // Changed by the app on its own: the history no longer leads to it.
      caret = map(caret, text, value);
      anchor = map(anchor, text, value);
      undo.clear();
      redo.clear();
      run = null;
    }
    text = value;
    caret = snap(text, caret);
    anchor = snap(text, anchor);
  }

  /** Whether the element takes edits: it has a handler and is not read-only. */
  boolean editable() {
    return el.onEdit() != null && !el.isReadOnly();
  }

  /** The string with the composition in place of the selection. */
  private String shown() {
    return text.substring(0, start()) + composition + text.substring(end());
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

  /** Whether something changed since the last call; a change keeps the caret on for a while. */
  boolean takeChanged() {
    boolean was = changed;
    changed = false;
    return was;
  }

  /** The element lost the focus: a composition ends without being committed. */
  void blur() {
    composition = "";
    run = null;
    unit = 1;
    changed = true;
  }

  // ---- Keys

  /**
   * Acts on a key for the focused element.
   *
   * @return whether the key was the field's: its own keys, and keys that type, whose text comes
   *     separately; Enter, Escape, Tab and shortcuts with Control, Alt or Meta that the field does
   *     not know go on to the handlers
   */
  boolean key(Key key, Clipboard clipboard) {
    String name = key.name();
    int modifiers = key.modifiers();
    boolean shift = (modifiers & Key.SHIFT) != 0;
    int other = modifiers & ~Key.SHIFT;
    if (!composition.isEmpty()) {
      return !name.equals("Tab"); // the input method has the keys while it composes
    }
    boolean word = other == Key.CONTROL;
    boolean plain = other == 0 || word;
    switch (name) {
      case "ArrowLeft" -> {
        if (!plain) {
          return false;
        }
        int to = word ? wordLeft(text, caret)
            : caret != anchor && !shift ? start() : previous(text, caret);
        moveTo(to, shift);
        return true;
      }
      case "ArrowRight" -> {
        if (!plain) {
          return false;
        }
        int to = word ? wordRight(text, caret, WORD_ENDS)
            : caret != anchor && !shift ? end() : next(text, caret);
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
        delete(word ? wordLeft(text, caret) : previous(text, caret));
        return true;
      }
      case "Delete" -> {
        if (modifiers == Key.SHIFT) {
          cut(clipboard);
        } else if (other == 0 || word && !shift) {
          delete(word ? wordRight(text, caret, WORD_ENDS) : next(text, caret));
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

  private int lineStart(int at) {
    return 0; // shortcut: a single-line field; a multi-line one goes to the start of the line
  }

  private int lineEnd(int at) {
    return text.length();
  }

  // ---- Edits

  /** Text typed, or committed by an input method: it replaces the selection. */
  boolean type(String typed) {
    boolean composed = !composition.isEmpty();
    composition = "";
    changed |= composed;
    if (!editable()) {
      return composed;
    }
    return replace(singleLine(typed), Run.TYPING) || composed;
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
    composition = singleLine(composing);
    compositionCursor = cursor < 0 ? composition.length() : Math.min(cursor, composition.length());
    run = null;
    changed = true;
    return true;
  }

  void selectAll() {
    anchor = 0;
    caret = text.length();
    run = null;
    changed = true;
  }

  void copy(Clipboard clipboard) {
    if (caret != anchor) {
      clipboard.write(text.substring(start(), end()));
    }
  }

  void cut(Clipboard clipboard) {
    if (caret != anchor && editable()) {
      copy(clipboard);
      replace("", null);
    }
  }

  void paste(Clipboard clipboard) {
    if (editable()) {
      replace(singleLine(clipboard.read()), null);
    }
  }

  void undo() {
    if (editable() && !undo.isEmpty()) {
      push(redo, new State(text, caret, anchor));
      restore(undo.pop());
    }
  }

  void redo() {
    if (editable() && !redo.isEmpty()) {
      push(undo, new State(text, caret, anchor));
      restore(redo.pop());
    }
  }

  private static void push(ArrayDeque<State> history, State state) {
    history.push(state);
    while (history.size() > HISTORY) {
      history.removeLast();
    }
  }

  private void restore(State state) {
    propose(state.text, state.caret, state.anchor);
    run = null;
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
    String next = text.substring(0, from) + text.substring(Math.max(to, caret));
    edit(next, from, from, Run.DELETING, run == Run.DELETING);
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
    String next = text.substring(0, from) + inserted + text.substring(to);
    int at = from + inserted.length();
    edit(next, at, at, kind, kind != null && kind == run && from == to);
    return true;
  }

  private void edit(String next, int newCaret, int newAnchor, Run kind, boolean coalesce) {
    if (!coalesce) {
      push(undo, new State(text, caret, anchor));
    }
    redo.clear();
    propose(next, newCaret, newAnchor);
    run = kind;
  }

  /** Hands a new string to the app, which answers with the next tree. */
  private void propose(String next, int newCaret, int newAnchor) {
    if (before == null) {
      before = new State(text, caret, anchor);
    }
    proposed = next;
    text = next;
    caret = newCaret;
    anchor = newAnchor;
    changed = true;
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

  // ---- Moves

  private void moveTo(int to, boolean extend) {
    caret = to;
    if (!extend) {
      anchor = to;
    }
    run = null;
    changed = true;
  }

  // ---- The pointer

  /**
   * A press: places the caret at {@code boundary}, or selects the word under {@code hit} for a
   * double click and everything for a triple one; with Shift it moves the caret and keeps the
   * anchor.
   */
  void press(int boundary, int hit, int clicks, boolean extend) {
    composition = "";
    run = null;
    changed = true;
    if (extend) {
      unit = 1;
      caret = boundary;
      return;
    }
    unit = Math.clamp(clicks, 1, 3);
    int[] range = switch (unit) {
      case 1 -> new int[] {boundary, boundary};
      case 2 -> word(text, hit);
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
    switch (unit) {
      case 1 -> caret = boundary;
      case 2 -> {
        int[] range = word(text, hit);
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
    return moved;
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

  /** The caret's offset from the left of the content box, in logical pixels, as drawn. */
  float caretOffset() {
    Text t = el.text();
    int shownCaret = composition.isEmpty() ? caret : start() + compositionCursor;
    return (t.x(shownCaret) - t.scroll()) / t.scale();
  }

  /** Whole physical pixels, at least one. */
  private static float caretWidth(float scale) {
    return Math.max(1, Math.round(scale));
  }

  // ---- Boundaries

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
