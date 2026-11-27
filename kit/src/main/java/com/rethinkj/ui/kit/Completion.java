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
import com.rethinkj.ui.TextEdit;
import com.rethinkj.ui.Work;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * Completions at the caret of a text field, such as the keywords and table names of SQL in a
 * code editor ({@code CodeEditor}): a list below the caret ({@link El#atCaret}) of what the app
 * suggests for the word before it. The list opens as a word is typed, from its second character, after a character
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
 * characters the field's highlighter adds ({@link Highlighter#wordCharacters}). With {@link
 * #complete}, the app's function gets the part of the word before the caret, maybe empty after
 * Control+Space, and returns the entries to show, in order; the kit shows them as they come and
 * filters nothing. With {@link #completeWithContext}, the function gets a {@link Context} with the
 * field's full text, caret, word bounds, and what opened the list ({@link Trigger}), and each
 * {@link Entry} can also carry a muted {@code detail} after its text, an explicit replace range,
 * and {@code additionalEdits} applied with the insertion as one undo step. Entries that take time
 * to find, as from a database or language server, come from another thread with {@link
 * #completeInBackground} or {@link #completeWithContextInBackground}, which show a row "Loading…"
 * meanwhile; entries the app still loads into its own state show that row while {@link
 * State#loading} says so. In a tree dump the list is {@code role=listbox} with {@code
 * label="Completions"}, its entries {@code role=option} with their text as the label and the keys
 * {@code option-<index>}, the highlighted one {@code selected}. When {@link State#documentation} is
 * set, the highlighted entry's documentation shows beside the list after {@link
 * Theme.Motion#tooltipDelay} as an overlay with key {@code documentation}, {@code role=dialog} and
 * {@code label="Documentation"}. Only entries in or near the visible part are built. The list is
 * as wide as its first entries need, up to twice a field's width; a longer text ends with an
 * ellipsis. Its look is that of a {@link Menu}.
 */
public final class Completion {

  private Completion() {}

  /** The most entries the list shows at once; more scroll. */
  public static final int ROWS = 8;

  /** What opened the completion list. */
  public enum Trigger {
    /** A word character was typed, from the word's second character. */
    TYPED,
    /** A character of {@link State#openAfter} was typed. */
    OPEN_AFTER,
    /** Control+Space was pressed or the app called {@link State#open()}. */
    INVOKED
  }

  /**
   * The context passed to a completion provider ({@link #completeWithContext}, {@link
   * #completeWithContextInBackground}): an immutable snapshot of the field's text, the caret and
   * word bounds around it, and what opened the list.
   *
   * @param text the field's full text, safe to read on a background thread
   * @param caret the caret offset in {@code text}, in UTF-16 units
   * @param wordFrom start of the word before {@code caret}
   * @param wordTo end of the word at {@code caret}, including its part after the caret
   * @param trigger what opened the list; stays the same while the list stays open and narrows
   * @param character the {@link State#openAfter} character for {@link Trigger#OPEN_AFTER}, or null
   */
  public record Context(
      String text, int caret, int wordFrom, int wordTo, Trigger trigger, String character) {
    public Context {
      Objects.requireNonNull(text, "text");
      Objects.requireNonNull(trigger, "trigger");
      if (wordFrom < 0 || caret < wordFrom || wordTo < caret || wordTo > text.length()) {
        throw new IllegalArgumentException(
            "expected 0 <= wordFrom <= caret <= wordTo <= text.length(), were wordFrom="
                + wordFrom
                + ", caret="
                + caret
                + ", wordTo="
                + wordTo
                + ", length="
                + text.length());
      }
    }

    /** The part of the word before the caret: {@code text.substring(wordFrom, caret)}. */
    public String word() {
      return text.substring(wordFrom, caret);
    }
  }

  /**
   * An entry of the completion list.
   *
   * @param text the text the list shows
   * @param symbol an icon before {@code text}, or null
   * @param kind what kind of thing it is, shown muted at the right, such as {@code "keyword"} or
   *     {@code "table"}, or null
   * @param insert the text inserted when the entry is chosen, such as a name in quotes, or null for
   *     {@code text}
   * @param detail muted secondary text shown after {@code text}, such as a signature {@code
   *     "(String name) : int"}, or null
   * @param from start offset of the range replaced by {@code insert} in the text the entry was
   *     found for ({@link Context#text()}), or {@code -1} (with {@code to == -1}) to replace the
   *     word at the caret, its part after the caret too
   * @param to exclusive end offset of the range replaced by {@code insert}, or {@code -1} (with
   *     {@code from == -1}) to replace the word at the caret
   * @param additionalEdits extra edits applied together with the main replacement as one undo step,
   *     such as adding an import, or null for none; must not overlap the replace range or each
   *     other
   */
  public record Entry(
      String text,
      Icon symbol,
      String kind,
      String insert,
      String detail,
      int from,
      int to,
      List<TextEdit> additionalEdits) {
    public Entry {
      Objects.requireNonNull(text, "text");
      insert = insert == null ? text : insert;
      if ((from != -1 || to != -1) && (from < 0 || to < from)) {
        throw new IllegalArgumentException(
            "from and to must both be -1, or 0 or more with to at least from, were "
                + from
                + " and "
                + to);
      }
      additionalEdits = additionalEdits == null ? List.of() : List.copyOf(additionalEdits);
    }

    public Entry(String text, Icon symbol, String kind, String insert) {
      this(text, symbol, kind, insert, null, -1, -1, List.of());
    }

    public Entry(String text, Icon symbol, String kind) {
      this(text, symbol, kind, null, null, -1, -1, List.of());
    }

    public Entry(String text) {
      this(text, null, null, null, null, -1, -1, List.of());
    }
  }

  /**
   * What the app keeps of a field's completions, as a {@link Menu.State}: whether the list is open
   * and which entry is highlighted. The component changes it in its handlers.
   */
  public static final class State {
    private boolean open;
    private int highlight;
    private Trigger trigger; // what opened the list; non-null while open
    private String triggerCharacter; // the openAfter character for OPEN_AFTER, else null
    private int typed = -1; // after a character typed, where the caret goes, until the next build
    // The field an edit went to the app in, until the app heard the selection after its answer,
    // also in a build without the completions; and the entries of the last build that decided.
    private El edited;
    private List<Entry> shown = List.of();
    private boolean triggered; // that character is one of openAfter's
    private String typedCharacter; // the character typed when triggered, until the next build
    private boolean history; // the key of this input is undo or redo: its edit is no typing
    private String openAfter = "";
    private String wordCharacters = ""; // the field's language's, as of the last build
    private boolean loading; // set by the app: what suggest returns is not all there yet
    // completeInBackground: the context asked for, the work that finds its entries, and the
    // entries found last, which show until the next arrive.
    private Context asked;
    private Work work;
    private List<Entry> answer = List.of();
    // documentation: background provider, the entry asked for, its work, the document found,
    // and an epoch that drops delayed timers when the highlight changes or the list closes.
    private String docName;
    private Function<Entry, MarkdownDocument> docProvider;
    private Entry docEntry;
    private Work docWork;
    private MarkdownDocument doc;
    private int docEpoch;

    public boolean isOpen() {
      return open;
    }

    /** Opens the list with its first entry highlighted and trigger {@link Trigger#INVOKED}. */
    public void open() {
      open = true;
      highlight = 0;
      trigger = Trigger.INVOKED;
      triggerCharacter = null;
    }

    /**
     * Closes the list, and cancels the work that finds its entries or documentation in the
     * background.
     */
    public void close() {
      open = false;
      trigger = null;
      triggerCharacter = null;
      if (work != null) {
        work.cancel();
        work = null;
      }
      asked = null;
      answer = List.of();
      clearDocumentation();
    }

    private void clearDocumentation() {
      if (docWork != null) {
        docWork.cancel();
        docWork = null;
      }
      if (docEntry != null || doc != null) {
        docEntry = null;
        doc = null;
        docEpoch++;
      }
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

    /**
     * Supplies documentation for the highlighted entry on another thread ({@link App#background}),
     * shown beside the list once the highlight rests on the entry for {@link
     * Theme.Motion#tooltipDelay}; moving the highlight or closing the list cancels pending work
     * and hides the panel. Returning null shows nothing. Pass a null {@code provider} to disable.
     * {@code provider} gets only the entry: what else it reads it takes in its closure, and nothing
     * of the app's state that the app's thread changes. Set it once, as {@link #openAfter}: a
     * new provider answers from its next lookup, while a document found before stays.
     *
     * @param name what the work is called, which {@link App#await} names
     */
    public State documentation(String name, Function<Entry, MarkdownDocument> provider) {
      if (provider == null) {
        docName = null;
        docProvider = null;
        clearDocumentation();
      } else {
        docName = Objects.requireNonNull(name, "name");
        docProvider = provider;
      }
      return this;
    }
  }

  /**
   * Gives a text field its completions: the list at its caret while {@code state} is open, and the
   * handlers of its edits and keys that open, move and close the list. The field's own handlers go
   * on as before: its edits reach its handler, and the keys the list does not take reach its key
   * handler.
   *
   * @param app the app the field is in, which puts an entry in place ({@link App#edit})
   * @param field an {@link El#editable} field, such as a code editor
   * @param caret where the field's caret is, as its {@link El#onSelect} handler told the app
   * @param suggest the entries for the part of the word before the caret, called on the app's
   *     thread on every build while the list is open, but not while an edit of the field waits for
   *     the app: from the edit until its {@link El#onSelect} heard the selection after the app's
   *     answer ({@link El#isSelectionPending}); while more are on their way, it sets {@link
   *     State#loading}
   * @return the field
   */
  public static El complete(
      App<?> app, El field, int caret, State state, Function<String, List<Entry>> suggest) {
    Objects.requireNonNull(suggest, "suggest");
    Theme theme = Theme.CURRENT.get();
    return complete(
        theme, app, field, caret, state, null, false, context -> suggest.apply(context.word()));
  }

  /**
   * As {@link #complete}, but {@code suggest} runs on another thread ({@link App#background}), once
   * for each word and text while the list is open, and again when Control+Space makes it {@link
   * Trigger#INVOKED}. Until its entries arrive, the list shows those found before, but for those
   * with a range of their own or additional edits, and a last row "Loading…", which alone keeps the
   * list open; entries that arrive empty close it. A new word cancels the work for the one before,
   * and so does closing the list. {@code suggest} gets only the word: what else it reads it takes
   * in its closure, and nothing of the app's state that the app's thread changes.
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
    Objects.requireNonNull(suggest, "suggest");
    Theme theme = Theme.CURRENT.get();
    return complete(
        theme,
        app,
        field,
        caret,
        state,
        Objects.requireNonNull(name, "name"),
        false,
        context -> suggest.apply(context.word()));
  }

  /**
   * As {@link #complete}, but {@code suggest} receives a {@link Context} with the field's text,
   * caret, word bounds, and what opened the list ({@link Trigger}).
   *
   * @param app the app the field is in, which puts an entry in place ({@link App#edit})
   * @param field an {@link El#editable} field, such as a code editor
   * @param caret where the field's caret is, as its {@link El#onSelect} handler told the app
   * @param state the completion popup state kept by the app
   * @param suggest the entries for the completion context, called on the app's thread on every
   *     build while the list is open, except while an edit of the field waits for {@link
   *     El#onSelect}
   * @return the field
   */
  public static El completeWithContext(
      App<?> app, El field, int caret, State state, Function<Context, List<Entry>> suggest) {
    Theme theme = Theme.CURRENT.get();
    return complete(
        theme, app, field, caret, state, null, true, Objects.requireNonNull(suggest, "suggest"));
  }

  /**
   * As {@link #completeWithContext}, but {@code suggest} runs on another thread ({@link
   * App#background}), once for each context while the list is open: {@link Context#text()} is a
   * snapshot of the field's string that {@code suggest} can read safely off the app thread. Until
   * the entries for the current context arrive, the list shows only a row "Loading…", since what
   * was found for another context may no longer fit.
   *
   * @param name what the work is called, which {@link App#await} names
   */
  public static El completeWithContextInBackground(
      App<?> app,
      El field,
      int caret,
      State state,
      String name,
      Function<Context, List<Entry>> suggest) {
    Theme theme = Theme.CURRENT.get();
    return complete(
        theme,
        app,
        field,
        caret,
        state,
        Objects.requireNonNull(name, "name"),
        true,
        Objects.requireNonNull(suggest, "suggest"));
  }

  private static El complete(
      Theme theme,
      App<?> app,
      El field,
      int caret,
      State state,
      String background,
      boolean contextual,
      Function<Context, List<Entry>> suggest) {
    String text = field.text().string();
    // The builds that answer an edit come before onSelect tells its selection: the app's state,
    // which suggest may read, still has the one before. The build after decides.
    boolean waiting = state.edited != null && state.edited.isSelectionPending();
    if (!waiting) {
      state.edited = null;
    }
    caret = Math.clamp(caret, 0, text.length());
    state.wordCharacters = field.text().wordCharacters();
    int from = CodeWords.start(text, caret, state.wordCharacters);
    int to = CodeWords.end(
        text, caret, state.wordCharacters); // the whole word, also its part after the caret
    // A character typed into a word opens the list from the word's second character, one of
    // openAfter's at once; an undo that brings one back leaves the caret elsewhere, or a
    // selection, and opens nothing.
    if (!waiting) {
      if (state.typed >= 0 && state.typed == caret) {
        if (state.triggered) {
          if (!state.open) {
            state.open();
          }
          state.trigger = Trigger.OPEN_AFTER;
          state.triggerCharacter = state.typedCharacter;
        } else if (!state.open && caret - from >= 2) {
          state.open();
          state.trigger = Trigger.TYPED;
          state.triggerCharacter = null;
        }
      }
      state.typed = -1;
      state.triggered = false;
      state.typedCharacter = null;
    }
    state.history = false;
    List<Entry> entries;
    if (waiting) {
      entries = state.shown;
    } else if (!state.open) {
      entries = List.of();
    } else {
      Context context = new Context(text, caret, from, to, state.trigger, state.triggerCharacter);
      entries = background == null
          ? suggest.apply(context)
          : ask(app, state, background, contextual, context, suggest);
    }
    state.shown = entries;
    boolean loading = state.work != null || state.loading; // read after suggest, which may set it
    if (entries.isEmpty() && !loading && !waiting) {
      // A list with nothing to show is closed, or it would show wherever the caret went next,
      // as after a number typed, Home, a press or a find bar's match.
      state.close();
    }
    if (state.highlight >= entries.size()) {
      state.highlight = 0;
    }
    IntConsumer take = i -> {
      state.close();
      apply(app, field, from, to, entries.get(i));
    };
    Consumer<String> onEdit = field.onEdit();
    if (onEdit != null) {
      field.editable(v -> {
        if (state.history) {
          state.close(); // a step of the history brings text back; nobody typed it
        } else {
          follow(state, text, v);
        }
        state.edited = field;
        onEdit.accept(v);
      });
    }
    El.SelectHandler ownSelect = field.onSelect();
    field.onSelect((caretNow, anchor) -> {
      state.edited = null;
      if (ownSelect != null) {
        ownSelect.select(caretNow, anchor);
      }
    });
    field.onKeyFirst(k -> {
      // The field's keys for undo and redo; onEdit does not say where an edit came from.
      state.history = (k.modifiers() & ~Key.SHIFT) == Key.CONTROL
          && (k.name().equals("Z") || k.name().equals("Y"));
      return key(k, state, entries, take);
    });
    if (!state.open) {
      return field;
    }
    int count = entries.size();
    if (!waiting) {
      describe(theme, app, state, state.highlight < count ? entries.get(state.highlight) : null);
    }
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
    if (state.doc != null) {
      list.overlay(Markdown.documentation(state.doc)
          .keepFocus()
          .side(Side.RIGHT, Style.Align.START, theme.space().xs())
          .avoidAnchor()); // below or above the list where neither side has room
    }
    return field.overlay(Popup.frame(theme, list));
  }

  /**
   * Schedules background documentation for {@code highlighted} after the theme's tooltip delay;
   * cancels any previous work and drops its document when the highlighted entry changes.
   */
  private static void describe(Theme theme, App<?> app, State state, Entry highlighted) {
    if (state.docProvider == null || highlighted == null) {
      state.clearDocumentation();
      return;
    }
    if (highlighted.equals(state.docEntry)) {
      return;
    }
    state.clearDocumentation();
    state.docEntry = highlighted;
    int epoch = state.docEpoch;
    app.after(theme.motion().tooltipDelay(), () -> {
      if (!state.open || state.docEpoch != epoch || state.docProvider == null) {
        return;
      }
      Function<Entry, MarkdownDocument> provider = state.docProvider;
      state.docWork = app.background(
          state.docName,
          () -> provider.apply(highlighted),
          found -> {
            state.doc = found;
            state.docWork = null;
          },
          error -> {
            state.doc = null;
            state.docWork = null;
          });
    });
  }

  /** Puts the chosen entry in place, with its additional edits, as one undo step. */
  private static void apply(App<?> app, El field, int wordFrom, int wordTo, Entry entry) {
    TextEdit main = entry.from() >= 0
        ? new TextEdit(entry.from(), entry.to(), entry.insert())
        : new TextEdit(wordFrom, wordTo, entry.insert());
    List<TextEdit> edits = new ArrayList<>(entry.additionalEdits());
    edits.add(main);
    edits.sort(Comparator.comparingInt(TextEdit::from).thenComparingInt(TextEdit::to));
    int caret = main.from() + main.text().length();
    for (TextEdit edit : edits.subList(0, edits.indexOf(main))) {
      caret += edit.text().length() - (edit.to() - edit.from()); // the edits before it shift it
    }
    app.edit(field, edits, caret);
  }

  /**
   * Finds the entries on another thread, once per context; meanwhile, for a provider of words,
   * those found before that replace only the word.
   */
  private static List<Entry> ask(
      App<?> app,
      State state,
      String name,
      boolean contextual,
      Context context,
      Function<Context, List<Entry>> suggest) {
    if (!context.equals(state.asked)) {
      if (state.work != null) {
        state.work.cancel();
      }
      // Of what was found for the text before, the word's entries still fit, ranges and additional
      // edits no longer; a provider that reads the context finds others for the new one.
      state.answer = contextual
          ? List.of()
          : state.answer.stream()
              .filter(e -> e.from() < 0 && e.additionalEdits().isEmpty())
              .toList();
      state.asked = context;
      state.work = app.background(
          name,
          () -> suggest.apply(context),
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
      float detail = entry.detail() == null || entry.detail().isEmpty()
          ? 0
          : TextSizing.natural(detail(entry)) + theme.space().s();
      float kind = entry.kind() == null
          ? 0
          : TextSizing.natural(kind(entry)) + theme.space().m() + theme.space().l();
      texts = Math.max(texts, TextSizing.natural(text(theme, entry)) + detail + kind);
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

  private static El detail(Entry entry) {
    return label(Label.Variant.SMALL, entry.detail());
  }

  private static El kind(Entry entry) {
    return label(Label.Variant.SMALL, entry.kind());
  }

  private static El option(Theme theme, Entry entry, int i, State state, IntConsumer take) {
    El kind = null;
    if (entry.kind() != null) {
      // The text gives way first, the kind only beyond half the row.
      kind = kind(entry)
          .ellipsis()
          .minSize(0, 0)
          .shrink(0)
          .margin(0, 0, 0, theme.space().l());
      kind.style().maxWidth = Len.percent(0.5f);
    }
    El main;
    if (entry.detail() == null || entry.detail().isEmpty()) {
      main = text(theme, entry).ellipsis().minSize(0, 0);
    } else {
      // The detail gives way before the entry's text; the text yields only when it alone overflows.
      El name = text(theme, entry).ellipsis();
      name.style().minYields = true;
      El detail = detail(entry).ellipsis().minSize(0, 0).shrink(1);
      main = El.row()
          .align(Style.Align.CENTER)
          .gap(theme.space().s())
          .minSize(0, 0)
          .children(name, detail);
    }
    El content = PassiveContent.row(
        theme,
        PassiveContent.leading(
            theme, entry.symbol() != null ? icon(entry.symbol(), Icon.Variant.MUTED) : null),
        main,
        kind);
    return PassiveList.option(
            theme, i, state.highlight, at -> state.highlight = at, () -> take.accept(i))
        .label(entry.text())
        .child(content);
  }

  /** What a key does while the list shows; whether the list took it. */
  private static boolean key(Key k, State state, List<Entry> entries, IntConsumer take) {
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
      state.typedCharacter = state.triggered ? inserted : null;
      state.highlight = 0; // what it shows changes
    } else if (insertion
        && inserted.codePointCount(0, inserted.length()) == 1
        && state.openAfter.contains(inserted)) {
      state.typed = change.newEnd();
      state.triggered = true;
      state.typedCharacter = inserted;
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
