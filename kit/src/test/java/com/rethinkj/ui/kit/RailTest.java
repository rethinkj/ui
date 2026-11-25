package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
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

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private App<RailTest> app(Side side) {
    App<RailTest> app = new App<>(this, state -> {
      Theme theme = Theme.CURRENT.get();
      var strip = Rail.rail(
              state.rail,
              "Tool windows",
              side,
              List.of(
                  Rail.item(Symbol.FOLDER, "Explorer").shortcut(Key.CONTROL, "1"),
                  Rail.item(Symbol.SEARCH, "Search").shortcut(Key.CONTROL, "2"),
                  Rail.separator(),
                  Rail.item(Symbol.WARNING, "Problems").dot(Badge.Kind.DANGER, "3 errors")),
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
    Script.run("press role=button label=\"Explorer\"\nframe\n", app, out);
    assertEquals(-1, open);
  }

  @Test
  void tooltipsOpenTowardTheInsideAndShortRailsScroll() throws IOException {
    open = 0;
    App<RailTest> left = app(Side.LEFT);
    Script.run("""
        size 240 240
        frame
        move role=button label="Explorer"
        frame
        advance 1
        frame
        """, left, out);
    String leftDump = left.tree().dump();
    assertTrue(
        leftDump.contains("overlay right center gap 4 on #item 0 role=button label=\"Explorer\""),
        leftDump);
    assertTrue(leftDump.contains("label=\"Ctrl+1\""), leftDump);

    // Dot text appears in the tooltip too.
    Script.run("move role=button label=\"Problems\"\nframe\n", left, out);
    String dotDump = left.tree().dump();
    assertTrue(dotDump.contains("text \"3 errors\""), dotDump);

    App<RailTest> right = app(Side.RIGHT);
    Script.run("""
        size 240 240
        frame
        move role=button label="Search"
        frame
        advance 1
        frame
        """, right, out);
    String rightDump = right.tree().dump();
    assertTrue(
        rightDump.contains("overlay left center gap 4 on #item 1 role=button label=\"Search\""),
        rightDump);

    // Short height: the rail scrolls so bottom items remain reachable.
    Script.run("size 160 110\nframe\nwheel #rail 0 40\nframe\n", left, out);
    assertTrue(
        StructureTest.line(left.tree().dump(), "#rail").contains(" scroll 0,"),
        left.tree().dump());

    // A rail goes on the left or the right.
    Rail.State state = new Rail.State();
    assertThrows(
        IllegalArgumentException.class,
        () -> Rail.rail(state, "Rail", Side.TOP, List.of(), List.of(), -1, i -> {}));
  }
}
