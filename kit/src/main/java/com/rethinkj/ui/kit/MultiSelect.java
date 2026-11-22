package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Theme.over;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Choosing several values from a list of choices as removable tokens: the chosen values show as
 * {@link Badge}s inside a text field's frame, wrapping onto further lines as they grow, followed by
 * a text field that filters the remaining (not yet chosen) choices {@linkplain Fuzzy fuzzily} in a
 * {@link FilterList} below it, at least as wide as the frame. Use it for tags, roles, schemas or
 * other sets of values chosen from known options; use {@link ComboBox} to choose a single value.
 *
 * <p>A press on the field, Arrow Down, Alt+Arrow Down or Alt+Arrow Up, or typing while the field
 * has the focus opens the list, the typed text filtering the remaining choices with the best match
 * highlighted. Enter or a press on an entry adds it to the chosen values, clears the typed text and
 * leaves the list open for the next one. While the list is open, Up and Down, Page Up and Page
 * Down, Home and End move the highlight, and the pointer highlights the entry it is over; the focus
 * stays in the field. Backspace in the empty text field removes the last badge, and a badge's cross
 * removes that badge without moving the focus or closing the list. Escape, Tab, Shift+Tab,
 * Alt+Arrow Down or Up, or a press elsewhere closes the list and clears any typed text.
 *
 * <p>The app keeps the chosen values and a {@link State}; {@code onChange} receives a new immutable
 * list on every addition or removal, and a null handler disables the control. Reuse the same
 * {@code choices} list when unchanged so the list keeps what it filtered. It is {@code
 * size().field()} wide unless the view sets another width, and grows in height as badges wrap.
 *
 * <p>In a tree dump the frame is {@code role=combobox} with {@code value} set to the chosen values
 * joined with {@code ", "}, {@code expanded} or {@code expanded=false}, and {@code disabled} when
 * disabled; set its name with {@code .label(...)} (or through {@link Form#field}). Inside are the
 * chosen badges (each with its {@code role=button} remove cross when enabled), a {@code
 * role=textbox} for filtering, and when open the overlay {@code role=listbox} with {@code
 * role=option} entries (see {@link FilterList}).
 */
public final class MultiSelect {

  /** The most rows the list shows at once; more scroll. */
  public static final int ROWS = ComboBox.ROWS;

  private MultiSelect() {}

  /** What the app keeps of a multi-select: whether its list is open and what was typed into it. */
  public static final class State {
    final FilterList.State list = new FilterList.State();
    private boolean open;
    private int opened; // focuses the editable field when opened or pressed
    // The entries of the last build's remaining choices, and the handler their entries call.
    private List<String> choices;
    private List<String> selected;
    List<FilterList.Entry> entries; // package-private for tests
    private Consumer<String> choose;

    public boolean isOpen() {
      return open;
    }

    /** The text typed to filter the remaining choices. */
    public String text() {
      return list.query;
    }

    /** Opens the list with nothing typed. */
    public void open() {
      open("");
    }

    void open(String typed) {
      open = true;
      opened++;
      list.reset(-1);
      list.typed(typed, true);
    }

    void focus() {
      opened++;
    }

    /** Closes the list and clears any typed text. */
    public void close() {
      open = false;
      list.reset(-1);
    }
  }

  /**
   * A multi-select of choices, of which the user chooses any number as removable badges.
   *
   * @param choices the texts to choose from
   * @param selected the chosen values, from the app's state
   * @param state whether the list is open and what was typed; the app keeps it
   * @param onChange receives a new immutable list when a value is added or removed; null disables
   *     the multi-select
   */
  public static El multiSelect(
      List<String> choices, List<String> selected, State state, Consumer<List<String>> onChange) {
    Objects.requireNonNull(choices, "choices");
    Objects.requireNonNull(selected, "selected");
    Objects.requireNonNull(state, "state");
    Theme theme = Theme.CURRENT.get();
    boolean enabled = onChange != null;
    if (!enabled && state.open) {
      state.close();
    }
    float xs = theme.space().xs();
    int fill = theme.surface().raised();
    El box = row()
        .role("combobox")
        .expanded(state.open)
        .value(String.join(", ", selected))
        .disabled(!enabled)
        .wrap()
        .align(Style.Align.CENTER)
        .gap(xs)
        .w(theme.size().field())
        .padding(xs)
        .radius(theme.radius().medium())
        .fill(fill)
        .motion(theme.motion().feedback());
    if (!enabled) {
      TextField.edge(theme, box, theme.border().divider(), theme.border().divider());
    } else {
      int hover = over(fill, theme.surface().hover());
      TextField.edge(theme, box, theme.border().control(), theme.border().strong())
          .cursor(Cursor.TEXT)
          .keepFocus()
          .onPress(() -> {
            if (state.open) {
              state.focus();
            } else {
              state.open();
            }
          })
          .whenHovered(e -> {
            e.fill(hover);
            if (e.borderColor() != theme.status().danger()) {
              TextField.edge(theme, e, theme.border().strong(), theme.border().strong());
            }
          })
          .whenFocusWithin(e -> {
            if (e.borderColor() != theme.status().danger()) {
              TextField.edge(theme, e, theme.accent().color(), theme.accent().color());
            }
          });
    }
    Set<String> seen = new HashSet<>(selected.size());
    for (int i = 0; i < selected.size(); i++) {
      String value = selected.get(i);
      Badge.Options options = Badge.options();
      if (enabled) {
        int at = i;
        options.onRemove(() -> onChange.accept(without(selected, at)));
      }
      String key = seen.add(value) ? "token-" + value : "token-" + value + "-" + i;
      El badge = Badge.badge(value, options).key(key);
      // A value longer than the frame shortens with an ellipsis, so that its cross stays inside.
      badge.style().maxWidth = Len.percent(1);
      badge.children().getFirst().ellipsis().shrink(1).minSize(0, 0);
      box.child(badge);
    }
    Theme.TextStyle body = theme.type().body();
    El field = El.text(state.open ? state.list.query : "")
        .key("input")
        .font(body.font().get())
        .fontSize(body.size())
        .color(enabled ? theme.text().primary() : theme.text().disabled())
        .h(theme.badge().row())
        .w(theme.size().field() / 3) // at least this wide, or it wraps onto the next row
        .grow(1)
        .padding(0, theme.space().m() - xs);
    if (!enabled) {
      field.editable(null);
    } else if (state.open) {
      field
          .editable(v -> state.list.typed(v, true))
          .autofocus(state.opened == 0 ? null : state.opened)
          .focusRing(theme.accent().edge())
          .selectionColor(theme.accent().selection())
          .fieldMenu((f, open) -> TextField.menu(f, open, List.of()))
          .onKey(k -> key(k, selected, state, onChange));
    } else {
      field
          .role("textbox")
          .focusable()
          .focusRing(theme.accent().edge())
          .onText(state::open)
          .onKey(k -> key(k, selected, state, onChange));
    }
    box.child(field);
    if (!state.open) {
      return box;
    }
    List<FilterList.Entry> entries = entries(state, choices, selected, value -> {
      List<String> next = new ArrayList<>(selected.size() + 1);
      next.addAll(selected);
      next.add(value);
      state.list.typed("", true);
      onChange.accept(List.copyOf(next));
    });
    FilterList.Shown shown = FilterList.shown(state.list, FilterList.Entries.of(entries));
    El list = FilterList.list(
        theme,
        state.list,
        shown,
        ROWS,
        theme.size().control(),
        null,
        e -> e.onChoose().run());
    El panel = Popup.frame(theme, col())
        .key("choices")
        .padding(theme.space().s())
        .keepFocus()
        .dismissGroup(state)
        // Clear of the field's focus ring, which lies its gap apart from it; whole pixels, since
        // the overlay's place is rounded.
        .side(Side.BOTTOM, Style.Align.STRETCH, (float) Math.ceil(Dialog.ring(theme)))
        .onDismiss(state::close)
        .child(list);
    return box.dismissGroup(state).overlay(panel);
  }

  /**
   * A multi-select of the list of a {@link Prop}, with the prop's error when it comes from an
   * {@link Editing}; a prop that cannot be written disables it.
   */
  public static El multiSelect(List<String> choices, Prop<List<String>> selected, State state) {
    Objects.requireNonNull(selected, "selected");
    Theme theme = Theme.CURRENT.get();
    List<String> now = selected.get();
    El box = multiSelect(choices, now == null ? List.of() : now, state, selected.onChange());
    Prop.shown(theme, box, selected);
    if (selected.shownError() != null) {
      int danger = theme.status().danger();
      TextField.edge(theme, box, danger, danger).whenFocusWithin(null);
    }
    return box;
  }

  /**
   * The entries of the choices not yet chosen, made once while {@code choices} and {@code selected}
   * stay unchanged and kept in the state so that the list's filter keeps what it found.
   */
  private static List<FilterList.Entry> entries(
      State state, List<String> choices, List<String> selected, Consumer<String> choose) {
    // The handlers of the entries go through the state, which holds the handler of this build.
    state.choose = choose;
    if (choices == state.choices && selected.equals(state.selected) && state.entries != null) {
      return state.entries;
    }
    Set<String> chosen = selected.isEmpty() ? Set.of() : new HashSet<>(selected);
    List<FilterList.Entry> entries = new ArrayList<>(choices.size());
    for (String choice : choices) {
      if (!chosen.contains(choice)) {
        entries.add(FilterList.entry(choice, () -> state.choose.accept(choice)));
      }
    }
    state.choices = choices;
    state.selected = List.copyOf(selected);
    state.entries = List.copyOf(entries);
    return state.entries;
  }

  /** The keys of the field while the list is closed or open, besides the list's own. */
  private static boolean key(
      Key k, List<String> selected, State state, Consumer<List<String>> onChange) {
    boolean arrow = k.name().equals("ArrowDown") || k.name().equals("ArrowUp");
    if (arrow && k.modifiers() == Key.ALT) {
      if (state.open) {
        state.close();
      } else {
        state.open();
      }
      return true;
    }
    if (k.modifiers() == 0
        && k.name().equals("Backspace")
        && state.list.query.isEmpty()
        && !selected.isEmpty()) {
      onChange.accept(without(selected, selected.size() - 1));
      return true;
    }
    if (!state.open) {
      if (k.modifiers() == 0 && k.name().equals("ArrowDown")) {
        state.open();
        return true;
      }
      return false;
    }
    if (k.name().equals("Tab") && (k.modifiers() & ~Key.SHIFT) == 0) {
      state.close();
      return false; // and the focus moves on, either way
    }
    if (k.modifiers() == 0) {
      switch (k.name()) {
        case "Escape" -> {
          state.close();
          return true;
        }
        case "Enter", "NumpadEnter" -> {
          if (!state.list.shown.choosable(state.list.highlight)) {
            return true;
          }
        }
        default -> {}
      }
    }
    return FilterList.key(k, state.list, ROWS, e -> e.onChoose().run());
  }

  private static List<String> without(List<String> selected, int index) {
    List<String> fewer = new ArrayList<>(selected);
    fewer.remove(index);
    return List.copyOf(fewer);
  }
}
