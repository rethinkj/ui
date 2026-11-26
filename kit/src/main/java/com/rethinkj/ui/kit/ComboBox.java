package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;

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
import java.util.function.Supplier;

/**
 * A choice that is typed: closed it shows the current choice and a chevron as a {@link Select}
 * does; open it is a text field in the same frame with the {@linkplain FilterList filtered list}
 * of the choices below it, at least as wide as itself, which narrows {@linkplain Fuzzy fuzzily} as
 * one types. Use it where a {@link Select} would list more than a screenful, or where the user
 * knows what to type: a database among dozens, a font, a language. A select stays the control for
 * a few choices that one reads rather than types, such as a theme or an encoding.
 *
 * <p>It looks as a {@link Select} does, also while one types into it and in its free kind: raised
 * with the control's shadow and lifted under the pointer; the edge turns to the accent color while
 * its field has the focus.
 *
 * <p>It comes in two kinds:
 *
 * <ul>
 *   <li>{@link #comboBox}: one of the choices. A press, Alt+Arrow Down or Up, or a letter or digit
 *       typed while it has the focus opens it, the typed text filtering the list; the current
 *       choice is checked and, before anything is typed, highlighted, then the best match; when
 *       the app changes the choices meanwhile, the highlighted one stays highlighted where it went.
 *       Enter or a press chooses, Escape, Tab, Shift+Tab or a press elsewhere closes it and leaves
 *       the choice as it was. {@link #comboBox(FilterList.Entries, String, State, Runnable)} takes
 *       choices from a {@link FilterList.Entries} — fixed, found on demand or on another thread,
 *       with group headings and disabled entries — and an optional clear button in the closed box.
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
    private Side opens = Side.BOTTOM;

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
      list.answer = List.of(); // what a search found is not shown again for another text
      list.failure = null;
    }

    /**
     * Which side of the box its list opens on, {@link Side#TOP} or {@link Side#BOTTOM} (the
     * default), falling back to the other side where it does not fit ({@link El#side}).
     *
     * @throws IllegalArgumentException for {@link Side#LEFT} or {@link Side#RIGHT}, beside which
     *     the list, as wide as the box, would have no width
     */
    public State opens(Side side) {
      if (Objects.requireNonNull(side, "side") == Side.LEFT || side == Side.RIGHT) {
        throw new IllegalArgumentException("a list opens above or below: " + side);
      }
      this.opens = side;
      return this;
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
  public static El comboBox(List<String> choices, int selected, State state, IntConsumer onSelect) {
    Theme theme = Theme.CURRENT.get();
    boolean enabled = onSelect != null;
    String text = selected >= 0 && selected < choices.size() ? choices.get(selected) : "";
    if (!enabled || !state.open) {
      if (state.open) {
        state.close();
      }
      return closed(theme, text, null, enabled, state);
    }
    state.free = false;
    state.list.current = selected; // highlighted while nothing is typed
    List<FilterList.Entry> entries = entries(state, choices, selected, i -> {
      state.close();
      onSelect.accept(i);
    });
    El field = TextField.textField(state.list.query, text, v -> state.list.typed(v, true))
        .label(text);
    return open(theme, text, field, null, FilterList.Entries.of(entries), state, true);
  }

  /**
   * A combo box whose choices come from a {@link FilterList.Entries}: a fixed list ({@link
   * FilterList.Entries#of}), entries found for the typed text ({@link FilterList.Entries#by}), or
   * entries found on another thread ({@link FilterList.Entries#background}), with group headings,
   * disabled entries and the "Loading…" row as {@link FilterList} shows them. Each entry's own
   * {@link FilterList.Entry#onChoose} runs after the list closes; mark the current choice with
   * {@link FilterList.Entry#checked} where you want a check mark.
   *
   * @param entries where the choices come from; null disables the combo box
   * @param current the text of the current choice shown in the closed box and as the label; null
   *     or empty for none
   * @param state whether the list is open and what was typed; the app keeps it
   * @param onClear runs when the clear button inside the closed box is pressed; shown only while
   *     {@code current} is non-empty and the box is enabled. Null for no clear button
   */
  public static El comboBox(
      FilterList.Entries entries, String current, State state, Runnable onClear) {
    Theme theme = Theme.CURRENT.get();
    boolean enabled = entries != null;
    String text = current == null ? "" : current;
    if (!enabled || !state.open) {
      if (state.open) {
        state.close();
      }
      return closed(theme, text, null, enabled, state, onClear);
    }
    state.free = false;
    state.list.current = -1;
    El field = TextField.textField(state.list.query, text, v -> state.list.typed(v, true))
        .label(text);
    return open(theme, text, field, null, entries, state, true);
  }

  /**
   * A combo box of values of any type, of which the user chooses one, as {@link
   * #comboBox(List, int, State, IntConsumer)} is of texts:
   *
   * <pre>{@code
   * comboBox(schemas, state.schema, Schema::name, state.schemaBox,
   *     value -> state.schema = value)
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
        texts, Choices.index(values, selected), state, Choices.handler(values, onSelect));
  }

  /** A combo box of the value of a {@link Prop}; a prop that cannot be written disables it. */
  public static <T> El comboBox(
      List<T> values, Prop<T> selected, Function<? super T, String> text, State state) {
    Theme theme = Theme.CURRENT.get();
    return Prop.shown(
        theme, comboBox(values, selected.get(), text, state, selected.onChange()), selected);
  }

  /**
   * A choice with more than its text: what stands before it and a muted text at its right.
   *
   * @param text what the choice shows, and what a typed text is matched against
   * @param leading builds what stands before the text, or null; see {@link #icon}, {@link #image}
   * @param detail a muted text at the right of the row, such as a version, or null
   */
  public record Item(String text, Supplier<El> leading, String detail) {
    public Item {
      Objects.requireNonNull(text, "text");
    }

    /** An icon before the text. */
    public Item icon(Icon icon) {
      return leading(new IconLead(Objects.requireNonNull(icon, "icon")));
    }

    /** An image before the text, fitted into an icon's square, such as a database's logo. */
    public Item image(com.rethinkj.ui.Image image) {
      return leading(PassiveContent.image(image));
    }

    /**
     * Any element before the text, built for each place it shows, such as an {@link Avatar} or a
     * colored mark. The closed box gives it an icon's square; a row of the list is as high as a
     * control.
     */
    public Item leading(Supplier<El> leading) {
      return new Item(text, leading, detail);
    }

    public Item detail(String detail) {
      return new Item(text, leading, detail);
    }
  }

  /** What {@link Item#icon} stands before the text: the icon, muted, or off in a box that is. */
  private record IconLead(Icon icon) implements Supplier<El> {
    @Override
    public El get() {
      return Icon.icon(icon, Icon.Variant.MUTED);
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
  public static El items(List<Item> items, int selected, State state, IntConsumer onSelect) {
    Theme theme = Theme.CURRENT.get();
    boolean enabled = onSelect != null;
    Item current = selected >= 0 && selected < items.size() ? items.get(selected) : null;
    String text = current == null ? "" : current.text();
    El lead;
    if (current == null || current.leading() == null) {
      lead = null;
    } else if (!enabled && current.leading() instanceof IconLead icon) {
      lead = Icon.icon(icon.icon(), Icon.Variant.DISABLED); // off as its chevron
    } else {
      lead = current.leading().get();
      if (!enabled && lead != null) {
        Icon.Picture.off(lead); // an image keeps its colors and dims; an app's element stays
      }
    }
    if (!enabled || !state.open) {
      if (state.open) {
        state.close();
      }
      return closed(theme, text, lead, enabled, state);
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
    El field = TextField.textField(state.list.query, text, v -> state.list.typed(v, true))
        .label(text);
    return open(theme, text, field, lead, FilterList.Entries.of(state.entries), state, true);
  }

  /**
   * A combo box of values of any type with more than their texts, as {@link #items(List,
   * int, State, IntConsumer)} is of {@link Item}s:
   *
   * <pre>{@code
   * items(products, state.product,
   *     product -> ComboBox.item(product.name()).image(product.logo()).detail(product.versions()),
   *     state.productBox, value -> state.product = value)
   * }</pre>
   *
   * <p>The items are made in every build, one call of {@code item} per value, and while the values
   * are equal to the last build's and so are the items' texts and details, the items of the last
   * build go on, so that the list keeps what it filtered; what stands before a text is taken to
   * follow from the value.
   *
   * @param selected the current value, found by {@code equals}; null, or a value not among them,
   *     for none
   * @param item the item a value shows, such as {@code
   *     value -> ComboBox.item(value.name()).icon(...)}
   * @param onSelect told the value chosen; null disables the combo box
   */
  @SuppressWarnings("unchecked")
  public static <T> El items(
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
    return items(items, Choices.index(values, selected), state, Choices.handler(values, onSelect));
  }

  /** A combo box of items of the value of a {@link Prop}, as {@link #comboBox(List, Prop, Function, State)}. */
  public static <T> El items(
      List<T> values, Prop<T> selected, Function<? super T, Item> item, State state) {
    Theme theme = Theme.CURRENT.get();
    return Prop.shown(
        theme, items(values, selected.get(), item, state, selected.onChange()), selected);
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
  public static El free(Prop<String> value, List<String> suggestions, State state) {
    Theme theme = Theme.CURRENT.get();
    String text = value.get();
    return Prop.shown(
        theme, free(text == null ? "" : text, suggestions, state, value.onChange()), value);
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
      String value, List<String> suggestions, State state, Consumer<String> onChange) {
    Theme theme = Theme.CURRENT.get();
    boolean enabled = onChange != null;
    state.free = true;
    if (!enabled) {
      state.open = false;
    }
    El field = TextField.textField(
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
      return frame(theme, value, field.key("field-" + state.picked), null, state, false, enabled);
    }
    field.key("field-" + state.picked);
    List<FilterList.Entry> entries = entries(state, suggestions, -1, i -> {
      state.close();
      state.picked++;
      onChange.accept(suggestions.get(i));
    });
    return open(theme, value, field, null, FilterList.Entries.of(entries), state, false);
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

  /** The closed look of {@link #comboBox}: the current choice and a chevron, as a select's. */
  private static El closed(Theme theme, String text, El lead, boolean enabled, State state) {
    return closed(theme, text, lead, enabled, state, null);
  }

  private static El closed(
      Theme theme, String text, El lead, boolean enabled, State state, Runnable onClear) {
    Button.Look look = Select.look(theme, enabled);
    El clear = enabled && onClear != null && !text.isEmpty()
        ? FieldActions.button(theme, Symbol.DISMISS, Texts.CURRENT.get().clear(), onClear)
            .key("clear")
            .shrink(0)
            .focusable()
            .focusRing(theme.accent().edge())
        : null;
    El box = Select.box(theme, look, text, lead, clear, enabled).expanded(false);
    if (!enabled) {
      return box;
    }
    return Button.enable(theme, look, box, state::open).onKey(k -> {
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
    });
  }

  /**
   * A field in a select's frame with the chevron, which opens and closes the list. The field, its
   * text, what stands before it and the chevron lie where the closed box has them.
   */
  private static El frame(
      Theme theme, String value, El field, El lead, State state, boolean choice, boolean enabled) {
    Button.Look look = Select.look(theme, enabled);
    float side = theme.size().control();
    float stroke = theme.size().stroke();
    float inset = stroke + theme.space().m();
    float chevron = theme.space().m() + theme.size().icon() + theme.space().s();
    // A clear field in the box's frame: the box gives the fill and the shadow, the field the edge,
    // the accent while one types, and the ring apart from it as a button's.
    field
        .wFull()
        .hFull()
        .fill(0)
        .border(stroke, look.edge())
        .whenHovered(null)
        .whenFocused(e -> e.border(stroke, theme.accent().color()))
        .focusRingGap(theme.size().ringGap());
    field.style().paddingRight = Len.px(chevron); // the text stops before the mark
    El before = null;
    if (lead != null) { // where the closed box has it, and the text starts after it
      before = leading(theme, lead);
      Style place = before.style();
      place.position = Style.Position.ABSOLUTE;
      place.left = Len.px(inset);
      place.top = Len.px((side - theme.size().icon()) / 2);
      field.style().paddingLeft =
          Len.px(theme.space().m() + theme.size().icon() + theme.space().s());
    }
    field.autofocus(state.opened == 0 ? null : state.opened);
    El mark = el().role("button")
        .label(state.open ? Texts.CURRENT.get().close() : Texts.CURRENT.get().open())
        .w(stroke + chevron)
        .align(Style.Align.CENTER)
        .justify(Style.Content.END)
        .padding(0, inset, 0, 0)
        .child(Icon.icon(OPENS, enabled ? Icon.Variant.MUTED : Icon.Variant.DISABLED));
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
        .w(theme.size().field())
        .h(side)
        .radius(theme.radius().medium())
        .fill(look.fill())
        .shadow(look.rest())
        .motion(theme.motion().feedback())
        .children(field, mark);
    if (before != null) {
      box.child(before);
    }
    if (enabled) {
      box.whenHovered(e -> e.fill(look.hover()).shadow(look.lifted()));
      field.onKey(k -> key(k, state, choice));
    }
    return box;
  }

  /** An item's own element in an icon's square, which takes no input. */
  static El leading(Theme theme, El lead) {
    return el().key("lead")
        .size(theme.size().icon(), theme.size().icon())
        .shrink(0)
        .center()
        .child(lead.shrink(0));
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
    return FilterList.key(k, state.list, ROWS, e -> choose(state, e));
  }

  /** Closes the list, cancelling any background search, before running the chosen entry's handler. */
  private static void choose(State state, FilterList.Entry entry) {
    state.close();
    entry.onChoose().run();
  }

  /** The open combo box: the field in its frame and the list below it. */
  private static El open(
      Theme theme,
      String value,
      El field,
      El lead,
      FilterList.Entries entries,
      State state,
      boolean choice) {
    FilterList.Shown shown = FilterList.shown(state.list, entries);
    El box = frame(theme, value, field, lead, state, choice, true);
    if (!choice && shown.count() == 0) {
      return box.expanded(false); // no suggestion for what is typed: nothing to show
    }
    El list = FilterList.list(
        theme, state.list, shown, ROWS, theme.size().control(), value, e -> choose(state, e));
    El panel = Popup.frame(theme, col())
        .key("choices")
        .padding(theme.space().s())
        .keepFocus()
        .dismissGroup(state)
        // Clear of the field's focus ring, which lies its gap apart from it; whole pixels, since
        // the overlay's place is rounded.
        .side(state.opens, Style.Align.STRETCH, (float) Math.ceil(Dialog.ring(theme)))
        .onDismiss(state::close)
        .child(list);
    return box.dismissGroup(state).overlay(panel);
  }
}
