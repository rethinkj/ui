package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Tabs.tabs;
import static com.rethinkj.ui.kit.Workbench.dumpLine;
import static com.rethinkj.ui.kit.Workbench.find;
import static com.rethinkj.ui.kit.Workbench.x;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

  @TempDir Path out;

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
    App<Object> app = new App<>(new Object(), s -> col().wFull().hFull().child(
        tabs(T, List.of(new Tab("rows")), 0, i -> {}, null,
            col().key("list").rows(1000, 20, i -> El.el())).key("tabs").grow(1)));
    app.resize(300, 200, 1);
    app.frame();
    assertEquals(200, find(app, "tabs").height());
    assertEquals(200, find(app, "panel").y() + find(app, "list").height(),
        "the content fills the panel below the bar, inside the window");
    app.wheel(20, 150, 0, 100);
    app.frame();
    assertEquals(100, find(app, "list").scrollY());
  }

  @Test
  void aPressChoosesATabAndFocusesTheBarWithoutARing() throws IOException {
    App<Workbench> app = start();
    Workbench w = app.state();
    assertEquals("customers.sql", find(app, "panel").label());
    run(app, "press #tab-3\nframe");
    assertEquals(List.of("select 3"), w.log);
    assertEquals("products.sql", find(app, "panel").label());
    assertEquals(find(app, "tablist"), app.focused());
    assertTrue(app.tree().dump().contains("text \"products.sql\"")); // in the content too
    assertEquals(T.surface().raised(), find(app, "tab-3").fill(), "the active tab is raised");
    assertEquals(0, find(app, "tab-0").fill(), "the others show the bar");
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
    assertEquals(0, find(app, "close-1").opacity());
    assertNull(find(app, "close-2"), "Console cannot be closed");

    run(app, "move #tab-1\nframe");
    assertEquals(1, find(app, "close-1").opacity(), "under the pointer");
    assertEquals(T.surface().hover(), find(app, "tab-1").fill());
    run(app, "move #close-1\nframe");
    assertEquals(T.surface().hover(), find(app, "close-1").fill());
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
  void aModifiedTabHasADotBeforeItsTitle() throws IOException {
    App<Workbench> app = start();
    List<El> children = find(app, "tab-1").children();
    assertEquals("●", children.get(0).text().string());
    assertEquals("orders by month.sql", children.get(1).text().string());
    assertEquals("customers.sql", find(app, "tab-0").children().getFirst().text().string());
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
        List.of("select 1", "select 0", "select 7", "select 0", "select 7"), w.log,
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
    assertEquals(List.of("select 7", "select 6", "select 7"), w.log);

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
  void eachInputOfAFrameGoesToTheTabsTheOneBeforeLeft() throws IOException {
    App<Workbench> app = start();
    run(app, "key Tab\nkey W control\nkey W control\npress #close-0\nframe");
    // The second Control+W closes the tab the first made active, and the press lands where the
    // first tab's cross was, now on the second tab of the view the closes left, whose cross is
    // hidden: it chooses that tab.
    assertEquals(List.of("close 0", "close 0", "select 1"), app.state().log);
    assertEquals(1, app.state().active);
    assertEquals(app.state().queries.get(1).title().replace("*", ""),
        find(app, "panel").label());
  }

  // An overfull bar

  @Test
  void anOverfullBarScrollsUnderTheWheelAndShowsTheActiveTab() throws IOException {
    App<Workbench> app = start();
    El list = find(app, "tablist");
    assertEquals(396, list.width(), "the bar less its padding");
    run(app, "wheel #tablist 0 100\nframe");
    assertEquals(100, find(app, "tablist").scrollX(), "a mouse's wheel moves it sideways");
    run(app, "wheel #tablist 0 10000\nframe");
    double end = find(app, "tablist").scrollX();
    El last = find(app, "tab-7");
    assertEquals(398, x(app, last) + last.width(), "the last tab at the end");

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

  // Without handlers

  @Test
  void aBarWithoutASelectHandlerTakesNoFocusAndNoPress() throws IOException {
    App<Object> app = new App<>(new Object(), s -> col().wFull().hFull().child(
        tabs(T, List.of(new Tab("one"), new Tab("two")), 0, null, i -> {}, null).grow(1)));
    run(app, "frame\nkey Tab\nframe");
    assertNull(app.focused());
    assertFalse(find(app, "tablist").isFocusable());
    assertNull(find(app, "close-0"));
    assertNull(find(app, "tab-1").onPress());
    assertEquals(T.text().disabled(), find(app, "tab-1").children().getFirst().text().color());
    assertTrue(dumpLine(app, "tab-0").endsWith(" disabled selected"));
    assertTrue(dumpLine(app, "tab-1").endsWith(" disabled"));
  }

  @Test
  void withoutAnActiveTabTheFirstKeyChoosesAnEnd() throws IOException {
    int[] chosen = {-1};
    App<int[]> app = new App<>(chosen, c -> col().wFull().hFull().child(
        tabs(T, List.of(new Tab("one"), new Tab("two"), new Tab("three")), c[0],
            i -> c[0] = i, null, null).grow(1)));
    run(app, "frame\nkey Tab\nkey Tab control shift\nframe");
    assertEquals(2, chosen[0]);
    assertNull(find(app, "close-2"), "no close handler: no crosses");
  }

  // Images

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void looksAsStored(String name) throws IOException {
    Workbench w = Workbench.many(name.equals("dark") ? Theme.DARK : Theme.LIGHT);
    assertEquals(5, Workbench.assertLooksAsStored("tabs", name, w.app(Workbench::tabsView)));
  }
}
