package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.render.Png;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The small parts ({@link Parts}): badges, a breadcrumb, a toolbar with a split and a toggle
 * button, properties and a popover's form, by scripts and the dump; stored images in both themes
 * at scales 1 and 2.
 */
class PartsTest {

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private static Theme theme(String name) {
    return name.equals("dark") ? Theme.DARK : Theme.LIGHT;
  }

  /** Runs the steps in a window of this size and compares the snap {@code parts-<name>-…}. */
  private String check(
      String name,
      String theme,
      int scale,
      int width,
      int height,
      Function<Parts, El> view,
      String steps)
      throws IOException {
    App<Parts> app = new Parts(theme(theme)).app(view);
    String image = "parts-" + name + "-" + theme + "-" + scale + "x";
    Script.run(
        "size " + width + " " + height + " scale " + scale + "\nframe\nframe\n" + steps
            + "\nadvance 1\nframe\nsnap " + image,
        app,
        out);
    String tree = Files.readString(out.resolve(image + ".tree.txt"));
    Golden.assertMatches(image, Png.decode(Files.readAllBytes(out.resolve(image + ".png"))), tree);
    return tree;
  }

  @ParameterizedTest(name = "{0} at {1}x")
  @CsvSource({"dark, 1", "dark, 2", "light, 1", "light, 2"})
  void looksAsStored(String theme, int scale) throws IOException {
    check("badges", theme, scale, 560, 400, Parts::badges, "");
    check("detail", theme, scale, 720, 420, Parts::detail, "");
    // Narrow: the toolbar's end and the breadcrumb's start go into menus.
    check("detail-narrow", theme, scale, 330, 420, Parts::detail, "");
    check(
        "popover",
        theme,
        scale,
        720,
        420,
        Parts::detail,
        "press #filter\nframe\nadvance 0.25\nframe");
  }

  private App<Parts> run(int width, String steps) throws IOException {
    App<Parts> app = new Parts(Theme.DARK).app(Parts::detail);
    Script.run(
        "size " + width + " 420\nframe\nframe\nadvance 1\nframe\n" + steps
            + "\nframe\nadvance 1\nframe",
        app,
        out);
    return app;
  }

  private static App<Parts> more(App<Parts> app, Path out, int width, String steps)
      throws IOException {
    Script.run("size " + width + " 420\n" + steps + "\nframe\nadvance 1\nframe", app, out);
    return app;
  }

  @Test
  void aNarrowToolbarMovesItsEndIntoAMenu() throws IOException {
    App<Parts> app = run(720, "");
    String wide = app.tree().dump();
    assertTrue(wide.contains("label=\"Export\""), wide);
    assertTrue(!wide.contains("label=\"More\""), "everything fits: " + wide);
    more(app, out, 330, "frame\nframe");
    String narrow = app.tree().dump();
    assertTrue(narrow.contains("role=button label=\"More\""), narrow);
    assertTrue(!narrow.contains("role=button label=\"Export\""), narrow);
    more(
        app,
        out,
        330,
        "press role=button label=\"More\"\nframe\nadvance 0.25\nframe\n"
            + "press role=menuitem label=\"Export\"\nframe");
    assertEquals("export", app.state().log.getLast(), "the menu runs the same action");
    more(
        app,
        out,
        240,
        "frame\nframe\nframe\nadvance 1\nframe\npress role=button label=\"More\"\nframe\n"
            + "advance 0.25\nframe\npress role=menuitem label=\"Wrap values\"\nframe");
    assertTrue(app.state().wrap, "a toggle as a checked item");
    more(app, out, 720, "frame\nframe");
    assertTrue(app.tree().dump().contains("role=button label=\"Wrap values\" "), "back");
    assertTrue(
        StructureTest.line(app.tree().dump(), "label=\"Wrap values\"").contains(" checked"),
        app.tree().dump());
  }

