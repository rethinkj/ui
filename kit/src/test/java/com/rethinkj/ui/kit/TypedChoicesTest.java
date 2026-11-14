package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

  /** The entries the combo box keeps in its state, which its list knows by their identity. */
  private static List<?> entries(ComboBox.State state) {
    return state.entries;
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
