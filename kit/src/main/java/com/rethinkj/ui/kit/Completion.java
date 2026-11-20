package com.rethinkj.ui.kit;

import static com.rethinkj.ui.kit.Icon.icon;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.App;
import com.rethinkj.ui.CodeWords;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.TextChange;
import com.rethinkj.ui.Work;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Completions at the caret of a text field, such as the keywords and table names of SQL in a
 * {@link CodeEditor}: a list below the caret ({@link El#atCaret}) of what the app suggests for the
 * word before it. The list opens as a word is typed, from its second character, after a character
 * the state names ({@link State#openAfter}, such as the {@code .} before a column), and with
 * Control+Space; it narrows as typing goes on and closes when the word ends. The focus stays in the
 * field ({@link El#keepFocus}): Up and Down move the highlight, Enter and Tab put the highlighted
 * entry in place of the word, its part after the caret too, as one step of the undo history
 * ({@link App#edit}), and Escape closes the list, as do a press elsewhere, the wheel and the keys
 * that move the caret sideways. A press on an entry takes it.
 *
 * <pre>{@code
 * El editor =
 *     codeEditor(state.sql, state.editor, highlighter, value -> state.sql = value);
 * Completion.complete(app, editor, state.editor.caret(), state.completion,
 *     word -> state.suggest(word));
 * }</pre>
 *
 * <p>A word is what {@link El#codeWords} takes for one: letters, digits and {@code _}, and the
 * characters the field's highlighter adds ({@link Highlighter#wordCharacters}). The app's
 * function gets the part of it before the caret, maybe empty after Control+Space, and returns the
 * entries to show, in order; the kit shows them as they come and filters nothing. What stands
 * before the word, such as {@code orders.}, the app reads from its own text at the caret. Entries
 * that take time to find, as from a database, come from another thread with {@link
 * #completeInBackground}, which shows a row "Loading…" meanwhile; entries the app still loads into
 * its own state show that row while {@link State#loading} says so. In a tree dump
 * the list is {@code role=listbox} with {@code label="Completions"}, its entries {@code
 * role=option} with their text as the label and the keys {@code option-<index>}, the highlighted
 * one {@code selected}. Only entries in or near the visible part are built. The list is as wide
 * as its first entries need, up to twice a field's width; a longer text ends with an ellipsis. Its
 * look is that of a {@link Menu}.
 */
public final class Completion {

  private Completion() {}

  /** The most entries the list shows at once; more scroll. */
  static final int ROWS = 8;

  /**
   * An entry: the text the list shows, an icon before it or null, what kind of thing it is, shown
   * muted at the right, such as {@code "keyword"} or {@code "table"}, or null, and the text that
   * replaces the word when it is chosen, such as a name in quotes, or null for the text shown.
   */
  public record Entry(String text, Symbol symbol, String kind, String insert) {
    public Entry {
      Objects.requireNonNull(text, "text");
      insert = insert == null ? text : insert;
    }

    public Entry(String text, Symbol symbol, String kind) {
      this(text, symbol, kind, null);
    }

    public Entry(String text) {
      this(text, null, null, null);
    }
  }

  /**
   * What the app keeps of a field's completions, as a {@link Menu.State}: whether the list is open
   * and which entry is highlighted. The component changes it in its handlers.
   */
  public static final class State {
    private boolean open;
    private int highlight;
    private int typed = -1; // after a character typed, where the caret goes, until the next build
    // The string an edit proposed and where its caret went, until the build that answers it, and
    // whether the key of this input is Backspace, which leaves the caret before what it took.
    private String edited;
    private int editedCaret;
    private boolean back;
    private boolean triggered; // that character is one of openAfter's
    private boolean history; // the key of this input is undo or redo: its edit is no typing
    private String openAfter = "";
    private String wordCharacters = ""; // the field's language's, as of the last build
    private boolean loading; // set by the app: what suggest returns is not all there yet
    // completeInBackground: the word asked for, the work that finds its entries, and the entries
    // found last, which show until the next arrive.
    private String asked;
    private Work work;
    private List<Entry> answer = List.of();

    public boolean isOpen() {
      return open;
    }

    /** Opens the list with its first entry highlighted. */
    public void open() {
      open = true;
      highlight = 0;
    }

    /** Closes the list, and cancels the work that finds its entries in the background. */
    public void close() {
      open = false;
      if (work != null) {
        work.cancel();
        work = null;
      }
      asked = null;
      answer = List.of();
    }

    /**
     * The characters after which the list opens as they are typed, with the word after them still
     * empty, such as {@code "."} for the columns after a table's name; none unless set.
     *
     * @throws IllegalArgumentException for a character of a word, as a letter, which opens the
     *     list from a word's second character anyway
     */
    public State openAfter(String characters) {
      if (characters.codePoints().anyMatch(CodeWords::isWordChar)) {
        throw new IllegalArgumentException(
            "openAfter is for characters that end a word, such as \".\", was \"" + characters
                + "\"");
      }
      openAfter = characters;
      return this;
    }

    /**
     * Says whether entries are still on their way, as while the app loads them into its own state:
     * the open list then shows a last row "Loading…" below the entries it has, which alone keeps
     * it open, until set back to false. The app sets it before {@link #complete} or in its
     * function, which the next build calls again for the entries that arrived.
     */
    public State loading(boolean loading) {
      this.loading = loading;
      return this;
    }

    /** The highlighted entry's index. */
    public int highlight() {
      return highlight;
    }
  }

  /**
   * Gives a text field its completions: the list at its caret while {@code state} is open, and the
   * handlers of its edits and keys that open, move and close the list. The field's own handlers go
   * on as before: its edits reach its handler, and the keys the list does not take reach its key
   * handler.
   *
   * @param app the app the field is in, which puts an entry in place ({@link App#edit})
   * @param field an {@link El#editable} field, such as a {@link CodeEditor}
   * @param caret where the field's caret is, as its {@link El#onSelect} handler told the app
   * @param suggest the entries for the part of the word before the caret, called on the app's
   *     thread on every build while the list is open; while more are on their way, it sets {@link
   *     State#loading}
   * @return the field
   */
  public static El complete(
      App<?> app, El field, int caret, State state, Function<String, List<Entry>> suggest) {
    Theme theme = Theme.CURRENT.get();
    return complete(theme, app, field, caret, state, null, suggest);
  }

  /**
   * As {@link #complete}, but {@code suggest} runs on another thread ({@link App#background}), once
   * for each word while the list is open. Until its entries arrive, the list shows those found for
   * the word before and a last row "Loading…", which alone keeps the list open; entries that arrive
   * empty close it. A new word cancels the work for the one before, and so does closing the list.
   * {@code suggest} gets only the word: what else it reads it takes in its closure, and nothing of
   * the app's state that the app's thread changes.
   *
   * @param name what the work is called, which {@link App#await} names
   */
  public static El completeInBackground(
      App<?> app,
      El field,
      int caret,
      State state,
      String name,
      Function<String, List<Entry>> suggest) {
    Theme theme = Theme.CURRENT.get();
    return complete(theme, app, field, caret, state, Objects.requireNonNull(name, "name"), suggest);
  }

  private static El complete(
      Theme theme,
      App<?> app,
      El field,
      int caret,
      State state,
      String background,
      Function<String, List<Entry>> suggest) {
    String text = field.text().string();
    // The build that answers an edit comes before onSelect tells its caret: an edit the app took
    // as it was has the caret where the edit put it.
    if (text.equals(state.edited)) {
      caret = state.editedCaret;
    }
    state.edited = null;
    caret = Math.clamp(caret, 0, text.length());
    state.wordCharacters = field.text().wordCharacters();
    int from = CodeWords.start(text, caret, state.wordCharacters);
    // A character typed into a word opens the list from the word's second character, one of
    // openAfter's at once; an undo that brings one back leaves the caret elsewhere, or a
    // selection, and opens nothing.
    if (state.typed >= 0
        && !state.open
        && state.typed == caret
        && (state.triggered || caret - from >= 2)) {
      state.open();
    }
    state.typed = -1;
    state.triggered = false;
    state.history = false;
    String word = text.substring(from, caret);
    List<Entry> entries = !state.open
        ? List.of()
        : background == null ? suggest.apply(word) : ask(app, state, background, word, suggest);
    boolean loading = state.work != null || state.loading; // read after suggest, which may set it
    if (entries.isEmpty() && !loading) {
      // A list with nothing to show is closed, or it would show wherever the caret went next,
      // as after a number typed, Home, a press or a find bar's match.
      state.close();
    }
    if (state.highlight >= entries.size()) {
      state.highlight = 0;
    }
    int to = CodeWords.end(
        text, caret, state.wordCharacters); // the whole word, also its part after the caret
    Consumer<Integer> take = i -> {
      state.close();
      String chosen = entries.get(i).insert();
      app.edit(field, from, to, chosen, from + chosen.length());
    };
    Consumer<String> onEdit = field.onEdit();
    if (onEdit != null) {
      int at = caret;
      field.editable(v -> {
        if (state.history) {
          state.close(); // a step of the history brings text back; nobody typed it
        } else {
          follow(state, text, v);
        }
        // Typing puts the caret after what it put in, Delete leaves it, Backspace moves it back.
        int grown = v.length() - text.length();
        state.edited = v;
        state.editedCaret = grown > 0 || state.back ? at + grown : at;
        onEdit.accept(v);
      });
    }
    field.onKeyFirst(k -> {
      // The field's keys for undo and redo; onEdit does not say where an edit came from.
      state.history = (k.modifiers() & ~Key.SHIFT) == Key.CONTROL
          && (k.name().equals("Z") || k.name().equals("Y"));
      state.back = k.name().equals("Backspace");
      return key(k, state, entries, take);
    });
    if (!state.open) {
      return field;
    }
    int count = entries.size();
    int rows = count + (loading ? 1 : 0);
    El list = PassiveList.view(
            theme,
            rows,
            ROWS,
            state.highlight,
            theme.space().s(),
            theme.size().control(),
            i -> i == count
                ? PassiveList.loading(theme)
                : option(theme, entries.get(i), i, state, take))
        .key("completions")
        .role("listbox")
        .label(Texts.CURRENT.get().completions())
        .atCaret()
        .side(Side.BOTTOM, Style.Align.START, theme.space().xs())
        .keepFocus()
        .onDismiss(state::close);
    list.style().minWidth = Len.px(width(theme, entries) + PassiveList.gutter(theme, rows, ROWS));
    list.style().maxHeight = Len.px(
        ROWS * theme.size().control() + 2 * (theme.space().s() + theme.size().stroke()));
    return field.overlay(Popup.frame(theme, list));
  }

  /** Finds the word's entries on another thread, once per word; meanwhile those found before. */
  private static List<Entry> ask(
      App<?> app, State state, String name, String word, Function<String, List<Entry>> suggest) {
    if (!word.equals(state.asked)) {
      if (state.work != null) {
        state.work.cancel();
      }
      state.asked = word;
      state.work = app.background(
          name,
          () -> suggest.apply(word),
          found -> {
            state.answer = List.copyOf(found);
            state.work = null;
          },
          error -> {
            state.answer = List.of();
            state.work = null;
          });
    }
    return state.answer;
  }

  /**
   * The list's width with its padding: what its first rows need, at least a field's width and at
   * most twice that.
   */
  private static float width(Theme theme, List<Entry> entries) {
    float texts = 0;
    for (int i = 0; i < Math.min(entries.size(), ROWS); i++) {
      Entry entry = entries.get(i);
      float kind = entry.kind() == null
          ? 0
          : TextSizing.natural(kind(theme, entry))
              + theme.space().m()
              + theme.space().l();
      texts = Math.max(texts, TextSizing.natural(text(theme, entry)) + kind);
    }
    float m = theme.space().m();
    // The row's padding, its icon and the gap after it, around the texts; the list's padding.
    float need = (float)
        Math.ceil(3 * m + theme.size().icon() + texts + 2 * theme.space().s());
    return Math.clamp(need, theme.size().field(), 2 * theme.size().field());
  }

  private static El text(Theme theme, Entry entry) {
    return label(entry.text())
        .font(theme.type().mono().font().get())
        .fontSize(theme.type().mono().size());
  }

  private static El kind(Theme theme, Entry entry) {
    return label(Label.Variant.SMALL, entry.kind());
  }

  private static El option(Theme theme, Entry entry, int i, State state, Consumer<Integer> take) {
    El kind = null;
    if (entry.kind() != null) {
      // The text gives way first, the kind only beyond half the row.
      kind = kind(theme, entry)
          .ellipsis()
          .minSize(0, 0)
          .shrink(0)
          .margin(0, 0, 0, theme.space().l());
      kind.style().maxWidth = Len.percent(0.5f);
    }
    El content = PassiveContent.row(
        theme,
        PassiveContent.leading(
            theme, entry.symbol() != null ? icon(entry.symbol(), Icon.Variant.MUTED) : null),
        text(theme, entry).ellipsis().minSize(0, 0),
        kind);
    return PassiveList.option(
            theme, i, state.highlight, at -> state.highlight = at, () -> take.accept(i))
        .label(entry.text())
        .child(content);
  }

  /** What a key does while the list shows; whether the list took it. */
  private static boolean key(Key k, State state, List<Entry> entries, Consumer<Integer> take) {
    if (k.name().equals("Space") && k.modifiers() == Key.CONTROL) {
      state.open();
      return true;
    }
    if (!state.open) {
      return false;
    }
    int n = entries.size();
    if ((k.modifiers() & (Key.CONTROL | Key.ALT | Key.META)) != 0) {
      state.close(); // a command, such as Control+F, which moves the caret elsewhere
      return false;
    }
    if (k.modifiers() != 0) {
      return false;
    }
    switch (k.name()) {
      case "ArrowDown", "ArrowUp", "PageDown", "PageUp" -> {
        if (n > 0) {
          state.highlight = PassiveList.move(k.name(), state.highlight, n, ROWS, i -> true);
        }
      }
      case "Enter", "NumpadEnter", "Tab" -> {
        if (n == 0) {
          state.close(); // only "Loading…" shows: nothing to take, the field has the key
          return false;
        }
        take.accept(state.highlight);
      }
      case "Escape" -> state.close();
      default -> {
        if (List.of("ArrowLeft", "ArrowRight", "Home", "End").contains(k.name())) {
          state.close(); // the caret leaves the word; the field moves it
        }
        return false;
      }
    }
    return true;
  }

  /**
   * Follows an edit from {@code before} to {@code after}: a letter, digit or {@code _} typed opens
   * the list at the next build, if the caret is right after it and the word before it has two
   * characters, and keeps it open; one of {@link State#openAfter} opens it, or keeps it open, for
   * the empty word after it; a deletion keeps it open while some of the word is left; any other
   * edit closes it.
   */
  static void follow(State state, String before, String after) {
    TextChange change = TextChange.between(before, after);
    boolean insertion = change.start() == change.oldEnd();
    String inserted = after.substring(change.start(), change.newEnd());
    if (insertion
        && inserted.codePointCount(0, inserted.length()) == 1
        && CodeWords.isWordChar(inserted.codePointAt(0), state.wordCharacters)) {
      state.typed = change.newEnd(); // the build after it may open the list
      state.triggered = state.openAfter.contains(inserted); // a word character of openAfter's
      state.highlight = 0; // what it shows changes
    } else if (insertion
        && inserted.codePointCount(0, inserted.length()) == 1
        && state.openAfter.contains(inserted)) {
      state.typed = change.newEnd();
      state.triggered = true;
      state.highlight = 0;
    } else if (inserted.isEmpty()
        && !insertion
        && state.open
        && CodeWords.start(after, change.start(), state.wordCharacters) < change.start()) {
      state.highlight = 0;
    } else {
      state.close();
    }
  }
}
