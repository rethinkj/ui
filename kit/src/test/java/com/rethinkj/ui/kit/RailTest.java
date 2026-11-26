package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Workbench.dumpLine;
import static com.rethinkj.ui.kit.Workbench.find;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A left rail with an open item, a separator, a bottom group and a dot (stored image and tree),
 * keyboard navigation and open/close toggling, tooltip placement toward the inside, and scrolling.
 */
class RailTest {

  @TempDir
  Path out;

  final Rail.State rail = new Rail.State();
  int open;
  int presses;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private App<RailTest> app(Side side) {
    return app(this, state -> {
      Theme theme = Theme.CURRENT.get();
      var strip = Rail.rail(
              state.rail,
              "Tool windows",
              side,
              List.of(
                  Rail.item(Symbol.FOLDER, "Explorer").shortcut(Key.CONTROL, "1"),
                  Rail.item(Symbol.SEARCH, "Search").shortcut(Key.CONTROL, "2"),
                  Rail.separator(),
                  Rail.item(Symbol.WARNING, "Problems")
                      .dot(Badge.Kind.DANGER, "3 errors")
                      .description("Open diagnostics")),
              List.of(Rail.item(Symbol.SETTINGS, "Settings")),
              state.open,
              index -> state.open = index)
          .key("rail");
      var content = col()
          .grow(1)
          .hFull()
          .padding(theme.space().l())
          .fill(theme.surface().panel())
          .child(Label.label(state.open < 0 ? "Closed" : "Panel " + state.open));
      return row()
          .wFull()
          .hFull()
          .align(Style.Align.START) // the rail stretches to the height all the same
          .fill(theme.surface().window())
          .children(side == Side.LEFT ? strip : content, side == Side.LEFT ? content : strip);
    });
  }

