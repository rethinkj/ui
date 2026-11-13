package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A navigation list with headings, and the same list as a virtual list of many items. */
class NavListTest {

  private static final Theme T = Theme.DARK;
  private static final List<NavList.Item> PAGES = List.of(
      NavList.heading("Workbench"),
      NavList.item(Symbol.SETTINGS, "General"),
      NavList.item(Symbol.DATABASE, "Connections").count("3"),
      NavList.heading("Help"),
      NavList.item(Symbol.INFO, "About"));

  int selected = 1;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private App<NavListTest> app(boolean virtual) {
    App<NavListTest> app = new App<>(this, s -> col().wFull().hFull().child(virtual
        ? NavList.navList(T, 100_000,
            i -> i % 10 == 0 ? NavList.heading("Group " + i / 10)
                : NavList.item("Item " + i), selected, i -> selected = i).key("list").grow(1)
        : NavList.navList(T, PAGES, selected, i -> selected = i).key("list")));
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  private static void run(App<?> app, String steps) throws IOException {
    Script.run("size 200 300\nframe\n" + steps + "\nframe", app, Path.of("target/nav-test"));
  }

  @Test
  void theKeysMovePastTheHeadingsAndStopAtTheEnds() throws IOException {
    App<NavListTest> app = app(false);
    run(app, "focus #list\nkey ArrowDown\nframe\nkey ArrowDown");
    assertEquals(4, selected, "past the heading Help");
    run(app, "key ArrowDown");
    assertEquals(4, selected, "the last stays");
    run(app, "key Home");
    assertEquals(1, selected, "the first item, not the heading");
    run(app, "key ArrowUp\nframe\nkey End");
    assertEquals(4, selected);
    run(app, "press role=option label=\"Connections\"");
    assertEquals(2, selected);
    String dump = app.tree().dump();
    assertTrue(dump.contains("el #list role=listbox "), dump);
    assertTrue(StructureTest.line(dump, "label=\"Connections\"").contains(" selected pos=3/5"),
        dump);
    assertTrue(dump.contains("text \"3\""), "the count");
  }

  @Test
  void aVirtualListBuildsOnlyTheItemsInViewAndFollowsTheSelection() throws IOException {
    App<NavListTest> app = app(true);
    run(app, "focus #list\nkey End");
    assertEquals(99_999, selected);
    run(app, "key PageUp\nframe\nkey ArrowDown");
    assertTrue(selected < 99_999 && selected % 10 != 0, "a page up, past headings: " + selected);
    String dump = app.tree().dump();
    assertTrue(StructureTest.line(dump, "#list").contains("/100000"), dump);
    assertTrue(dump.lines().filter(l -> l.contains("role=option")).count() < 30, dump);
    assertTrue(dump.contains("label=\"Item " + selected + "\""), "in view: " + dump);
  }
}