  @Test
  void theToolbarIsOneStopAndTheArrowsGoThroughIt() throws IOException {
    App<Parts> app = run(720, "focus role=link label=\"localhost\"\nkey Tab\nframe");
    assertEquals("Refresh", app.focused().label(), "Tab reaches the first item");
    more(app, out, 720, "key ArrowRight\nframe");
    assertEquals("Run", app.focused().label());
    more(app, out, 720, "key ArrowRight\nkey ArrowRight\nframe");
    assertEquals("Wrap values", app.focused().label(), "past the split's chevron");
    more(app, out, 720, "key Space\nframe");
    assertTrue(app.state().wrap, "Space presses it");
    more(app, out, 720, "key End\nframe");
    assertEquals("Export", app.focused().label());
    more(app, out, 720, "key ArrowRight\nframe");
    assertEquals("Refresh", app.focused().label(), "around the end");
    more(app, out, 720, "key Tab\nframe");
    assertEquals("filter", app.focused().key(), "Tab leaves the toolbar");
    more(app, out, 720, "key Tab shift\nframe");
    assertEquals("Refresh", app.focused().label(), "and comes back where it was");
  }

  @Test
  void theSplitButtonRunsAndOpensItsMenu() throws IOException {
    App<Parts> app = run(720, "press #main\nframe");
    assertEquals("run", app.state().log.getLast());
    more(
        app,
        out,
        720,
        "press label=\"More Run\"\nframe\nadvance 0.25\nframe\n"
            + "press role=menuitem label=\"Explain\"\nframe");
    assertEquals("explain", app.state().log.getLast());
  }

  /** In a column with room to spare, a path and a toolbar stay a row high, as they look. */
  @Test
  void aBreadcrumbAndAToolbarStayARowHighInAColumnWithRoom() throws IOException {
    Theme t = Theme.DARK;
    Breadcrumb.State crumbs = new Breadcrumb.State();
    Toolbar.State tools = new Toolbar.State();
    App<Object> app = new App<>(
        new Object(),
        x -> El.col()
            .h(300)
            .children(
                Breadcrumb.breadcrumb(t, crumbs, java.util.List.of("localhost", "shop"), i -> {}),
                Toolbar.toolbar(
                    t,
                    tools,
                    "Table",
                    java.util.List.of(
                        Toolbar.button(Symbol.ARROW_CLOCKWISE, "Refresh", () -> {})))));
    Script.run("size 400 320\nframe\nframe", app, out);
    String dump = app.tree().dump();
    assertEquals(
        t.size().control(),
        StructureTest.rect(StructureTest.line(dump, "role=navigation"))[3],
        dump);
    assertEquals(
        t.size().control(), StructureTest.rect(StructureTest.line(dump, "role=toolbar"))[3], dump);
  }

  @Test
  void theBreadcrumbGoesUpAndShortensAtItsStart() throws IOException {
    App<Parts> app = run(720, "");
    String dump = app.tree().dump();
    assertTrue(dump.contains("role=navigation label=\"Breadcrumb\""), dump);
    assertTrue(
        StructureTest.line(dump, "label=\"orders_by_customer_and_month\"").contains("role=link")
            && StructureTest.line(dump, "label=\"orders_by_customer_and_month\"")
                .contains(" current"),
        dump);
    more(app, out, 720, "focus role=link label=\"localhost\"\nframe");
    more(app, out, 720, "key Tab shift\nkey Tab\nframe");
    assertEquals("localhost", app.focused().label(), "one stop, at the first part");
    more(app, out, 720, "key ArrowRight\nframe");
    assertEquals("shop", app.focused().label());
    more(app, out, 720, "key Enter\nframe\nframe");
    assertEquals("crumb shop", app.state().log.getLast());
    assertEquals(2, app.state().path.size());

    App<Parts> narrow = run(200, "frame");
    String shortened = narrow.tree().dump();
    assertTrue(shortened.contains("role=button label=\"Hidden parts\""), shortened);
    assertTrue(!shortened.contains("label=\"localhost\""), shortened);
    more(
        narrow,
        out,
        200,
        "press label=\"Hidden parts\"\nframe\nadvance 0.25\nframe\n"
            + "press role=menuitem label=\"localhost\"\nframe");
    assertEquals("crumb localhost", narrow.state().log.getLast());
  }