  private static <T> App<T> app(T state, Function<T, El> view) {
    App<T> app = new App<>(state, view);
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  @Test
  void leftRailLooksAsStoredWithOpenItemSeparatorBottomGroupAndDot() throws IOException {
    open = 0;
    App<RailTest> app = app(Side.LEFT);
    List<Path> files =
        Script.run("size 160 240 scale 2\nframe\nsnap view\n", app, out.resolve("dark2"));
    Golden.assertSnaps("rail-dark-2x-", files, "view");

    String dump = app.tree().dump();
    assertTrue(dump.contains("role=toolbar label=\"Tool windows\""), dump);
    assertTrue(
        StructureTest.line(dump, "label=\"Explorer\"").contains("role=button")
            && StructureTest.line(dump, "label=\"Explorer\"").contains(" checked"),
        dump);
    assertTrue(StructureTest.line(dump, "label=\"Search\"").contains(" checked=false"), dump);
    assertTrue(dump.contains("role=separator"), dump);
    assertTrue(
        StructureTest.line(dump, "label=\"Problems\"")
            .contains(" checked=false value=\"3 errors\""),
        dump);
    assertTrue(StructureTest.line(dump, "label=\"Settings\"").contains(" checked=false"), dump);
  }

  @Test
  void keyboardMovesAcrossGroupsAndTogglesOpenAndClosed() throws IOException {
    open = 0;
    App<RailTest> app = app(Side.LEFT);
    // One Tab stop enters the rail at the first item; ArrowDown wraps across separator and groups.
    Script.run("""
        size 240 240
        frame
        key Tab
        frame
        """, app, out);
    assertTrue(StructureTest.line(app.tree().dump(), "label=\"Explorer\"").endsWith(" focused"));

    // Enter on the open item closes it -> onSelect(-1).
    Script.run("key Enter\nframe\n", app, out);
    assertEquals(-1, open);
    assertTrue(
        StructureTest.line(app.tree().dump(), "label=\"Explorer\"").contains(" checked=false"));

    // ArrowDown moves to Search (index 1); Space opens it -> onSelect(1).
    Script.run("key ArrowDown\nframe\nkey Space\nframe\n", app, out);
    assertEquals(1, open);
    assertTrue(StructureTest.line(app.tree().dump(), "label=\"Search\"").contains(" checked"));

    // ArrowDown past the separator to Problems (index 2); Enter opens it -> onSelect(2).
    Script.run("key ArrowDown\nframe\nkey Enter\nframe\n", app, out);
    assertEquals(2, open);

    // End jumps to Settings in the bottom group (index 3); ArrowDown wraps to Explorer (index 0).
    Script.run("key End\nframe\nkey Enter\nframe\n", app, out);
    assertEquals(3, open);
    Script.run("key ArrowDown\nframe\nkey Enter\nframe\n", app, out);
    assertEquals(0, open);

    // ArrowUp wraps to Settings (index 3); Home jumps back to Explorer (index 0).
    Script.run("key ArrowUp\nframe\n", app, out);
    assertTrue(StructureTest.line(app.tree().dump(), "label=\"Settings\"").endsWith(" focused"));
    Script.run("key Home\nframe\n", app, out);
    assertTrue(StructureTest.line(app.tree().dump(), "label=\"Explorer\"").endsWith(" focused"));

    // Pointer press on the open item closes it too.
    Script.run("press #item-0\nframe\n", app, out);
    assertEquals(-1, open);
  }

  @Test
  void tooltipsOpenTowardTheInsideWithKeycapsAndDescription() throws IOException {
    open = 0;
    App<RailTest> left = app(Side.LEFT);
    Script.run("""
        size 240 240
        frame
        move #item-0
        frame
        advance 1
        frame
        """, left, out);
    String leftDump = left.tree().dump();
    assertTrue(
        leftDump.contains("overlay right center gap 4 on #item-0 role=button label=\"Explorer\""),
        leftDump);
    assertTrue(leftDump.contains("label=\"Ctrl+1\""), leftDump);

    Script.run("move #item-2\nframe\n", left, out);
    String dotDump = left.tree().dump();
    assertTrue(dotDump.contains("text \"Open diagnostics — 3 errors\""), dotDump);

    App<RailTest> right = app(Side.RIGHT);
    Script.run("""
        size 240 240
        frame
        move #item-1
        frame
        advance 1
        frame
        """, right, out);
    String rightDump = right.tree().dump();
    assertTrue(
        rightDump.contains("overlay left center gap 4 on #item-1 role=button label=\"Search\""),
        rightDump);
  }

  @Test
  void shortRailKeepsOneGroupGapAndScrollsFocusedButtonsIntoView() throws IOException {
    App<RailTest> app = app(Side.LEFT);
    Script.run("size 160 64\nframe\nkey Tab\nframe\n", app, out);
    El problems = find(app, "item-2");
    El settings = find(app, "item-3");
    assertEquals(Theme.DARK.space().s(), settings.y() - (problems.y() + problems.height()));

    Script.run("key ArrowDown\nframe\n", app, out);
    assertEquals("item-1", app.focused().key());
    double afterArrow = find(app, "rail").scrollY();
    assertTrue(afterArrow > 0);
    assertTrue(app.focused().y() + app.focused().height() <= 64);

    Script.run("key End\nframe\n", app, out);
    assertEquals("item-3", app.focused().key());
    assertTrue(find(app, "rail").scrollY() > afterArrow);
    assertTrue(app.focused().y() + app.focused().height() <= 64);

    Script.run("key Home\nframe\nwheel #rail 0 40\nframe\n", app, out);
    assertTrue(find(app, "rail").scrollY() > afterArrow);
  }

  @Test
  void bottomOnlyRailPinsItsGroupAndUsesTheCurrentThemeWidth() throws IOException {
    Theme theme = Theme.DARK.withSpace(new Theme.Spacing(2, 6, 8, 12, 16, 24));
    App<Rail.State> app = app(
            new Rail.State(),
            state -> Rail.rail(
                    state,
                    "Rail",
                    Side.LEFT,
                    List.of(),
                    List.of(Rail.item(Symbol.SETTINGS, "Settings")),
                    -1,
                    _ -> {})
                .key("rail")
                .hFull())
        .provide(Theme.CURRENT, _ -> theme);
    float width = Theme.CURRENT.with(theme, Rail::width);
    assertEquals(
        theme.size().controlLarge() + 2 * theme.space().s() + theme.size().stroke(), width);
    Script.run("size 160 96\nframe\n", app, out);
    assertEquals(width, find(app, "rail").width());
    El settings = find(app, "item-0");
    assertEquals(96 - theme.space().s(), settings.y() + settings.height());

    Script.run("size 160 32\nframe\nadvance 1\nframe\n", app, out);
    assertEquals(theme.space().s(), find(app, "item-0").y());
  }

  @Test
  void actionsRunIndependentlyAndKeyboardNavigationSkipsDisabledEntries() throws IOException {
    List<Integer> selections = new ArrayList<>();
    App<RailTest> app = app(
        this,
        state -> Rail.rail(
                state.rail,
                "Tools",
                Side.LEFT,
                List.of(
                    Rail.item(Symbol.FOLDER, "Explorer"),
                    Rail.item(Symbol.SEARCH, "Search").disabled(true).busy(true),
                    Rail.action(Symbol.ARROW_CLOCKWISE, "Refresh", null).busy(true)),
                List.of(
                    Rail.action(Symbol.SETTINGS, "Settings", () -> state.presses++)
                        .key("settings")
                        .shortcut(Key.CONTROL, "P")
                        .dot(Badge.Kind.ACCENT, "")
                        .description("Open preferences"),
                    Rail.item(Symbol.WARNING, "Problems")),
                state.open,
                index -> {
                  selections.add(index);
                  state.open = index;
                },
                Rail.options().closable(false))
            .hFull());
    Script.run("""
        size 240 240
        frame
        press #item-0
        frame
        key Tab
        frame
        key ArrowDown
        frame
        key Enter
        frame
        """, app, out);
    assertEquals(0, open);
    assertTrue(selections.isEmpty());
    assertEquals(1, presses);
    assertEquals("settings", app.focused().key());
    assertTrue(dumpLine(app, "item-1").contains(" disabled"));
    assertTrue(dumpLine(app, "action-0").contains(" disabled"));

    Script.run("press #settings\nframe\nmove #settings\nframe\nadvance 1\nframe\n", app, out);
    assertEquals(2, presses);
    String action = dumpLine(app, "settings");
    assertFalse(action.contains(" checked"), action);
    assertFalse(action.contains(" value="), action);
    assertTrue(app.tree().dump().contains("text \"Open preferences\""));
    assertTrue(app.tree().dump().contains("label=\"Ctrl+P\""));
    assertEquals(
        Theme.DARK.size().dot(), find(app, "settings").children().getLast().width());

    Script.run("key ArrowDown\nframe\nkey Space\nframe\n", app, out);
    assertEquals("item-2", app.focused().key());
    assertEquals(2, open);
    assertEquals(List.of(2), selections);
  }

  @Test
  void railIsDisabledOnlyWhenItHasNoEnabledButtons() throws IOException {
    Rail.Action refresh = Rail.action(Symbol.ARROW_CLOCKWISE, "Refresh", () -> {});
    List<Rail.Entry> entries = new ArrayList<>(List.of(
        Rail.item(Symbol.WARNING, "Problems").dot(Badge.Kind.DANGER, "3 errors").busy(true),
        refresh.disabled(true).dot(Badge.Kind.DANGER, "Disconnected")));
    App<List<Rail.Entry>> app = app(
        entries, tools -> Rail.rail(rail, "Tools", Side.LEFT, tools, -1, null).key("rail"));
    Script.run("size 240 160\nframe\nkey Tab\nframe\n", app, out);
    assertTrue(dumpLine(app, "rail").contains(" disabled"));
    assertTrue(dumpLine(app, "item-0").contains(" disabled"));
    assertFalse(dumpLine(app, "item-0").contains(" busy"));
    assertEquals(
        Theme.DARK.text().disabled(), find(app, "item-0").children().getLast().fill());
    assertEquals(
        Theme.DARK.text().disabled(), find(app, "action-0").children().getLast().fill());
    assertNull(app.focused());

    entries.set(1, refresh);
    app.invalidate();
    Script.run("frame\nkey Tab\nframe\n", app, out);
    assertFalse(dumpLine(app, "rail").contains(" disabled"));
    assertEquals("action-0", app.focused().key());
  }

  @Test
  void busyToggleAndActionKeepFocusAndIgnoreKeyboardAndPointerPresses() throws IOException {
    App<RailTest> app = app(
        this,
        state -> Rail.rail(
            state.rail,
            "Tools",
            Side.LEFT,
            List.of(
                Rail.item(Symbol.FOLDER, "Explorer").busy(true).progress(0.5),
                Rail.action(Symbol.ARROW_CLOCKWISE, "Refresh", () -> state.presses++)
                    .busy(true)),
            state.open,
            index -> state.open = index));
    Script.run("""
        size 240 160
        frame
        key Tab
        frame
        key Enter
        frame
        press #item-0
        frame
        """, app, out);
    assertEquals(0, open);
    assertEquals("item-0", app.focused().key());
    assertTrue(dumpLine(app, "item-0").contains(" busy checked"));
    assertTrue(dumpLine(app, "progress").contains("value=\"50%\""));

    Script.run("key ArrowDown\nframe\nkey Space\nframe\npress #action-0\nframe\n", app, out);
    assertEquals("action-0", app.focused().key());
    assertTrue(dumpLine(app, "action-0").contains(" busy"));
    assertTrue(app.tree().dump().contains("role=progressbar label=\"Loading\""));
    assertEquals(0, presses);
  }

  @Test
  void typedRailSelectsDistinctValuesAndClosesWithNull() throws IOException {
    String[] selected = {"Missing"};
    App<Rail.State> app = app(
        new Rail.State(),
        state -> Rail.rail(
            state,
            "Tools",
            Side.LEFT,
            List.of("Explorer", "Explorer", "Search"),
            selected[0],
            text -> Rail.item(Symbol.FOLDER, text),
            value -> selected[0] = value));
    Script.run("size 240 160\nframe\n", app, out);
    assertTrue(dumpLine(app, "item-0").contains(" checked=false"));
    assertTrue(dumpLine(app, "item-1").contains(" checked=false"));
    assertFalse(app.tree().dump().contains("#item-2"));

    Script.run("press #item-1\nframe\n", app, out);
    assertEquals("Search", selected[0]);
    Script.run("press #item-1\nframe\n", app, out);
    assertNull(selected[0]);
    assertTrue(dumpLine(app, "item-1").contains(" checked=false"));
  }

  @Test
  void keyedTabStopSurvivesInsertionAndRemovalBeforeIt() throws IOException {
    List<Rail.Item> items = new ArrayList<>(List.of(
        Rail.item(Symbol.FOLDER, "Explorer").key("explorer"),
        Rail.item(Symbol.SEARCH, "Search").key("search")));
    App<List<Rail.Item>> app = app(
        items,
        tools -> row()
            .hFull()
            .children(
                Rail.rail(rail, "Tools", Side.LEFT, tools, -1, _ -> {}),
                Button.icon(Button.Variant.GHOST, Symbol.SETTINGS, "Outside", () -> {})
                    .key("outside")));
    Script.run(
        "size 240 160\nframe\nkey Tab\nframe\nkey ArrowDown\nframe\nkey Tab\nframe\n", app, out);
    assertEquals("outside", app.focused().key());

    items.addFirst(Rail.item(Symbol.WARNING, "Problems").key("problems"));
    app.invalidate();
    Script.run("frame\nkey Tab shift\nframe\n", app, out);
    assertEquals("search", app.focused().key());
    Script.run("key Tab\nframe\n", app, out);
    items.removeFirst();
    app.invalidate();
    Script.run("frame\nkey Tab shift\nframe\n", app, out);
    assertEquals("search", app.focused().key());
  }

  @Test
  void rejectsOtherSidesNullLabelsAndNullEntries() {
    Rail.State state = new Rail.State();
    assertThrows(
        IllegalArgumentException.class,
        () -> Rail.rail(state, "Rail", Side.TOP, List.of(), List.of(), -1, i -> {}));
    assertEquals(
        "label",
        assertThrows(
                NullPointerException.class,
                () -> Rail.rail(state, null, Side.LEFT, List.of(), -1, null))
            .getMessage());
    List<Rail.Entry> withNull = Arrays.asList(Rail.separator(), null);
    assertEquals(
        "entry",
        assertThrows(
                NullPointerException.class,
                () -> Rail.rail(state, "Rail", Side.LEFT, withNull, -1, null))
            .getMessage());
    assertEquals(
        "entry",
        assertThrows(
                NullPointerException.class,
                () -> Rail.rail(state, "Rail", Side.LEFT, List.of(), withNull, -1, null))
            .getMessage());
  }
}
