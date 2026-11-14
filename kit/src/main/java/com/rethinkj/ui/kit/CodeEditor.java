package com.rethinkj.ui.kit;

import com.rethinkj.ui.App;
import com.rethinkj.ui.CodeWords;
import com.rethinkj.ui.El;
import com.rethinkj.ui.FieldState;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Style;
import java.util.function.Consumer;

/**
 * An editor of code, such as the SQL of a query: a {@link TextField#codeArea} with line numbers in
 * a gutter, the caret's line highlighted, the bracket at the caret and its partner marked, the
 * other occurrences of the word at the caret marked, the tokens coloured by a highlighter ({@link
 * Sql}) and an error underlined. Given the app, it also finds and replaces ({@link Find}), goes to
 * a line, and has the commands of {@link Command}.
 *
 * <pre>{@code
 * codeEditor(t, app, s.sql, s.editor, Sql.of(t), v -> s.sql = v).key("query").lines(12).wFull()
 * }</pre>
 *
 * <p>Its keys are the field's {@link El#onKey} handler: a view that adds one of its own keeps them
 * with {@code editor.onKey(mine.or(editor.onKey()))}.
 */
public final class CodeEditor {

  private CodeEditor() {}

  /** The most characters bracket matching looks through from the caret, each way. */
  static final int BRACKET_REACH = 100_000;

  private static final String BRACKETS = "([{)]}";

  /**
   * A language whose comments run to the end of a line, which the highlighter tells the editor
   * for {@link Command#TOGGLE_COMMENT}: {@link Sql} says {@code --}.
   */
  public interface LineComments {
    /** What starts a comment to the end of the line, such as {@code --} or {@code //}. */
    String lineComment();
  }

  /**
   * What an editor does besides typing, each as one step of the undo history where it edits, with
   * its keys; {@link #run} runs one, as an Edit menu does.
   */
  public enum Command {
    /**
     * Control+D: selects the word at the caret, then the next occurrence of the selection, round
     * to the start.
     */
    SELECT_NEXT,
    /** Control+Shift+D: puts a copy of the caret's line, or the selected lines, below them. */
    DUPLICATE_LINES,
    /** Alt+Up: moves the caret's line, or the selected lines, up by one. */
    MOVE_LINES_UP,
    /** Alt+Down: moves them down by one. */
    MOVE_LINES_DOWN,
    /** Control+Shift+K: deletes them. */
    DELETE_LINES,
    /**
     * Control+/ (on a German keyboard Control+Shift+7 or Control+#, and Control with the numpad's
     * /): comments the lines out with the language's {@link LineComments}, or back in when each
     * line that is not blank is a comment.
     */
    TOGGLE_COMMENT
  }

  /** The command of a key, or null. */
  static Command command(Key k) {
    int mods = k.modifiers();
    int controlShift = Key.CONTROL | Key.SHIFT;
    return switch (k.name()) {
      case "D" ->
        mods == Key.CONTROL
            ? Command.SELECT_NEXT
            : mods == controlShift ? Command.DUPLICATE_LINES : null;
      case "K" -> mods == controlShift ? Command.DELETE_LINES : null;
      case "ArrowUp" -> mods == Key.ALT ? Command.MOVE_LINES_UP : null;
      case "ArrowDown" -> mods == Key.ALT ? Command.MOVE_LINES_DOWN : null;
      case "/", "#", "NumpadDivide" -> mods == Key.CONTROL ? Command.TOGGLE_COMMENT : null;
      case "7" -> mods == controlShift ? Command.TOGGLE_COMMENT : null;
      default -> null;
    };
  }

  /**
   * What the app keeps of an editor in its state, as a {@link Menu.State}: where the caret and the
   * selection are, which the editor updates as they change ({@link El#onSelect}), and an error
   * to underline, which the app sets, such as where the database said a query went wrong. It also
   * holds the field's editing state ({@link FieldState}), so that an editor that leaves the tree,
   * as the content of a tab that is not active does, comes back with its caret, selection,
   * scroll and undo history: keep one per editor, such as one per tab.
   */
  public static final class State {
    private final FieldState field = new FieldState();
    private final Find.State find = new Find.State();
    private int caret;
    private int anchor;
    private int errorFrom;
    private int errorTo;
    private boolean occurrences = true;
    private boolean findBar = true;
    // Control+D: the selection it made, and whether it went by whole words from the caret.
    private int nextFrom = -1;
    private int nextTo = -1;
    private boolean nextWords;
    // The editor of the last build, and what it was built with, for the commands.
    private El editor;
    private String lineComment;

