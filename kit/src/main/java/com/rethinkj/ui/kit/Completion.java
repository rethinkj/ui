package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Icon.icon;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.App;
import com.rethinkj.ui.CodeWords;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Completions at the caret of a text field, such as the keywords and table names of SQL in a
 * {@link CodeEditor}: a list below the caret ({@link El#atCaret}) of what the app suggests for the
 * word before it. The list opens as a word is typed, from its second character, and with
 * Control+Space; it narrows as typing goes on and closes when the word ends. The focus stays in the
 * field ({@link El#keepFocus}): Up and Down move the highlight, Enter and Tab put the highlighted
 * entry in place of the word, as one step of the undo history ({@link App#edit}), and Escape
 * closes the list, as do a press elsewhere, the wheel and the keys that move the caret sideways. A
 * press on an entry takes it.
 *
 * <pre>{@code
 * El editor = codeEditor(t, s.sql, s.editor, Sql.of(t), v -> s.sql = v);
 * Completion.complete(t, app, editor, s.editor.caret(), s.completion, word -> s.suggest(word));
 * }</pre>
 *
 * <p>A word is what {@link El#codeWords} takes for one: letters, digits and {@code _}. The app's
 * function gets the part of it before the caret, maybe empty after Control+Space, and returns the
 * entries to show, in order; the kit shows them as they come and filters nothing. In a tree dump
 * the list is {@code role=listbox} with {@code label="Completions"}, its entries {@code
 * role=option} with their text as the label and the keys {@code option-<index>}, the highlighted
 * one {@code selected}. Its look is that of a {@link Menu}.
 */
public final class Completion {

  private Completion() {}

  /** The most entries the list shows at once; more scroll. */
  static final int ROWS = 8;

  /**
   * An entry: the text that replaces the word, an icon before it or null, and what kind of thing
   * it is, shown muted at the right, such as {@code "keyword"} or {@code "table"}, or null.
   */
  public record Entry(String text, Symbol symbol, String kind) {
    public Entry {
      Objects.requireNonNull(text, "text");
    }

    public Entry(String text) {
      this(text, null, null);
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
    private boolean history; // the key of this input is undo or redo: its edit is no typing

    public boolean isOpen() {
      return open;
    }

    /** Opens the list with its first entry highlighted. */
    public void open() {
      open = true;
      highlight = 0;
    }

    public void close() {
      open = false;
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
   * @param suggest the entries for the part of the word before the caret
   * @return the field
   */
  public static El complete(
      Theme t,
      App<?> app,
      El field,
      int caret,
      State state,
      Function<String, List<Entry>> suggest) {
    String text = field.text().string();
    caret = Math.clamp(caret, 0, text.length());
    int from = CodeWords.start(text, caret);
    // A character typed into a word opens the list from the word's second character; an undo
    // that brings one back leaves the caret elsewhere, or a selection, and opens nothing.
    if (state.typed >= 0 && !state.open && state.typed == caret && caret - from >= 2) {
      state.open();
    }
    state.typed = -1;
    state.history = false;
    List<Entry> entries = state.open ? suggest.apply(text.substring(from, caret)) : List.of();
    if (entries.isEmpty()) {
      // A list with nothing to show is closed, or it would show wherever the caret went next,
      // as after a number typed, Home, a press or a find bar's match.
      state.close();
    }
    if (state.highlight >= entries.size()) {
      state.highlight = 0;
    }
    int at = caret;
    Consumer<Integer> take = i -> {
      state.close();
      String chosen = entries.get(i).text();
      app.edit(field, from, at, chosen, from + chosen.length());
    };
    Consumer<String> onEdit = field.onEdit();
    if (onEdit != null) {
      field.editable(v -> {
        if (state.history) {
          state.close(); // a step of the history brings text back; nobody typed it
        } else {
          follow(state, text, v);
        }
        onEdit.accept(v);
      });
    }
    Predicate<Key> own = field.onKey();
    field.onKey(k -> {
      // The field's keys for undo and redo; onEdit does not say where an edit came from.
      state.history = (k.modifiers() & ~Key.SHIFT) == Key.CONTROL
          && (k.name().equals("Z") || k.name().equals("Y"));
      return key(k, state, entries, take) || own != null && own.test(k);
    });
    if (entries.isEmpty()) {
      return field;
    }
    El list = col()
        .key("completions")
        .role("listbox")
        .label("Completions")
        .padding(t.space().s())
        .radius(t.radius().medium())
        .fill(t.surface().raised())
        .border(t.size().stroke(), t.border().control())
        .shadow(t.elevation().popup())
        .motion(t.motion().fade())
        .scroll()
        .scrollbars(t.scrollbars())
        .revealRow(state.highlight)
        .atCaret()
        .side(Side.BOTTOM, Style.Align.START, t.space().xs())
        .keepFocus()
        .onDismiss(state::close);
    list.style().minWidth = Len.px(t.size().field());
    list.style().maxHeight =
        Len.px(ROWS * t.size().control() + 2 * (t.space().s() + t.size().stroke()));
    for (int i = 0; i < entries.size(); i++) {
      list.child(option(t, entries.get(i), i, state, take));
    }
    return field.overlay(list);
  }

  private static El option(Theme t, Entry entry, int i, State state, Consumer<Integer> take) {
    boolean lit = i == state.highlight;
    El mark = el().w(t.size().icon()).shrink(0).center();
    if (entry.symbol() != null) {
      mark.child(icon(t, entry.symbol(), Icon.Variant.MUTED));
    }
    El content = row()
        .wFull()
        .hFull()
        .padding(0, t.space().m())
        .gap(t.space().m())
        .align(Style.Align.CENTER)
        .children(
            mark,
            label(t, entry.text())
                .font(t.type().mono().font().get())
                .fontSize(t.type().mono().size())
                .grow(1));
    if (entry.kind() != null) {
      content.child(
          label(t, Label.Variant.MUTED, entry.kind()).margin(0, 0, 0, t.space().l()));
    }
    return row()
        .key("option-" + i)
        .role("option")
        .label(entry.text())
        .selected(lit)
        .h(t.size().control())
        .shrink(0)
        .radius(t.radius().small())
        .fill(lit ? t.grid().selected() : 0)
        .onHoverFor(0, () -> state.highlight = i)
        .onPress(() -> take.accept(i))
        .child(content);
  }

  /** What a key does while the list shows; whether the list took it. */
  private static boolean key(Key k, State state, List<Entry> entries, Consumer<Integer> take) {
    if (k.name().equals("Space") && k.modifiers() == Key.CONTROL) {
      state.open();
      return true;
    }
    if (entries.isEmpty()) {
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
      case "ArrowDown" -> state.highlight = (state.highlight + 1) % n;
      case "ArrowUp" -> state.highlight = (state.highlight + n - 1) % n;
      case "PageDown" -> state.highlight = Math.min(state.highlight + ROWS, n - 1);
      case "PageUp" -> state.highlight = Math.max(state.highlight - ROWS, 0);
      case "Enter", "NumpadEnter", "Tab" -> take.accept(state.highlight);
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
   * characters, and keeps it open; a deletion
   * keeps it open while some of the word is left; any other edit closes it.
   */
  static void follow(State state, String before, String after) {
    int prefix = 0;
    int shorter = Math.min(before.length(), after.length());
    while (prefix < shorter && before.charAt(prefix) == after.charAt(prefix)) {
      prefix++;
    }
    int suffix = 0;
    while (suffix < shorter - prefix
        && before.charAt(before.length() - 1 - suffix)
            == after.charAt(after.length() - 1 - suffix)) {
      suffix++;
    }
    String removed = before.substring(prefix, before.length() - suffix);
    String inserted = after.substring(prefix, after.length() - suffix);
    if (removed.isEmpty()
        && inserted.codePointCount(0, inserted.length()) == 1
        && CodeWords.isWordChar(inserted.codePointAt(0))) {
      state.typed = prefix + inserted.length(); // the build after it may open the list
      state.highlight = 0; // what it shows changes
    } else if (inserted.isEmpty()
        && !removed.isEmpty()
        && state.open
        && CodeWords.start(after, prefix) < prefix) {
      state.highlight = 0;
    } else {
      state.close();
    }
  }
}
