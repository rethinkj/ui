package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The typed forms of the choice components build the trees of the forms with indexes and hand on
 * values. Without layout: the trees are compared as built.
 */
class TypedChoicesTest {

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded(); // building a label loads the font
  }

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
  void aSelectReportsOnlyChangedChoices() {
    Menu.State menu = new Menu.State();
    menu.open();
    El select = Select.select(LEVELS, Level.MEDIUM, l -> l.label, menu, told::add);
    press(select.overlays().getFirst(), "Medium");
    assertEquals(List.of(), told);
    assertFalse(menu.isOpen());

    menu = new Menu.State();
    menu.open();
    select = Select.select(LEVELS, Level.MEDIUM, l -> l.label, menu, told::add);
    press(select.overlays().getFirst(), "High");
    assertEquals(List.of(Level.HIGH), told);
    assertFalse(menu.isOpen());
  }

  @Test
  void aTypedComboBoxReportsOnlyChangedChoices() {
    for (boolean itemVariant : new boolean[] {false, true}) {
      List<Level> values = LEVELS;
      ComboBox.State state = new ComboBox.State();
      state.open();
      if (itemVariant) {
        ComboBox.items(values, Level.MEDIUM, l -> ComboBox.item(l.label), state, told::add);
      } else {
        ComboBox.comboBox(values, Level.MEDIUM, l -> l.label, state, told::add);
      }
      state.entries.get(1).onChoose().run();
      assertEquals(List.of(), told);
      assertFalse(state.isOpen());
      told.clear();

      state = new ComboBox.State();
      state.open();
      if (itemVariant) {
        ComboBox.items(values, Level.MEDIUM, l -> ComboBox.item(l.label), state, told::add);
      } else {
        ComboBox.comboBox(values, Level.MEDIUM, l -> l.label, state, told::add);
      }
      state.entries.get(2).onChoose().run();
      assertEquals(List.of(Level.HIGH), told);
      assertFalse(state.isOpen());
      told.clear();
    }
  }

  @Test
  void aSelectOfValuesIsTheSelectOfTheirTextsAndTellsTheValue() {
    for (Level selected : new Level[] {Level.MEDIUM, null}) {
      int index = selected == null ? -1 : selected.ordinal();
      assertEquals(
          Select.select(TEXTS, index, new Menu.State(), i -> {}).dump(),
          Select.select(LEVELS, selected, l -> l.label, new Menu.State(), told::add)
              .dump());
    }
    El select = Select.select(LEVELS, Level.MEDIUM, l -> l.label, new Menu.State(), told::add);
    assertEquals("Medium", select.label());
    assertTrue(key(select, "ArrowDown"));
    assertTrue(key(select, "End"));
    assertTrue(key(select, "L"), "a letter goes to the choice that starts with it");
    assertEquals(List.of(Level.HIGH, Level.HIGH, Level.LOW), told);

    El none = Select.select(LEVELS, null, l -> l.label, new Menu.State(), told::add);
    assertEquals("", none.label(), "null selects nothing");
    El missing = Select.select(
        List.of(Level.LOW, Level.HIGH), Level.MEDIUM, l -> l.label, new Menu.State(), told::add);
    assertEquals("", missing.label(), "nor does a value that is not among the values");

    El disabled = Select.select(LEVELS, Level.LOW, l -> l.label, new Menu.State(), null);
    assertEquals(Select.select(TEXTS, 0, new Menu.State(), null).dump(), disabled.dump());
    assertNull(disabled.onKey());
  }

  @Test
  void aRadioGroupAndASegmentedControlOfValuesTellTheValue() {
    assertEquals(
        Radio.radioGroup(TEXTS, 2, i -> {}).dump(),
        Radio.radioGroup(LEVELS, Level.HIGH, l -> l.label, told::add).dump());
    assertEquals(
        Segmented.segmented(TEXTS, -1, i -> {}).dump(),
        Segmented.segmented(LEVELS, null, l -> l.label, told::add).dump());
    assertEquals(
        Radio.radioGroup(TEXTS, 0, null).dump(),
        Radio.radioGroup(LEVELS, Level.LOW, l -> l.label, null).dump());

    El radio = Radio.radioGroup(LEVELS, Level.HIGH, l -> l.label, told::add);
    assertTrue(radio.dump().contains("role=radio label=\"High\" checked\n"), radio.dump());
    assertTrue(key(radio, "ArrowDown"), "around from the last to the first");
    press(radio, "Medium");
    press(radio, "High"); // chosen already: not told
    El segmented = Segmented.segmented(LEVELS, null, l -> l.label, told::add);
    assertTrue(key(segmented, "End"));
    press(segmented, "Low");
    assertEquals(List.of(Level.LOW, Level.MEDIUM, Level.HIGH, Level.LOW), told);

    assertNull(Radio.radioGroup(LEVELS, Level.LOW, l -> l.label, null).onKey());
    assertNull(Segmented.segmented(LEVELS, Level.LOW, l -> l.label, null).onKey());
  }

  @Test
  void aSelectKeepsItsValueWhenRelabeled() {
    for (Level selected : new Level[] {Level.MEDIUM, null}) {
      for (boolean enabled : new boolean[] {true, false}) {
        El select = Select.select(
            LEVELS, selected, l -> l.label, new Menu.State(), enabled ? told::add : null);
        assertEquals(selected == Level.MEDIUM ? "Medium" : "", select.label());
        select.label("Level");
        String dump = select.dump().lines().findFirst().orElseThrow();
        assertTrue(dump.contains("label=\"Level\""));
        assertTrue(dump.contains("value=\"" + (selected == Level.MEDIUM ? "Medium" : "") + "\""));
      }
    }
    El missing = Select.select(
        List.of(Level.LOW, Level.HIGH), Level.MEDIUM, l -> l.label, new Menu.State(), null);
    missing.label("Level");
    String dump = missing.dump().lines().findFirst().orElseThrow();
    assertTrue(dump.contains("value=\"\""));
  }

  @Test
  void aComboBoxKeepsItsValueWhenRelabeled() {
    for (boolean open : new boolean[] {true, false}) {
      for (boolean itemVariant : new boolean[] {true, false}) {
        ComboBox.State state = new ComboBox.State();
        if (open) {
          state.open();
        }
        El comboBox = itemVariant
            ? ComboBox.items(LEVELS, Level.MEDIUM, l -> ComboBox.item(l.label), state, told::add)
            : ComboBox.comboBox(LEVELS, Level.MEDIUM, l -> l.label, state, told::add);
        assertEquals("Medium", comboBox.label());
        comboBox.label("Level");
        String dump = comboBox.dump().lines().findFirst().orElseThrow();
        assertTrue(dump.contains("label=\"Level\""));
        assertTrue(dump.contains("value=\"Medium\""));
      }
    }
    ComboBox.State state = new ComboBox.State();
    El nullSelection = ComboBox.comboBox(LEVELS, null, l -> l.label, state, told::add);
    assertEquals("", nullSelection.label());
    nullSelection.label("Level");
    String dump = nullSelection.dump().lines().findFirst().orElseThrow();
    assertTrue(dump.contains("label=\"Level\""));
    assertTrue(dump.contains("value=\"\""));
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
    List<NavList.Entry> rows = List.of(
        Page.GENERAL.item(),
        Page.EDITOR.item(),
        NavList.heading("Advanced"),
        Page.KEYMAP.item(),
        NavList.heading("Help"),
        Page.ABOUT.item());
    El list = NavList.navList(pages, Page.ABOUT, Page::item, p -> p.group, told::add);
    assertEquals(NavList.navList(rows, 5, i -> {}).dump(), list.dump());
    assertEquals(
        NavList.navList(pages.subList(0, 2).stream().map(Page::item).toList(), -1, i -> {})
            .dump(),
        NavList.navList(pages.subList(0, 2), Page.ABOUT, Page::item, told::add).dump(),
        "without headings, and a value not among them");

    assertTrue(key(list, "ArrowUp"), "from About past the heading to Keymap");
    assertTrue(key(list, "Home"));
    press(list, "Keymap");
    press(list, "About"); // selected already: not told
    assertEquals(List.of(Page.KEYMAP, Page.GENERAL, Page.KEYMAP), told);

    assertTrue(key(NavList.navList(pages, null, Page::item, p -> p.group, told::add), "End"));
    assertEquals(Page.ABOUT, told.getLast(), "from none, End selects the last");
    assertNull(NavList.navList(pages, Page.ABOUT, Page::item, null).onKey());
  }

  @Test
  void aClosedComboBoxMapsOnlyItsCurrentValue() {
    List<Level> values = new ArrayList<>(List.of(Level.LOW, Level.LOW, Level.HIGH));
    values.add(null);
    for (boolean itemVariant : new boolean[] {false, true}) {
      for (boolean enabled : new boolean[] {true, false}) {
        for (Level selected : new Level[] {Level.HIGH, null, Level.MEDIUM}) {
          int[] count = {0};
          Function<Level, String> text = v -> {
            count[0]++;
            return v == null ? "None" : v.label;
          };
          ComboBox.State state = new ComboBox.State();
          if (!enabled) {
            state.open();
          }
          El combo = itemVariant
              ? ComboBox.items(
                  values,
                  selected,
                  v -> ComboBox.item(text.apply(v)),
                  state,
                  enabled ? told::add : null)
              : ComboBox.comboBox(values, selected, text, state, enabled ? told::add : null);
          assertFalse(state.isOpen(), "the popup is closed after building enabled=" + enabled);
          int expectedCalls;
          String expectedLabel;
          if (selected == Level.MEDIUM) {
            expectedCalls = 0;
            expectedLabel = "";
          } else if (selected == Level.HIGH) {
            expectedCalls = 1;
            expectedLabel = "High";
          } else {
            expectedCalls = 1;
            expectedLabel = "None";
          }
          assertEquals(
              expectedCalls,
              count[0],
              "mapper calls for " + selected + " enabled=" + enabled + " item=" + itemVariant);
          assertEquals(expectedLabel, combo.label());
        }
      }
    }
  }

  @Test
  void aComboBoxRefreshesItsChoicesAfterAClosedBuild() {
    for (boolean itemVariant : new boolean[] {false, true}) {
      List<Schema> schemas =
          new ArrayList<>(List.of(new Schema("orders", "alice"), new Schema("users", "bob")));
      told.clear();
      ComboBox.State state = new ComboBox.State();
      Supplier<El> build = () -> itemVariant
          ? ComboBox.items(schemas, schemas.get(0), s -> ComboBox.item(s.name()), state, told::add)
          : ComboBox.comboBox(schemas, schemas.get(0), s -> s.name(), state, told::add);
      state.open();
      build.get();
      state.close();
      schemas.set(1, new Schema("invoices", "bob"));
      build.get();
      state.open();
      build.get();
      List<FilterList.Entry> entries = state.entries;
      assertEquals(2, entries.size());
      assertEquals("orders", entries.get(0).text());
      assertEquals("invoices", entries.get(1).text());
      entries.get(1).onChoose().run();
      assertEquals(List.of(new Schema("invoices", "bob")), told);
      assertFalse(state.isOpen());
    }
  }
}
