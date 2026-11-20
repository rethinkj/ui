package com.rethinkj.ui.kit;

import com.rethinkj.ui.App;
import com.rethinkj.ui.CodeWords;
import com.rethinkj.ui.El;
import com.rethinkj.ui.FieldState;
import com.rethinkj.ui.Formatted;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Spans;
import com.rethinkj.ui.Style;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * An editor of code, such as the SQL of a query: a {@link TextField#codeArea} with line numbers and
 * markers in a gutter on a rail of its own, the caret's line highlighted, the bracket at the caret
 * and its partner marked, the other occurrences of the word at the caret marked, guides in the
 * indentation, the tokens colored by a highlighter ({@link Sql}) and the app's diagnostics
 * underlined. Given the app, it also finds and replaces ({@link Find}), goes to a line, goes from
 * diagnostic to diagnostic with F8 and Shift+F8, and has the commands of {@link Command}.
 *
 * <pre>{@code
 * codeEditor(app, state.sql, state.editor, Sql.of(), value -> state.sql = value)
 *     .key("query").lines(12).wFull()
 * }</pre>
 *
 * <p>Its keys are the field's {@link El#onKey} handler and its marks the field's {@link
 * El#decorate decorator}: a view adds its own and keeps the editor's with {@link El#onKeyFirst} and
 * {@link El#alsoDecorate}. The message that F8 shows is the field's {@link El#whenFocusWithin}
 * look, which a look of the view's own replaces.
 */
public final class CodeEditor {

  private CodeEditor() {}

  /** The columns of a step of indentation, at which guides stand: four, as the field's tabs. */
  private static final int INDENT = 4;

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
     * /): comments the lines out with the highlighter's {@link Highlighter#lineComment}, or back
     * in when each line that is not blank is a comment.
     */
    TOGGLE_COMMENT,
    /**
     * Control+Alt+L: formats the whole text with the highlighter's {@link Highlighter#formatter},
     * the caret and the selection at the same characters that are not white space. A text it
     * cannot format stays as it is: the problem shows as an error diagnostic beside the app's
     * ({@link State#diagnostics}) until the text changes, with its message above the caret, which
     * goes to the problem's start, as F8 does. Nothing for a text that is blank or already
     * formatted. On Windows, AltGr comes as Control+Alt: the characters it makes are typed, and on
     * a layout with one on AltGr+L, such as Polish, that character is typed into the formatted
     * text.
     */
    FORMAT
  }

  /** How much a {@link Diagnostic} matters. */
  public enum Severity {
    /** Something that is wrong, such as a column the database does not know: the danger color. */
    ERROR,
    /** Something to think twice about: the warning color. */
    WARNING
  }

  /**
   * Something the app says about characters {@code from} to {@code to} of the string, such as
   * where the database said a query went wrong: a straight line under them in the severity's
   * status color, and a marker at the start of the line of {@code from} with the message as its
   * tooltip.
   */
  public record Diagnostic(int from, int to, Severity severity, String message) {
    public Diagnostic {
      Objects.requireNonNull(severity, "severity");
      Objects.requireNonNull(message, "message");
    }
  }

  /**
   * A marker in the gutter at the line of position {@code at} of the string: {@code symbol} in
   * {@code tone}'s color, with {@code label} as its tooltip, which a press on it runs {@code
   * onPress} for, such as {@link Symbol#PLAY} "Run statement" at the first line of a statement.
   *
   * @param onPress what a press runs, or null for a marker that only shows
   */
  public record Marker(int at, Symbol symbol, Icon.Variant tone, String label, Runnable onPress) {
    public Marker {
      Objects.requireNonNull(symbol, "symbol");
      Objects.requireNonNull(tone, "tone");
      Objects.requireNonNull(label, "label");
    }
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
      case "L" -> mods == (Key.CONTROL | Key.ALT) ? Command.FORMAT : null;
      default -> null;
    };
  }

  /**
   * What the app keeps of an editor in its state, as a {@link Menu.State}: where the caret and the
   * selection are, which the editor updates as they change ({@link El#onSelect}), and a marked
   * block, markers and diagnostics, which the app sets. It also
   * holds the field's editing state ({@link FieldState}), so that an editor that leaves the tree,
   * as the content of a tab that is not active does, comes back with its caret, selection,
   * scroll and undo history: keep one per editor, such as one per tab.
   *
   * <p>Positions, those it reports and those the app sets, are indexes into the string as the
   * editor shows it, in which a carriage return with or without a line feed after it and the
   * separators U+2028 and U+2029 are each one line feed ({@link El#multiline}).
   */
  public static final class State {
    private final FieldState field = new FieldState();
    private final Find.State find = new Find.State();
    private int caret;
    private int anchor;
    private List<Marker> markers = List.of();
    private List<Diagnostic> diagnostics = List.of();
    private String diagnosed; // the text the diagnostics are about
    private String checked; // the value last compared with it, by identity, and whether it was it
    private boolean current;
    private int blockFrom;
    private int blockTo;
    private boolean occurrences = true;
    private boolean findBar = true;
    private boolean readOnly;
    // Control+D: the selection it made, and whether it went by whole words from the caret.
    private int nextFrom = -1;
    private int nextTo = -1;
    private boolean nextWords;
    // The editor of the last build, and what it was built with, for the commands.
    private El editor;
    private String value;
    private String lineComment;
    private Function<String, Formatted> formatter;
    // FORMAT's problem, shown while the app's string is the one it could not format.
    private Formatted.Problem problem;
    private String problemOf;
    // F8: where the diagnostics start whose message shows at the caret; -1 while none does.
    private int shown = -1;

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

    /**
     * Whether the editor only shows its string, as a field made {@link El#readOnly}: it takes the
     * focus, and its text can be selected, copied and found, but not changed, so that it needs no
     * handler. Off unless set.
     */
    public State readOnly(boolean on) {
      readOnly = on;
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

    /**
     * Selects from {@code anchor} to {@code caret}, or puts the caret there when they are equal,
     * and scrolls it into view, at the editor's next build: also while the editor is not in the
     * tree, as in a tab that is not shown ({@link FieldState#select}).
     */
    public State select(int caret, int anchor) {
      field.select(caret, anchor);
      this.caret = caret;
      this.anchor = anchor;
      return this;
    }

    /**
     * Replaces the selection by {@code text}, or puts it in at the caret, with the caret after it,
     * as one step of the undo history, such as a snippet from a button beside the editor: right
     * after the handler that calls it, in the editor of the tree built from what the handler
     * changed ({@link FieldState#replaceSelection}); nothing while the editor is not in it.
     */
    public State replaceSelection(String text) {
      field.replaceSelection(text);
      return this;
    }

    /**
     * Replaces the selection by {@code text} as {@link #replaceSelection(String)} does, with the
     * caret at {@code caret} in {@code text}, such as between the parentheses of {@code "()"}
     * ({@link FieldState#replaceSelection(String, int)}).
     *
     * @throws IllegalArgumentException if {@code caret} is negative or beyond {@code text}
     */
    public State replaceSelection(String text, int caret) {
      field.replaceSelection(text, caret);
      return this;
    }

    /**
     * Replaces characters {@code from} to {@code to} of the string by {@code text}, with the caret
     * at {@code caret} in {@code text}, as {@link #replaceSelection(String, int)} does with the
     * selection: for text at a place the app computed, such as a line below the one with the
     * caret. The positions are taken as they are, and Control+Z brings back the selection from
     * before ({@link FieldState#replace}).
     *
     * @throws IllegalArgumentException if {@code from} is negative, {@code to} is before it, or
     *     {@code caret} is negative or beyond {@code text}
     */
    public State replace(int from, int to, String text, int caret) {
      field.replace(from, to, text, caret);
      return this;
    }

    /**
     * Marks the lines of characters {@code from} to {@code to} of the string on the gutter's rail,
     * tinted with {@code code().block()} beside a line in the accent's edge, such as the statement
     * that Control+Enter would run. {@code from == to} marks none.
     */
    public State block(int from, int to) {
      blockFrom = from;
      blockTo = to;
      return this;
    }

    /** Marks no block of lines. */
    public State noBlock() {
      return block(0, 0);
    }

    /**
     * Shows {@code markers} in the gutter, each at its position's line, in place of those before;
     * where several are on one line, the first, and a diagnostic's marker before them all.
     */
    public State markers(List<Marker> markers) {
      this.markers = List.copyOf(markers);
      return this;
    }

    /**
     * Shows {@code diagnostics} about {@code text}, in place of those before, while the editor's
     * value is {@code text}: after an edit they go, and they come back with an undo to it. Each
     * is underlined in its severity's status color, and each line where one starts has a marker,
     * of the worst severity among them, whose tooltip is their messages. A tree dump shows the
     * lines under them on the editor as {@code underline <from>-<to>} while their lines are in
     * view, and the marker in the line's cell, with the messages of its errors as {@code error=}
     * and of its warnings as {@code warning=}.
     *
     * <p>Given the app, F8 puts the caret at the start of the next diagnostic after it, Shift+F8 at
     * the start of the one before, round at the ends, and the message of those that start there
     * shows above the caret, as their marker's tooltip, while the editor has the focus, until the
     * caret leaves them or Escape hides it; a tree dump shows it as an overlay of the editor, {@code role=tooltip} with the
     * message as the label. Without diagnostics the keys do nothing.
     */
    public State diagnostics(String text, List<Diagnostic> diagnostics) {
      this.diagnostics = List.copyOf(diagnostics);
      diagnosed = text;
      checked = null;
      return this;
    }

    /** Shows no diagnostics. */
    public State noDiagnostics() {
      return diagnostics(null, List.of());
    }

    /**
     * The diagnostics that show with {@code value}: those about it, and the problem that kept it
     * from formatting, in the view's texts.
     */
    private List<Diagnostic> diagnosticsOf(String value) {
      if (problem != null && !value.equals(problemOf)) { // at once for the same string object
        shown = shown == problem.from() ? -1 : shown; // its message goes, not on to another
        problem = null;
        problemOf = null;
      }
      List<Diagnostic> about = aboutValue(value);
      if (problem == null) {
        return about;
      }
      String message = Texts.CURRENT.get().formatProblem(problem.kind());
      return Stream.concat(
              about.stream(),
              Stream.of(new Diagnostic(problem.from(), problem.to(), Severity.ERROR, message)))
          .toList();
    }

    private List<Diagnostic> aboutValue(String value) {
      if (diagnostics.isEmpty()) {
        return List.of();
      }
      if (value != checked) { // the same string as before is compared once
        checked = value;
        current = value.equals(diagnosed);
      }
      return current ? diagnostics : List.of();
    }
  }

  /**
   * The editor: the code area of the theme, whose gutter lies on a rail of the band's color with a
   * hairline at its right ({@link Theme.Borders#divider}) and shows the number of each line,
   * muted but on the caret's line, after a column of markers ({@link State#markers}, {@link
   * State#diagnostics}); a {@link State#block marked block} tints the rail. The caret's line has
   * {@code code().line()} behind it, the bracket before or after the caret and the one that matches
   * it ({@link El#matchingBracket}, among those the highlighter marks) {@code code().bracket()},
   * and the white space that starts a line {@code code().guide()} at each step of four columns.
   *
   * @param value the code, from the app's state
   * @param state the app's state of this editor
   * @param highlighter colors the tokens, or null for none; it is also the key the field keeps
   *     its colors by ({@link El#highlight}), so pass the same object again, or one equal to it,
   *     such as {@link Sql#of} of the same theme
   * @param onChange receives the new string after each edit; null disables the editor, unless the
   *     state makes it {@link State#readOnly read-only}
   */
  public static El codeEditor(
      String value, State state, Highlighter highlighter, Consumer<String> onChange) {
    return codeEditor(null, value, state, highlighter, onChange);
  }

  /**
   * The editor with its find bar ({@link Find}: Control+F, Control+H, F3, Control+G), also in the
   * field's menu as Find and Replace, F8 and Shift+F8 to go to the next diagnostic and the one
   * before ({@link State#diagnostics}), and its {@link Command commands}, which edit through the
   * app ({@link App#edit}).
   *
   * @param app the app the editor is in; null for an editor without find and commands
   */
  public static El codeEditor(
      App<?> app, String value, State state, Highlighter highlighter, Consumer<String> onChange) {
    return codeEditor(app, value, null, state, highlighter, onChange);
  }

  /**
   * The editor with a placeholder, as that of {@link TextField#codeArea}.
   *
   * @param placeholder shown while the code is empty, and the label unless {@code .label(...)}
   *     sets another; null for none
   */
  public static El codeEditor(
      App<?> app,
      String value,
      String placeholder,
      State state,
      Highlighter highlighter,
      Consumer<String> onChange) {
    Theme theme = Theme.CURRENT.get();
    Theme.Code code = theme.code();
    String word =
        state.occurrences && !state.find.isOpen() ? word(value, state.caret, state.anchor) : null;
    // Where the word at the caret starts, which is not marked: only its other occurrences are.
    int own = word == null
        ? -1
        : state.caret == state.anchor
            ? CodeWords.start(value, Math.min(state.caret, value.length()))
            : Math.min(state.caret, state.anchor);
    List<Diagnostic> diagnostics = state.diagnosticsOf(value);
    El rail = El.el()
        .fill(theme.surface().band())
        .border(0, theme.size().stroke(), 0, 0, theme.border().divider())
        .margin(0, theme.space().m(), 0, 0);
    El editor = TextField.codeArea(value, placeholder, state.readOnly ? text -> {} : onChange)
        .fieldState(state.field)
        .onSelect((caret, anchor) -> {
          state.caret = caret;
          state.anchor = anchor;
        });
    if (state.readOnly) { // before the find bar, which then offers no replacing
      editor.readOnly();
    }
    if (highlighter != null) {
      editor.highlight(highlighter, highlighter);
    }
    int[] pair = editor.matchingBracket(state.caret);
    editor
        .decorate((line, start, text, out) -> {
          int end = start + text.length();
          if (state.caret >= start && state.caret <= end) {
            out.lineBackground(code.line());
          }
          guides(text, code.guide(), out);
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
          for (Diagnostic d : diagnostics) {
            if (d.to > start && d.from <= end) {
              out.underline(d.from - start, d.to - start, color(theme, d.severity));
            }
          }
        })
        .gutter(line -> cell(theme, state, diagnostics, line), rail);
    El message = message(theme, state, diagnostics);
    if (message != null) { // as a tooltip of the keyboard's, only while the editor has the focus
      editor.whenFocusWithin(e -> e.overlay(message));
    }
    // The rail starts at the frame: the field's padding lies after the gutter.
    editor.style().paddingLeft = Len.ZERO;
    if (app == null) {
      return editor;
    }
    state.editor = editor;
    state.value = value;
    state.lineComment = highlighter == null ? null : highlighter.lineComment();
    state.formatter = highlighter == null ? null : highlighter.formatter();
    editor.onKey(k -> {
      if (diagnosticKey(app, state, editor, diagnostics, message, k)) {
        return true;
      }
      Command command = command(k);
      boolean formats = state.formatter != null && editor.onEdit() != null && !editor.isReadOnly();
      if (command == null || (command == Command.FORMAT && !formats)) {
        return false; // Control+Alt+L stays the app's where the editor cannot format
      }
      run(app, state, command);
      return true;
    });
    if (!state.findBar) {
      return editor;
    }
    editor.fieldMenu((field, open) -> {
      Texts texts = Texts.CURRENT.get();
      boolean edits = field.onEdit() != null && !field.isReadOnly(); // also if made so after this
      return TextField.menu(
          field,
          open,
          List.of(
              Menu.separator(),
              TextField.command(texts.find(), true, () -> Find.open(state.find), "F"),
              TextField.command(texts.replace(), edits, () -> Find.openReplace(state.find), "H")));
    });
    return Find.find(app, editor, state.find);
  }

  /**
   * Gives an editor of this class the look of the body of a framed card or panel, such as a {@link
   * Card#flush} card under its {@link Card#toolbar}: as wide as the card, without corners or a
   * frame of its own, and with a hairline along its top ({@link Theme.Borders#divider}) in place of
   * a {@link Divider}, which takes the accent's edge color while the editor has the focus. The
   * ring of the keyboard's focus lies just inside it, where the card clips it.
   *
   * <pre>{@code
   * Card.flush(Card.toolbar("orders.sql", null, run),
   *     embedded(editor).grow(1))
   * }</pre>
   *
   * @param editor an editor of {@link #codeEditor}
   * @return the editor
   */
  public static El embedded(El editor) {
    Theme theme = Theme.CURRENT.get();
    float stroke = theme.size().stroke();
    editor.radius(0).border(stroke, 0, 0, 0, theme.border().divider()).shadow().wFull();
    editor.style().paddingRight = Len.px(theme.space().l());
    // Without the field's frame, no stroke of padding stands in for its bottom border.
    editor.style().paddingBottom = Len.px(editor.style().paddingBottom.value() - stroke);
    if (editor.onEdit() != null) {
      int hover = Theme.over(theme.surface().raised(), theme.surface().hover());
      editor
          .whenHovered(e -> e.fill(hover)) // the field's hover, without its frame
          .whenFocused(e -> e.border(stroke, 0, 0, 0, theme.accent().edge()));
    }
    return editor;
  }

  /** Guides at each step of indentation in the white space that starts {@code text}. */
  private static void guides(String text, int color, Spans out) {
    int column = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c != ' ' && c != '\t') {
        return;
      }
      if (column % INDENT == 0) {
        out.guide(i, color);
      }
      column = c == '\t' ? (column / INDENT + 1) * INDENT : column + 1;
    }
  }

  private static int color(Theme theme, Severity severity) {
    return severity == Severity.ERROR ? theme.status().danger() : theme.status().warning();
  }

  /**
   * The gutter's cell of a line: a bar in the accent's edge and a tint where the line is in the
   * marked block, the column of markers, and the line's number.
   */
  private static El cell(
      Theme theme, State state, List<Diagnostic> diagnostics, El.Gutter.Line line) {
    Theme.TextStyle mono = theme.type().mono();
    float bar = theme.size().indicator();
    float s = theme.space().s();
    boolean block = state.blockFrom < state.blockTo
        && state.blockTo > line.start()
        && state.blockFrom <= line.end();
    El cell = El.row()
        .align(Style.Align.CENTER)
        .gap(s)
        .padding(0, theme.space().m(), 0, block ? s : s + bar);
    if (block) {
      cell.fill(theme.code().block()).border(0, 0, 0, bar, theme.accent().edge());
    }
    return cell.children(
        marker(theme, state, diagnostics, line).w(mono.size()).shrink(0),
        El.text(String.valueOf(line.index() + 1))
            .font(mono.font().get())
            .fontSize(mono.size())
            .color(line.caret() ? theme.text().primary() : theme.text().muted())
            .textAlign(Style.Align.END)
            .grow(1));
  }

  /**
   * The marker of a line: of the diagnostics that start on it, else of the app's first marker on
   * it; an empty place for one where there is none, so that the numbers stay where they are.
   */
  private static El marker(
      Theme theme, State state, List<Diagnostic> diagnostics, El.Gutter.Line line) {
    Texts texts = Texts.CURRENT.get(); // the gutter builds in the view's values
    float size = theme.type().mono().size();
    StringBuilder messages = new StringBuilder();
    StringBuilder errors = new StringBuilder();
    StringBuilder warnings = new StringBuilder();
    boolean any = false;
    boolean error = false;
    for (Diagnostic d : diagnostics) {
      if (d.from >= line.start() && d.from <= line.end()) {
        any = true;
        error |= d.severity == Severity.ERROR;
        StringBuilder kind = d.severity == Severity.ERROR ? errors : warnings;
        kind.append(kind.isEmpty() ? "" : "\n").append(d.message);
        messages.append(messages.isEmpty() ? "" : "\n").append(d.message);
      }
    }
    El place = El.row().key("marker").justify(Style.Content.CENTER);
    if (any) {
      place
          .role("img")
          .label(error ? texts.error() : texts.warning())
          .child(Icon.icon(
              error ? Symbol.DISMISS : Symbol.WARNING,
              error ? Icon.Variant.DANGER : Icon.Variant.WARNING,
              size));
      place.errorMessage(error ? errors.toString() : null);
      place.warningMessage(warnings.isEmpty() ? null : warnings.toString());
      // A diagnostic without a message still shows, with its severity as the tooltip.
      return Tooltip.tooltip(
          place,
          messages.isEmpty() ? (error ? texts.error() : texts.warning()) : messages.toString(),
          Tooltip.options().kind(error ? Callout.Kind.DANGER : Callout.Kind.WARNING));
    }
    for (Marker m : state.markers) {
      if (m.at >= line.start() && m.at <= line.end()) {
        place
            .role(m.onPress == null ? "img" : "button")
            .label(m.label)
            .child(Icon.icon(m.symbol, m.tone, size));
        if (m.onPress != null) {
          place.onPress(m.onPress).keepFocus();
        }
        return Tooltip.tooltip(place, m.label);
      }
    }
    return El.el();
  }

  /**
   * The message of the diagnostics that start where F8 put the caret, above the caret, where a
   * list of completions does not cover it, on the look of their marker's tooltip while the caret
   * is at their start or inside them; else null, and F8's place is forgotten.
   */
  private static El message(Theme theme, State state, List<Diagnostic> diagnostics) {
    if (state.shown < 0) {
      return null;
    }
    int to = -1;
    boolean error = false;
    StringBuilder messages = new StringBuilder();
    for (Diagnostic d : diagnostics) {
      if (d.from == state.shown) {
        to = Math.max(to, Math.max(d.from, d.to));
        error |= d.severity == Severity.ERROR;
        messages.append(messages.isEmpty() ? "" : "\n").append(d.message);
      }
    }
    boolean in = state.caret == state.shown || (state.caret > state.shown && state.caret < to);
    if (to < 0 || !in) {
      state.shown = -1;
      return null;
    }
    Texts texts = Texts.CURRENT.get();
    return Tooltip.panel(
            theme,
            messages.isEmpty() ? (error ? texts.error() : texts.warning()) : messages.toString(),
            Tooltip.options()
                .kind(error ? Callout.Kind.DANGER : Callout.Kind.WARNING)
                .side(Side.TOP, Style.Align.START))
        .key("message") // beside a tooltip of the view's on the editor
        .atCaret();
  }

  /**
   * F8 and Shift+F8, which put the caret at the start of the next diagnostic or the one before and
   * show its message, and Escape, which hides the message where it shows; whether the key was one
   * of them.
   */
  private static boolean diagnosticKey(
      App<?> app, State state, El editor, List<Diagnostic> diagnostics, El message, Key k) {
    if (k.name().equals("Escape")
        && k.modifiers() == 0
        && message != null
        && app.tree().shownOverlays().contains(message)) { // not while scrolled away
      state.shown = -1;
      state.shown = -1;
      return true;
    }
    if (!k.name().equals("F8") || (k.modifiers() & ~Key.SHIFT) != 0 || diagnostics.isEmpty()) {
      return false;
    }
    int at = next(diagnostics, state.caret, k.modifiers() == Key.SHIFT);
    app.select(editor, at, at);
    state.caret = at;
    state.anchor = at;
    state.shown = at;
    return true;
  }

  /**
   * The start of the first diagnostic after {@code caret}, or {@code back} of the last before it;
   * round at the ends.
   */
  private static int next(List<Diagnostic> diagnostics, int caret, boolean back) {
    int[] starts = diagnostics.stream().mapToInt(Diagnostic::from).sorted().toArray();
    if (back) {
      for (int i = starts.length - 1; i >= 0; i--) {
        if (starts[i] < caret) {
          return starts[i];
        }
      }
      return starts[starts.length - 1];
    }
    for (int start : starts) {
      if (start > caret) {
        return start;
      }
    }
    return starts[0];
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
      case FORMAT -> format(app, state, editor, text, caret, anchor);
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

  /**
   * The edit that formats the whole text, or null where there is nothing to do; a problem that
   * kept the text from formatting shows, with the caret at its start.
   */
  private static LineEdits.Edit format(
      App<?> app, State state, El editor, String text, int caret, int anchor) {
    if (state.formatter == null || text.isBlank()) {
      return null;
    }
    Formatted formatted = state.formatter.apply(text);
    if (formatted.valid() && formatted.text().equals(text)) {
      return null;
    }
    int max = editor.maxLength();
    if (formatted.valid()
        && max >= 0
        && formatted.text().codePointCount(0, formatted.text().length()) > max) {
      // The field would cut the formatted text at its limit.
      formatted = new Formatted(null, new Formatted.Problem(0, 0, Formatted.Kind.TOO_LARGE));
    }
    if (formatted.valid()) {
      String to = formatted.text();
      return new LineEdits.Edit(
          0,
          text.length(),
          to,
          same(text, to, caret, caret <= anchor),
          same(text, to, anchor, anchor <= caret));
    }
    int at = Math.min(formatted.problem().from(), text.length());
    state.problem = formatted.problem();
    state.problemOf = state.value;
    app.select(editor, at, at);
    state.caret = at;
    state.anchor = at;
    state.shown = at;
    return null;
  }

  /**
   * Where position {@code at} of {@code text} is in {@code formatted}, the same characters with
   * other white space between some of them: at its place in white space that stayed as it was,
   * such as in a string; else right after the character before it that is not white space where
   * white space follows {@code at} only, or where none does and not {@code after}; else right
   * before the next one. So a selection keeps its order.
   */
  private static int same(String text, String formatted, int at, boolean after) {
    int count = 0;
    int from = 0; // the white space around at, from to to
    for (int i = 0; i < at; i++) {
      if (!Character.isWhitespace(text.charAt(i))) {
        count++;
        from = i + 1;
      }
    }
    int to = at;
    while (to < text.length() && Character.isWhitespace(text.charAt(to))) {
      to++;
    }
    int start = 0; // the white space at the same place in formatted, start to end
    for (; start < formatted.length() && count > 0; start++) {
      count -= Character.isWhitespace(formatted.charAt(start)) ? 0 : 1;
    }
    int end = start;
    while (end < formatted.length() && Character.isWhitespace(formatted.charAt(end))) {
      end++;
    }
    if (to - from == end - start && text.regionMatches(from, formatted, start, to - from)) {
      return start + at - from;
    }
    return at == from && (at < to || !after) ? start : end;
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
}
