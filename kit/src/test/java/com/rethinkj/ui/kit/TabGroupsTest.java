package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.TabGroups.Group;
import com.rethinkj.ui.kit.Tabs.Tab;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.Windows;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tabs in several windows ({@link TabGroups}), driven by scripts: moving along the bar with the
 * pointer and the keys, Escape, tearing off into a window that follows the pointer, docking into
 * the bar of the main window and of another window at a place, the only tab of a window, closing a
 * window, the menu, and tabs without the handlers.
 *
 * <p>The main window is 400 by 160 at 100,100 on the script's screen and shows the tabs alone,
 * a, b, c and d; a window torn off is 300 by 160 and shows its group the same way. In both the
 * bar's tabs are 32 high from y 1, a from x 3, b from 48, c from 94 and d from 139; the scripts
 * press them 5 pixels inside their left edge, since a tab's centre is on its cross.
 */
class TabGroupsTest {

  private static final Theme T = Theme.DARK;

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  /** The state of the test: the groups of the tabs a to d, and the windows' apps. */
  static final class Docs {
    App<Docs> app;
    final TabGroups<String> groups = new TabGroups<>(List.of("a", "b", "c", "d"), this::open);
    final List<String> closed = new ArrayList<>();

    Docs() {
      groups.onClosed(closed::add);
    }

    Windows.Handle open(Group<String> group, Window.Options options) {
      return app.windows()
          .open(group.activeTab(), 300, 160, options, new App<>(this, s -> s.view(group)));
    }

    El view(Group<String> group) {
      return col()
          .wFull()
          .hFull()
          .child(groups
              .tabs(
                  T, group, Tab::new, s -> s == null ? null : Label.label(T, s).key("text"))
              .grow(1));
    }
  }

  private App<Docs> start() {
    Docs docs = new Docs();
    docs.app = new App<>(docs, s -> s.view(s.groups.main()));
    return docs.app;
  }

  /** Runs the steps in the main window, 400 by 160 at 100,100; the snap {@code s} of the end. */
  private void run(App<Docs> app, String steps) throws IOException {
    Script.run("size 400 160 at 100,100\nframe\n" + steps + "\nframe\nsnap s\n", app, out);
  }

  private String tree(String snap) throws IOException {
    return Files.readString(out.resolve(snap + ".tree.txt"));
  }

  private static String line(String tree, String part) {
    return tree.lines().filter(l -> l.contains(part)).findFirst().orElse("").strip();
  }

  private static List<List<String>> order(Docs d) {
    return d.groups.groups().stream().map(g -> List.copyOf(g.tabs())).toList();
  }

  // Moving along the bar

  @Test
  void aTabDraggedAlongTheBarMovesWhereTheMarkerShows() throws IOException {
    App<Docs> app = start();
    run(app, """
        down 8,17        # on a
        move 100,17      # beyond the middle of b: before c
        frame
        snap moving
        up 100,17""");
    String moving = tree("moving");
    assertTrue(line(moving, "#tab-0").endsWith("selected grabbed hovered"), moving);
    assertTrue(line(moving, "#marker").startsWith("el #marker 92,"), "between b and c: " + moving);
    Docs d = app.state();
    assertEquals(List.of(List.of("b", "a", "c", "d")), order(d));
    assertEquals(1, d.groups.main().active(), "a stays active");
    assertFalse(tree("s").contains("#marker") || tree("s").contains("grabbed"));
  }

  @Test
  void controlShiftPageDownAndUpMoveTheActiveTab() throws IOException {
    App<Docs> app = start();
    run(app, """
        focus #tablist
        key PageDown control shift
        key PageDown control shift
        key PageUp control shift""");
    assertEquals(List.of(List.of("b", "a", "c", "d")), order(app.state()));
    run(app, """
        key End
        key PageDown control shift  # the last stays last""");
    assertEquals(List.of(List.of("b", "a", "c", "d")), order(app.state()));
    assertEquals(3, app.state().groups.main().active());
  }

  @Test
  void escapeGivesUpTheDragAndTheReleasePressesNothing() throws IOException {
    App<Docs> app = start();
    run(app, """
        down 8,17
        move 140,17
        frame
        key Escape
        frame
        snap given-up
        up 140,17""");
    assertEquals(List.of(List.of("a", "b", "c", "d")), order(app.state()));
    assertEquals(0, app.state().groups.main().active(), "the release on d chose nothing");
    String tree = tree("given-up");
    assertFalse(tree.contains("#marker") || tree.contains("grabbed"), tree);
  }

