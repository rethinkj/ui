package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.render.Png;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The small parts ({@link Parts}): badges, a breadcrumb, a toolbar with a split and a toggle
 * button, properties and a popover's form, by scripts and the dump; stored images.
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
  @CsvSource({"dark, 2", "light, 2"})
  void looksAsStored(String theme, int scale) throws IOException {
    check("badges", theme, scale, 560, 400, Parts::badges, "");
    check("detail", theme, scale, 720, 420, Parts::detail, "");
    if (theme.equals("dark")) { // narrow: the toolbar's end and the breadcrumb's start in menus
      check("detail-narrow", theme, scale, 330, 420, Parts::detail, "");
    }
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
    more(app, out, 160, "frame\nframe");
    String smallest = app.tree().dump();
    assertTrue(smallest.contains("role=button label=\"More\""), smallest);
    assertFalse(smallest.contains("role=button label=\"Refresh\""), smallest);
    more(
        app,
        out,
        160,
        "press role=button label=\"More\"\nframe\nadvance 0.25\nframe\n"
            + "press role=menuitem label=\"Refresh\"\nframe");
    assertEquals("refresh", app.state().log.getLast(), "even the first item can move to More");
    more(app, out, 720, "frame\nframe");
    assertTrue(app.tree().dump().contains("role=button label=\"Wrap values\" "), "back");
    assertTrue(
        StructureTest.line(app.tree().dump(), "label=\"Wrap values\"").contains(" checked"),
        app.tree().dump());
    assertTrue(app.tree().dump().contains("role=link label=\"localhost\""), "the path expands too");
    assertFalse(app.tree().dump().contains("label=\"Hidden parts\""), app.tree().dump());
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
    assertTrue(
        StructureTest.line(shortened, "label=\"orders_by_customer_and_month\"")
            .contains(" current"),
        "the current part stays even when it is wider than the breadcrumb: " + shortened);
    more(
        narrow,
        out,
        200,
        "press label=\"Hidden parts\"\nframe\nadvance 0.25\nframe\n"
            + "press role=menuitem label=\"localhost\"\nframe");
    assertEquals("crumb localhost", narrow.state().log.getLast());
  }

  @Test
  void aBreadcrumbKeepsTheSuffixThatFitsBesideMore() throws IOException {
    Theme t = Theme.DARK;
    Breadcrumb.State state = new Breadcrumb.State();
    float[] width = {400};
    App<Breadcrumb.State> app = new App<>(
        state,
        s -> col()
            .w(width[0])
            .child(Breadcrumb.breadcrumb(t, s, List.of("first", "middle", "current"), i -> {})));
    Script.run("size 400 100\nframe\nframe", app, out);
    width[0] = 1;
    app.invalidate();
    Script.run("frame\nframe\nframe", app, out);
    width[0] = (float) Math.ceil(state.fitting.width("more")
        + state.fitting.width("1:middle")
        + state.fitting.width("2:current")
        + 2 * t.space().xs());
    app.invalidate();
    Script.run("frame\nframe", app, out);
    String dump = app.tree().dump();
    assertTrue(dump.contains("label=\"middle\""), dump);
    assertFalse(dump.contains("label=\"first\""), dump);
    width[0] -= t.space().xs();
    app.invalidate();
    Script.run("frame\nframe", app, out);
    assertFalse(app.tree().dump().contains("label=\"middle\""), app.tree().dump());
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

  @Test
  void aToolbarThatChangesLeavesTheFocusWhereTabTookIt() throws IOException {
    final class Tools {
      final Toolbar.State toolbar = new Toolbar.State();
      boolean first = true;

      El view() {
        List<Toolbar.Item> items = new ArrayList<>();
        if (first) {
          items.add(Toolbar.text(null, "A", () -> {}));
        }
        items.add(Toolbar.text(null, "B", () -> {}));
        return col()
            .wFull()
            .hFull()
            .children(
                Toolbar.toolbar(Theme.DARK, toolbar, "Tools", items),
                TextField.textField(Theme.DARK, "", "Editor", v -> {}).key("editor"))
            .onKey(k -> {
              if (!k.name().equals("F2")) {
                return false;
              }
              first = false;
              return true;
            });
      }
    }
    App<Tools> app = new App<>(new Tools(), Tools::view);
    Script.run("size 400 200\nframe\nframe\nkey Tab\nkey ArrowRight\nkey Tab\nframe\n", app, out);
    assertEquals("editor", app.focused().key());
    Script.run("key F2\nframe\nframe\n", app, out);
    assertEquals("editor", app.focused().key(), "the toolbar's change moved nothing");
    Script.run("key Tab shift\nkey End\nframe\n", app, out);
    assertEquals("B", app.focused().label(), "the keys still move in the toolbar");
  }

  @Test
  void escapeClosesAPopoverWithNothingToFocusInItAndGivesTheFocusBack() throws IOException {
    Popover.State state = new Popover.State();
    App<Popover.State> app = new App<>(
        state,
        s -> col()
            .wFull()
            .hFull()
            .child(Popover.popover(
                Theme.DARK,
                Button.button(Theme.DARK, Button.Variant.SECONDARY, "Details", s::toggle)
                    .key("details"),
                s,
                "Details",
                Label.label(Theme.DARK, "Read-only information"))));
    Script.run("size 400 200\nframe\nfocus #details\nkey Enter\nframe\n", app, out);
    assertEquals("popover", app.focused().key(), "the popover itself took the focus");
    Script.run("key Escape\nframe\nadvance 1\nframe\n", app, out);
    assertFalse(state.isOpen());
    assertEquals("details", app.focused().key());
  }
}
