package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Tabs.tabs;
import static com.rethinkj.ui.kit.TextField.textField;
import static com.rethinkj.ui.kit.Workbench.dumpLine;
import static com.rethinkj.ui.kit.Workbench.find;
import static com.rethinkj.ui.kit.Workbench.x;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Tabs.Tab;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The tabs of {@link Workbench#tabsView} in a window of 400 by 160: eight queries, more than the
 * bar has room for, the second modified and the third, Console, not closable; the active one's
 * content has a text field. Choosing, closing, the keys, the overfull bar, and stored images.
 */
class TabsTest {

  private static final Theme T = Theme.DARK;

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private App<Workbench> start() throws IOException {
    App<Workbench> app = Workbench.many(T).app(Workbench::tabsView);
    run(app, "frame");
    return app;
  }

  /** Runs the steps in a window of 400 by 160, which each script has to say first. */
  private void run(App<?> app, String steps) throws IOException {
    Script.run("size 400 160\n" + steps, app, out);
  }

  // The pointer

  @Test
  void aListOfRowsInTheContentScrollsInsideTabsInAColumnThatGrows() {
    App<Object> app = new App<>(
        new Object(),
        s -> col()
            .wFull()
            .hFull()
            .child(tabs(
                    List.of(new Tab("rows")),
                    0,
                    i -> {},
                    null,
                    col().key("list").rows(1000, 20, i -> El.el()))
                .key("tabs")
                .grow(1)));
    app.resize(300, 200, 1);
    app.frame();
    assertEquals(200, find(app, "tabs").height());
    assertEquals(
        200 - T.size().stroke(),
        find(app, "panel").y() + find(app, "list").height(),
        "the content fills the panel below the bar, inside the card's edge");
    app.wheel(20, 150, 0, 100);
    app.frame();
    assertEquals(100, find(app, "list").scrollY());
  }

  @Test
  void aPressChoosesATabAndLeavesTheFocusWhereItWas() throws IOException {
    App<Workbench> app = start();
    Workbench w = app.state();
    assertEquals("customers.sql", find(app, "panel").label());
    run(app, "press #tab-3\nframe");
    assertEquals(List.of("select 3"), w.log);
    assertEquals("products.sql", find(app, "panel").label());
    assertNull(app.focused(), "nowhere, as before the press");
    assertTrue(app.tree().dump().contains("text \"products.sql\"")); // in the content too
    assertEquals(
        T.accent().edge(),
        find(app, "indicator").fill(),
        "the active tab has the accent mark at its top");
    assertEquals(T.surface().panel(), find(app, "tab-3").fill(), "and joins the panel");
    assertEquals(T.border().divider(), find(app, "tab-3").borderColor());
    assertEquals(0, find(app, "tab-0").fill(), "inactive tabs sit on the bar");
    assertTrue(
        find(app, "tab-3").children().contains(find(app, "indicator")),
        "the indicator belongs to the active tab");
    assertTrue(dumpLine(app, "tab-3").endsWith(" selected"));
    assertFalse(dumpLine(app, "tab-0").contains(" selected"));

    run(app, "press #tab-3\nframe");
    assertEquals(List.of("select 3"), w.log, "told only of changes");
  }

  @Test
  void theCrossShowsOnTheActiveTabAndUnderThePointerAndClosesItsTab() throws IOException {
    App<Workbench> app = start();
    Workbench w = app.state();
    assertEquals(1, find(app, "close-0").opacity(), "on the active tab");
    assertEquals(1, find(app, "close-0").children().getFirst().opacity());
    assertEquals(
        T.text().muted(), find(app, "close-0").children().getFirst().text().color());
    assertEquals(0, find(app, "close-3").opacity(), "hidden on an inactive unmodified tab");
    assertNull(find(app, "close-2"), "Console cannot be closed");

    run(app, "move #tab-1\nframe");
    assertEquals(T.surface().hover(), find(app, "tab-1").children().getFirst().fill());
    assertEquals(1, find(app, "close-1").children().get(0).opacity(), "dot stays over the title");
    assertEquals(0, find(app, "close-1").children().get(1).opacity(), "cross waits for right end");
    run(app, "move #tab-1 at right-2\nframe");
    assertEquals(0, find(app, "close-1").children().get(0).opacity(), "dot hides over right zone");
    assertEquals(
        1, find(app, "close-1").children().get(1).opacity(), "cross shows over right zone");
    assertEquals(T.text().muted(), find(app, "close-1").children().get(1).text().color());
    assertEquals(0, find(app, "close-1").fill());
    run(app, "move #close-1\nframe");
    assertEquals(T.surface().hover(), find(app, "close-1").fill());
    assertEquals(
        T.text().primary(), find(app, "close-1").children().get(1).text().color());
    run(app, "press #close-1\nframe");
    assertEquals(List.of("close 1"), w.log);
    assertEquals(7, w.queries.size());
    assertEquals("Console", find(app, "tab-1").label(), "the next one moved up");
    assertEquals(0, w.active);

    run(app, "press #close-0\nframe"); // the active tab: the next one takes over
    assertEquals("Console", find(app, "panel").label());
    assertEquals(List.of("close 1", "close 0"), w.log);
  }

  @Test
  void theCrossAppearsOnlyOverTheTabsRightEnd() throws IOException {
    class State {
      final List<Tab> items = new java.util.ArrayList<>(List.of(
          new Tab("orders", true, true),
          new Tab("customers", true, false),
          new Tab("users", true, true)));
      int active;
      Tabs.Variant variant = Tabs.Variant.CARD;

      El view() {
        return col()
            .wFull()
            .hFull()
            .child(tabs(
                    items,
                    active,
                    i -> active = i,
                    i -> {
                      items.remove(i);
                      active = Math.min(active, items.size() - 1);
                    },
                    null,
                    Tabs.options().variant(variant))
                .grow(1));
      }
    }
    State state = new State();
    App<State> app = new App<>(state, State::view).provide(Theme.CURRENT, s -> T);
    run(app, "frame");

    // Active modified tab: dot shows at rest and over the title, swaps to cross over the right end.
    assertEquals(1, find(app, "close-0").children().get(0).opacity());
    assertEquals(0, find(app, "close-0").children().get(1).opacity());
    run(app, "move #tab-0 at left+12\nframe");
    assertEquals(1, find(app, "close-0").children().get(0).opacity(), "still dot over title");
    assertEquals(0, find(app, "close-0").children().get(1).opacity());
    run(app, "move #tab-0 at right-2\nframe");
    assertEquals(0, find(app, "close-0").children().get(0).opacity());
    assertEquals(1, find(app, "close-0").children().get(1).opacity());
    assertEquals(T.text().muted(), find(app, "close-0").children().get(1).text().color());
    assertEquals(0, find(app, "close-0").fill());
    run(app, "move #close-0\nframe");
    assertEquals(T.surface().hover(), find(app, "close-0").fill());
    assertEquals(
        T.text().primary(), find(app, "close-0").children().get(1).text().color());

    // Inactive unmodified tab: hover over title lifts ink and fill but keeps cross hidden.
    run(app, "move #tab-1 at left+12\nframe");
    assertEquals(T.surface().hover(), find(app, "tab-1").children().getFirst().fill());
    assertEquals(T.text().primary(), find(app, "tab-1").children().get(1).text().color());
    assertEquals(0, find(app, "close-1").opacity(), "cross stays hidden over title");

    // Right zone outside the 16px button shows muted cross; directly over button highlights it.
    run(app, "move #tab-1 at right-2\nframe");
    assertEquals(1, find(app, "close-1").opacity());
    assertEquals(
        T.text().muted(), find(app, "close-1").children().getFirst().text().color());
    assertEquals(0, find(app, "close-1").fill());

    // Moving straight from outside onto the 16px button reveals and highlights it in one frame.
    run(app, "move 0,0\nframe");
    assertEquals(0, find(app, "close-1").opacity());
    run(app, "move #tab-1 at right-8\nframe");
    assertEquals(1, find(app, "close-1").opacity());
    assertEquals(T.surface().hover(), find(app, "close-1").fill());
    assertEquals(
        T.text().primary(), find(app, "close-1").children().getFirst().text().color());

    // Same rule in LINE variant.
    state.variant = Tabs.Variant.LINE;
    run(app, "move 0,0\nframe\nmove #tab-1 at left+12\nframe");
    assertEquals(0, find(app, "close-1").opacity(), "LINE keeps cross hidden over title");
    run(app, "move #tab-1 at right-2\nframe");
    assertEquals(1, find(app, "close-1").opacity());
    assertEquals(
        T.text().muted(), find(app, "close-1").children().getFirst().text().color());
    assertEquals(0, find(app, "close-1").fill());
    run(app, "move #tab-1 at right-8\nframe\npress #close-1\nframe");
    assertEquals(2, state.items.size());
    assertEquals("users", state.items.get(1).title());
  }

  @Test
  void aBusyTabHasASpinnerBeforeItsTitle() throws IOException {
    App<Workbench> app = start();
    assertTrue(dumpLine(app, "tab-2").endsWith(" busy"), "Console's query runs");
    assertEquals(
        "progressbar",
        find(app, "tab-2").children().get(1).children().getFirst().role());
    assertFalse(dumpLine(app, "tab-0").contains(" busy"));
    run(app, "move #tab-2\nframe"); // lit under the pointer, the spinner too
    assertEquals(T.surface().hover(), find(app, "tab-2").children().getFirst().fill());
  }

  @Test
  void aModifiedTabHasADotInTheCloseSlot() throws IOException {
    App<Workbench> app = start();
    El close = find(app, "close-1");
    El dot = close.children().get(0);
    assertEquals((T.size().dot() + T.space().xs()) / 2, dot.radius(), "round");
    assertEquals(T.text().muted(), dot.fill());
    assertEquals(1, dot.opacity());
    assertEquals(0, close.children().get(1).opacity(), "cross hidden behind dot at rest");
    assertEquals(
        "orders by month.sql", find(app, "tab-1").children().get(1).text().string());
    assertEquals(
        "customers.sql", find(app, "tab-0").children().getFirst().text().string());
    String dump = app.tree().dump();
    assertTrue(StructureTest.line(dump, "#tab-1").contains(" modified"), dump);
    assertFalse(StructureTest.line(dump, "#tab-0").contains(" modified"), dump);
  }

  @Test
  void aTabWithAHueHasATickInItsColorAndSaysItInTheDump() throws IOException {
    List<Tab> tabs = List.of(new Tab("orders").hue(Theme.Hue.RED), new Tab("users"));
    App<Object> app = new App<>(new Object(), s -> Tabs.tabs(tabs, 1, i -> {}, i -> {}, El.el()));
    run(app, "frame");
    El marked = find(app, "tab-0");
    String dump = app.tree().dump();
    assertTrue(StructureTest.line(dump, "#tab-0").contains("value=\"red\""), dump);
    assertFalse(StructureTest.line(dump, "#tab-1").contains("value="), "no hue, no mark: " + dump);
    El tick = find(app, "hue-0");
    assertEquals(T.withAccent(Theme.Hue.RED).accent().edge(), tick.fill());
    assertEquals(T.size().indicator(), tick.width());
    assertEquals(T.size().icon() - T.space().s(), tick.height());
    assertTrue(x(app, tick) < x(app, marked.children().get(1)), "in the leading padding");
  }

  @Test
  void aTabShowsItsTooltipWhileThePointerRestsOnIt() throws IOException {
    List<Tab> tabs = List.of(new Tab("orders").tooltip("shop/public/orders"), new Tab("users"));
    App<Object> app = new App<>(new Object(), s -> Tabs.tabs(tabs, 1, i -> {}, i -> {}, El.el()));
    run(app, "frame\nmove #tab-0\nframe\nadvance 1\nframe");
    assertTrue(app.tree().dump().contains("role=tooltip label=\"shop/public/orders\""));
    run(app, "move #tab-1\nframe\nadvance 1\nframe");
    assertFalse(app.tree().dump().contains("role=tooltip"), "users has none");
  }

  // Keys

  @Test
  void theBarIsOneStopForTabAndItsKeysChooseTabs() throws IOException {
    App<Workbench> app = start();
    Workbench w = app.state();
    run(app, "key Tab\nframe");
    assertEquals(find(app, "tablist"), app.focused());

    run(app, "key ArrowRight\nframe\nkey ArrowLeft\nframe\nkey ArrowLeft\nframe");
    assertEquals(List.of("select 1", "select 0", "select 7"), w.log, "around at the start");
    run(app, "key ArrowRight\nframe\nkey Home\nframe\nkey End\nframe\nkey End\nframe");
    assertEquals(
        List.of("select 1", "select 0", "select 7", "select 0", "select 7"),
        w.log,
        "around at the end; told only of changes");
    run(app, "key ArrowLeft\nkey ArrowLeft\nframe");
    assertEquals(5, w.active, "two keys in one frame take two steps");

    run(app, "key Tab\nframe");
    assertEquals(find(app, "filter"), app.focused(), "the next stop is in the content");
    run(app, "key Tab shift\nframe");
    assertEquals(find(app, "tablist"), app.focused());
  }

  @Test
  void controlTabChoosesFromInsideTheContentAndControlWClosesTheActiveTab() throws IOException {
    App<Workbench> app = start();
    Workbench w = app.state();
    run(app, "press #filter\nkey Tab control shift\nframe");
    assertEquals(7, w.active, "back, around at the start");
    assertEquals(find(app, "filter"), app.focused(), "the field of the content keeps the focus");
    run(app, "key Tab control shift\nframe\nkey Tab control\nframe");
    run(app, "key PageUp control\nframe\nkey PageDown control\nframe");
    assertEquals(List.of("select 7", "select 6", "select 7", "select 6", "select 7"), w.log);

    run(app, "key W control\nframe");
    assertEquals("close 7", w.log.getLast());
    assertEquals(6, w.active);
    run(app, "key F4 control\nframe");
    assertEquals("close 6", w.log.getLast());
    assertEquals(5, w.active);
    assertEquals(find(app, "filter"), app.focused());
    assertEquals("", w.filter, "the field took none of these keys");

    w.active = 2; // Console, which cannot be closed
    run(app, "frame\nkey W control\nframe");
    assertEquals(find(app, "filter"), app.focused());
    assertEquals(6, w.queries.size());
    assertEquals("close 6", w.log.getLast());
  }

  @Test
  void innerTabsWithoutSwitchKeysLeaveThemToTheOuterTabs() throws IOException {
    int[] active = {0, 0}; // the outer tab, the inner one
    App<int[]> app = new App<>(
        active,
        a -> col()
            .wFull()
            .hFull()
            .child(tabs(
                    List.of(new Tab("query 1"), new Tab("query 2")),
                    a[0],
                    i -> a[0] = i,
                    null,
                    tabs(
                            List.of(
                                new Tab("Result 1", false, false),
                                new Tab("Messages", false, false)),
                            a[1],
                            i -> a[1] = i,
                            null,
                            textField("", v -> {}).key("field"),
                            Tabs.options().switchKeys(false))
                        .key("inner"))
                .grow(1)));
    Script.run("size 400 240 scale 2\nframe\npress #field\nkey PageDown control\nframe", app, out);
    assertTrue(dumpLine(app, "panel").contains("label=\"query 2\""), dumpLine(app, "panel"));
    assertEquals(0, active[1], "the inner tabs stay");
    Script.run("size 400 240 scale 2\nkey Tab shift\nframe\nkey ArrowRight\nframe", app, out);
    assertEquals(1, active[1], "the inner bar's own keys still choose");
    assertEquals(1, active[0], "and the outer tabs stay");
  }

  @Test
  void fromTheContentTheFocusGoesIntoTheNextTabsContentAndElseToTheBar() throws IOException {
    App<Workbench> app = start();
    run(app, "press #filter\nkey Tab control\nframe");
    assertEquals("orders by month.sql", find(app, "panel").label());
    assertTrue(find(app, "filter").isExiting(), "the content of this tab has no field");
    assertEquals(find(app, "divider"), app.focused(), "its first stop, not nowhere");
    run(app, "key ArrowDown\nframe");
    assertTrue(app.state().log.getLast().startsWith("editor "), "and the keys reach it");
    run(app, "key W control\nframe");
    assertEquals(find(app, "filter"), app.focused(), "closed: into the next tab's field");

    int[] active = {0};
    App<int[]> bare = new App<>(
        active,
        a -> col()
            .wFull()
            .hFull()
            .child(tabs(
                    List.of(new Tab("one"), new Tab("two")),
                    a[0],
                    i -> a[0] = i,
                    null,
                    a[0] == 0 ? textField("", v -> {}).key("field") : null)
                .grow(1)));
    run(bare, "frame\npress #field\nkey Tab control\nframe");
    assertEquals(1, active[0]);
    assertEquals(find(bare, "tablist"), bare.focused(), "nothing to focus in the content: the bar");
    run(bare, "key ArrowLeft\nframe");
    assertEquals(0, active[0], "whose keys work at once");
    assertEquals(find(bare, "tablist"), bare.focused(), "and the focus stays on the bar");
  }

  @Test
  void eachInputOfAFrameGoesToTheTabsTheOneBeforeLeft() throws IOException {
    App<Workbench> app = start();
    run(app, "key Tab\nkey W control\nkey W control\npress #close-0\nframe");
    // The second Control+W closes the tab the first made active, and the press lands where the
    // first tab's cross was, now on the second tab of the view the closes left, whose cross is
    // hidden: it chooses that tab.
    assertEquals(List.of("close 0", "close 0", "select 1"), app.state().log);
    assertEquals(1, app.state().active);
    assertEquals(
        app.state().queries.get(1).title().replace("*", ""), find(app, "panel").label());
  }

  /** Tabs alone, without {@link TabGroups}: moving, dragging and a menu. */
  private static final class Bar {
    final List<String> tabs = new java.util.ArrayList<>(List.of("a", "b", "c", "d"));
    int active;
    final Tabs.Dragging dragging = new Tabs.Dragging();
    final Menu.State menu = new Menu.State();

    El view() {
      return col()
          .wFull()
          .hFull()
          .child(tabs(
                  tabs.stream().map(Tab::new).toList(),
                  active,
                  i -> active = i,
                  i -> {
                    tabs.remove(i);
                    active = Math.min(active, tabs.size() - 1);
                  },
                  null,
                  Tabs.options()
                      .drag(dragging)
                      .onMove((from, to) -> active = Tabs.move(tabs, active, from, to))
                      .contextMenu(menu, List.of(Menu.item("Rename", () -> {}))))
              .grow(1));
    }
  }

  @Test
  void theMenuOfATabEndsWhenTheKeysCloseItOrChooseAnother() throws IOException {
    Bar bar = new Bar();
    App<Bar> app = new App<>(bar, Bar::view);
    run(app, "frame\npress 53,17 right\nframe");
    assertTrue(bar.menu.isOpen());
    run(app, "key W control\nframe");
    assertFalse(bar.menu.isOpen(), "its tab closed");
    assertEquals(List.of("a", "c", "d"), bar.tabs);
    run(app, "press 8,17 right\nframe\nkey Tab control\nframe");
    assertFalse(bar.menu.isOpen(), "another tab is active");
    assertEquals(1, bar.active);
  }

  // An overfull bar

  @Test
  void anOverfullBarScrollsUnderTheWheelAndShowsTheActiveTab() throws IOException {
    App<Workbench> app = start();
    El list = find(app, "tablist");
    assertEquals(390, list.width(), "the bar less its padding and the card's edge");
    run(app, "wheel #tablist 0 100\nframe");
    assertEquals(100, find(app, "tablist").scrollX(), "a mouse's wheel moves it sideways");
    run(app, "wheel #tablist 0 10000\nframe");
    double end = find(app, "tablist").scrollX();
    El last = find(app, "tab-7");
    assertEquals(395, x(app, last) + last.width(), "the last tab at the end, inside the edge");

    run(app, "key Tab\nkey ArrowLeft\nframe");
    assertEquals(end, find(app, "tablist").scrollX(), "the last tab chosen, already in view");
    run(app, "key Home\nframe");
    assertEquals(0, find(app, "tablist").scrollX(), "the first tab chosen and shown");
    run(app, "wheel #tablist 0 60\nframe");
    assertEquals(60, find(app, "tablist").scrollX(), "the bar scrolls freely in between");
    run(app, "key ArrowRight\nframe");
    assertEquals(60, find(app, "tablist").scrollX(), "the second tab is in view");
    run(app, "key End\nframe");
    assertEquals(end, find(app, "tablist").scrollX());
  }

  // The line variant

  @Test
  void theLineSlidesUnderTheChosenTabAlsoInAnOverfullBar() throws IOException {
    Workbench w = Workbench.many(T);
    w.variant = Tabs.Variant.LINE;
    App<Workbench> app = w.app(Workbench::tabsView);
    float inset = T.space().m();
    run(app, "frame");
    El first = find(app, "tab-0");
    assertEquals(x(app, first) + inset, x(app, find(app, "indicator")), "under the active tab");
    assertEquals(first.width() - 2 * inset, find(app, "indicator").width());
    assertEquals(0, find(app, "bar").fill(), "on the parent's surface");
    assertEquals(
        "indicator",
        find(app, "tablist").children().getFirst().key(),
        "under the tabs, which take the presses");

    run(app, "press #tab-1\nframe");
    assertEquals(
        x(app, find(app, "tab-0")) + inset, x(app, find(app, "indicator")), "it starts from there");
    run(app, "advance 1\nframe");
    El second = find(app, "tab-1");
    assertEquals(x(app, second) + inset, x(app, find(app, "indicator")), "and slides over");
    assertEquals(second.width() - 2 * inset, find(app, "indicator").width());

    String image = "tabs-line-dark";
    run(app, "wheel #tablist 0 10000\nframe\npress #tab-7\nframe\nadvance 1\nmove #tab-6\nframe");
    El last = find(app, "tab-7");
    assertEquals(x(app, last) + inset, x(app, find(app, "indicator")), "in a scrolled bar too");
    run(app, "frame\nsnap " + image); // the last tab active, the pointer on the one before it
    Golden.assertMatches(
        image,
        com.rethinkj.ui.render.Png.decode(
            java.nio.file.Files.readAllBytes(out.resolve(image + ".png"))),
        java.nio.file.Files.readString(out.resolve(image + ".tree.txt")));
  }

  @Test
  void onAddShowsAPlusButtonAndBindsControlT() throws IOException {
    class State {
      final List<String> titles = new java.util.ArrayList<>(List.of("one"));
      int active;
      boolean allowAdd = true;

      void add() {
        titles.add("tab-" + (titles.size() + 1));
        active = titles.size() - 1;
      }

      El view() {
        Tabs.Options options = Tabs.options();
        if (allowAdd) {
          options.onAdd(this::add);
        }
        El built = tabs(
                titles.stream().map(Tab::new).toList(),
                active,
                i -> active = i,
                i -> {
                  titles.remove(i);
                  active = Math.min(active, titles.size() - 1);
                },
                null,
                options)
            .grow(1);
        return col().wFull().hFull().onKey(built.onKey()).child(built);
      }
    }
    State state = new State();
    App<State> app = new App<>(state, State::view);
    run(app, "frame");
    El add = find(app, "add");
    assertNotNull(add);
    assertEquals("button", add.role());
    assertEquals("New tab", add.label());
    El first = find(app, "tab-0");
    assertEquals(
        x(app, first) + first.width() + T.space().s(),
        x(app, add),
        "right after the last tab while they fit");

    run(app, "press #add\nframe");
    assertEquals(List.of("one", "tab-2"), state.titles);
    assertEquals(1, state.active);

    run(app, "focus #tablist\nkey T control\nframe");
    assertEquals(List.of("one", "tab-2", "tab-3"), state.titles);

    // Overflow: + stays pinned at the bar's end.
    for (int i = 0; i < 6; i++) {
      state.titles.add("overflow-tab-" + i);
    }
    run(app, "frame");
    El bar = find(app, "bar");
    El pinned = find(app, "add");
    assertEquals(
        x(app, bar) + bar.width() - T.space().s(),
        x(app, pinned) + pinned.width(),
        "pinned at the bar's end once tabs overflow");

    // Empty bar: + still shows, press and Control+T both add.
    state.titles.clear();
    state.active = -1;
    run(app, "frame");
    assertNotNull(find(app, "add"));
    run(app, "press #add\nframe");
    assertEquals(List.of("tab-1"), state.titles);

    state.titles.clear();
    state.active = -1;
    run(app, "frame\nkey T control\nframe");
    assertEquals(List.of("tab-1"), state.titles, "Control+T adds in an empty bar too");

    // Without onAdd: no + and Control+T has no effect.
    state.allowAdd = false;
    run(app, "frame");
    assertNull(find(app, "add"));
    run(app, "focus #tablist\nkey T control\nframe");
    assertEquals(List.of("tab-1"), state.titles);
  }

  @Test
  void overflowListsAllTabsAndRevealsTheChosenOne() throws IOException {
    class State {
      final List<String> titles = new java.util.ArrayList<>();
      int active;
      int selects;
      final Tabs.Overflow overflow = new Tabs.Overflow();

      State(int count) {
        for (int i = 1; i <= count; i++) {
          titles.add("query-" + i);
        }
      }

      El view() {
        List<Tab> built = new java.util.ArrayList<>(titles.size());
        for (int i = 0; i < titles.size(); i++) {
          Tab tab = new Tab(titles.get(i));
          if (i == 0 || i == 20) {
            tab = tab.icon(Symbol.TABLE);
          }
          built.add(tab);
        }
        return col()
            .wFull()
            .hFull()
            .child(tabs(
                    built,
                    active,
                    i -> {
                      active = i;
                      selects++;
                    },
                    null,
                    null,
                    Tabs.options().onAdd(() -> {}).overflow(overflow))
                .grow(1));
      }
    }
    State state = new State(25);
    App<State> app = new App<>(state, State::view);
    Script.run("size 400 240\nframe", app, out);

    El add = find(app, "add");
    El overflow = find(app, "overflow");
    assertNotNull(overflow);
    assertEquals("button", overflow.role());
    assertEquals("All tabs", overflow.label());
    assertTrue(x(app, overflow) > x(app, add), "overflow comes after +");

    // Open the menu: all 25 tabs in bar order, active checked, icons shown on unchecked tabs.
    Script.run("size 400 240\npress #overflow\nframe\nadvance 1\nframe", app, out);
    assertTrue(state.overflow.menu().isOpen());
    El menu = find(app, "overflow").overlays().stream()
        .filter(e -> "menu".equals(e.key()))
        .findFirst()
        .orElseThrow();
    assertEquals(25, menu.children().size());
    assertEquals("query-1", menu.children().get(0).label());
    assertEquals("query-25", menu.children().get(24).label());
    assertTrue(dumpLine(app, "item-0").contains(" checked"), dumpLine(app, "item-0"));
    assertFalse(dumpLine(app, "item-20").contains(" checked"), dumpLine(app, "item-20"));
    assertTrue(
        menu.children().get(20).dump().contains("label=\"table\""),
        menu.children().get(20).dump());

    // Choose an off-screen tab: it becomes active and scrolls into view.
    Script.run("size 400 240\nwheel #menu 0 1000\nframe\npress #item-20\nframe", app, out);
    assertEquals(20, state.active);
    assertEquals(1, state.selects);
    assertFalse(state.overflow.menu().isOpen());
    El list = find(app, "tablist");
    El chosen = find(app, "tab-20");
    assertTrue(list.scrollX() > 0);
    assertTrue(x(app, chosen) >= x(app, list), "chosen tab's start is in view");
    assertTrue(
        x(app, chosen) + chosen.width() <= x(app, list) + list.width(),
        "chosen tab's end is in view");

    // Scroll the active tab off-screen to the right, then choose it again from the overflow menu.
    Script.run("size 400 240\nwheel #tablist 0 -10000\nframe", app, out);
    assertEquals(0, find(app, "tablist").scrollX());
    assertTrue(
        x(app, find(app, "tab-20"))
            >= x(app, find(app, "tablist")) + find(app, "tablist").width(),
        "active tab was scrolled off-screen");
    Script.run("""
        size 400 240
        press #overflow
        frame
        advance 1
        frame
        wheel #menu 0 1000
        frame
        press #item-20
        frame""", app, out);
    assertEquals(20, state.active);
    assertEquals(1, state.selects, "already active: onSelect not called again");
    list = find(app, "tablist");
    chosen = find(app, "tab-20");
    assertTrue(list.scrollX() > 0, "active tab scrolled back into view");
    assertTrue(x(app, chosen) >= x(app, list));
    assertTrue(x(app, chosen) + chosen.width() <= x(app, list) + list.width());

    // Three tabs fit: no #overflow button.
    State few = new State(3);
    App<State> small = new App<>(few, State::view);
    Script.run("size 400 240\nframe", small, out);
    assertNull(find(small, "overflow"));
  }

  // Without handlers

  @Test
  void aBarWithoutASelectHandlerTakesNoFocusAndNoPress() throws IOException {
    App<Object> app = new App<>(
        new Object(),
        s -> col()
            .wFull()
            .hFull()
            .child(tabs(
                    List.of(new Tab("one"), new Tab("two")),
                    0,
                    null,
                    i -> {},
                    null,
                    Tabs.options().onAdd(() -> {}).overflow(new Tabs.Overflow()))
                .grow(1)));
    run(app, "frame\nkey Tab\nframe");
    assertNull(app.focused());
    assertFalse(find(app, "tablist").isFocusable());
    assertNull(find(app, "close-0"));
    assertNull(find(app, "add"));
    assertNull(find(app, "overflow"));
    assertNull(find(app, "tab-1").onPress());
    assertEquals(
        T.text().disabled(), find(app, "tab-1").children().getFirst().text().color());
    assertTrue(dumpLine(app, "tab-0").endsWith(" disabled selected"));
    assertTrue(dumpLine(app, "tab-1").endsWith(" disabled"));
  }

  // Images

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void looksAsStored(String name) throws IOException {
    Workbench w = Workbench.many(name.equals("dark") ? Theme.DARK : Theme.LIGHT);
    assertEquals(1, Workbench.assertLooksAsStored("tabs", name, w.app(Workbench::tabsView)));
  }

  /**
   * The bar with the keyboard's focus at scales between whole pixels too: its ring follows the
   * card's rounded top corners inside the card's clip.
   */
  @ParameterizedTest(name = "{0} at {1}x")
  @org.junit.jupiter.params.provider.CsvSource({"dark, 1.5"})
  void theFocusedBarsRingFollowsTheCard(String name, String scale) throws IOException {
    Workbench w = Workbench.many(name.equals("dark") ? Theme.DARK : Theme.LIGHT);
    App<Workbench> app = w.app(Workbench::tabsView);
    String image = "tabs-focused-" + name + "-" + scale.replace(".", "_") + "x";
    Script.run("size 400 160 scale " + scale + "\nframe\nkey Tab\nframe\nsnap " + image, app, out);
    Golden.assertMatches(
        image,
        com.rethinkj.ui.render.Png.decode(
            java.nio.file.Files.readAllBytes(out.resolve(image + ".png"))),
        java.nio.file.Files.readString(out.resolve(image + ".tree.txt")));
  }
}