    /**
     * Whether the editor has its find bar and the keys that open it (Control+F, Control+H, F3,
     * Control+G); on unless set, given the app.
     */
    public State findBar(boolean on) {
      findBar = on;
      return this;
    }

    /** The state of the editor's find bar. */
    public Find.State find() {
      return find;
    }

    /**
     * Whether the other occurrences of the word at the caret are marked while nothing or a whole
     * word is selected; on unless set.
     */
    public State occurrences(boolean on) {
      occurrences = on;
      return this;
    }

    /** The caret, as an index into the string. */
    public int caret() {
      return caret;
    }

    /** The other end of the selection; the caret's position when nothing is selected. */
    public int anchor() {
      return anchor;
    }

    /** Underlines characters {@code from} to {@code to} of the string in the danger colour. */
    public State error(int from, int to) {
      errorFrom = from;
      errorTo = to;
      return this;
    }

    /** Underlines nothing. */
    public State noError() {
      return error(0, 0);
    }
  }

  /**
   * The editor: the code area of the theme, whose gutter shows the number of each line, muted but
   * on the caret's line, and whose caret's line has {@code code().line()} behind it, and the
   * bracket before or after the caret and the one that matches it {@code code().bracket()}.
   *
   * @param value the code, from the app's state
   * @param state the app's state of this editor
   * @param highlighter colours the tokens, or null for none; it is also the key the field keeps
   *     its colours by ({@link El#highlight}), so pass the same object again, or one equal to it,
   *     such as {@link Sql#of} of the same theme
   * @param onChange receives the new string after each edit; null disables the editor
   */
  public static El codeEditor(
      Theme t, String value, State state, Highlighter highlighter, Consumer<String> onChange) {
    return codeEditor(t, null, value, state, highlighter, onChange);
  }

  /**
   * The editor with its find bar ({@link Find}: Control+F, Control+H, F3, Control+G) and its
   * {@link Command commands}, which edit through the app ({@link App#edit}).
   *
   * @param app the app the editor is in; null for an editor without find and commands
   */
  public static El codeEditor(
      Theme t,
      App<?> app,
      String value,
      State state,
      Highlighter highlighter,
      Consumer<String> onChange) {
    Theme.Code code = t.code();
    int[] pair = matching(value, state.caret);
    String word =
        state.occurrences && !state.find.isOpen() ? word(value, state.caret, state.anchor) : null;
    // Where the word at the caret starts, which is not marked: only its other occurrences are.
    int own = word == null
        ? -1
        : state.caret == state.anchor
            ? CodeWords.start(value, Math.min(state.caret, value.length()))
            : Math.min(state.caret, state.anchor);
    Theme.TextStyle mono = t.type().mono();
    El editor = TextField.codeArea(t, value, null, onChange)
        .fieldState(state.field)
        .onSelect((caret, anchor) -> {
          state.caret = caret;
          state.anchor = anchor;
        })
        .decorate((line, start, text, out) -> {
          int end = start + text.length();
          if (state.caret >= start && state.caret <= end) {
            out.lineBackground(code.line());
          }
          for (int at : pair) {
            out.background(at - start, at - start + 1, code.bracket());
          }
          if (word != null) {
            int n = word.length();
            for (int at = text.indexOf(word); at >= 0; at = text.indexOf(word, at + n)) {
              if (start + at != own && whole(text, at, at + n)) {
                out.background(at, at + n, code.occurrence());
              }
            }
          }
          if (state.errorTo > start && state.errorFrom <= end) {
            out.underline(
                state.errorFrom - start, state.errorTo - start, t.status().danger());
          }
        })
        .gutter((line, caret) -> El.text(String.valueOf(line + 1))
            .font(mono.font().get())
            .fontSize(mono.size())
            .color(caret ? t.text().primary() : t.text().muted())
            .textAlign(Style.Align.END)
            .padding(0, t.space().m(), 0, 0));
    if (highlighter != null) {
      editor.highlight(highlighter, highlighter);
    }
    if (app == null) {
      return editor;
    }
    state.editor = editor;
    state.lineComment = highlighter instanceof LineComments c ? c.lineComment() : null;
    editor.onKey(k -> {
      Command command = command(k);
      if (command == null) {
        return false;
      }
      run(app, state, command);
      return true;
    });
    return state.findBar ? Find.find(t, app, editor, state.find) : editor;
  }