  @Test
  void nearTheEndOfAFullBarTheDragScrollsIt() throws IOException {
    App<Docs> app = start();
    Script.run("""
        size 120 160
        frame
        down 8,17
        move 60,17
        move 110,17
        move 111,17
        move 112,17
        frame
        snap s""", app, out);
    assertTrue(line(tree("s"), "#tablist").contains(" scroll 24,0"), "three steps: " + tree("s"));
  }

  @Test
  void aTabMovedByTheKeysWhileItIsDraggedStaysTheOneDragged() throws IOException {
    App<Docs> app = start();
    Script.run("""
        size 400 160 at 100,100
        frame
        focus #tablist
        down 8,17
        move 100,17
        frame
        key PageDown control shift
        frame
        snap moved
        up 210,17
        frame""", app, out);
    assertTrue(line(tree("moved"), "#tab-1 ").contains(" grabbed"), tree("moved"));
    assertFalse(line(tree("moved"), "#tab-0 ").contains(" grabbed"), tree("moved"));
    assertEquals(List.of(List.of("b", "c", "d", "a")), order(app.state()));
  }

  // Tearing off and docking

  /**
   * b dragged out of the bar, below it, to the screen at 152,220, where its window opens at 145,203:
   * held 4,16 from the top left of its tab, which lies at 3,1 in that window.
   */
  private static final String TEAR_B = """
      down 52,17
      move 52,40
      move 52,120
      frame
      """;

  @Test
  void aTabDraggedOutOfTheBarOpensInAWindowThatFollowsAndStays() throws IOException {
    App<Docs> app = start();
    Docs d = app.state();
    run(app, TEAR_B + """
        snap torn
        move screen 400,400
        frame
        up screen 400,400""");
    assertEquals(List.of(List.of("a", "c", "d"), List.of("b")), order(d));
    Windows.Handle b = d.groups.groups().get(1).window();
    assertEquals("b", b.title());
    assertEquals(400 - 7, b.x());
    assertEquals(400 - 17, b.y());
    assertTrue(line(tree("b-torn"), "#tab-0").contains("selected grabbed"), tree("b-torn"));
    assertFalse(tree("b-s").contains("grabbed"));
  }

  @Test
  void aTornTabDocksInTheMainBarWhereTheMarkerShows() throws IOException {
    App<Docs> app = start();
    run(app, TEAR_B + """
        move screen 200,117   # the main bar between c and d
        frame
        snap docking
        up screen 200,117""");
    assertTrue(line(tree("docking"), "#marker").startsWith("el #marker 91,"), tree("docking"));
    Docs d = app.state();
    assertEquals(List.of(List.of("a", "c", "b", "d")), order(d));
    assertEquals(2, d.groups.main().active(), "the docked tab is active");
    assertFalse(Files.exists(out.resolve("b-s.png")), "its window closed");
  }

  @Test
  void aTornTabDocksInTheBarOfAnotherWindow() throws IOException {
    App<Docs> app = start();
    run(app, TEAR_B + """
        up screen 152,220      # b's window at 145,203
        frame
        down 53,17             # c out of the bar, over b's bar after b
        move 53,40
        move 53,120
        frame
        move screen 185,220
        frame
        snap docking
        up screen 205,220""");
    assertTrue(line(tree("b-docking"), "#marker").startsWith("el #marker 45,"), tree("b-docking"));
    assertEquals(List.of(List.of("a", "d"), List.of("b", "c")), order(app.state()));
  }

  @Test
  void escapeAfterATearOffPutsTheTabBack() throws IOException {
    App<Docs> app = start();
    run(app, TEAR_B + "key Escape");
    assertEquals(List.of(List.of("a", "b", "c", "d")), order(app.state()));
  }

  @Test
  void theOnlyTabOfAWindowMovesItsWindowAndDocksWithIt() throws IOException {
    App<Docs> app = start();
    Docs d = app.state();
    run(app, TEAR_B + """
        move screen 400,400     # b's window at 393,383
        up screen 400,400
        frame
        window b
        down 8,17
        move 40,17              # the window moves with the tab
        frame
        move screen 450,450
        frame""");
    Windows.Handle b = d.groups.groups().get(1).window();
    assertEquals(List.of(450 - 8, 450 - 17), List.of(b.x(), b.y()));
    assertEquals(2, d.groups.groups().size(), "no other window");
    run(app, """
        window b
        move screen 150,117     # the main bar between a and c
        frame
        up screen 150,117
        window main""");
    assertEquals(List.of(List.of("a", "b", "c", "d")), order(d));
    assertFalse(b.isOpen());
  }

