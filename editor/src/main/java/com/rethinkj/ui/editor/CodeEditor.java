package com.rethinkj.ui.editor;

import com.rethinkj.ui.App;
import com.rethinkj.ui.CodeWords;
import com.rethinkj.ui.Colors;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.FieldState;
import com.rethinkj.ui.Formatted;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Measure;
import com.rethinkj.ui.Scrollbars;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Spans;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.TextDocument;
import com.rethinkj.ui.WindowState;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Find;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Markdown;
import com.rethinkj.ui.kit.MarkdownDocument;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Texts;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tooltip;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * An editor of code, such as the SQL of a query: a {@link TextField#codeArea} with line numbers and
 * markers in a gutter on a rail of its own, the caret's line highlighted, the bracket at the caret
 * and its partner marked, the other occurrences of the word at the caret marked, guides in the
 * indentation, the tokens colored by a {@link Highlighter}, such as one of a language module's,
 * and the app's diagnostics marked with a squiggle. Given the app, it also finds and replaces
 * ({@link Find}), goes to a line, goes from diagnostic to diagnostic with F8 and Shift+F8, and has
 * the commands of {@link Command}.
 *
 * <pre>{@code
 * codeEditor(app, state.sql, state.editor, highlighter, value -> state.sql = value)
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

  /** The markup layer of {@link Command#HIGHLIGHT_USAGES}. */
  private static final String USAGES_LAYER = "usages";

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
    FORMAT,
    /**
     * Control+Shift+[: folds the innermost foldable bracket range around the caret and moves the
     * caret to the end of its first line.
     */
    FOLD,
    /**
     * Control+Shift+]: unfolds the folded range at the caret's line, or the innermost folded range
     * around it.
     */
    UNFOLD,
    /**
     * Alt+Enter: opens the menu of quick fixes for the diagnostics at the caret, with the first
     * fix highlighted. Nothing where there are no fixes at the caret or the editor is not
     * editable.
     */
    QUICK_FIXES,
    /**
     * Control+Q: shows the app's documentation ({@link State#documentation}) for the symbol at the
     * caret in a popover, until Escape, a press outside, typing or moving the caret.
     */
    QUICK_DOCUMENTATION,
    /**
     * Switches the doc comment at or right before the caret between its rendered view and its raw
     * source, when the highlighter is a {@link Language}. It has no key of its own: Control+Alt+Q,
     * as in other editors, is AltGr+Q on Windows, which types {@code @} on some layouts.
     */
    TOGGLE_DOC_COMMENT,
    /**
     * Control+B: goes to the declaration ({@link State#declaration}) of the symbol at the caret,
     * unfolding and scrolling it into view, or passing a location in another document to {@link
     * State#onOpen}.
     */
    GO_TO_DECLARATION,
    /**
     * Alt+F7: opens a menu at the caret listing the usages ({@link State#usages}) of the symbol at
     * the caret; choosing one goes to its location.
     */
    FIND_USAGES,
    /**
     * Control+Shift+F7: highlights the usages ({@link State#usages}) of the symbol at the caret in
     * this document in the {@code "usages"} markup layer and along the scrollbar, until Escape in
     * the editor.
     */
    HIGHLIGHT_USAGES
  }

  /** How much a {@link Diagnostic} matters. */
  public enum Severity {
    /** Something that is wrong, such as a column the database does not know: the danger color. */
    ERROR,
    /** Something to think twice about: the warning color. */
    WARNING
  }

  /**
   * Something shown on the code of a {@link State#markup markup layer}: a {@link Diagnostic}, a
   * gutter {@link Marker}, a {@link Highlight} behind a range of characters, a version control
   * {@link Change} bar, or an inline {@link InlayHint} inside a line.
   */
  public sealed interface Mark permits Diagnostic, Marker, Highlight, Change, InlayHint {}

  /**
   * A quick fix for a {@link Diagnostic}: replaces characters {@code from} to {@code to} by {@code
   * text} in the string the diagnostic's {@link State#markup markup layer} was computed for, its
   * positions as the editor shows that string, as a diagnostic's are ({@link State}), with
   * {@code label} shown in the quick fixes menu (Alt+Enter or the gutter bulb on the caret's line).
   *
   * @param label text shown for this fix in the menu
   * @param from start offset in the markup layer's text, 0 or more
   * @param to end offset in the markup layer's text, at least {@code from}
   * @param text replacement text
   */
  public record Fix(String label, int from, int to, String text) {
    public Fix {
      Objects.requireNonNull(label, "label");
      Objects.requireNonNull(text, "text");
      if (from < 0 || to < from) {
        throw new IllegalArgumentException(
            "from must be 0 or more and to at least from, were " + from + " and " + to);
      }
    }
  }

  /**
   * Something the app says about characters {@code from} to {@code to} of the string, such as
   * where the database said a query went wrong: a wavy line under them in the severity's status
   * color, and a marker at the start of the line of {@code from} with the message as its tooltip.
   * May carry {@link Fix quick fixes} offered at the caret via Alt+Enter and the gutter bulb while
   * the editor's text still equals the revision the diagnostic's markup layer was computed for.
   */
  public record Diagnostic(int from, int to, Severity severity, String message, List<Fix> fixes)
      implements Mark {
    public Diagnostic {
      Objects.requireNonNull(severity, "severity");
      Objects.requireNonNull(message, "message");
      fixes = fixes == null ? List.of() : List.copyOf(fixes);
    }

    /** A diagnostic with no quick fixes. */
    public Diagnostic(int from, int to, Severity severity, String message) {
      this(from, to, severity, message, List.of());
    }
  }

  /**
   * A marker in the gutter at the line of position {@code at} of the string: {@code symbol} in
   * {@code tone}'s color, with {@code label} as its tooltip, which a press on it runs {@code
   * onPress} for, such as {@link Symbol#PLAY} "Run statement" at the first line of a statement.
   *
   * @param onPress what a press runs, or null for a marker that only shows
   */
  public record Marker(int at, Icon symbol, Icon.Variant tone, String label, Runnable onPress)
      implements Mark {
    public Marker {
      Objects.requireNonNull(symbol, "symbol");
      Objects.requireNonNull(tone, "tone");
      Objects.requireNonNull(label, "label");
    }
  }

  /**
   * A background behind characters {@code from} to {@code to} of the string in the theme's {@code
   * code().occurrence()} color, such as the usages of a symbol.
   */
  public record Highlight(int from, int to) implements Mark {}

  /**
   * Lines of characters {@code from} to {@code to} of the string that changed against the version
   * control base: shown as a colored bar in the gutter's change lane and as a tick along the
   * vertical scrollbar. For {@link Kind#DELETED}, {@code from == to} marks the point where lines
   * were removed: before the line of {@code from}, or after it where {@code from} is the end of a
   * line that is not empty, such as lines removed at the end of a text without a final line feed.
   *
   * @param from start offset in the markup layer's text, 0 or more
   * @param to end offset in the markup layer's text, at least {@code from}
   * @param kind what changed against the base
   */
  public record Change(int from, int to, Kind kind) implements Mark {
    /** What changed against the version control base. */
    public enum Kind {
      /** Lines added since the base. */
      ADDED,
      /** Lines modified since the base. */
      MODIFIED,
      /** Lines removed at the point {@code from}. */
      DELETED
    }

    public Change {
      Objects.requireNonNull(kind, "kind");
      if (from < 0 || to < from) {
        throw new IllegalArgumentException(
            "from must be 0 or more and to at least from, were " + from + " and " + to);
      }
    }
  }

  /**
   * An inline hint shown inside a line at position {@code at} of the string ({@link El#hints}),
   * such as a parameter name before an argument ({@link El.Hint.Affinity#AFTER}) or an inferred
   * type after a variable name ({@link El.Hint.Affinity#BEFORE}). Hints are not part of the
   * string: the caret stops on both sides of them and copy ignores them. When moved across edits
   * in a {@link State#markup markup layer}, an {@link El.Hint.Affinity#AFTER} hint moves with the
   * text after it (text inserted at {@code at} lands before it), a {@link
   * El.Hint.Affinity#BEFORE} hint moves with the text before it (text inserted at {@code at} lands
   * after it), and either is dropped if text is replaced across {@code at}.
   */
  public record InlayHint(int at, El.Hint.Affinity affinity, String text) implements Mark {
    public InlayHint {
      if (at < 0) {
        throw new IllegalArgumentException("inlay hint offset must be 0 or more, was " + at);
      }
      Objects.requireNonNull(affinity, "affinity");
      Objects.requireNonNull(text, "text");
    }
  }

  /**
   * A code vision hint shown in a muted row above 0-based {@code line}, such as usage counts or
   * authorship ({@link State#codeVision}). Entries for the same line share one row in list order,
   * aligned with that line's indentation.
   *
   * @param line 0-based line index above which the entry appears
   * @param symbol optional leading icon, or null for text only
   * @param label text shown for this entry
   * @param onPress what a press runs, or null for an entry that only shows
   */
  public record Vision(int line, Icon symbol, String label, Runnable onPress) {
    public Vision {
      if (line < 0) {
        throw new IllegalArgumentException("vision line must be 0 or more, was " + line);
      }
      Objects.requireNonNull(label, "label");
    }
  }

  /**
   * A location in this editor's document or in another document of the app, supplied by {@link
   * State#declaration} or {@link State#usages}. Positions are UTF-16 offsets in the displayed text
   * ({@link State}).
   *
   * @param document null for this editor's own document, or the app's name for another document
   *     (passed to {@link State#onOpen})
   * @param from start offset, 0 or more
   * @param to end offset, at least {@code from}
   * @param label text shown in the find-usages menu; null lets the editor show {@code "<line>:
   *     <trimmed line>"} for this document and {@code document} for another
   */
  public record Location(String document, int from, int to, String label) {
    public Location {
      if (from < 0 || to < from) {
        throw new IllegalArgumentException(
            "from must be 0 or more and to at least from, were " + from + " and " + to);
      }
    }

    /** A location in this editor's own document, with its menu label derived from the line. */
    public Location(int from, int to) {
      this(null, from, to, null);
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
      case "[" -> mods == controlShift ? Command.FOLD : null;
      case "]" -> mods == controlShift ? Command.UNFOLD : null;
      case "Enter" -> mods == Key.ALT ? Command.QUICK_FIXES : null;
      case "Q" -> mods == Key.CONTROL ? Command.QUICK_DOCUMENTATION : null;
      case "B" -> mods == Key.CONTROL ? Command.GO_TO_DECLARATION : null;
      case "F7" ->
        mods == Key.ALT
            ? Command.FIND_USAGES
            : mods == controlShift ? Command.HIGHLIGHT_USAGES : null;
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
    private final FieldState field;
    private final Find.State find = new Find.State();

    /** Creates an editor state with a document of its own. */
    public State() {
      this.field = new FieldState();
    }

    /**
     * Creates an editor state backed by {@code document}, which may be shared with other editors:
     * views of one document share an undo and redo history and update their carets and lines from
     * each other's edits, while keeping their own caret, selection, scroll, folds, blocks and find
     * bar.
     */
    public State(TextDocument document) {
      this.field = new FieldState(Objects.requireNonNull(document, "document"));
    }

    private final Menu.State quickFixes = new Menu.State();
    private List<Fix> fixesShown; // the fixes of the open menu's last build
    private int caret;
    private int anchor;
    private String text = ""; // the string of the editor's last build
    private List<Marker> markers = List.of();
    private final Map<String, Layer> layers = new LinkedHashMap<>();
    private List<Vision> codeVision = List.of();
    private int blockFrom;
    private int blockTo;
    private boolean occurrences = true;
    private boolean inlineDiagnostics;
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
    private List<Diagnostic> currentDiagnostics = List.of();
    // FORMAT's problem, shown while the app's string is the one it could not format.
    private Formatted.Problem problem;
    private String problemOf;
    // F8: where the diagnostics start whose message shows at the caret; -1 while none does.
    private int shown = -1;
    // Folding: the folded pairs, sorted by their first line, the last build's value and its
    // string as the field shows it (null before the first build), and the last build's pairs that
    // can fold.
    private List<Folded> folds = new ArrayList<>();
    private String builtValue;
    private String builtText;
    // The folds the field asked to open since the last build ({@link #ask}).
    private final List<El.Fold> asked = new ArrayList<>();
    // The last edits computed, between these two strings, which the folds and the layers share.
    private String editsFrom;
    private String editsTo;
    private TextEdits edits;
    private List<El.BracketFold> ranges = List.of();
    // Code vision: the rows of the last build's value and entries, rebuilt when either changes.
    private String visionOf;
    private List<Vision> visionFor;
    private Map<Integer, CodeVision.Row> visionRows = Map.of();
    // Quick documentation: provider supplied by the app and the currently open popover.
    private IntFunction<MarkdownDocument> documentation;
    private MarkdownDocument doc;
    private String docValue;
    private int docFrom = -1;
    private int docTo = -1;
    private int docCaret = -1;
    private boolean docHover;
    private int hoverPos = -1;
    private boolean overDoc;
    private double docLeftAt = Double.NaN; // when the pointer left the word and the popover
    // Rendered doc comments (reader mode): off by default.
    private boolean readerMode;
    private Consumer<String> onLink;
    private Language language;
    private Comments comments;
    private List<DocRange> docRanges = List.of();
    private List<DocToggle> docToggles = new ArrayList<>();
    private String docsText; // the string the doc ranges and toggles are positions in
    private final Map<String, DocComment> parsedDocs = new LinkedHashMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<String, DocComment> eldest) {
        return size() > 256;
      }
    };
    // Code navigation: declaration and usages providers, open usages menu and Ctrl-hover link.
    private IntFunction<Location> declaration;
    private IntFunction<List<Location>> usages;
    private Consumer<Location> onOpen;
    private final Menu.State usagesMenu = new Menu.State();
    private List<Location> usagesFound = List.of();
    private List<String> usageLabels = List.of(); // computed once, while the text stays the same
    private String usagesValue;
    private String navValue;
    private int navFrom = -1;
    private int navTo = -1;
    private Location navTarget;
    private Layer usagesShown; // the usages layer HIGHLIGHT_USAGES set, not one of the app's

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
     * Whether the message of the most severe diagnostic on each line shows muted after the line's
     * end, in the severity's status color and cut off at the field's edge; off unless set. A folded
     * range's first line shows only diagnostics that start on that line, and lines replaced by a
     * rendered doc comment show nothing.
     */
    public State inlineDiagnostics(boolean on) {
      inlineDiagnostics = on;
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

    /**
     * Whether standalone doc comments are rendered in place of their raw lines when the
     * highlighter is a {@link Language}; off unless set. Changing the mode clears per-comment
     * toggles. Comments are found a bounded part per build: with its app ({@link
     * #codeEditor(App, String, State, Highlighter, Consumer)}), the editor builds again on the
     * next frames until it has found all; without it, only builds for other reasons go on.
     */
    public State readerMode(boolean on) {
      if (readerMode != on) {
        readerMode = on;
        docToggles.clear();
      }
      return this;
    }

    /** Whether reader mode is on. */
    public boolean readerMode() {
      return readerMode;
    }

    /**
     * Receives the destination of a link pressed inside a rendered doc comment, as {@link
     * Markdown.Options#onLink} gives it; null for none.
     */
    public State onLink(Consumer<String> handler) {
      this.onLink = handler;
      return this;
    }

    /** The caret, as an index into the string. */
    public int caret() {
      return caret;
    }

    /**
     * The caret's line, from 1: the number of line breaks before it, plus one, which the gutter
     * shows next to it. Like {@link #column}, it is about the string of the editor's last build, so
     * a view reads it after building the editor, as for a status bar below it.
     */
    public int line() {
      return position()[0];
    }

    /**
     * The caret's column, from 1: the characters before it on its line, plus one. A character is
     * one {@code char} of the string, as in every position the editor reports, so a tab counts as
     * one however wide it shows, and an emoji outside the Basic Multilingual Plane as two.
     */
    public int column() {
      return position()[1];
    }

    // The caret's line and column in the editor's string, whose line breaks are as it shows them.
    private int[] position() {
      int line = 1;
      int start = 0;
      int at = 0;
      for (int i = 0; i < text.length() && at < caret; i++, at++) {
        char c = text.charAt(i);
        if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
          i++;
        }
        if (c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029') {
          line++;
          start = at + 1;
        }
      }
      return new int[] {line, at - start + 1};
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
     * Shows {@code markers} in the gutter, each at its position's line in the current string, in
     * place of those before: each gets its own slot in the icon lane after the diagnostic slot and
     * any {@link #markup} markers on that line. These positions are not moved with edits; for
     * markers that move with edits, pass them to {@link #markup}.
     */
    public State markers(List<Marker> markers) {
      this.markers = List.copyOf(markers);
      return this;
    }

    /**
     * Sets the marks of layer {@code layer} (one layer per provider, such as {@code "analysis"},
     * {@code "vcs"}, {@code "references"}, {@code "hints"} or {@code "app"}), in place of that layer's
     * marks before, computed for {@code text}. All layers show together: {@link Diagnostic}s draw squiggles,
     * show in the gutter's first icon slot, mark the scrollbar and take part in F8/Shift+F8,
     * {@link Marker}s show side by side in the gutter's icon lane (layers in order, then {@link
     * #markers}), {@link Highlight}s draw a background in {@code code().occurrence()}, and {@link
     * Change}s draw a bar in the gutter's change lane and ticks along the vertical scrollbar, and {@link InlayHint}s show inline inside their lines.
     *
     * <p>{@code text} is the revision the marks were computed for and may be older than the
     * editor's current value. The marks move with each change of the value, each edit of a bulk
     * change such as Replace All or a format on its own, and the originals show again when the
     * value is {@code text} again, as after an undo: a range {@code [from, to)} does not grow at
     * its ends (text inserted at {@code from} lands before it, at {@code to} after it), a point
     * ({@code from == to}, a {@link Marker}'s {@code at}, and an {@link El.Hint.Affinity#AFTER}
     * {@link InlayHint}) moves with the text after it (text inserted at it lands before it), a
     * {@link El.Hint.Affinity#BEFORE} {@link InlayHint} moves with the text before it (text
     * inserted at it lands after it), and a range whose characters are deleted or replaced or that
     * gets text inserted strictly inside, or a point inside replaced text, is dropped.
     */
    public State markup(String layer, String text, List<? extends Mark> marks) {
      Objects.requireNonNull(layer, "layer");
      List<Mark> copy = List.copyOf(marks);
      if (copy.isEmpty()) {
        layers.remove(layer);
        return this;
      }
      Objects.requireNonNull(text, "text");
      layers.put(layer, new Layer(displayed(text), copy));
      return this;
    }

    /** Clears the marks of layer {@code layer}. */
    public State noMarkup(String layer) {
      Objects.requireNonNull(layer, "layer");
      layers.remove(layer);
      return this;
    }

    /**
     * Shows code vision {@code entries} above their lines, in place of those before. Entries for
     * the same line share one inserted block row above it, in list order, aligned with that line's
     * indentation; pass an empty list to clear them.
     */
    public State codeVision(List<Vision> entries) {
      this.codeVision = List.copyOf(Objects.requireNonNull(entries, "entries"));
      return this;
    }

    /**
     * Shorthand for {@link #markup markup("diagnostics", text, diagnostics)}: shows {@code
     * diagnostics} about {@code text}, in place of those before. They move with edits elsewhere in
     * the text, are dropped when their own characters are edited, and come back with an undo back
     * to {@code text}. Each has a squiggle under it in its severity's status color, and each line
     * where one starts has a marker, of the worst severity among them, whose tooltip is their
     * messages. A tree dump shows the squiggles under them on the editor as {@code squiggle
     * <from>-<to>} while their lines are in view, and the marker in the line's cell, with the
     * messages of its errors as {@code error=} and of its warnings as {@code warning=}.
     *
     * <p>Given the app, F8 puts the caret at the start of the next diagnostic after it, Shift+F8 at
     * the start of the one before, round at the ends, and the message of those that start there
     * shows above the caret, as their marker's tooltip, while the editor has the focus, until the
     * caret leaves them or Escape hides it; a tree dump shows it as an overlay of the editor, {@code
     * role=tooltip} with the message as the label. Without diagnostics the keys do nothing.
     */
    public State diagnostics(String text, List<Diagnostic> diagnostics) {
      return markup("diagnostics", text, diagnostics);
    }

    /** Shorthand for {@link #noMarkup noMarkup("diagnostics")}. */
    public State noDiagnostics() {
      return noMarkup("diagnostics");
    }

    /** The folded line ranges of the editor, sorted by their first line. */
    public List<El.Fold> folds() {
      return folds.stream().map(Folded::lines).toList();
    }

    /**
     * Sets the folded line ranges of the editor, in place of those before, such as ranges saved
     * with a document. A range folds the pair of brackets that can fold on its first line; one
     * whose first line has none is dropped once that part of the text has been read.
     */
    public State folds(List<El.Fold> folds) {
      this.folds = sorted(Objects.requireNonNull(folds, "folds").stream()
          .map(f -> new Folded(f, -1, -1))
          .toList());
      return this;
    }

    /**
     * Supplies quick documentation for a character offset in the editor's string: a {@link
     * MarkdownDocument} to show in a popover, or null for none. Shown below a word after the
     * pointer rests on it for {@link Theme.Motion#tooltipDelay}, and at the caret on Control+Q
     * ({@link Command#QUICK_DOCUMENTATION}). Null, the default, disables quick documentation.
     */
    public State documentation(IntFunction<MarkdownDocument> provider) {
      documentation = provider;
      if (provider == null) {
        closeDocumentation();
      }
      return this;
    }

    private void closeDocumentation() {
      doc = null;
      docValue = null;
      docFrom = -1;
      docTo = -1;
      docCaret = -1;
      docHover = false;
      overDoc = false;
      docLeftAt = Double.NaN;
    }

    /**
     * Supplies the declaration of the symbol at a character offset in the editor's string, or null
     * for none. Reached with Control+B ({@link Command#GO_TO_DECLARATION}) or Control+click, which
     * underlines the hovered word and shows a pointer cursor while Control is held. Null, the
     * default, disables go to declaration.
     */
    public State declaration(IntFunction<Location> provider) {
      declaration = provider;
      if (provider == null) {
        clearNavigation();
      }
      return this;
    }

    /**
     * Supplies the usages of the symbol at a character offset in the editor's string, or null or an
     * empty list for none. Listed in a menu at the caret with Alt+F7 ({@link Command#FIND_USAGES})
     * and highlighted in this document in the {@code "usages"} markup layer with Control+Shift+F7
     * ({@link Command#HIGHLIGHT_USAGES}). Null, the default, disables finding and highlighting
     * usages.
     */
    public State usages(IntFunction<List<Location>> provider) {
      usages = provider;
      if (provider == null) {
        closeUsagesMenu();
        clearUsages();
      }
      return this;
    }

    /**
     * Called when navigating to a {@link Location} whose {@link Location#document document} is not
     * null: the app opens that document and selects the location on its {@link State}. Without a
     * handler, locations in other documents do nothing.
     */
    public State onOpen(Consumer<Location> handler) {
      onOpen = handler;
      return this;
    }

    private void closeUsagesMenu() {
      usagesMenu.close();
      usagesFound = List.of();
      usageLabels = List.of();
      usagesValue = null;
    }

    /** Removes the usages layer if it is still the one HIGHLIGHT_USAGES set; whether it was. */
    private boolean clearUsages() {
      boolean own = usagesShown != null && layers.get(USAGES_LAYER) == usagesShown;
      if (own) {
        layers.remove(USAGES_LAYER);
      }
      usagesShown = null;
      return own;
    }

    private void clearNavigation() {
      navValue = null;
      navFrom = -1;
      navTo = -1;
      navTarget = null;
    }

    private void fold(El.BracketFold pair) {
      List<Folded> next = new ArrayList<>(folds);
      next.add(new Folded(pair.lines(), pair.open(), pair.close()));
      folds = sorted(next);
    }

    private void unfold(El.Fold fold) {
      folds.removeIf(f -> f.lines().first() == fold.first());
    }

    /**
     * The field asks to open a fold: for a press on its mark, a caret on a line it hides, and an
     * edit across it, such as Replace All or a format. That waits for the next build, which opens
     * it only when the string stayed the same: an edit keeps the folds whose brackets it keeps,
     * and the field asks again where it left the caret on a hidden line.
     */
    private void ask(El.Fold fold) {
      asked.add(fold);
    }

    private TextEdits edits(String from, String to) {
      if (!from.equals(editsFrom) || !to.equals(editsTo)) { // the same strings at once
        edits = TextEdits.between(from, to);
        editsFrom = from;
        editsTo = to;
      }
      return edits;
    }

    private El.Fold foldAt(int line) {
      for (Folded f : folds) {
        if (f.lines().first() == line) {
          return f.lines();
        }
      }
      return null;
    }

    private void updateFolds(String value, El editor, El.BracketFolds found) {
      // The field's string is read again only for a new value: it converts line breaks anew.
      String text = value.equals(builtValue) ? builtText : editor.text().string();
      if (value.equals(builtValue)) {
        asked.forEach(this::unfold);
      }
      asked.clear();
      if (!folds.isEmpty()) {
        if (builtText != null && !text.equals(builtText)) {
          folds = shiftFolds(edits(builtText, text), folds);
        }
        folds = reconcileFolds(folds, found, text);
      }
      builtValue = value;
      builtText = text;
      ranges = found.folds();
    }

    /**
     * Finds the doc comments of the last build's string, a bounded part per build, and moves the
     * toggles across its edits. Comments past the part scanned so far keep their moved ranges
     * until the scan reaches them, so an edit does not show them raw for the frames it takes.
     */
    private void updateDocs(Highlighter highlighter, App<?> app) {
      Language lang = highlighter instanceof Language l ? l : null;
      if (!Objects.equals(language, lang)) { // such as the same language in another palette
        language = lang;
        // Scanned again, as its comments may differ; the toggles move on and are checked then.
        comments = null;
        docRanges = List.of();
        parsedDocs.clear();
      }
      if (language == null || (!readerMode && docToggles.isEmpty())) {
        comments = null;
        docRanges = List.of();
        docsText = null;
        return;
      }
      String text = builtText;
      TextEdits moved = docsText == null || docsText.equals(text) ? null : edits(docsText, text);
      if (moved != null) {
        docRanges = moveDocs(docRanges, moved);
        docToggles = shiftDocToggles(moved, docToggles, readerMode);
      }
      Comments scanned = Comments.scan(text, language, comments);
      if (scanned != comments || moved != null) {
        comments = scanned;
        int boundary =
            scanned.complete() ? Integer.MAX_VALUE : lineStart(text, scanned.scannedLines() - 1);
        docRanges = eligibleDocs(text, candidates(scanned, docRanges, boundary));
        docToggles = reconcileDocToggles(docToggles, docRanges, boundary);
      }
      docsText = text;
      // A caret placed in a comment, such as after typing one, shows it raw; the build that answers
      // an edit still has the caret from before it, so the build after it checks.
      DocRange atCaret = moved == null ? docContaining(caret) : null;
      if (atCaret != null && isDocRendered(atCaret)) {
        revealDoc(atCaret);
      }
      if (!scanned.complete() && app != null) {
        app.after(0, () -> {}); // the next frame builds again and scans on
      }
    }

    /** Scans the whole string for the command, which acts on comments past the scanned part. */
    private void ensureDocRanges(String text) {
      if (language == null || (comments != null && comments.complete() && text.equals(docsText))) {
        return;
      }
      Comments scanned = text.equals(docsText) ? comments : null;
      do {
        scanned = Comments.scan(text, language, scanned);
      } while (!scanned.complete());
      if (docsText != null && !text.equals(docsText)) {
        docToggles = shiftDocToggles(edits(docsText, text), docToggles, readerMode);
      }
      comments = scanned;
      docRanges = eligibleDocs(text, candidates(scanned, List.of(), Integer.MAX_VALUE));
      docToggles = reconcileDocToggles(docToggles, docRanges, Integer.MAX_VALUE);
      docsText = text;
    }

    /** The doc comment that holds {@code at} after its first character, or null. */
    private DocRange docContaining(int at) {
      int lo = 0;
      int hi = docRanges.size() - 1;
      while (lo <= hi) {
        int mid = (lo + hi) >>> 1;
        DocRange r = docRanges.get(mid);
        if (at <= r.start()) {
          hi = mid - 1;
        } else if (at > r.end()) {
          lo = mid + 1;
        } else {
          return r;
        }
      }
      return null;
    }

    private boolean isToggled(DocRange doc) {
      int lo = 0;
      int hi = docToggles.size() - 1;
      while (lo <= hi) {
        int mid = (lo + hi) >>> 1;
        DocToggle t = docToggles.get(mid);
        if (t.start() == doc.start()) {
          return t.end() == doc.end();
        }
        if (t.start() < doc.start()) {
          lo = mid + 1;
        } else {
          hi = mid - 1;
        }
      }
      return false;
    }

    private boolean isDocRendered(DocRange doc) {
      return readerMode != isToggled(doc);
    }

    private List<DocRange> renderedDocs() {
      if (docRanges.isEmpty()) {
        return List.of();
      }
      List<DocRange> out = new ArrayList<>();
      for (DocRange doc : docRanges) {
        if (isDocRendered(doc)) {
          out.add(doc);
        }
      }
      return out;
    }

    private void addDocToggle(DocRange doc) {
      if (isToggled(doc)) {
        return;
      }
      List<DocToggle> next = new ArrayList<>(docToggles);
      next.add(new DocToggle(doc.start(), doc.end(), false));
      docToggles = sortedDocToggles(next);
    }

    private void removeDocToggle(DocRange doc) {
      docToggles.removeIf(t -> t.start() == doc.start() && t.end() == doc.end());
    }

    private void revealDoc(DocRange doc) {
      if (readerMode) {
        addDocToggle(doc);
      } else {
        removeDocToggle(doc);
      }
    }

    private boolean flipDoc(DocRange doc) {
      if (isToggled(doc)) {
        removeDocToggle(doc);
      } else {
        addDocToggle(doc);
      }
      return isDocRendered(doc);
    }

    private DocRange docAtLine(int line) {
      int lo = 0;
      int hi = docRanges.size() - 1;
      while (lo <= hi) {
        int mid = (lo + hi) >>> 1;
        DocRange r = docRanges.get(mid);
        if (r.firstLine() == line) {
          return r;
        }
        if (r.firstLine() < line) {
          lo = mid + 1;
        } else {
          hi = mid - 1;
        }
      }
      return null;
    }

    private DocRange docContainingLine(int line) {
      int lo = 0;
      int hi = docRanges.size() - 1;
      while (lo <= hi) {
        int mid = (lo + hi) >>> 1;
        DocRange r = docRanges.get(mid);
        if (line < r.firstLine()) {
          hi = mid - 1;
        } else if (line > r.lastLine()) {
          lo = mid + 1;
        } else {
          return r;
        }
      }
      return null;
    }

    private DocComment docComment(Language lang, String raw) {
      if (parsedDocs.containsKey(raw)) {
        return parsedDocs.get(raw);
      }
      DocComment parsed = lang.docComment(raw);
      parsedDocs.put(raw, parsed);
      return parsed;
    }

    private record Marks(
        List<Diagnostic> diagnostics,
        List<Diagnostic> currentDiagnostics,
        List<Marker> markers,
        List<Highlight> highlights,
        List<Highlight> usageHighlights,
        List<Change> changes,
        List<InlayHint> hints) {}

    private static final class Layer {
      final String text;
      final List<Mark> original;
      String checked; // the value the marks were last moved to
      List<Mark> mapped;

      Layer(String text, List<Mark> original) {
        this.text = text;
        this.original = original;
        checked = text;
        mapped = original;
      }

      List<Mark> marksFor(String value, State state) {
        if (!value.equals(checked)) { // the same string at once
          if (value.equals(text)) {
            mapped = original;
          } else if (!mapped.isEmpty()) {
            mapped = moved(mapped, state.edits(checked, value));
          }
          checked = value;
        }
        return mapped;
      }

      private static List<Mark> moved(List<Mark> marks, TextEdits edits) {
        List<Mark> out = new ArrayList<>(marks.size());
        for (Mark mark : marks) {
          Mark shifted = shift(mark, edits);
          if (shifted != null) {
            out.add(shifted);
          }
        }
        return List.copyOf(out);
      }
    }

    private static Mark shift(Mark mark, TextEdits edits) {
      return switch (mark) {
        case Diagnostic d -> {
          int from = edits.mapRange(d.from(), d.to());
          yield from < 0
              ? null
              : new Diagnostic(
                  from,
                  from + Math.max(0, d.to() - d.from()),
                  d.severity(),
                  d.message(),
                  d.fixes());
        }
        case Marker m -> {
          int at = edits.mapPoint(m.at());
          yield at < 0 ? null : new Marker(at, m.symbol(), m.tone(), m.label(), m.onPress());
        }
        case Highlight h -> {
          int from = edits.mapRange(h.from(), h.to());
          yield from < 0 ? null : new Highlight(from, from + Math.max(0, h.to() - h.from()));
        }
        case Change c -> {
          int from = edits.mapRange(c.from(), c.to());
          yield from < 0 ? null : new Change(from, from + Math.max(0, c.to() - c.from()), c.kind());
        }
        case InlayHint h -> {
          int at = h.affinity() == El.Hint.Affinity.BEFORE
              ? edits.mapPointBefore(h.at())
              : edits.mapPoint(h.at());
          yield at < 0 ? null : new InlayHint(at, h.affinity(), h.text());
        }
      };
    }

    /**
     * All marks that show with {@code value}, after {@link #updateFolds} read its string as the
     * field shows it: every markup layer moved to it, the unmoved {@link #markers}, and the problem
     * that kept {@code value} from formatting.
     */
    private Marks marksOf(String value) {
      if (problem != null && !value.equals(problemOf)) { // at once for the same string object
        shown = shown == problem.from() ? -1 : shown; // its message goes, not on to another
        problem = null;
        problemOf = null;
      }
      if (layers.isEmpty() && problem == null) {
        return new Marks(List.of(), List.of(), markers, List.of(), List.of(), List.of(), List.of());
      }
      List<Diagnostic> diagnostics = new ArrayList<>();
      List<Diagnostic> current = new ArrayList<>();
      List<Marker> allMarkers = new ArrayList<>();
      List<Highlight> highlights = new ArrayList<>();
      List<Highlight> usageHighlights = new ArrayList<>();
      List<Change> changes = new ArrayList<>();
      List<InlayHint> hints = new ArrayList<>();
      for (Map.Entry<String, Layer> entry : layers.entrySet()) {
        boolean isUsages = USAGES_LAYER.equals(entry.getKey());
        Layer layer = entry.getValue();
        boolean fresh = layer.text.equals(builtText);
        for (Mark mark : layer.marksFor(builtText, this)) { // in the string as the field shows it
          switch (mark) {
            case Diagnostic d -> {
              diagnostics.add(d);
              if (fresh && !d.fixes().isEmpty()) {
                current.add(d);
              }
            }
            case Marker m -> allMarkers.add(m);
            case Highlight h -> {
              highlights.add(h);
              if (isUsages) {
                usageHighlights.add(h);
              }
            }
            case Change c -> changes.add(c);
            case InlayHint h -> hints.add(h);
          }
        }
      }
      allMarkers.addAll(markers);
      if (problem != null) {
        String message = Texts.CURRENT.get().formatProblem(problem.kind());
        diagnostics.add(new Diagnostic(problem.from(), problem.to(), Severity.ERROR, message));
      }
      return new Marks(
          diagnostics, current, allMarkers, highlights, usageHighlights, changes, hints);
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
   *     such as a language module's highlighter made again in the same theme
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
    String extra = highlighter == null ? "" : highlighter.wordCharacters();
    String word = state.occurrences && !state.find.isOpen()
        ? word(value, state.caret, state.anchor, extra)
        : null;
    // Where the word at the caret starts, which is not marked: only its other occurrences are.
    int own = word == null
        ? -1
        : state.caret == state.anchor
            ? CodeWords.start(value, Math.min(state.caret, value.length()), extra)
            : Math.min(state.caret, state.anchor);
    El rail = El.el()
        .fill(theme.surface().band())
        .border(0, theme.size().stroke(), 0, 0, theme.border().divider())
        .margin(0, theme.space().m(), 0, 0);
    state.text = value;
    El editor = TextField.codeArea(value, placeholder, state.readOnly ? text -> {} : onChange)
        .whenHovered(null)
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
    state.updateFolds(value, editor, editor.bracketFolds());
    editor.folds(state.folds(), state::ask, fold -> foldMark(theme));
    State.Marks marks = state.marksOf(value);
    List<Diagnostic> diagnostics = marks.diagnostics();
    List<Diagnostic> currentDiagnostics = marks.currentDiagnostics();
    List<Marker> markers = marks.markers();
    List<Highlight> highlights = marks.highlights();
    List<Highlight> usageHighlights = marks.usageHighlights();
    List<Change> changes = marks.changes();
    int markerSlots = maxMarkersPerLine(state.builtText, markers);
    List<InlayHint> inlayHints = marks.hints();
    if (!inlayHints.isEmpty()) {
      List<El.Hint> fieldHints = new ArrayList<>(inlayHints.size());
      for (InlayHint hint : inlayHints) {
        fieldHints.add(new El.Hint(hint.at(), hint.affinity(), hint.text()));
      }
      editor.hints(fieldHints, hint -> inlayHint(theme, hint));
    }
    boolean editable = app != null && editor.onEdit() != null && !editor.isReadOnly();
    List<Fix> fixes = editable ? fixesAt(currentDiagnostics, state.caret) : List.of();
    boolean fixError = !fixes.isEmpty() && fixError(currentDiagnostics, state.caret);
    // The menu closes when its fixes change, so that Enter never chooses one the user did not see.
    if (fixes.isEmpty() || (state.fixesShown != null && !fixes.equals(state.fixesShown))) {
      state.quickFixes.close();
    }
    state.fixesShown = state.quickFixes.isOpen() ? fixes : null;
    state.updateDocs(highlighter, app);
    if (state.navFrom >= 0 && (state.declaration == null || !value.equals(state.navValue))) {
      state.clearNavigation();
    }
    if (state.usagesMenu.isOpen()
        && (state.usagesFound.isEmpty() || !value.equals(state.usagesValue))) {
      state.closeUsagesMenu();
    }
    wireBlocks(theme, state, value, editor);
    if (state.inlineDiagnostics && !diagnostics.isEmpty()) {
      Map<Integer, Diagnostic> inline = inlineDiagnosticsByLine(state.builtText, diagnostics);
      if (!inline.isEmpty()) {
        editor.lineEnds(line -> inlineDiagnostic(theme, inline.get(line)));
      }
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
              if (start + at != own && whole(text, at, at + n, extra)) {
                out.background(at, at + n, code.occurrence());
              }
            }
          }
          for (Highlight h : highlights) {
            if (h.to > start && h.from < end) {
              out.background(h.from - start, h.to - start, code.occurrence());
            }
          }
          if (state.navTarget != null && state.navTo > start && state.navFrom < end) {
            out.underline(
                state.navFrom - start, state.navTo - start, theme.prose().link());
          }
          for (Diagnostic d : diagnostics) {
            if (d.to > start && d.from <= end) {
              out.squiggle(d.from - start, d.to - start, color(theme, d.severity));
            }
          }
        })
        .gutter(
            line -> cell(
                app,
                theme,
                state,
                editor,
                diagnostics,
                markers,
                markerSlots,
                changes,
                !fixes.isEmpty() && editor.onEdit() != null && !editor.isReadOnly(),
                fixError,
                line),
            rail);
    if (state.navTarget != null) {
      editor.cursor(Cursor.POINTER);
    }
    if (!usageHighlights.isEmpty() || !diagnostics.isEmpty() || !changes.isEmpty()) {
      List<Scrollbars.Mark> ticks =
          new ArrayList<>(usageHighlights.size() + diagnostics.size() + changes.size());
      for (Change c : changes) {
        ticks.add(new Scrollbars.Mark(c.from(), c.to(), color(theme, c.kind())));
      }
      for (Highlight h : usageHighlights) {
        int from = Math.max(0, h.from());
        ticks.add(
            new Scrollbars.Mark(from, Math.max(from, h.to()), theme.accent().edge()));
      }
      for (Diagnostic d : diagnostics) {
        int from = Math.max(0, d.from());
        ticks.add(new Scrollbars.Mark(from, Math.max(from, d.to()), color(theme, d.severity)));
      }
      editor.scrollMarks(ticks);
    }
    El message = message(state, diagnostics);
    if (message != null) { // as a tooltip of the keyboard's, only while the editor has the focus
      editor.whenFocusWithin(e -> e.overlay(message));
    }
    if (!fixes.isEmpty() && state.quickFixes.isOpen()) {
      String revision = state.builtText;
      List<Menu.Item> items = new ArrayList<>(fixes.size());
      for (Fix fix : fixes) {
        items.add(Menu.item(fix.label(), () -> applyFix(app, state, revision, fix)));
      }
      editor.overlay(Menu.menu(state.quickFixes, items)
          .atCaret()
          .side(Side.BOTTOM, Style.Align.START, theme.space().xs()));
    }
    if (state.usagesMenu.isOpen() && !state.usagesFound.isEmpty()) {
      List<Menu.Item> items = new ArrayList<>(state.usagesFound.size());
      for (int i = 0; i < state.usagesFound.size(); i++) {
        Location loc = state.usagesFound.get(i);
        items.add(Menu.item(state.usageLabels.get(i), () -> goTo(app, state, editor, loc)));
      }
      editor.overlay(Menu.menu(state.usagesMenu, items)
          .key("usages")
          .label(Texts.CURRENT.get().usages())
          .atCaret()
          .side(Side.BOTTOM, Style.Align.START, theme.space().xs()));
    }
    attachDocumentation(app, theme, state, editor, value, extra);
    attachPointerNavigation(app, state, editor, value, extra);
    // The rail starts at the frame: the field's padding lies after the gutter.
    editor.style().paddingLeft = Len.ZERO;
    if (app == null) {
      if (state.doc != null) {
        editor.onKey(k -> {
          if (k.name().equals("Escape") && k.modifiers() == 0 && state.doc != null) {
            state.closeDocumentation();
            return true;
          }
          return false;
        });
      }
      return editor;
    }
    state.editor = editor;
    state.value = value;
    state.lineComment = highlighter == null ? null : highlighter.lineComment();
    state.formatter = highlighter == null ? null : highlighter.formatter();
    state.currentDiagnostics = currentDiagnostics;
    editor.onKey(k -> {
      if (k.name().equals("Escape") && k.modifiers() == 0 && state.doc != null) {
        state.closeDocumentation();
        return true;
      }
      if (diagnosticKey(app, state, editor, diagnostics, message, k)) {
        return true;
      }
      if (k.name().equals("Escape") && k.modifiers() == 0 && state.clearUsages()) {
        return true;
      }
      Command command = command(k);
      boolean editableNow = editor.onEdit() != null && !editor.isReadOnly();
      boolean formats = state.formatter != null
          && editableNow
          && app.focused() == editor; // not from the find bar, an overlay of the editor
      boolean fixesHere = editableNow
          && app.focused() == editor
          && !fixesAt(state.currentDiagnostics, state.caret).isEmpty();
      if (command == null
          || (command == Command.FORMAT && !formats)
          || (command == Command.QUICK_FIXES && !fixesHere)
          || (command == Command.QUICK_DOCUMENTATION
              && (state.documentation == null || app.focused() != editor))
          || (command == Command.GO_TO_DECLARATION
              && (state.declaration == null || app.focused() != editor))
          || ((command == Command.FIND_USAGES || command == Command.HIGHLIGHT_USAGES)
              && (state.usages == null || app.focused() != editor))
          || ((command == Command.FOLD || command == Command.UNFOLD) && app.focused() != editor)) {
        return false; // keys stay the app's where not applicable
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
              Menu.item(texts.find(), () -> Find.open(state.find)).shortcut(Key.CONTROL, "F"),
              Menu.item(texts.replace(), edits ? () -> Find.openReplace(state.find) : null)
                  .shortcut(Key.CONTROL, "H")));
    });
    // Commands the editor takes only where applicable stay the app's.
    return Find.find(app, editor, state.find, k -> {
      Command command = command(k);
      return command != null
          && command != Command.FORMAT
          && command != Command.QUICK_FIXES
          && (command != Command.QUICK_DOCUMENTATION || state.documentation != null)
          && (command != Command.GO_TO_DECLARATION || state.declaration != null)
          && ((command != Command.FIND_USAGES && command != Command.HIGHLIGHT_USAGES)
              || state.usages != null);
    });
  }

  /**
   * Gives an editor of this class the look of the body of a framed card or panel, such as a {@link
   * com.rethinkj.ui.kit.Card#flush} card under its {@link com.rethinkj.ui.kit.Card#toolbar}: as
   * wide as the card, without corners or a frame of its own, and with a hairline along its top
   * ({@link Theme.Borders#divider}) in place of a {@link com.rethinkj.ui.kit.Divider}, which takes
   * the accent's edge color while the editor has the focus. The ring of the keyboard's focus lies
   * just inside it, where the card clips it.
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
    if (editor.onEdit() != null) {
      editor.whenFocused(e -> e.border(stroke, 0, 0, 0, theme.accent().edge()));
    }
    return editor;
  }

  /** The editor's blocks between lines, all kinds in one list, and the element of each. */
  private static void wireBlocks(Theme theme, State state, String value, El editor) {
    if (!value.equals(state.visionOf) || state.codeVision != state.visionFor) {
      state.visionRows = CodeVision.rows(editor.text().string(), state.codeVision);
      state.visionOf = value;
      state.visionFor = state.codeVision;
    }
    Map<Integer, CodeVision.Row> vision = state.visionRows;
    List<DocRange> rendered = state.renderedDocs();
    if (vision.isEmpty() && rendered.isEmpty()) {
      return;
    }
    List<El.Block> blocks = new ArrayList<>(vision.size() + rendered.size());
    for (int line : vision.keySet()) {
      blocks.add(El.Block.insert(line));
    }
    Map<El.Block, DocRange> docs = new HashMap<>();
    for (DocRange doc : rendered) {
      El.Block block = El.Block.replace(doc.firstLine(), doc.lastLine());
      blocks.add(block);
      docs.put(block, doc);
    }
    float space = rendered.isEmpty() ? 0 : spaceWidth(theme);
    editor.blocks(
        blocks,
        block -> block.placement() == El.Block.Placement.INSERT
            ? CodeVision.row(theme, editor, block.first(), vision.get(block.first()))
            : renderedDoc(theme, state, docs.get(block), space),
        block -> {
          DocRange doc = docs.get(block);
          if (doc != null) {
            state.revealDoc(doc);
          }
        });
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
   * The most severe diagnostic with a non-empty message on each line of {@code text}: ERROR over
   * WARNING, then the first by {@code from}.
   */
  private static Map<Integer, Diagnostic> inlineDiagnosticsByLine(
      String text, List<Diagnostic> diagnostics) {
    int len = text.length();
    List<Diagnostic> eligible = new ArrayList<>(diagnostics.size());
    for (Diagnostic d : diagnostics) {
      if (d.from >= 0 && d.from <= len && !d.message.isEmpty()) {
        eligible.add(d);
      }
    }
    if (eligible.isEmpty()) {
      return Map.of();
    }
    eligible.sort(Comparator.comparingInt(Diagnostic::from));
    Map<Integer, Diagnostic> byLine = HashMap.newHashMap(eligible.size());
    int cursor = 0;
    int line = 0;
    for (Diagnostic d : eligible) {
      while (cursor < d.from) {
        if (text.charAt(cursor++) == '\n') {
          line++;
        }
      }
      Diagnostic current = byLine.get(line);
      if (current == null || (current.severity != Severity.ERROR && d.severity == Severity.ERROR)) {
        byLine.put(line, d);
      }
    }
    return byLine;
  }

  private static List<El> inlineDiagnostic(Theme theme, Diagnostic diagnostic) {
    if (diagnostic == null) {
      return List.of();
    }
    Theme.TextStyle small = theme.type().small();
    int muted = Colors.mix(color(theme, diagnostic.severity), theme.text().muted(), 0.35);
    // One row: a message's line breaks would draw over the lines around it.
    return List.of(El.text(diagnostic.message.replaceAll("\\R", " "))
        .font(small.font().get())
        .fontSize(small.size())
        .color(muted)
        .margin(0, 0, 0, theme.space().l())
        .inert());
  }

  private static int color(Theme theme, Change.Kind kind) {
    return switch (kind) {
      case ADDED -> theme.status().success();
      case MODIFIED -> theme.accent().edge();
      case DELETED -> theme.status().danger();
    };
  }

  /** The most app markers on any single line of {@code text}, or 0 when there are none. */
  private static int maxMarkersPerLine(String text, List<Marker> markers) {
    if (markers.isEmpty()) {
      return 0;
    }
    int len = text.length();
    int[] positions = new int[markers.size()];
    int n = 0;
    for (Marker m : markers) {
      if (m.at >= 0 && m.at <= len) {
        positions[n++] = m.at;
      }
    }
    if (n <= 1) {
      return n;
    }
    Arrays.sort(positions, 0, n);
    int max = 0;
    int currentLine = -1;
    int count = 0;
    int cursor = 0;
    int line = 0;
    for (int i = 0; i < n; i++) {
      int pos = positions[i];
      while (cursor < pos) {
        if (text.charAt(cursor++) == '\n') {
          line++;
        }
      }
      if (line != currentLine) {
        currentLine = line;
        count = 1;
      } else {
        count++;
      }
      if (count > max) {
        max = count;
      }
    }
    return max;
  }

  /**
   * The gutter's cell of a line: a bar in the accent's edge and a tint where the line is in the
   * marked block, then the lanes in fixed order — icons (diagnostic/bulb slot plus app marker
   * slots), the line's number, the VCS change bar, and the fold/doc toggle.
   */
  private static El cell(
      App<?> app,
      Theme theme,
      State state,
      El editor,
      List<Diagnostic> diagnostics,
      List<Marker> markers,
      int markerSlots,
      List<Change> changes,
      boolean hasFixes,
      boolean fixError,
      El.Gutter.Line line) {
    Theme.TextStyle mono = theme.type().mono();
    float bar = theme.size().indicator();
    float s = theme.space().s();
    boolean block = state.blockFrom < state.blockTo
        && state.blockTo > line.start()
        && state.blockFrom <= line.end();
    El cell = El.row().align(Style.Align.CENTER).gap(s).padding(0, s, 0, block ? s : s + bar);
    if (block) {
      cell.fill(theme.code().block()).border(0, 0, 0, bar, theme.accent().edge());
    }
    cell.child(marker(theme, diagnostics, hasFixes, fixError, state.quickFixes::open, line)
        .w(mono.size())
        .shrink(0));
    if (markerSlots > 0) {
      int placed = 0;
      for (Marker m : markers) {
        if (m.at >= line.start() && m.at <= line.end()) {
          placed++;
          cell.child(appMarker(theme, m, placed).w(mono.size()).shrink(0));
          if (placed == markerSlots) {
            break;
          }
        }
      }
      for (int i = placed; i < markerSlots; i++) {
        cell.child(El.el().w(mono.size()).shrink(0));
      }
    }
    cell.child(El.text(String.valueOf(line.index() + 1))
        .font(mono.font().get())
        .fontSize(mono.size())
        .color(line.caret() ? theme.text().primary() : theme.text().muted())
        .textAlign(Style.Align.END)
        .grow(1));
    cell.child(changeBar(theme, changes, line));
    cell.child(gutterControl(app, theme, state, editor, cell, line));
    return cell;
  }

  /**
   * The VCS change bar of a line: full cell height for added or modified lines, a short bar at the
   * top of the cell for lines deleted before it, or an empty fixed-width placeholder.
   */
  private static El changeBar(Theme theme, List<Change> changes, El.Gutter.Line line) {
    float bar = theme.size().indicator();
    Change.Kind kind = null;
    boolean after = false; // a deletion at the end of a line is after it
    for (Change c : changes) {
      boolean hits = c.kind == Change.Kind.DELETED || c.from == c.to
          ? c.from >= line.start() && c.from <= line.end()
          : c.to > line.start() && c.from <= line.end();
      if (hits) {
        kind = c.kind;
        after = c.from == line.end() && line.end() > line.start();
        if (kind != Change.Kind.DELETED) {
          break;
        }
      }
    }
    if (kind == null) {
      return El.el().w(bar).shrink(0);
    }
    El mark = El.el().key("change").w(bar).shrink(0).fill(color(theme, kind));
    if (kind == Change.Kind.DELETED) {
      return mark.h(theme.size().dot()).alignSelf(after ? Style.Align.END : Style.Align.START);
    }
    return mark.alignSelf(Style.Align.STRETCH);
  }

  /**
   * The right-hand control of a gutter cell: the doc-comment toggle on a doc comment's first line
   * (pencil when rendered, eye when raw in reader mode), otherwise the fold chevron.
   */
  private static El gutterControl(
      App<?> app, Theme theme, State state, El editor, El cell, El.Gutter.Line line) {
    DocRange doc = state.docAtLine(line.index());
    if (doc != null) {
      boolean rendered = state.isDocRendered(doc);
      if (rendered || state.readerMode) {
        float size = theme.type().mono().size();
        Texts texts = Texts.CURRENT.get();
        String label = rendered ? texts.editDocComment() : texts.renderDocComment();
        Icon icon = rendered ? Symbol.EDIT : Symbol.EYE;
        El button = El.row()
            .key("doc-line-" + line.index())
            .role("button")
            .label(label)
            .size(size, size)
            .align(Style.Align.CENTER)
            .justify(Style.Content.CENTER)
            .shrink(0)
            .radius(theme.radius().small())
            .cursor(Cursor.POINTER)
            .onPress(() -> toggleDoc(app, state, editor, doc, false))
            .keepFocus()
            .whenHovered(e -> e.fill(theme.surface().hover()))
            .child(Icon.icon(icon, Icon.Variant.MUTED, size));
        return Tooltip.tooltip(button, label);
      }
    }
    return foldControl(app, theme, state, editor, cell, line);
  }

  /**
   * The fold chevron of a line: always shown when folded, shown on hover of the cell when the line
   * starts a foldable range, or an empty placeholder of the same width so line numbers stay fixed.
   */
  private static El foldControl(
      App<?> app, Theme theme, State state, El editor, El cell, El.Gutter.Line line) {
    float size = theme.type().mono().size();
    Texts texts = Texts.CURRENT.get();
    El.Fold folded = state.foldAt(line.index());
    if (folded != null) {
      return El.row()
          .key("fold-line-" + line.index())
          .role("button")
          .label(texts.unfold())
          .size(size, size)
          .align(Style.Align.CENTER)
          .justify(Style.Content.CENTER)
          .shrink(0)
          .radius(theme.radius().small())
          .cursor(Cursor.POINTER)
          .onPress(() -> state.unfold(folded))
          .keepFocus()
          .whenHovered(e -> e.fill(theme.surface().hover()))
          .child(Icon.icon(Symbol.CHEVRON_RIGHT, Icon.Variant.MUTED, size));
    }
    El.BracketFold range = rangeAt(state.ranges, line.index());
    if (range != null) {
      El button = El.row()
          .key("fold-line-" + line.index())
          .role("button")
          .label(texts.fold())
          .size(size, size)
          .align(Style.Align.CENTER)
          .justify(Style.Content.CENTER)
          .shrink(0)
          .radius(theme.radius().small())
          .cursor(Cursor.POINTER)
          .opacity(0)
          .onPress(() -> foldRange(app, state, editor, range, false))
          .keepFocus()
          .whenHovered(e -> e.fill(theme.surface().hover()))
          .child(Icon.icon(Symbol.CHEVRON_DOWN, Icon.Variant.MUTED, size));
      cell.whenHovered(e -> button.opacity(1));
      return button;
    }
    return El.el().w(size).shrink(0);
  }

  /** The placeholder pill shown after the end of a folded line; a press unfolds it (wired by core). */
  private static El foldMark(Theme theme) {
    float size = theme.type().mono().size();
    Texts texts = Texts.CURRENT.get();
    int hover = Theme.over(theme.code().bracket(), theme.surface().hover());
    return El.row()
        .role("button")
        .label(texts.unfold())
        .align(Style.Align.CENTER)
        .justify(Style.Content.CENTER)
        .padding(0, theme.space().s())
        .radius(theme.radius().small())
        .fill(theme.code().bracket())
        .border(theme.size().stroke(), theme.border().subtle())
        .whenHovered(e -> e.fill(hover))
        .child(Icon.icon(Symbol.MORE_HORIZONTAL, Icon.Variant.MUTED, size));
  }

  /** The subtle rounded pill of an inline hint, in the code font a bit smaller and muted. */
  private static El inlayHint(Theme theme, El.Hint hint) {
    Theme.TextStyle mono = theme.type().mono();
    float size = Math.max(theme.size().stroke(), mono.size() - theme.size().stroke());
    float xs = theme.space().xs();
    return El.row()
        .align(Style.Align.CENTER)
        .padding(0, theme.space().s())
        .margin(0, xs, 0, xs)
        .radius(theme.radius().small())
        .fill(theme.code().bracket())
        .child(El.text(hint.text())
            .font(mono.font().get())
            .fontSize(size)
            .color(theme.text().muted()));
  }

  /**
   * The first icon slot of a line: the intention bulb on the caret's line where there are quick
   * fixes at the caret, else of the diagnostics that start on it; an empty place for one where
   * there is none, so that the lane stays a fixed width while typing.
   */
  private static El marker(
      Theme theme,
      List<Diagnostic> diagnostics,
      boolean hasFixes,
      boolean fixError,
      Runnable openFixes,
      El.Gutter.Line line) {
    Texts texts = Texts.CURRENT.get(); // the gutter builds in the view's values
    float size = theme.type().mono().size();
    El place = El.row().key("marker").justify(Style.Content.CENTER);
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
    if (line.caret() && hasFixes) { // the bulb's tooltip keeps the line's messages
      String label = texts.quickFixes();
      place
          .role("button")
          .label(label)
          .cursor(Cursor.POINTER)
          .onPress(openFixes)
          .keepFocus()
          .child(Icon.icon(
              Symbol.LIGHTBULB, fixError ? Icon.Variant.DANGER : Icon.Variant.WARNING, size));
      place.errorMessage(error ? errors.toString() : null);
      place.warningMessage(warnings.isEmpty() ? null : warnings.toString());
      return Tooltip.tooltip(
          place,
          messages.isEmpty() ? label : messages + "\n" + label,
          Tooltip.options().kind(error ? Callout.Kind.DANGER : Callout.Kind.WARNING));
    }
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
    return El.el();
  }

  /** One app marker slot in the gutter's icon lane. */
  private static El appMarker(Theme theme, Marker marker, int slot) {
    float size = theme.type().mono().size();
    El place = El.row()
        .key("marker-" + slot)
        .justify(Style.Content.CENTER)
        .role(marker.onPress == null ? "img" : "button")
        .label(marker.label)
        .child(Icon.icon(marker.symbol, marker.tone, size));
    if (marker.onPress != null) {
      place.onPress(marker.onPress).keepFocus();
    }
    return Tooltip.tooltip(place, marker.label);
  }

  /**
   * The message of the diagnostics that start where F8 put the caret, above the caret, where a
   * list of completions does not cover it, on the look of their marker's tooltip while the caret
   * is at their start or inside them; else null, and F8's place is forgotten.
   */
  private static El message(State state, List<Diagnostic> diagnostics) {
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
      return true;
    }
    if (!k.name().equals("F8") || (k.modifiers() & ~Key.SHIFT) != 0 || diagnostics.isEmpty()) {
      return false;
    }
    int at = next(diagnostics, state.caret, k.modifiers() == Key.SHIFT);
    goTo(app, state, editor, at);
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
  static String word(String text, int caret, int anchor, String extra) {
    int s = Math.clamp(Math.min(caret, anchor), 0, text.length());
    int e = Math.clamp(Math.max(caret, anchor), 0, text.length());
    if (s == e) {
      s = CodeWords.start(text, s, extra);
      e = CodeWords.end(text, e, extra);
    }
    if (s == e || !whole(text, s, e, extra)) {
      return null;
    }
    for (int i = s; i < e; i = text.offsetByCodePoints(i, 1)) {
      if (!CodeWords.isWordChar(text.codePointAt(i), extra)) {
        return null;
      }
    }
    return text.substring(s, e);
  }

  /** Whether no code-word character lies right before {@code from} or after {@code to}. */
  static boolean whole(String text, int from, int to, String extra) {
    return (from == 0 || !CodeWords.isWordChar(text.codePointBefore(from), extra))
        && (to == text.length() || !CodeWords.isWordChar(text.codePointAt(to), extra));
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
    if (command == Command.FOLD) {
      foldAtCaret(app, state, editor, text, caret);
      return;
    }
    if (command == Command.UNFOLD) {
      unfoldAtCaret(state, text, caret);
      return;
    }
    if (command == Command.QUICK_DOCUMENTATION) {
      showDocumentationAtCaret(state, editor, text, caret);
      return;
    }
    if (command == Command.TOGGLE_DOC_COMMENT) {
      toggleDocCommentAtCaret(app, state, editor, text, caret);
      return;
    }
    if (command == Command.GO_TO_DECLARATION) {
      goToDeclaration(app, state, editor, text, caret);
      return;
    }
    if (command == Command.FIND_USAGES) {
      findUsages(state, editor, text, caret);
      return;
    }
    if (command == Command.HIGHLIGHT_USAGES) {
      highlightUsages(state, editor, text, caret);
      return;
    }
    if (editor.onEdit() == null || editor.isReadOnly()) {
      return;
    }
    if (command == Command.QUICK_FIXES) {
      if (text.equals(state.builtText)
          && !fixesAt(state.currentDiagnostics, caret).isEmpty()) {
        state.quickFixes.openByKey();
      }
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
    app.edit(editor, edit.from(), edit.to(), edit.text(), edit.caret(), edit.anchor());
  }

  /** The quick fixes of current-revision diagnostics whose range contains {@code caret}. */
  private static List<Fix> fixesAt(List<Diagnostic> current, int caret) {
    List<Fix> fixes = List.of();
    for (Diagnostic d : current) {
      int to = Math.max(d.from, d.to);
      if (caret >= d.from && caret <= to && !d.fixes.isEmpty()) {
        if (fixes.isEmpty()) {
          fixes = new ArrayList<>();
        }
        fixes.addAll(d.fixes);
      }
    }
    return fixes;
  }

  /** Whether any current-revision diagnostic with quick fixes at {@code caret} is an error. */
  private static boolean fixError(List<Diagnostic> current, int caret) {
    for (Diagnostic d : current) {
      int to = Math.max(d.from, d.to);
      if (caret >= d.from && caret <= to && !d.fixes.isEmpty() && d.severity == Severity.ERROR) {
        return true;
      }
    }
    return false;
  }

  /** Applies {@code fix} as one undoable edit if the field is still editable and on {@code revision}. */
  private static void applyFix(App<?> app, State state, String revision, Fix fix) {
    El editor = state.editor;
    if (editor == null || editor.onEdit() == null || editor.isReadOnly()) {
      return;
    }
    int from = Math.min(fix.from(), revision.length());
    int to = Math.min(fix.to(), revision.length());
    int caret = from + fix.text().length();
    app.edit(editor, from, to, fix.text(), caret, caret, revision);
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
    goTo(app, state, editor, at);
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
      String extra = editor.text().wordCharacters();
      next = new int[] {CodeWords.start(text, s, extra), CodeWords.end(text, s, extra)};
      if (next[0] == next[1]) {
        return;
      }
      state.nextWords = true;
    } else {
      boolean ours = s == state.nextFrom && e == state.nextTo;
      state.nextWords = ours && state.nextWords;
      next =
          Find.next(text, text.substring(s, e), state.nextWords, editor.text().wordCharacters(), e);
      if (next.length == 0) {
        return;
      }
    }
    state.nextFrom = next[0];
    state.nextTo = next[1];
    app.select(editor, next[1], next[0]);
    state.caret = next[1];
    state.anchor = next[0];
  }

  private static void foldAtCaret(App<?> app, State state, El editor, String text, int caret) {
    int line = lineOf(text, caret);
    El.BracketFold best = null;
    for (El.BracketFold range : state.ranges) {
      if (range.lines().first() > line) {
        break;
      }
      // Up to its closing bracket's line, which stays shown.
      if (line <= range.lines().last() + 1 && state.foldAt(range.lines().first()) == null) {
        best = range;
      }
    }
    if (best != null) {
      foldRange(app, state, editor, best, true);
    }
  }

  private static void unfoldAtCaret(State state, String text, int caret) {
    int line = lineOf(text, caret);
    El.Fold best = null;
    for (Folded f : state.folds) {
      if (f.lines().first() > line) {
        break;
      }
      if (line <= f.lines().last() + 1) {
        best = f.lines();
      }
    }
    if (best != null) {
      state.unfold(best);
    }
  }

  private static void foldRange(
      App<?> app, State state, El editor, El.BracketFold pair, boolean alwaysMoveCaret) {
    state.fold(pair);
    El.Fold range = pair.lines();
    String text = editor.text().string();
    int caretLine = lineOf(text, Math.min(state.caret, text.length()));
    int anchorLine = lineOf(text, Math.min(state.anchor, text.length()));
    boolean hidden = (caretLine > range.first() && caretLine <= range.last())
        || (anchorLine > range.first() && anchorLine <= range.last());
    if (alwaysMoveCaret || hidden) {
      int at = lineEnd(text, range.first());
      if (app != null) {
        app.select(editor, at, at);
        state.caret = at;
        state.anchor = at;
      } else {
        state.select(at, at);
      }
    }
  }

  private static El.BracketFold rangeAt(List<El.BracketFold> ranges, int line) {
    int lo = 0;
    int hi = ranges.size() - 1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      int first = ranges.get(mid).lines().first();
      if (first == line) {
        return ranges.get(mid);
      }
      if (first < line) {
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return null;
  }

  /**
   * A folded pair of brackets: the lines it hides, and where its brackets are in the string as the
   * editor shows it; both -1 for one set by its lines ({@link State#folds(List)}) and not found yet.
   */
  record Folded(El.Fold lines, int open, int close) {}

  /** {@code text} as a field shows it, with each line break one line feed ({@link El#multiline}). */
  private static String displayed(String text) {
    return text.replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace('\u2028', '\n')
        .replace('\u2029', '\n');
  }

  /** The folds sorted by their first line, the widest where several start on one line. */
  private static List<Folded> sorted(List<Folded> folds) {
    Map<Integer, Folded> byFirst = new HashMap<>();
    for (Folded f : folds) {
      byFirst.merge(
          f.lines().first(), f, (a, b) -> a.lines().last() >= b.lines().last() ? a : b);
    }
    List<Folded> out = new ArrayList<>(byFirst.values());
    out.sort(Comparator.comparingInt(f -> f.lines().first()));
    return out;
  }

  /**
   * Moves the brackets of {@code folds} across {@code edits}: each
   * bracket moves with the text around it, and a fold whose bracket was deleted or replaced goes.
   * Folds not found yet stay as they are.
   */
  static List<Folded> shiftFolds(TextEdits edits, List<Folded> folds) {
    List<Folded> out = new ArrayList<>(folds.size());
    for (Folded f : folds) {
      if (f.open() < 0) {
        out.add(f);
        continue;
      }
      int open = edits.mapRange(f.open(), f.open() + 1);
      int close = edits.mapRange(f.close(), f.close() + 1);
      if (open >= 0 && close >= 0) {
        out.add(new Folded(f.lines(), open, close)); // its lines are found again
      }
    }
    return out;
  }

  /**
   * Keeps each fold whose pair of brackets is still a pair that can fold in {@code found}, with its
   * lines now; one set by its lines takes the pair on its first line. While {@code found} is not
   * complete, a fold whose closing bracket has not been read yet stays.
   *
   * @param value the editor's string, which the pairs' positions are in
   */
  static List<Folded> reconcileFolds(List<Folded> folds, El.BracketFolds found, String value) {
    Map<Integer, El.BracketFold> byOpen = new HashMap<>();
    for (El.BracketFold pair : found.folds()) {
      byOpen.put(pair.open(), pair);
    }
    int scanned = found.scannedLines();
    List<Folded> out = new ArrayList<>(folds.size());
    for (Folded f : folds) {
      if (f.open() < 0) {
        El.BracketFold pair = rangeAt(found.folds(), f.lines().first());
        if (!found.complete() && f.lines().last() + 1 >= scanned) {
          out.add(f);
        } else if (pair != null) {
          out.add(new Folded(pair.lines(), pair.open(), pair.close()));
        }
        continue;
      }
      El.BracketFold pair = byOpen.get(f.open());
      if (pair != null && pair.close() == f.close()) {
        out.add(new Folded(pair.lines(), pair.open(), pair.close()));
      } else if (!found.complete()) {
        int first = lineOf(value, f.open());
        int closing = lineOf(value, f.close());
        if (closing >= scanned && closing > first + 1) {
          out.add(new Folded(new El.Fold(first, closing - 1), f.open(), f.close()));
        }
      }
    }
    return sorted(out);
  }

  /** Width of one monospace space at the current window scale, for indenting rendered comments. */
  private static float spaceWidth(Theme theme) {
    Theme.TextStyle mono = theme.type().mono();
    El sample = El.text(" ").font(mono.font().get()).fontSize(mono.size());
    sample.text().scale(WindowState.CURRENT.get().scale());
    return sample
        .text()
        .measure(Float.NaN, Float.NaN, Measure.MAX_CONTENT, Measure.MAX_CONTENT)
        .width();
  }

  /** Builds the rendered view of one doc comment, indented to its column in the comment color. */
  private static El renderedDoc(Theme theme, State state, DocRange doc, float spaceWidth) {
    String raw = state.builtText.substring(doc.start(), doc.end());
    DocComment parsed = state.docComment(state.language, raw);
    float indent = doc.indentColumns() * spaceWidth;
    El container = El.col()
        .key("doc-" + doc.firstLine())
        .gap(theme.space().m())
        .padding(theme.space().xs(), theme.space().m(), theme.space().s(), indent)
        .wFull();
    if (parsed == null) {
      return container.child(rawCommentFallback(theme, raw));
    }
    Texts texts = Texts.CURRENT.get();
    Markdown.Options options =
        Markdown.options().textColor(theme.code().comment()).onLink(state.onLink);
    if (parsed.body() != null && !parsed.body().blocks().isEmpty()) {
      container.child(Markdown.fragment(parsed.body(), options));
    }
    List<Markdown.Section> sections = new ArrayList<>();
    if (parsed.deprecated() != null) {
      sections.add(new Markdown.Section(
          texts.deprecated(), List.of(new Markdown.Definition(null, parsed.deprecated()))));
    }
    if (!parsed.parameters().isEmpty()) {
      sections.add(new Markdown.Section(
          texts.parameters(),
          parsed.parameters().stream().map(CodeEditor::definition).toList()));
    }
    if (parsed.returns() != null && !parsed.returns().blocks().isEmpty()) {
      sections.add(new Markdown.Section(
          texts.returns(), List.of(new Markdown.Definition(null, parsed.returns()))));
    }
    if (!parsed.exceptions().isEmpty()) {
      sections.add(new Markdown.Section(
          texts.thrown(),
          parsed.exceptions().stream().map(CodeEditor::definition).toList()));
    }
    if (!parsed.see().isEmpty()) {
      sections.add(new Markdown.Section(
          texts.seeAlso(),
          parsed.see().stream().map(s -> new Markdown.Definition(null, s)).toList()));
    }
    if (!sections.isEmpty()) {
      container.child(Markdown.definitions(sections, options));
    }
    if (container.children().isEmpty()) {
      container.child(rawCommentFallback(theme, raw));
    }
    return container;
  }

  private static El rawCommentFallback(Theme theme, String raw) {
    Theme.TextStyle mono = theme.type().mono();
    return El.text(raw)
        .font(mono.font().get())
        .fontSize(mono.size())
        .color(theme.code().comment());
  }

  private static Markdown.Definition definition(DocComment.Entry entry) {
    String name = entry.name();
    String type = entry.type();
    String term = name.isEmpty()
        ? (type == null || type.isEmpty() ? null : type)
        : (type == null || type.isEmpty() ? name : name + ": " + type);
    return new Markdown.Definition(term, entry.description());
  }

  private static void toggleDocCommentAtCaret(
      App<?> app, State state, El editor, String text, int caret) {
    if (state.language == null) {
      return;
    }
    state.ensureDocRanges(text);
    int line = lineOf(text, caret);
    DocRange target = state.docContainingLine(line);
    if (target == null) {
      // Caret cannot enter a rendered block: check the rendered block immediately above this line,
      // or immediately below if this is the last visible line before a trailing rendered block.
      for (DocRange r : state.docRanges) {
        if (state.isDocRendered(r) && r.lastLine() + 1 == line) {
          target = r;
          break;
        }
      }
      if (target == null) {
        for (DocRange r : state.docRanges) {
          if (state.isDocRendered(r) && r.firstLine() == line + 1) {
            target = r;
            break;
          }
        }
      }
    }
    if (target != null) {
      toggleDoc(app, state, editor, target, true);
    }
  }

  private static void toggleDoc(
      App<?> app, State state, El editor, DocRange doc, boolean moveCaretOnReveal) {
    boolean nowRendered = state.flipDoc(doc);
    String text = editor.text().string();
    if (nowRendered) {
      int caretLine = lineOf(text, Math.min(state.caret, text.length()));
      int anchorLine = lineOf(text, Math.min(state.anchor, text.length()));
      boolean inside = (caretLine >= doc.firstLine() && caretLine <= doc.lastLine())
          || (anchorLine >= doc.firstLine() && anchorLine <= doc.lastLine());
      if (inside) {
        int at = safePositionOutside(text, state, doc);
        if (at >= 0) {
          selectCaret(app, state, editor, at);
        }
      }
    } else if (moveCaretOnReveal) {
      selectCaret(app, state, editor, doc.start());
    }
  }

  private static void selectCaret(App<?> app, State state, El editor, int at) {
    if (app != null) {
      app.select(editor, at, at);
      state.caret = at;
      state.anchor = at;
    } else {
      state.select(at, at);
    }
  }

  /**
   * First caret position outside {@code doc} (and any other rendered doc comment): the start of the
   * first visible line after {@code doc}, else the end of the last visible line before it, or -1 if
   * no line is visible.
   */
  private static int safePositionOutside(String text, State state, DocRange doc) {
    int total = lineCount(text);
    for (int line = doc.lastLine() + 1; line < total; line++) {
      DocRange covering = state.docContainingLine(line);
      if (covering == null || !state.isDocRendered(covering)) {
        return lineStart(text, line);
      }
    }
    for (int line = doc.firstLine() - 1; line >= 0; line--) {
      DocRange covering = state.docContainingLine(line);
      if (covering == null || !state.isDocRendered(covering)) {
        return lineEnd(text, line);
      }
    }
    return -1;
  }

  /** One standalone doc comment eligible to render, in the normalized text of the last build. */
  record DocRange(int start, int end, int firstLine, int lastLine, int indentColumns) {}

  /** One per-comment override of {@link State#readerMode}, tracked by character offsets. */
  record DocToggle(int start, int end, boolean edited) {}

  /**
   * The doc comments to check: those {@code scanned} found that end before {@code boundary}, where
   * the last line scanned starts and a comment may go on, and then those of {@code before}, moved
   * to the current string, that end after it.
   */
  static List<Comments.Comment> candidates(Comments scanned, List<DocRange> before, int boundary) {
    List<Comments.Comment> out = new ArrayList<>();
    int last = 0;
    for (Comments.Comment c : scanned.comments()) {
      if (c.end() <= boundary) {
        if (c.doc()) {
          out.add(c);
        }
        last = c.end();
      }
    }
    for (DocRange d : before) {
      if (d.end() > boundary && d.start() >= last) {
        out.add(new Comments.Comment(d.start(), d.end(), true));
      }
    }
    return out;
  }

  /** The doc ranges moved across {@code edits}; a range the edits touch goes. */
  static List<DocRange> moveDocs(List<DocRange> docs, TextEdits edits) {
    List<DocRange> out = new ArrayList<>(docs.size());
    for (DocRange d : docs) {
      int at = edits.mapRange(d.start(), d.end());
      if (at >= 0) { // its lines are counted again
        out.add(new DocRange(at, at + d.end() - d.start(), -1, -1, 0));
      }
    }
    return out;
  }

  /**
   * The doc comments of {@code all} that render: those that share neither their first nor their
   * last line with code or another comment and have more than delimiters.
   */
  static List<DocRange> eligibleDocs(String text, List<Comments.Comment> all) {
    if (all.isEmpty()) {
      return List.of();
    }
    List<DocRange> out = new ArrayList<>();
    int cursor = 0;
    int line = 0;
    for (Comments.Comment c : all) {
      while (cursor < c.start()) {
        if (text.charAt(cursor++) == '\n') {
          line++;
        }
      }
      int firstLine = line;
      while (cursor < c.end()) {
        if (text.charAt(cursor++) == '\n') {
          line++;
        }
      }
      int lastLine = line;
      int firstStart = text.lastIndexOf('\n', c.start() - 1) + 1;
      if (!isHorizontalSpace(text, firstStart, c.start())) {
        continue;
      }
      int nextFeed = text.indexOf('\n', c.end());
      int lastEnd = nextFeed < 0 ? text.length() : nextFeed;
      if (!isHorizontalSpace(text, c.end(), lastEnd)) {
        continue;
      }
      if (!hasDocContent(text, c.start(), c.end())) {
        continue;
      }
      int indent = 0;
      for (int i = firstStart; i < c.start(); i++) {
        indent = text.charAt(i) == '\t' ? (indent / INDENT + 1) * INDENT : indent + 1;
      }
      out.add(new DocRange(c.start(), c.end(), firstLine, lastLine, indent));
    }
    return out;
  }

  private static boolean isHorizontalSpace(String text, int from, int to) {
    for (int i = from; i < to; i++) {
      char c = text.charAt(i);
      if (c != ' ' && c != '\t') {
        return false;
      }
    }
    return true;
  }

  /** Whether the doc comment contains any character besides its delimiters, stars and whitespace. */
  private static boolean hasDocContent(String text, int start, int end) {
    if (text.startsWith("///", start)) {
      int pos = start;
      while (pos < end) {
        int feed = text.indexOf('\n', pos);
        int lineEnd = feed < 0 || feed > end ? end : feed;
        int i = pos;
        while (i < lineEnd && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
          i++;
        }
        if (i + 3 <= lineEnd && text.startsWith("///", i)) {
          i += 3;
        }
        while (i < lineEnd) {
          if (!Character.isWhitespace(text.charAt(i))) {
            return true;
          }
          i++;
        }
        pos = feed < 0 ? end : feed + 1;
      }
      return false;
    }
    if (text.startsWith("/**", start)) {
      int from = start + 3;
      int to = end >= from + 2 && text.charAt(end - 2) == '*' && text.charAt(end - 1) == '/'
          ? end - 2
          : end;
      int pos = from;
      while (pos < to) {
        int feed = text.indexOf('\n', pos);
        int lineEnd = feed < 0 || feed > to ? to : feed;
        int i = pos;
        while (i < lineEnd && Character.isWhitespace(text.charAt(i))) {
          i++;
        }
        while (i < lineEnd && text.charAt(i) == '*') {
          i++;
        }
        while (i < lineEnd) {
          if (!Character.isWhitespace(text.charAt(i))) {
            return true;
          }
          i++;
        }
        pos = feed < 0 ? to : feed + 1;
      }
      return false;
    }
    return end > start;
  }

  private static List<DocToggle> sortedDocToggles(List<DocToggle> toggles) {
    Map<Integer, DocToggle> byStart = new HashMap<>();
    for (DocToggle t : toggles) {
      byStart.merge(t.start(), t, (a, b) -> a.end() >= b.end() ? a : b);
    }
    List<DocToggle> out = new ArrayList<>(byStart.values());
    out.sort(Comparator.comparingInt(DocToggle::start));
    return out;
  }

  /**
   * Moves the toggles across {@code edits}. With {@code keepEdited}, a toggle whose comment the
   * edits touch keeps the span between its moved ends, marked edited, so the comment edited there
   * stays raw; otherwise it goes.
   */
  static List<DocToggle> shiftDocToggles(
      TextEdits edits, List<DocToggle> toggles, boolean keepEdited) {
    List<DocToggle> out = new ArrayList<>(toggles.size());
    for (DocToggle t : toggles) {
      int at = edits.mapRange(t.start(), t.end());
      if (at >= 0) {
        out.add(new DocToggle(at, at + t.end() - t.start(), t.edited()));
      } else if (keepEdited) {
        int start = edits.mapPoint(t.start());
        int end = edits.mapPoint(t.end());
        if (start >= 0 && end > start) {
          out.add(new DocToggle(start, end, true));
        }
      }
    }
    return out;
  }

  /**
   * Keeps the toggles of comments in {@code found}; an edited one takes each comment it overlaps.
   * Toggles that end past {@code boundary}, where the scan has not been yet, stay as they are.
   */
  static List<DocToggle> reconcileDocToggles(
      List<DocToggle> toggles, List<DocRange> found, int boundary) {
    if (toggles.isEmpty()) {
      return toggles;
    }
    Map<Integer, DocRange> byStart = HashMap.newHashMap(found.size());
    for (DocRange r : found) {
      byStart.put(r.start(), r);
    }
    List<DocToggle> out = new ArrayList<>(toggles.size());
    for (DocToggle t : toggles) {
      DocRange exact = byStart.get(t.start());
      boolean bound = false;
      if (exact != null && exact.end() == t.end()) {
        out.add(new DocToggle(exact.start(), exact.end(), false));
        bound = true;
      } else if (t.edited()) {
        for (DocRange r : found) {
          if (r.start() < t.end() && t.start() < r.end()) {
            out.add(new DocToggle(r.start(), r.end(), false));
            bound = true;
          }
        }
      }
      if (!bound && t.end() > boundary) {
        out.add(t);
      }
    }
    return sortedDocToggles(out);
  }

  private static int lineCount(String text) {
    int lines = 1;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        lines++;
      }
    }
    return lines;
  }

  private static int lineStart(String text, int targetLine) {
    if (targetLine <= 0) {
      return 0;
    }
    int line = 0;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        line++;
        if (line == targetLine) {
          return i + 1;
        }
      }
    }
    return text.length();
  }

  private static int lineOf(String text, int index) {
    int line = 0;
    int limit = Math.min(index, text.length());
    for (int i = 0; i < limit; i++) {
      if (text.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }

  private static int lineEnd(String text, int targetLine) {
    int line = 0;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        if (line == targetLine) {
          return i;
        }
        line++;
      }
    }
    return text.length();
  }

  /** Wires hover quick documentation and attaches the open popover overlay, if any. */
  private static void attachDocumentation(
      App<?> app, Theme theme, State state, El editor, String value, String extra) {
    if (state.doc != null && !value.equals(state.docValue)) {
      state.closeDocumentation();
    }
    if (state.doc != null && state.caret != state.docCaret) {
      state.closeDocumentation();
    }
    if (state.doc != null && state.docHover) {
      boolean outside =
          !state.overDoc && (state.hoverPos < state.docFrom || state.hoverPos >= state.docTo);
      if (!outside) {
        state.docLeftAt = Double.NaN;
      } else if (app == null) {
        state.closeDocumentation();
      } else if (Double.isNaN(state.docLeftAt)) {
        // A grace period, so a pointer crossing the popover's rounded corner reaches it. Reuses
        // submenuDelay, the same hover intent as a submenu that stays while the pointer heads in.
        double grace = theme.motion().submenuDelay();
        double leftAt = app.time();
        state.docLeftAt = leftAt;
        Runnable close = () -> {
          if (state.docLeftAt == leftAt) { // still away since this leave
            state.closeDocumentation();
          }
        };
        // A frame later: a move of the frame the grace ends in reaches its hover handlers only at
        // that frame's end.
        app.after(grace, () -> app.after(0, close));
      }
    }
    if (state.documentation != null) {
      editor.onTextHoverFor(theme.motion().tooltipDelay(), (pos, mods) -> {
        if (state.doc != null && !state.docHover) {
          return; // Control+Q's popover stays until the caret moves
        }
        String text = editor.text().string();
        if (pos < 0
            || pos >= text.length()
            || !CodeWords.isWordChar(text.codePointAt(pos), extra)) {
          return;
        }
        int from = CodeWords.start(text, pos, extra);
        int to = CodeWords.end(text, pos, extra);
        if (state.doc != null && state.docHover && state.docFrom == from && state.docTo == to) {
          state.hoverPos = pos;
          return;
        }
        MarkdownDocument doc = state.documentation.apply(pos);
        if (doc == null) {
          return;
        }
        state.doc = doc;
        state.docValue = value;
        state.docFrom = from;
        state.docTo = to;
        state.docCaret = state.caret;
        state.docHover = true;
        state.hoverPos = pos;
        state.overDoc = false;
        state.docLeftAt = Double.NaN;
      });
    }
    if (state.doc == null) {
      return;
    }
    editor.overlay(documentationPanel(theme, state));
  }

  /**
   * Wires the single {@link El#onTextPointerMove} handler shared by hover documentation and
   * Control+hover navigation, plus Control+click navigation via {@link El#onPointerDown}.
   */
  private static void attachPointerNavigation(
      App<?> app, State state, El editor, String value, String extra) {
    if ((state.doc != null && state.docHover) || state.declaration != null) {
      editor.onTextPointerMove((pos, mods) -> {
        state.hoverPos = pos;
        if (state.doc != null && state.docHover && pos >= state.docFrom && pos < state.docTo) {
          state.docLeftAt = Double.NaN;
        }
        if (state.declaration != null) {
          updateNavigationHover(state, editor, value, extra, pos, mods);
        }
      });
    }
    if (state.declaration != null) {
      editor.onPointerDown((clicks, mods, x, y) -> {
        if (clicks != 1 || mods != Key.CONTROL || state.declaration == null) {
          return;
        }
        // The word under the press, not the last hover's: the press can come before its move.
        int pos =
            editor.textPositionAt((float) (x - editor.scrollX()), (float) (y - editor.scrollY()));
        state.clearNavigation(); // asks the app's resolver now, not the hover's cached answer
        updateNavigationHover(state, editor, value, extra, pos, mods);
        if (state.navTarget != null) {
          goTo(app, state, editor, state.navTarget);
        }
      });
    }
  }

  private static void updateNavigationHover(
      State state, El editor, String value, String extra, int pos, int mods) {
    if (mods != Key.CONTROL || pos < 0) {
      state.clearNavigation();
      return;
    }
    String text = editor.text().string();
    if (pos >= text.length() || !CodeWords.isWordChar(text.codePointAt(pos), extra)) {
      state.clearNavigation();
      return;
    }
    int from = CodeWords.start(text, pos, extra);
    int to = CodeWords.end(text, pos, extra);
    if (value.equals(state.navValue) && from == state.navFrom && to == state.navTo) {
      return;
    }
    state.navValue = value;
    state.navFrom = from;
    state.navTo = to;
    state.navTarget = state.declaration.apply(pos);
  }

  private static El documentationPanel(Theme theme, State state) {
    Texts texts = Texts.CURRENT.get();
    El panel = El.col()
        .key("documentation")
        .role("dialog")
        .label(texts.documentation())
        .keepFocus()
        .w(theme.size().tooltip())
        .radius(theme.radius().medium())
        .fill(theme.surface().popup())
        .border(theme.size().stroke(), theme.border().control())
        .shadow(theme.elevation().popup())
        .motion(theme.motion().fade())
        .padding(theme.space().l())
        .scroll()
        .scrollbars(theme.scrollbars())
        .onPressOutside(state::closeDocumentation)
        .onKey(k -> {
          if (k.name().equals("Escape") && k.modifiers() == 0) {
            state.closeDocumentation();
            return true;
          }
          return false;
        })
        .child(Markdown.fragment(state.doc, Markdown.options()))
        .side(Side.BOTTOM, Style.Align.START, 0); // no gap the pointer would cross off the word
    if (state.docHover) {
      panel.onHover(over -> {
        state.overDoc = over;
        if (state.overDoc) {
          state.docLeftAt = Double.NaN;
        }
      });
    }
    return state.docFrom < state.docTo
        ? panel.atRange(state.docFrom, state.docTo)
        : panel.atCaret();
  }

  private static void showDocumentationAtCaret(State state, El editor, String text, int caret) {
    if (state.documentation == null) {
      return;
    }
    String extra = editor.text().wordCharacters();
    int from = CodeWords.start(text, caret, extra);
    int to = CodeWords.end(text, caret, extra);
    int query = symbolOffset(text, caret, extra);
    MarkdownDocument doc = state.documentation.apply(query);
    if (doc == null) {
      state.closeDocumentation();
      return;
    }
    state.doc = doc;
    state.docValue = state.value;
    state.docFrom = from;
    state.docTo = to;
    state.docCaret = state.caret;
    state.docHover = false;
    state.overDoc = false;
    state.docLeftAt = Double.NaN;
  }

  /** Offset inside the word at {@code caret} (for end-of-word carets), or {@code caret}. */
  private static int symbolOffset(String text, int caret, String extra) {
    int from = CodeWords.start(text, caret, extra);
    int to = CodeWords.end(text, caret, extra);
    return from < to ? (caret < to ? caret : from) : caret;
  }

  /**
   * Moves the caret to {@code location}, revealing it through folds, or hands a location in
   * another document to {@link State#onOpen}.
   */
  private static void goTo(App<?> app, State state, El editor, Location location) {
    if (location == null) {
      return;
    }
    if (location.document() != null) {
      if (state.onOpen != null) {
        state.onOpen.accept(location);
      }
      return;
    }
    goTo(app, state, editor, location.from());
  }

  /** Moves the caret to {@code at} in this editor and reveals it through folds and scrolling. */
  private static void goTo(App<?> app, State state, El editor, int at) {
    int clamped = Math.clamp(at, 0, editor.text().string().length());
    if (app != null) {
      app.select(editor, clamped, clamped);
      state.caret = clamped;
      state.anchor = clamped;
    } else {
      state.select(clamped, clamped);
    }
  }

  private static void goToDeclaration(App<?> app, State state, El editor, String text, int caret) {
    if (state.declaration == null) {
      return;
    }
    int query = symbolOffset(text, caret, editor.text().wordCharacters());
    goTo(app, state, editor, state.declaration.apply(query));
  }

  private static void findUsages(State state, El editor, String text, int caret) {
    if (state.usages == null) {
      return;
    }
    int query = symbolOffset(text, caret, editor.text().wordCharacters());
    List<Location> found = state.usages.apply(query);
    if (found == null || found.isEmpty()) {
      state.closeUsagesMenu();
      return;
    }
    state.usagesFound = List.copyOf(found);
    state.usageLabels = state.usagesFound.stream().map(l -> usageLabel(text, l)).toList();
    state.usagesValue = state.value;
    state.usagesMenu.openByKey();
  }

  private static void highlightUsages(State state, El editor, String text, int caret) {
    if (state.usages == null) {
      return;
    }
    int query = symbolOffset(text, caret, editor.text().wordCharacters());
    List<Location> found = state.usages.apply(query);
    List<Highlight> highlights = new ArrayList<>();
    for (Location loc : found == null ? List.<Location>of() : found) {
      if (loc.document() == null) {
        highlights.add(new Highlight(loc.from(), loc.to()));
      }
    }
    state.clearUsages();
    if (!highlights.isEmpty()) {
      state.markup(USAGES_LAYER, text, highlights);
      state.usagesShown = state.layers.get(USAGES_LAYER);
    }
  }

  private static String usageLabel(String text, Location location) {
    if (location.label() != null) {
      return location.label();
    }
    if (location.document() != null) {
      return location.document();
    }
    int at = Math.min(location.from(), text.length());
    int line = lineOf(text, at) + 1;
    int start = at == 0 ? 0 : text.lastIndexOf('\n', at - 1) + 1;
    int end = text.indexOf('\n', at);
    if (end < 0) {
      end = text.length();
    }
    String snippet = text.substring(start, end).strip();
    return snippet.isEmpty() ? String.valueOf(line) : line + ": " + snippet;
  }
}