  /** The word at the caret, if nothing or exactly a word is selected; else null. */
  static String word(String text, int caret, int anchor) {
    int s = Math.clamp(Math.min(caret, anchor), 0, text.length());
    int e = Math.clamp(Math.max(caret, anchor), 0, text.length());
    if (s == e) {
      s = CodeWords.start(text, s);
      e = CodeWords.end(text, e);
    }
    if (s == e || !whole(text, s, e)) {
      return null;
    }
    for (int i = s; i < e; i = text.offsetByCodePoints(i, 1)) {
      if (!CodeWords.isWordChar(text.codePointAt(i))) {
        return null;
      }
    }
    return text.substring(s, e);
  }

  /** Whether no code-word character lies right before {@code from} or after {@code to}. */
  static boolean whole(String text, int from, int to) {
    return (from == 0 || !CodeWords.isWordChar(text.codePointBefore(from)))
        && (to == text.length() || !CodeWords.isWordChar(text.codePointAt(to)));
  }

  /**
   * Runs a command on the editor that the last build of the view made with this state and an
   * app; nothing before that.
   */
  public static void run(App<?> app, State state, Command command) {
    El editor = state.editor;
    if (editor == null) {
      return;
    }
    String text = editor.text().string();
    int caret = Math.min(state.caret, text.length());
    int anchor = Math.min(state.anchor, text.length());
    if (command == Command.SELECT_NEXT) {
      selectNext(app, state, editor, text, caret, anchor);
      return;
    }
    if (editor.onEdit() == null || editor.isReadOnly()) {
      return;
    }
    LineEdits.Edit edit = switch (command) {
      case DUPLICATE_LINES -> LineEdits.duplicate(text, caret, anchor);
      case MOVE_LINES_UP -> LineEdits.move(text, caret, anchor, -1);
      case MOVE_LINES_DOWN -> LineEdits.move(text, caret, anchor, 1);
      case DELETE_LINES -> LineEdits.delete(text, caret, anchor);
      case TOGGLE_COMMENT ->
        state.lineComment == null
            ? null
            : LineEdits.comment(text, caret, anchor, state.lineComment);
      default -> null;
    };
    if (edit == null) {
      return;
    }
    app.edit(editor, edit.from(), edit.to(), edit.text(), edit.caret());
    app.select(editor, edit.caret(), edit.anchor());
    state.caret = edit.caret();
    state.anchor = edit.anchor();
  }

  private static void selectNext(
      App<?> app, State state, El editor, String text, int caret, int anchor) {
    int s = Math.min(caret, anchor);
    int e = Math.max(caret, anchor);
    int[] next;
    if (s == e) {
      next = new int[] {CodeWords.start(text, s), CodeWords.end(text, s)};
      if (next[0] == next[1]) {
        return;
      }
      state.nextWords = true;
    } else {
      boolean ours = s == state.nextFrom && e == state.nextTo;
      state.nextWords = ours && state.nextWords;
      Search.Query query = new Search.Query(text.substring(s, e), true, state.nextWords, false);
      try {
        next = Search.next(
            query.compile(), text, 0, text.length(), e, Search.deadline(Find.SYNC_MILLIS));
      } catch (Search.Stopped stopped) {
        return;
      }
      if (next == null) {
        return;
      }
    }
    state.nextFrom = next[0];
    state.nextTo = next[1];
    app.select(editor, next[1], next[0]);
    state.caret = next[1];
    state.anchor = next[0];
  }

  /**
   * The bracket just before the caret, or else just after it, and the one that matches it, as
   * indexes into the text; empty where there is none, and the one bracket where its partner is
   * missing or more than {@link #BRACKET_REACH} characters away.
   */
  static int[] matching(String text, int caret) {
    // shortcut: brackets inside strings and comments count too; take the highlighter's tokens
    // when that shows in practice.
    for (int at : new int[] {caret - 1, caret}) {
      if (at < 0 || at >= text.length()) {
        continue;
      }
      int kind = BRACKETS.indexOf(text.charAt(at));
      if (kind < 0) {
        continue;
      }
      char own = text.charAt(at);
      char other = BRACKETS.charAt((kind + 3) % 6);
      int step = kind < 3 ? 1 : -1;
      int depth = 0;
      for (int i = at, n = 0; i >= 0 && i < text.length() && n < BRACKET_REACH; i += step, n++) {
        char c = text.charAt(i);
        if (c == own) {
          depth++;
        } else if (c == other && --depth == 0) {
          return new int[] {at, i};
        }
      }
      return new int[] {at};
    }
    return new int[0];
  }
}