  /**
   * A drag whose first step is already over another window, as a flick of the pointer or a
   * script makes it, still holds the window where the pointer took its tab.
   */
  @Test
  void theOnlyTabsWindowFollowsAlsoFromAFirstStepOverAnotherWindow() throws IOException {
    App<Docs> app = start();
    Docs d = app.state();
    run(app, TEAR_B + """
        move screen 400,400     # b's window at 393,383
        up screen 400,400
        frame
        window b
        down 8,17
        move screen 300,150     # at once over the main window
        frame""");
    Windows.Handle b = d.groups.groups().get(1).window();
    assertEquals(List.of(300 - 8, 150 - 17), List.of(b.x(), b.y()));
  }

  @Test
  void escapeWhileTheOnlyTabMovesItsWindowPutsTheWindowBack() throws IOException {
    App<Docs> app = start();
    run(app, TEAR_B + """
        move screen 400,400
        up screen 400,400
        frame
        window b
        down 8,17
        move 40,17
        move screen 500,500
        frame
        key Escape""");
    Windows.Handle b = app.state().groups.groups().get(1).window();
    assertEquals(List.of(393, 383), List.of(b.x(), b.y()));
  }

  @Test
  void cancellingATearOffPutsBackTheTornTabAfterTheAppDockedAnotherBeforeIt() throws IOException {
    Docs d = new Docs();
    d.app = new App<>(
        d,
        s -> s.view(s.groups.main()).onKey(k -> {
          if (!k.name().equals("F2")) {
            return false;
          }
          s.groups.dock(s.groups.main(), 0, s.groups.groups().get(1), 0);
          return true;
        }));
    Script.run("""
        size 400 160 at 100,100
        frame
        down 8,17
        move screen 550,260
        frame
        key F2
        frame
        key Escape
        frame""", d.app, out);
    assertEquals(List.of(List.of("a", "c", "d"), List.of("b")), order(d));
  }

  @Test
  void anEmptyMainGroupsBarTakesATabDroppedOnIt() throws IOException {
    Docs d = new Docs();
    d.app = new App<>(
        d,
        s -> s.view(s.groups.main()).onKey(k -> {
          if (!k.name().equals("F2")) {
            return false;
          }
          s.groups.toNewWindow(s.groups.main(), 3);
          s.groups.closeAll(s.groups.main());
          return true;
        }));
    Script.run("""
        size 400 160 at 100,100
        frame
        key F2
        frame
        snap empty
        window d
        size 300 160 at 600,100
        frame
        down 8,17
        move screen 120,117
        frame
        up screen 120,117
        frame""", d.app, out);
    // As high as with tabs, which are 32 high from y 1, and its hairline.
    assertEquals("el #bar 1,1 398x33", line(tree("empty"), "#bar "), tree("empty"));
    assertEquals(List.of(List.of("d")), order(d));
  }

  // Closing a window

  @Test
  void closingAWindowDocksItsTabsUnlessTheAppClosesThem() throws IOException {
    App<Docs> app = start();
    run(app, TEAR_B + """
        move screen 400,400
        up screen 400,400
        frame
        window b
        close
        frame
        window main""");
    assertEquals(List.of(List.of("a", "c", "d", "b")), order(app.state()));

    App<Docs> closing = start();
    Docs d = closing.state();
    d.groups.onWindowClose(d.groups::closeAll);
    run(closing, TEAR_B + """
        move screen 400,400
        up screen 400,400
        frame
        window b
        close
        frame
        window main""");
    assertEquals(List.of(List.of("a", "c", "d")), order(d));
    assertEquals(List.of("b"), d.closed);
  }

  @Test
  void aHandlerOfClosingATabMayAskFirst() throws IOException {
    App<Docs> app = start();
    Docs d = app.state();
    List<String> asked = new ArrayList<>();
    d.groups.onCloseTab((group, i) -> asked.add(group.tabs().get(i)));
    run(app, "focus #tablist\nkey W control");
    assertEquals(List.of("a"), asked);
    assertEquals(List.of(List.of("a", "b", "c", "d")), order(d), "nothing closed yet");
    d.groups.close(d.groups.main(), 0); // the user agreed
    assertEquals(List.of("a"), d.closed);
  }

