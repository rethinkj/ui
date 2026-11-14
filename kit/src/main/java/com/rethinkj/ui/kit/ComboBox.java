package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.Theme.over;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * A choice that is typed: closed it looks like a {@link Select}, the current choice and a chevron
 * in a text field's frame; open it is a text field with the {@linkplain FilterList filtered list}
 * of the choices below it, at least as wide as itself, which narrows {@linkplain Fuzzy fuzzily} as
 * one types. Use it where a {@link Select} would list more than a screenful, or where the user
 * knows what to type: a database among dozens, a font, a language. A select stays the control for
 * a few choices that one reads rather than types, such as a theme or an encoding.
 *
 * <p>It comes in two kinds:
 *
 * <ul>
 *   <li>{@link #comboBox}: one of the choices. A press, Alt+Arrow Down or Up, or a letter or digit
 *       typed while it has the focus opens it, the typed text filtering the list; the current
 *       choice is checked and, before anything is typed, highlighted, then the best match; when
 *       the app changes the choices meanwhile, the highlighted one stays highlighted where it went.
 *       Enter or a press chooses, Escape, Tab, Shift+Tab or a press elsewhere closes it and leaves
 *       the choice as it was.
 *   <li>{@link #free}: any text, the choices being suggestions, such as a host name with the ones
 *       used before. It is a text field whose every edit is the value; typing opens the
 *       suggestions that match, none of them highlighted, so that Enter keeps what was typed until
 *       Arrow Down highlights one. Alt+Arrow Down or the chevron opens them all.
 * </ul>
 *
 * <p>While it is open, Up and Down, Page Up and Page Down, Home and End move the highlight in the
 * list, and the pointer highlights the entry it is over; the focus stays in the field. The app
 * keeps the value and a {@link State}. It is {@code size().control()} high and {@code size().field()}
 * wide unless the view sets another size, as in a cell of a grid a row high. In a tree dump it is
 * {@code role=combobox} with the current value as the label and {@code expanded} or {@code
 * expanded=false}; open, its field is a {@code role=textbox} inside it and its list an overlay
 * {@code role=listbox} with {@code role=option} entries (see {@link FilterList}).
 */
public final class ComboBox {

  /** The mark at the right of the frame, as a {@link Select}'s. */
  public static final Symbol OPENS = Select.OPENS;

  /** The most rows the list shows at once; more scroll. */
  public static final int ROWS = 8;

  private ComboBox() {}

  /** What the app keeps of a combo box: whether its list is open and what was typed into it. */
  public static final class State {
    final FilterList.State list = new FilterList.State();
    private boolean open;
    private int opened; // how often it opened, which gives the field the focus each time
    private boolean free; // of which kind the combo box was last built
    private int picked; // suggestions taken, which make the field anew with its caret at the end
    // The entries of the last build's choices, and the handler their entries call.
    private List<?> choices;
    private int chosen;
    List<FilterList.Entry> entries; // package-private for tests
    private IntConsumer choose;
    // What the typed forms made of their values last, handed on while it comes out the same, so
    // that the list keeps what it found although the app maps its values anew in every build.
    private List<?> typedValues;
    private List<?> typed;

    public boolean isOpen() {
      return open;
    }

    /** The text typed since it opened, which filters the list. */
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
      list.typed(typed, !free);
    }

    public void close() {
      open = false;
      list.reset(-1);
    }
  }

  /**
   * A combo box of choices, of which the user chooses one.
   *
   * @param choices the texts to choose from
   * @param selected the index of the current choice, -1 for none
   * @param state whether the list is open and what was typed; the app keeps it
   * @param onSelect told the index of the choice made; null disables the combo box
   */
  public static El comboBox(
      Theme t, List<String> choices, int selected, State state, IntConsumer onSelect) {
    boolean enabled = onSelect != null;
    String text = selected >= 0 && selected < choices.size() ? choices.get(selected) : "";
    if (!enabled || !state.open) {
      if (state.open) {
        state.close();
      }
      return closed(t, text, null, enabled, state);
    }
    state.free = false;
    state.list.current = selected; // highlighted while nothing is typed
    List<FilterList.Entry> entries = entries(state, choices, selected, i -> {
      state.close();
      onSelect.accept(i);
    });
    El field = TextField.textField(t, state.list.query, text, v -> state.list.typed(v, true))
        .label(text);
    return open(t, text, field, null, FilterList.Entries.of(entries), state, true);
  }

  /**
   * A combo box of values of any type, of which the user chooses one, as {@link
   * #comboBox(Theme, List, int, State, IntConsumer)} is of texts:
   *
   * <pre>{@code
   * comboBox(t, schemas, s.schema, Schema::name, s.schemaBox, v -> s.schema = v)
   * }</pre>
   *
   * <p>The texts are made in every build, one call of {@code text} per value, and while they are
   * equal to the last build's, the list of the last build goes on, so that the list keeps what it
   * filtered, as it does for the same list of texts.
   *
   * @param selected the current value, found by {@code equals}; null, or a value not among them,
   *     for none
   * @param text the text a value shows, and what a typed text is matched against
   * @param onSelect told the value chosen; null disables the combo box
   */
  @SuppressWarnings("unchecked")
  public static <T> El comboBox(
      Theme t,
      List<T> values,
      T selected,
      Function<? super T, String> text,
      State state,
      Consumer<? super T> onSelect) {
    values = Choices.distinct(values);
    List<String> texts = Choices.texts(values, text);
    if (texts.equals(state.typed)) {
      texts = (List<String>) state.typed;
    } else {
      state.typed = texts;
      state.typedValues = null;
    }
    return comboBox(
        t, texts, Choices.index(values, selected), state, Choices.handler(values, onSelect));
  }

  /** A combo box of the value of a {@link Prop}; a prop that cannot be written disables it. */
  public static <T> El comboBox(
      Theme t, List<T> values, Prop<T> selected, Function<? super T, String> text, State state) {
    return Prop.shown(
        t, comboBox(t, values, selected.get(), text, state, selected.onChange()), selected);
  }

  /**
   * A choice with more than its text: what stands before it and a muted text at its right.
   *
   * @param text what the choice shows, and what a typed text is matched against
   * @param leading builds what stands before the text, or null; see {@link #icon}, {@link #image}
   * @param detail a muted text at the right of the row, such as a version, or null
   */
  public record Item(String text, Function<Theme, El> leading, String detail) {
    public Item {
      Objects.requireNonNull(text, "text");
    }

    /** An icon of the icon font before the text. */
    public Item icon(Symbol symbol) {
      return leading(t -> Icon.icon(t, symbol, Icon.Variant.MUTED));
    }

    /** An image before the text, fitted into an icon's square, such as a database's logo. */
    public Item image(com.rethinkj.ui.Image image) {
      Objects.requireNonNull(image, "image");
      return leading(t -> El.image(image)
          .size(t.size().icon(), t.size().icon())
          .fit(com.rethinkj.ui.Image.Fit.CONTAIN));
    }

    /**
     * Any element before the text, built for each place it shows, such as an {@link Avatar} or a
     * coloured mark. The closed box gives it an icon's square; a row of the list is as high as a
     * control.
     */
    public Item leading(Function<Theme, El> leading) {
      return new Item(text, leading, detail);
    }

    public Item detail(String detail) {
      return new Item(text, leading, detail);
    }
  }

  /** A choice of {@link #items} with this text, to which the other parts chain. */
  public static Item item(String text) {
    return new Item(text, null, null);
  }

  /**
   * A combo box of {@link Item}s, of which the user chooses one, as {@link #comboBox} is of
   * texts: each row shows the item's icon, image or own element before its text and its detail
   * at the right, the current choice with a check mark at the end, and the box shows the current
   * choice with what stands before it. A typed text is matched against the items' texts.
   *
   * @param items the choices
   * @param selected the index of the current choice, -1 for none
   * @param state whether the list is open and what was typed; the app keeps it
   * @param onSelect told the index of the choice made; null disables the combo box
   */
  public static El items(
      Theme t, List<Item> items, int selected, State state, IntConsumer onSelect) {
    boolean enabled = onSelect != null;
    Item current = selected >= 0 && selected < items.size() ? items.get(selected) : null;
    String text = current == null ? "" : current.text();
    El lead =
        current == null || current.leading() == null ? null : current.leading().apply(t);
    if (!enabled || !state.open) {
      if (state.open) {
        state.close();
      }
      return closed(t, text, lead, enabled, state);
    }
    state.free = false;
    state.list.current = selected; // highlighted while nothing is typed
    // The handlers of the entries go through the state, which holds the handler of this build.
    state.choose = i -> {
      state.close();
      onSelect.accept(i);
    };
    if (items != state.choices || selected != state.chosen || state.entries == null) {
      List<FilterList.Entry> entries = new ArrayList<>(items.size());
      for (int i = 0; i < items.size(); i++) {
        int at = i;
        Item item = items.get(i);
        entries.add(FilterList.entry(item.text(), () -> state.choose.accept(at))
            .leading(item.leading())
            .detail(item.detail())
            .checked(i == selected));
      }
      state.choices = items;
      state.chosen = selected;
      state.entries = List.copyOf(entries);
    }
    El field = TextField.textField(t, state.list.query, text, v -> state.list.typed(v, true))
        .label(text);
    return open(t, text, field, lead, FilterList.Entries.of(state.entries), state, true);
  }

  /**
   * A combo box of values of any type with more than their texts, as {@link #items(Theme, List,
   * int, State, IntConsumer)} is of {@link Item}s:
   *
   * <pre>{@code
   * items(t, products, s.product,
   *     p -> ComboBox.item(p.name()).image(p.logo()).detail(p.versions()),
   *     s.productBox, v -> s.product = v)
   * }</pre>
   *
   * <p>The items are made in every build, one call of {@code item} per value, and while the values
   * are equal to the last build's and so are the items' texts and details, the items of the last
   * build go on, so that the list keeps what it filtered; what stands before a text is taken to
   * follow from the value.
   *
   * @param selected the current value, found by {@code equals}; null, or a value not among them,
   *     for none
   * @param item the item a value shows, such as {@code v -> ComboBox.item(v.name()).icon(...)}
   * @param onSelect told the value chosen; null disables the combo box
   */
  @SuppressWarnings("unchecked")
  public static <T> El items(
      Theme t,
      List<T> values,
      T selected,
      Function<? super T, Item> item,
      State state,
      Consumer<? super T> onSelect) {
    values = Choices.distinct(values);
    List<Item> items = new ArrayList<>(values.size());
    for (T v : values) {
      items.add(item.apply(v));
    }
    if (values.equals(state.typedValues) && same(items, (List<Item>) state.typed)) {
      items = (List<Item>) state.typed;
    } else {
      state.typedValues = new ArrayList<>(values); // a copy, which a change in place does not reach
      state.typed = items;
    }
    return items(
        t, items, Choices.index(values, selected), state, Choices.handler(values, onSelect));
  }

  /** A combo box of items of the value of a {@link Prop}, as {@link #comboBox(Theme, List, Prop, Function, State)}. */
  public static <T> El items(
      Theme t, List<T> values, Prop<T> selected, Function<? super T, Item> item, State state) {
    return Prop.shown(
        t, items(t, values, selected.get(), item, state, selected.onChange()), selected);
  }

  /** Whether the items have the same texts and details, and leading elements in the same places. */
  private static boolean same(List<Item> a, List<Item> b) {
    if (b == null || a.size() != b.size()) {
      return false;
    }
    for (int i = 0; i < a.size(); i++) {
      Item x = a.get(i);
      Item y = b.get(i);
      if (!x.text().equals(y.text())
          || !Objects.equals(x.detail(), y.detail())
          || (x.leading() == null) != (y.leading() == null)) {
        return false;
      }
    }
    return true;
  }

  /** A free combo box of the text of a {@link Prop}; a prop that cannot be written disables it. */
  public static El free(Theme t, Prop<String> value, List<String> suggestions, State state) {
    String text = value.get();
    return Prop.shown(
        t, free(t, text == null ? "" : text, suggestions, state, value.onChange()), value);
  }

  /**
   * A combo box whose value is any text, with suggestions: a text field that shows the
   * suggestions that match what is typed below it.
   *
   * @param value the text, from the app's state
   * @param suggestions what the list offers, such as the values used before
   * @param state whether the list is open; the app keeps it
   * @param onChange receives the new text after each edit and the suggestion chosen; null
   *     disables the combo box
   */
  public static El free(
      Theme t, String value, List<String> suggestions, State state, Consumer<String> onChange) {
    boolean enabled = onChange != null;
    state.free = true;
    if (!enabled) {
      state.open = false;
    }
    El field = TextField.textField(
        t,
        value,
        null,
        !enabled
            ? null
            : v -> {
              onChange.accept(v);
              state.open = true;
              state.list.typed(v, false);
            });
    if (!state.open) {
      return frame(t, value, field.key("field-" + state.picked), null, state, false, enabled);
    }
    field.key("field-" + state.picked);
    List<FilterList.Entry> entries = entries(state, suggestions, -1, i -> {
      state.close();
      state.picked++;
      onChange.accept(suggestions.get(i));
    });
    return open(t, value, field, null, FilterList.Entries.of(entries), state, false);
  }

  /**
   * The entries of these choices, made once for the same list and choice and kept in the state,
   * so that the list's filter keeps what it found.
   */
  private static List<FilterList.Entry> entries(
      State state, List<String> choices, int selected, IntConsumer choose) {
    // The handlers of the entries go through the state, which holds the handler of this build.
    state.choose = choose;
    if (choices == state.choices && selected == state.chosen && state.entries != null) {
      return state.entries;
    }
    List<FilterList.Entry> entries = new ArrayList<>(choices.size());
    for (int i = 0; i < choices.size(); i++) {
      int at = i;
      FilterList.Entry e = FilterList.entry(choices.get(i), () -> state.choose.accept(at));
      entries.add(i == selected ? e.symbol(Menu.CHECKED) : e);
    }
    state.choices = choices;
    state.chosen = selected;
    state.entries = List.copyOf(entries);
    return state.entries;
  }

  /** The closed look of {@link #comboBox}: a {@link Select}'s. */
  private static El closed(Theme t, String text, El lead, boolean enabled, State state) {
    int fill = t.surface().raised();
    El box = row()
        .role("combobox")
        .label(text)
        .disabled(!enabled)
        .expanded(false)
        .w(t.size().field())
        .h(t.size().control())
        .padding(0, t.space().m())
        .gap(t.space().s())
        .align(Style.Align.CENTER)
        .radius(t.radius().medium())
        .fill(fill)
        .border(t.size().stroke(), enabled ? t.border().strong() : t.border().divider())
        .motion(t.motion().feedback());
    if (lead != null) {
      box.child(leadBox(t, lead));
    }
    box.children(
        label(t, text)
            .color(enabled ? t.text().primary() : t.text().disabled())
            .grow(1)
            .minSize(0, 0)
            .ellipsis(),
        Icon.icon(t, OPENS, enabled ? Icon.Variant.MUTED : Icon.Variant.DISABLED));
    if (!enabled) {
      return box;
    }
    return box.focusable()
        .focusRing(t.accent().edge())
        .cursor(Cursor.POINTER)
        .onPress((Runnable) state::open)
        .onKey(k -> {
          boolean arrow = k.name().equals("ArrowDown") || k.name().equals("ArrowUp");
          if (arrow && k.modifiers() == Key.ALT) {
            state.open();
            return true;
          }
          String name = k.name();
          if ((k.modifiers() & ~Key.SHIFT) == 0
              && name.codePointCount(0, name.length()) == 1
              && Character.isLetterOrDigit(name.codePointAt(0))) {
            state.open(k.modifiers() == Key.SHIFT ? name : name.toLowerCase(Locale.ROOT));
            return true;
          }
          return false;
        })
        .whenHovered(e -> e.fill(over(fill, t.surface().hover())));
  }

  /** An item's own element in an icon's square, which takes no input. */
  private static El leadBox(Theme t, El lead) {
    return el().key("lead")
        .size(t.size().icon(), t.size().icon())
        .shrink(0)
        .center()
        .child(lead.shrink(0));
  }

  /** A field in the frame with the chevron, which opens and closes the list. */
  private static El frame(
      Theme t, String value, El field, El lead, State state, boolean choice, boolean enabled) {
    float side = t.size().control();
    field.wFull().hFull().style().paddingRight = Len.px(side); // the text stops before the mark
    El before = null;
    if (lead != null) { // where the closed box has it, and the text starts after it
      before = leadBox(t, lead);
      Style place = before.style();
      place.position = Style.Position.ABSOLUTE;
      place.left = Len.px(t.space().m());
      place.top = Len.px((side - t.size().icon()) / 2);
      field.style().paddingLeft =
          Len.px(t.space().m() + t.size().icon() + t.space().s());
    }
    field.autofocus(state.opened == 0 ? null : state.opened);
    El mark = el().role("button")
        .label(state.open ? "Close" : "Open")
        .size(side, side)
        .center()
        .child(Icon.icon(t, OPENS, enabled ? Icon.Variant.MUTED : Icon.Variant.DISABLED));
    Style at = mark.style();
    at.position = Style.Position.ABSOLUTE;
    at.right = Len.px(0);
    at.top = Len.px(0);
    at.height = Len.percent(1);
    if (enabled) {
      mark.keepFocus().cursor(Cursor.POINTER).onPress(() -> {
        if (state.open) {
          state.close();
        } else {
          state.open();
        }
      });
    }
    El box = el().role("combobox")
        .label(value)
        .expanded(state.open)
        .disabled(!enabled)
        .w(t.size().field())
        .h(t.size().control())
        .children(field, mark);
    if (before != null) {
      box.child(before);
    }
    if (enabled) {
      box.whenFocusWithin(e -> field.border(t.size().stroke(), t.accent().color()));
      field.onKey(k -> key(k, state, choice));
    }
    return box;
  }

  /** The keys of the field while the list is closed or open, besides the list's own. */
  private static boolean key(Key k, State state, boolean choice) {
    boolean arrow = k.name().equals("ArrowDown") || k.name().equals("ArrowUp");
    if (arrow && k.modifiers() == Key.ALT) {
      if (state.open) {
        state.close();
      } else {
        state.open();
      }
      return true;
    }
    if (!state.open) {
      if (k.name().equals("ArrowDown") && k.modifiers() == 0) {
        state.open(); // the suggestions, the first highlighted
        state.list.want = FilterList.State.BEST;
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
            if (!choice) {
              state.close(); // the text typed is the value
            }
            return true;
          }
        }
        default -> {}
      }
    }
    return FilterList.key(k, state.list, ROWS, e -> e.onChoose().run());
  }

  /** The open combo box: the field in its frame and the list below it. */
  private static El open(
      Theme t,
      String value,
      El field,
      El lead,
      FilterList.Entries entries,
      State state,
      boolean choice) {
    FilterList.Shown shown = FilterList.shown(state.list, entries);
    El box = frame(t, value, field, lead, state, choice, true);
    if (!choice && shown.count() == 0) {
      return box.expanded(false); // no suggestion for what is typed: nothing to show
    }
    El list =
        FilterList.list(t, state.list, shown, ROWS, value, e -> e.onChoose().run());
    El panel = col()
        .key("choices")
        .padding(t.space().s())
        .radius(t.radius().medium())
        .fill(t.surface().raised())
        .border(t.size().stroke(), t.border().control())
        .shadow(t.elevation().popup())
        .motion(t.motion().fade())
        .keepFocus()
        .dismissGroup(state)
        .side(Side.BOTTOM, Style.Align.STRETCH, t.space().xs())
        .onDismiss(state::close)
        .child(list);
    return box.dismissGroup(state).overlay(panel);
  }
}
