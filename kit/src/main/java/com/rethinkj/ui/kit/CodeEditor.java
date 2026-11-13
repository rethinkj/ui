package com.rethinkj.ui.kit;

import com.rethinkj.ui.El;
import com.rethinkj.ui.FieldState;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Style;
import java.util.function.Consumer;

/**
 * An editor of code, such as the SQL of a query: a {@link TextField#codeArea} with line numbers in
 * a gutter, the caret's line highlighted, the bracket at the caret and its partner marked, the
 * tokens coloured by a highlighter ({@link Sql}) and an error underlined.
 *
 * <pre>{@code
 * codeEditor(t, s.sql, s.editor, Sql.of(t), v -> s.sql = v).key("query").lines(12).wFull()
 * }</pre>
 */
public final class CodeEditor {

  private CodeEditor() {}

  /** The most characters bracket matching looks through from the caret, each way. */
  static final int BRACKET_REACH = 100_000;

  private static final String BRACKETS = "([{)]}";

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
    private int caret;
    private int anchor;
    private int errorFrom;
    private int errorTo;

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
  public static El codeEditor(Theme t, String value, State state, Highlighter highlighter,
      Consumer<String> onChange) {
    Theme.Code code = t.code();
    int[] pair = matching(value, state.caret);
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
          if (state.errorTo > start && state.errorFrom <= end) {
            out.underline(state.errorFrom - start, state.errorTo - start, t.status().danger());
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
    return editor;
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
