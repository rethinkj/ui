package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * The typed forms of the choice components build the trees of the forms with indexes and hand on
 * values. Without layout, so without native libraries: the trees are compared as built.
 */
class TypedChoicesTest {

  private static final Theme T = Theme.DARK;

  enum Level {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High");

    final String label;

    Level(String label) {
      this.label = label;
    }
  }

  record Schema(String name, String owner) {}

  private static final List<Level> LEVELS = List.of(Level.values());
  private static final List<String> TEXTS = List.of("Low", "Medium", "High");

  private final List<Object> told = new ArrayList<>();

  private static El find(El e, Predicate<El> p) {
    if (p.test(e)) {
      return e;
    }
    for (El c : e.children()) {
      El found = find(c, p);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static void press(El root, String label) {
    find(root, e -> label.equals(e.label()) && e.onPress() != null).onPress().press(1, 0, 0, 0);
  }

  private static boolean key(El e, String name) {
    return e.onKey().test(new Key(name));
  }

  @Test
  void aSelectOfValuesIsTheSelectOfTheirTextsAndTellsTheValue() {
    for (Level selected : new Level[] {Level.MEDIUM, null}) {
      int index = selected == null ? -1 : selected.ordinal();
      assertEquals(
          Select.select(T, TEXTS, index, new Menu.State(), i -> {}).dump(),
          Select.select(T, LEVELS, selected, l -> l.label, new Menu.State(), told::add)
              .dump());
    }
    El select = Select.select(T, LEVELS, Level.MEDIUM, l -> l.label, new Menu.State(), told::add);
    assertEquals("Medium", select.label());
    assertTrue(key(select, "ArrowDown"));
    assertTrue(key(select, "End"));
    assertTrue(key(select, "L"), "a letter goes to the choice that starts with it");
    assertEquals(List.of(Level.HIGH, Level.HIGH, Level.LOW), told);

    El none = Select.select(T, LEVELS, null, l -> l.label, new Menu.State(), told::add);
    assertEquals("", none.label(), "null selects nothing");
    El missing = Select.select(
        T, List.of(Level.LOW, Level.HIGH), Level.MEDIUM, l -> l.label, new Menu.State(), told::add);
    assertEquals("", missing.label(), "nor does a value that is not among the values");

    El disabled = Select.select(T, LEVELS, Level.LOW, l -> l.label, new Menu.State(), null);
    assertEquals(Select.select(T, TEXTS, 0, new Menu.State(), null).dump(), disabled.dump());
    assertNull(disabled.onKey());
  }

  @Test
  void theKeysGoOnFromTheSelectedValuePastEqualValuesAndFromNull() {
    List<String> twice = List.of("A", "A", "B");
    List<String> none = java.util.Arrays.asList(null, "A");
    java.util.function.Function<String, String> text = v -> v == null ? "None" : v;
    assertTrue(key(Select.select(T, twice, "A", text, new Menu.State(), told::add), "ArrowDown"));
    assertTrue(key(Select.select(T, none, null, text, new Menu.State(), told::add), "ArrowDown"));
    assertTrue(key(Radio.radioGroup(T, twice, "A", text, told::add), "ArrowDown"));
    assertTrue(key(Segmented.segmented(T, none, null, text, told::add), "ArrowRight"));
    assertTrue(key(NavList.navList(T, twice, "A", v -> NavList.item(v), told::add), "ArrowDown"));
    assertEquals(List.of("B", "A", "B", "A", "B"), told);
    El select = Select.select(T, twice, "A", text, new Menu.State(), told::add);
    assertTrue(select.dump().contains("label=\"A\""), "equal values show once: " + select.dump());
    El checked = Radio.radioGroup(T, none, null, text, told::add);
    assertTrue(checked.dump().contains("label=\"None\" checked"), "null is a value among them");
  }

  @Test
  void aRadioGroupAndASegmentedControlOfValuesTellTheValue() {
    assertEquals(
        Radio.radioGroup(T, TEXTS, 2, i -> {}).dump(),
        Radio.radioGroup(T, LEVELS, Level.HIGH, l -> l.label, told::add).dump());
    assertEquals(
        Segmented.segmented(T, TEXTS, -1, i -> {}).dump(),
        Segmented.segmented(T, LEVELS, null, l -> l.label, told::add).dump());
    assertEquals(
        Radio.radioGroup(T, TEXTS, 0, null).dump(),
        Radio.radioGroup(T, LEVELS, Level.LOW, l -> l.label, null).dump());

    El radio = Radio.radioGroup(T, LEVELS, Level.HIGH, l -> l.label, told::add);
    assertTrue(radio.dump().contains("role=radio label=\"High\" checked\n"), radio.dump());
    assertTrue(key(radio, "ArrowDown"), "around from the last to the first");
    press(radio, "Medium");
    press(radio, "High"); // chosen already: not told
    El segmented = Segmented.segmented(T, LEVELS, null, l -> l.label, told::add);
    assertTrue(key(segmented, "End"));
    press(segmented, "Low");
    assertEquals(List.of(Level.LOW, Level.MEDIUM, Level.HIGH, Level.LOW), told);

    assertNull(Radio.radioGroup(T, LEVELS, Level.LOW, l -> l.label, null).onKey());
    assertNull(Segmented.segmented(T, LEVELS, Level.LOW, l -> l.label, null).onKey());
  }

  @Test
  void aComboBoxOfValuesKeepsItsTextsWhileTheyAreEqual() {
    List<Schema> schemas = List.of(
        new Schema("public", "postgres"),
        new Schema("sales", "shop"),
        new Schema("audit", "postgres"));
    ComboBox.State state = new ComboBox.State();
    El closed = ComboBox.comboBox(T, schemas, schemas.get(1), Schema::name, state, told::add);
    assertEquals(
        ComboBox.comboBox(T, List.of("public", "sales", "audit"), 1, new ComboBox.State(), i -> {})
            .dump(),
        closed.dump());
    assertEquals("sales", closed.label());
    assertEquals(
        "",
        ComboBox.comboBox(T, schemas, new Schema("x", "y"), Schema::name, state, told::add)
            .label(),
        "a value not among them");

    state.open();
    ComboBox.comboBox(T, schemas, schemas.get(1), Schema::name, state, told::add);
    List<?> first = entries(state);
    // A new list of equal values, and a function that is another object: the same entries.
    ComboBox.comboBox(T, new ArrayList<>(schemas), schemas.get(1), s -> s.name(), state, told::add);
    assertSame(first, entries(state), "the list keeps what it found");
    ComboBox.comboBox(T, schemas, schemas.get(1), s -> s.name().toUpperCase(), state, told::add);
    assertTrue(first != entries(state), "other texts are other entries");

    ComboBox.comboBox(T, schemas, schemas.get(1), Schema::name, state, told::add);
    FilterList.key(
        new Key("ArrowDown"), state.list, ComboBox.ROWS, e -> e.onChoose().run());
    FilterList.key(
        new Key("Enter"), state.list, ComboBox.ROWS, e -> e.onChoose().run());
    assertEquals(List.of(schemas.get(2)), told, "the key after the current one chooses audit");
    assertTrue(!state.isOpen());

    El disabled = ComboBox.comboBox(T, schemas, null, Schema::name, new ComboBox.State(), null);
    assertEquals(
        ComboBox.comboBox(T, List.of("public", "sales", "audit"), -1, new ComboBox.State(), null)
            .dump(),
        disabled.dump());
  }

  @Test
  void aComboBoxOfItemsMakesItsItemsAgainOnlyWhenTheyChange() {
    List<Schema> schemas =
        new ArrayList<>(List.of(new Schema("public", "postgres"), new Schema("sales", "shop")));
    ComboBox.State state = new ComboBox.State();
    El closed =
        ComboBox.items(T, schemas, schemas.get(0), ComboBoxTestItems::item, state, told::add);
    assertEquals(
        ComboBox.items(
                T,
                List.of(
                    ComboBoxTestItems.item(schemas.get(0)), ComboBoxTestItems.item(schemas.get(1))),
                0,
                new ComboBox.State(),
                i -> {})
            .dump(),
        closed.dump());

    state.open();
    ComboBox.items(T, schemas, schemas.get(0), ComboBoxTestItems::item, state, told::add);
    List<?> first = entries(state);
    ComboBox.items(T, schemas, schemas.get(0), ComboBoxTestItems::item, state, told::add);
    assertSame(first, entries(state), "new items of equal values, texts and details");
    schemas.set(1, new Schema("sales", "admin")); // in place: another detail
    ComboBox.items(T, schemas, schemas.get(0), ComboBoxTestItems::item, state, told::add);
    assertTrue(first != entries(state));

    ComboBox.items(T, schemas, null, ComboBoxTestItems::item, state, told::add);
    FilterList.key(
        new Key("Enter"), state.list, ComboBox.ROWS, e -> e.onChoose().run());
    assertEquals(List.of(schemas.get(0)), told, "nothing chosen: Enter takes the first");
  }

  /** The entries the combo box keeps in its state, which its list knows by their identity. */
  private static List<?> entries(ComboBox.State state) {
    return state.entries;
  }

  /** An item of a schema: its name with an icon, its owner as the detail. */
  private static final class ComboBoxTestItems {
    static ComboBox.Item item(Schema s) {
      return ComboBox.item(s.name()).icon(Symbol.DATABASE).detail(s.owner());
    }
  }

  enum Page {
    GENERAL(Symbol.SETTINGS, "General", null),
    EDITOR(Symbol.CODE, "Editor", null),
    KEYMAP(Symbol.KEY_MULTIPLE, "Keymap", "Advanced"),
    ABOUT(Symbol.INFO, "About", "Help");

    final Symbol icon;
    final String title;
    final String group;

    Page(Symbol icon, String title, String group) {
      this.icon = icon;
      this.title = title;
      this.group = group;
    }

    NavList.Item item() {
      return NavList.item(icon, title);
    }
  }

  @Test
  void aNavListOfValuesHasAHeadingWhereTheGroupChanges() {
    List<Page> pages = List.of(Page.values());
    List<NavList.Item> rows = List.of(
        Page.GENERAL.item(),
        Page.EDITOR.item(),
        NavList.heading("Advanced"),
        Page.KEYMAP.item(),
        NavList.heading("Help"),
        Page.ABOUT.item());
    El list = NavList.navList(T, pages, Page.ABOUT, Page::item, p -> p.group, told::add);
    assertEquals(NavList.navList(T, rows, 5, i -> {}).dump(), list.dump());
    assertEquals(
        NavList.navList(T, pages.subList(0, 2).stream().map(Page::item).toList(), -1, i -> {})
            .dump(),
        NavList.navList(T, pages.subList(0, 2), Page.ABOUT, Page::item, told::add)
            .dump(),
        "without headings, and a value not among them");

    assertTrue(key(list, "ArrowUp"), "from About past the heading to Keymap");
    assertTrue(key(list, "Home"));
    press(list, "Keymap");
    press(list, "About"); // selected already: not told
    assertEquals(List.of(Page.KEYMAP, Page.GENERAL, Page.KEYMAP), told);

    assertTrue(key(NavList.navList(T, pages, null, Page::item, p -> p.group, told::add), "End"));
    assertEquals(Page.ABOUT, told.getLast(), "from none, End selects the last");
    assertNull(NavList.navList(T, pages, Page.ABOUT, Page::item, null).onKey());
  }
}