  @Test
  void propertiesSayTheirValuesAndCopyThem() throws IOException {
    App<Parts> app = run(720, "move label=\"Rows\"\nframe");
    String dump = app.tree().dump();
    more(app, out, 720, "press label=\"Copy Rows\"\nframe");
    assertTrue(
        StructureTest.line(dump, "role=listitem label=\"Rows\"").contains("value=\"1,000,000\""),
        dump);
    assertEquals("1,000,000", app.state().clipboard.read());
    String copied = app.tree().dump();
    assertTrue(copied.contains("role=tooltip label=\"Copied\""), copied);
    assertTrue(copied.contains("text label=\"checkmark\""), "a check for a moment");
    more(app, out, 720, "advance 2\nframe\nadvance 1\nframe");
    assertTrue(!app.tree().dump().contains("label=\"checkmark\""), "the copy icon back");
    more(app, out, 720, "press label=\"Owner\"\nframe");
    assertTrue(
        app.focused() == null
            || app.focused().isEditable() == false
            || "shop_admin".equals(app.focused().text().string()),
        "a press selects in the value");
  }

  @Test
  void thePopoverTakesTheFocusAndGivesItBack() throws IOException {
    App<Parts> app = run(720, "focus #filter\nkey Enter\nframe\nadvance 0.25\nframe");
    String dump = app.tree().dump();
    assertTrue(dump.contains("role=dialog label=\"Filter rows\""), dump);
    assertEquals("column", app.focused().key(), "the first field has the focus");
    more(app, out, 720, "key Escape\nframe\nadvance 0.25\nframe");
    assertTrue(!app.state().filter.isOpen());
    assertEquals("filter", app.focused().key(), "back on the button");
    more(app, out, 720, "press #filter\nframe\nadvance 0.25\nframe\npress #apply\nframe");
    assertEquals("apply status", app.state().log.getLast());
    more(app, out, 720, "press #filter\nframe\nadvance 0.25\nframe\npress 700,400\nframe");
    assertTrue(!app.state().filter.isOpen(), "a press outside closes it");
  }

  @Test
  void aRemovableBadgeHasACrossAndAPressableOneIsAButton() throws IOException {
    App<Parts> app = new Parts(Theme.DARK).app(Parts::badges);
    Script.run(
        "size 560 360\nframe\npress label=\"Remove status = paid\"\nframe\n" + "press #add\nframe",
        app,
        out);
    assertEquals(java.util.List.of("total > 100"), app.state().filters);
    assertEquals("add", app.state().log.getLast());
    assertEquals(null, app.focused(), "neither takes the focus from a press");
    String dump = app.tree().dump();
    assertTrue(StructureTest.line(dump, "label=\"Add filter\"").contains("role=button"), dump);
    assertTrue(!StructureTest.line(dump, "label=\"bigint\"").contains("role="), dump);
  }

  @Test
  void anAvatarHasTheInitialsAndTheColourOfItsName() throws IOException {
    assertEquals("PE", Avatar.initials("prod-eu"));
    assertEquals("L", Avatar.initials("localhost"));
    assertEquals("R2", Avatar.initials("replica 2"));
    assertEquals("", Avatar.initials("--"));
    var palette = Theme.DARK.badge().avatars();
    assertEquals(Avatar.color(palette, "staging"), Avatar.color(palette, "staging"));
    App<Parts> app = new Parts(Theme.DARK).app(Parts::badges);
    Script.run("size 560 400\nframe", app, out);
    String dump = app.tree().dump();
    assertTrue(StructureTest.line(dump, "label=\"staging\"").contains("role=img"), dump);
    assertTrue(dump.contains("text \"PE\""), dump);
  }
}