  // The menu

  @Test
  void theMenuMovesATabToANewWindowAndBack() throws IOException {
    App<Docs> app = start();
    Docs d = app.state();
    run(app, """
        press #tab-2 right
        frame
        advance 1
        frame
        press label="Move to new window"
        frame""");
    assertEquals(List.of(List.of("a", "b", "d"), List.of("c")), order(d));
    run(app, """
        window c
        press #tab-0 right
        frame
        advance 1
        frame
        snap menu""");
    assertTrue(
        line(tree("c-menu"), "label=\"Move to new window\"").contains("disabled"),
        "a window's only tab is in a window of its own: " + tree("c-menu"));
    run(app, """
        window c
        press label="Move back to main window"
        frame
        window main""");
    assertEquals(List.of(List.of("a", "b", "d", "c")), order(d));
    assertNull(d.groups.groupOf("x"));
  }

  @Test
  void theMenuOfATabActsOnThatTabWhereverTheAppMovesItAndEndsWithIt() throws IOException {
    Docs d = new Docs();
    d.app = new App<>(
        d,
        s -> s.view(s.groups.main()).onKey(k -> {
          Group<String> main = s.groups.main();
          switch (k.name()) {
            case "F2" -> { // another tab before it closes, and another becomes active
              s.groups.close(main, 0);
              s.groups.select(main, 2);
            }
            case "F3" -> s.groups.close(main, main.tabs().indexOf("c"));
            default -> {
              return false;
            }
          }
          return true;
        }));
    Script.run("""
        size 400 160 at 100,100
        frame
        press 53,17 right
        frame
        key F2
        frame
        key Home
        key Enter
        frame""", d.app, out);
    assertEquals(List.of(List.of("c", "d"), List.of("b")), order(d), "b moved, not d");

    Script.run("""
        window main
        press 8,17 right
        frame
        key F3
        frame
        advance 1
        frame
        snap closed""", d.app, out);
    assertFalse(tree("closed").contains("role=menu"), "its tab closed: " + tree("closed"));
    Script.run("""
        press 8,17 right
        frame
        key W control
        frame
        advance 1
        frame
        snap closed""", d.app, out);
    assertFalse(tree("closed").contains("role=menu"), "Control+W: " + tree("closed"));
    assertEquals(List.of(List.of(), List.of("b")), order(d));
  }

  // Without the handlers

  @Test
  void withoutHandlersTabsDoNotMove() throws IOException {
    List<String> log = new ArrayList<>();
    List<Tab> tabs = List.of(new Tab("a"), new Tab("b"), new Tab("c"));
    App<Object> plain = new App<>(
        new Object(), s -> Tabs.tabs(T, tabs, 0, i -> log.add("select " + i), null, null));
    Script.run("""
        size 400 160
        frame
        down 8,17
        move 100,17
        frame
        snap plain
        up 100,17
        focus #tablist
        key PageDown control shift
        frame""", plain, out);
    assertFalse(tree("plain").contains("grabbed") || tree("plain").contains("#marker"));
    assertEquals(List.of(), log, "no drag, no press, no move");

    // With a move handler but no drag state, only the keys move tabs.
    App<Object> keys = new App<>(
        new Object(),
        s -> Tabs.tabs(
            T,
            tabs,
            0,
            i -> {},
            null,
            null,
            Tabs.options().onMove((from, to) -> log.add("move " + from + " " + to))));
    Script.run("""
        size 400 160
        frame
        down 8,17
        move 100,17
        frame
        snap keys
        up 100,17
        focus #tablist
        key PageDown control shift
        frame""", keys, out);
    assertFalse(tree("keys").contains("grabbed"));
    assertEquals(List.of("move 0 1"), log);
  }

  @Test
  void moveKeepsTheActiveTab() {
    List<String> list = new ArrayList<>(List.of("a", "b", "c", "d"));
    assertEquals(2, Tabs.move(list, 0, 0, 2));
    assertEquals(List.of("b", "c", "a", "d"), list);
    assertEquals(0, Tabs.move(list, 1, 0, 3), "c moves down when b goes behind it");
    assertEquals(List.of("c", "a", "d", "b"), list);
    assertEquals(2, Tabs.move(list, 1, 3, 0), "a moves up when b comes before it");
    assertEquals(1, Tabs.move(list, 1, 3, 2), "unchanged");
  }
}
