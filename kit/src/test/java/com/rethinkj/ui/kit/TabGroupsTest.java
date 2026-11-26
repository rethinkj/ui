package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Drop;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.kit.TabGroups.Frame;
import com.rethinkj.ui.kit.TabGroups.Group;
import com.rethinkj.ui.kit.Tabs.Tab;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.Windows;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tabs in several windows and split groups ({@link TabGroups}), driven by scripts: moving along
 * the bar with the pointer and the keys, Escape, tearing off into a window that follows the
 * pointer, docking into the bar of the main window and of another window at a place, the only tab
 * of a window, closing a window, the menu, splitting within a window, active group tracking, and
 * tabs without the handlers.
 *
 * <p>The main window is 400 by 160 at 100,100 on the script's screen and shows the tabs alone,
 * a, b, c and d; a window torn off is 300 by 160 and shows its frame the same way. In both the
 * bar's tabs are 32 high from y 1, a from x 3, b from 48, c from 94 and d from 139; the scripts
 * press them 5 pixels inside their left edge, since a tab's center is on its cross.
 */
class TabGroupsTest {

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
    final List<String> closedWindows = new ArrayList<>();
    Consumer<Frame<String>> onOpen = frame -> {}; // what the app does while a window opens

    Docs() {
      groups.onClosed(closed::add);
    }

    Windows.Handle open(Frame<String> frame, Window.Options options) {
      onOpen.accept(frame);
      Windows.Handle handle = app.windows()
          .open(frame.active().activeTab(), 300, 160, options, new App<>(this, s -> s.view(frame)));
      handle.onClosed(() -> closedWindows.add(handle.title()));
      return handle;
    }

    El view(Frame<String> frame) {
      El tabs = groups
          .tabs(frame, Tab::new, s -> s == null ? null : Label.label(s).key("text"))
          .grow(1);
      return col().wFull().hFull().onKey(tabs.onKey()).child(tabs);
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
    assertEquals(1, d.groups.main().active().active(), "a stays active");
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
    assertEquals(3, app.state().groups.main().active().active());
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
    assertEquals(0, app.state().groups.main().active().active(), "the release on d chose nothing");
    String tree = tree("given-up");
    assertFalse(tree.contains("#marker") || tree.contains("grabbed"), tree);
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
    assertTrue(line(tree("docking"), "#marker").startsWith("el #marker 92,"), tree("docking"));
    Docs d = app.state();
    assertEquals(List.of(List.of("a", "c", "b", "d")), order(d));
    assertEquals(2, d.groups.main().active().active(), "the docked tab is active");
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
  void escapeAfterATearOffWhoseOpenerClosedATabStillPutsTheTabBack() throws IOException {
    App<Docs> app = start();
    Docs d = app.state();
    d.onOpen = frame -> {
      Group<String> source = d.groups.groupOf("c");
      d.groups.close(source, source.tabs().indexOf("c"));
    };
    run(app, TEAR_B + "key Escape");
    assertEquals(List.of(List.of("a", "b", "d")), order(d));
    assertFalse(d.groups.isDragging());
  }

  @Test
  void aNestedOpenerCannotReAddTheOuterTabInFlight() throws IOException {
    App<Docs> app = start();
    Docs d = app.state();
    Script.run("size 400 160 at 100,100\nframe", app, out);
    List<String> rejected = new ArrayList<>();
    d.onOpen = frame -> {
      if (frame.active().activeTab().equals("b")) {
        Group<String> source = d.groups.groupOf("c");
        d.groups.toNewWindow(source, source.tabs().indexOf("c"));
      } else {
        Group<String> main = d.groups.main().active();
        assertThrows(IllegalArgumentException.class, () -> d.groups.add(main, 0, "b"));
        rejected.add("b");
      }
    };
    d.groups.toNewWindow(d.groups.main().active(), 1);
    assertEquals(List.of("b"), rejected);
    assertEquals(List.of(List.of("a", "d"), List.of("c"), List.of("b")), order(d));
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

  @Test
  void anEmptyMainGroupsBarTakesATabDroppedOnIt() throws IOException {
    Docs d = new Docs();
    d.app = new App<>(
        d,
        s -> s.view(s.groups.main()).onKey(k -> {
          if (!k.name().equals("F2")) {
            return false;
          }
          s.groups.toNewWindow(s.groups.main().active(), 3);
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
  void closingAWindowDirectlyRunsOnWindowCloseAndTheOpenersHandler() throws IOException {
    App<Docs> app = start();
    Docs d = app.state();
    Script.run(
        "size 400 160 at 100,100\nframe\n" + TEAR_B
            + "move screen 400,400\nup screen 400,400\nframe",
        app,
        out);
    assertEquals(2, d.groups.frames().size());
    d.groups.frames().get(1).window().close();
    Script.run("frame\nframe", app, out);
    assertEquals(1, d.groups.frames().size());
    assertEquals(List.of(List.of("a", "c", "d", "b")), order(d));
    assertEquals(List.of("b"), d.closedWindows);

    // Closing the following window mid-drag still lets Escape end the drag cleanly.
    Script.run(TEAR_B, app, out);
    assertTrue(d.groups.isDragging());
    assertEquals(2, d.groups.frames().size());
    d.groups.frames().get(1).window().close();
    Script.run("frame\nkey Escape\nframe", app, out);
    assertFalse(d.groups.isDragging());
    assertEquals(1, d.groups.frames().size());
    assertEquals(List.of(List.of("a", "d", "b", "c")), order(d));
    assertEquals(List.of("b", "c"), d.closedWindows);

    // Closing the window whose only tab is dragged, where the press began, ends the drag, also
    // when its first step is already outside it.
    run(
        app,
        TEAR_B
            + "move screen 400,400\nup screen 400,400\nframe\nwindow d\ndown 8,17\nmove 40,300\nframe");
    assertTrue(d.groups.isDragging());
    d.groups.frames().get(1).window().close();
    Script.run("frame\nframe", app, out);
    assertFalse(d.groups.isDragging());
    assertEquals(1, d.groups.frames().size());
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
    assertFalse(tree("s").contains("#add"), "no + without onAdd");
    assertFalse(tree("s").contains("#overflow"), "4 short tabs fit without overflow");
    d.groups.close(d.groups.main().active(), 0); // the user agreed
    assertEquals(List.of("a"), d.closed);

    List<Group<String>> added = new ArrayList<>();
    d.groups.onAdd(group -> {
      added.add(group);
      d.groups.add(group, group.tabs().size(), "new-" + added.size());
    });
    run(app, "frame\npress #add\nframe\nfocus #tablist\nkey T control");
    assertEquals(List.of(d.groups.main().active(), d.groups.main().active()), added);
    Group<String> detached = d.groups.toNewWindow(d.groups.main().active(), 0);
    run(app, "window b\nframe\npress #add\nwindow main");
    assertSame(detached, added.getLast());
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
    assertFalse(
        tree("c-menu").contains("Split right"), "split items are hidden while splits(false)");
    run(app, """
        window c
        press label="Move back to main window"
        frame
        window main""");
    assertEquals(List.of(List.of("a", "b", "d", "c")), order(d));
    assertNull(d.groups.groupOf("x"));
  }

  // Splitting within a window

  @Test
  void splitRightShowsTwoGroupsAndClosingTheRightGroupsLastTabLeavesOne() throws IOException {
    App<Docs> app = start();
    Docs d = app.state();
    d.groups.splits(true);
    String image = "tab-groups-split-dark";
    Script.run("""
        size 400 160
        frame
        press #tab-1 right
        frame
        advance 1
        frame
        press label="Split right"
        frame
        advance 1
        frame
        snap %s""".formatted(image), app, out);
    Golden.assertMatches(
        image,
        Png.decode(Files.readAllBytes(out.resolve(image + ".png"))),
        Files.readString(out.resolve(image + ".tree.txt")));

    assertEquals(List.of(List.of("a", "c", "d"), List.of("b")), order(d));
    List<Group<String>> splitGroups = d.groups.main().groups();
    assertEquals(2, splitGroups.size());
    Group<String> left = splitGroups.get(0);
    Group<String> right = splitGroups.get(1);
    assertSame(d.groups.main(), left.frame());
    assertSame(d.groups.main(), right.frame());
    assertSame(right, d.groups.main().active(), "the new split group is active");
    El leftRoot = find(app.tree(), "group-0");
    El rightRoot = find(app.tree(), "group-1");
    assertNotNull(leftRoot);
    assertNotNull(rightRoot);
    assertSame(find(rightRoot, "tablist"), app.focused(), "focus went to the new group's bar");
    assertEquals(
        Theme.DARK.accent().edge(),
        find(rightRoot, "tab-0").children().getFirst().fill(),
        "active group gets the accent line");
    assertEquals(
        Theme.DARK.accent().edgeMuted(),
        find(leftRoot, "tab-" + left.active()).children().getFirst().fill(),
        "inactive group gets the muted line");

    // Closing the right group's last tab removes it, leaves one unsplit group and moves focus back.
    Script.run("size 400 160\nkey W control\nframe", app, out);
    assertEquals(List.of(List.of("a", "c", "d")), order(d));
    assertEquals(1, d.groups.main().groups().size());
    assertSame(left, d.groups.main().active());
    assertNull(find(app.tree(), "group-0"), "a single-group frame renders without #group-<id>");
    assertNull(find(app.tree(), "divider"), "no split divider left");
    assertSame(find(app.tree(), "tablist"), app.focused(), "focus moved to the staying group");
    assertEquals(
        Theme.DARK.accent().edge(),
        find(app.tree(), "tab-" + left.active()).children().getFirst().fill(),
        "the staying group has the accent line again");
  }

  @Test
  void splitDownRoomCheckDoublePressResetAndNestedOrder() throws IOException {
    App<Docs> app = start();
    Docs d = app.state();
    d.groups.splits(true);

    // At 400x160: width 400 >= 2*120+8 fits Split right, height 160 < 248 disables Split down.
    Script.run("""
        size 400 160
        frame
        press #tab-0 right
        frame
        advance 1
        frame
        snap narrow-h
        key Escape
        frame""", app, out);
    String narrowH = tree("narrow-h");
    assertFalse(line(narrowH, "label=\"Split right\"").contains("disabled"), narrowH);
    assertTrue(line(narrowH, "label=\"Split down\"").contains("disabled"), narrowH);

    // At 240x160: neither fits.
    Script.run("""
        size 240 160
        frame
        press #tab-0 right
        frame
        advance 1
        frame
        snap too-small
        key Escape
        frame""", app, out);
    String tooSmall = tree("too-small");
    assertTrue(line(tooSmall, "label=\"Split right\"").contains("disabled"), tooSmall);
    assertTrue(line(tooSmall, "label=\"Split down\"").contains("disabled"), tooSmall);

    // At 400x300: Split down works, divider resizes and double press resets to half.
    Script.run("""
        size 400 300
        frame
        press #tab-1 right
        frame
        advance 1
        frame
        press label="Split down"
        frame""", app, out);
    assertEquals(List.of(List.of("a", "c", "d"), List.of("b")), order(d));
    El top = find(app.tree(), "group-0");
    El bottom = find(app.tree(), "group-1");
    assertEquals(top.height(), bottom.height(), 1f, "equal halves at first");

    // In the bottom group (only 1 tab), both Split right and Split down are disabled.
    Script.run("""
        size 400 300
        press role=tab label="b" right
        frame
        advance 1
        frame
        snap one-tab
        key Escape
        frame""", app, out);
    String oneTab = tree("one-tab");
    assertTrue(line(oneTab, "label=\"Split right\"").contains("disabled"), oneTab);
    assertTrue(line(oneTab, "label=\"Split down\"").contains("disabled"), oneTab);

    // Drag divider up, then double press resets to half.
    Script.run("""
        size 400 300
        down #divider
        move 200,130
        up 200,130
        frame""", app, out);
    assertTrue(find(app.tree(), "first").height() < find(app.tree(), "second").height());
    Script.run("""
        size 400 300
        press #divider clicks 2
        frame""", app, out);
    assertEquals(
        find(app.tree(), "first").height(),
        find(app.tree(), "second").height(),
        1f,
        "double press resets to half");

    // Split top group left with c: order is top-left first (new left, top-right, bottom), and ids
    // never repeat even after closing a group.
    Group<String> topGroup = d.groups.main().groups().get(0);
    Group<String> leftTop = d.groups.split(topGroup, 1, topGroup, Side.LEFT);
    app.invalidate();
    Script.run("size 400 300\nframe", app, out);
    assertEquals(List.of(List.of("c"), List.of("a", "d"), List.of("b")), order(d));
    assertNotNull(find(app.tree(), "group-2"));
    d.groups.close(leftTop, 0);
    Group<String> again = d.groups.split(topGroup, 1, topGroup, Side.RIGHT);
    app.invalidate();
    Script.run("size 400 300\nframe\nadvance 1\nframe", app, out);
    assertNull(find(app.tree(), "group-2"));
    assertNotNull(find(app.tree(), "group-3"), "group ids never repeat");
    assertSame(again, d.groups.main().active());
  }

  @Test
  void aTornOffWindowSplitsTooAndClosingItDrainsAllGroups() throws IOException {
    App<Docs> app = start();
    Docs d = app.state();
    d.groups.splits(true);
    Script.run("size 400 160\nframe", app, out);
    // Split main first so main has two groups, active is the right one.
    d.groups.split(d.groups.main().active(), 3, d.groups.main().active(), Side.RIGHT);
    Group<String> mainRight = d.groups.main().active();
    // Move b to a new window and dock c into it so the torn-off window has [b, c].
    Group<String> detached = d.groups.toNewWindow(d.groups.main().groups().get(0), 1);
    d.groups.dock(d.groups.main().groups().get(0), 1, detached, 1);
    Frame<String> floating = detached.frame();
    assertEquals(2, d.groups.frames().size());

    // Split c right inside the torn-off window (300x160 has width 300 >= 248).
    Script.run("""
        size 400 160
        frame
        window b
        press #tab-1 right
        frame
        advance 1
        frame
        press label="Split right"
        frame""", app, out);
    assertEquals(2, floating.groups().size(), "torn-off window split into two groups");
    assertEquals(List.of("b"), floating.groups().get(0).tabs());
    assertEquals(List.of("c"), floating.groups().get(1).tabs());

    // In a split torn-off window, a 1-tab group is NOT "alone": Move to new window is enabled.
    Script.run("""
        size 400 160
        window b
        press role=tab label="c" right
        frame
        advance 1
        frame
        snap split-torn-menu
        key Escape
        frame""", app, out);
    String menu = tree("b-split-torn-menu");
    assertFalse(
        line(menu, "label=\"Move to new window\"").contains("disabled"),
        "a split torn-off window's group is not alone: " + menu);

    // Closing the split torn-off window drains all groups into main's active group and closes once.
    Script.run("""
        size 400 160
        window b
        close
        frame
        window main""", app, out);
    assertEquals(1, d.groups.frames().size());
    assertFalse(floating.window().isOpen());
    assertEquals(List.of("d", "b", "c"), mainRight.tabs(), "docked into main's active group");

    // With closeAll, closing a split torn-off window closes all tabs across its groups.
    d.groups.onWindowClose(d.groups::closeAll);
    Group<String> detached2 = d.groups.toNewWindow(mainRight, 1);
    d.groups.dock(mainRight, 1, detached2, 1);
    d.groups.split(detached2, 1, detached2, Side.RIGHT);
    Frame<String> floating2 = detached2.frame();
    Script.run("""
        size 400 160
        frame
        window b
        close
        frame
        window main""", app, out);
    assertEquals(1, d.groups.frames().size());
    assertFalse(floating2.window().isOpen());
    assertEquals(List.of("b", "c"), d.closed);
  }

  @Test
  void controlBackslashSplitsTheActiveTabRight() throws IOException {
    // Splits off: Control+\ does nothing.
    App<Docs> off = start();
    Script.run("size 400 160\nframe\nfocus #tablist\nkey \\ control\nframe", off, out);
    assertEquals(1, off.state().groups.main().groups().size());

    // Splits on: Control+\ from the bar splits active tab right.
    App<Docs> app = start();
    Docs d = app.state();
    d.groups.splits(true);
    Script.run("size 400 160\nframe\nfocus #tablist\nkey \\ control\nframe", app, out);
    assertEquals(List.of(List.of("b", "c", "d"), List.of("a")), order(d));
    assertSame(d.groups.main().groups().get(1), d.groups.main().active());
    assertSame(find(find(app.tree(), "group-1"), "tablist"), app.focused());

    // On a 1-tab group, Control+\ does nothing.
    Script.run("size 400 160\nkey \\ control\nframe", app, out);
    assertEquals(2, d.groups.main().groups().size());

    // From outside the tabs when the app hands tabs.onKey() to the root: acts on frame.active().
    TabGroups<String> tg = new TabGroups<>(List.of("x", "y", "z"), (f, o) -> null).splits(true);
    App<Object> withOutside = new App<>(new Object(), s -> {
      El tabs = tg.tabs(tg.main(), Tab::new, t -> Label.label(t));
      return col()
          .wFull()
          .hFull()
          .onKey(tabs.onKey())
          .children(El.el().key("outside").focusable().size(10, 10), tabs.grow(1));
    });
    Script.run("size 400 160\nframe\nfocus #outside\nkey \\ control\nframe", withOutside, out);
    assertEquals(2, tg.main().groups().size(), "frame root onKey delegates to active group");
    assertEquals(List.of("y", "z"), tg.main().groups().get(0).tabs());
    assertEquals(List.of("x"), tg.main().groups().get(1).tabs());
  }

  @Test
  void aSplitFocusesTheNewBarOverAnAutofocusInTheStayingGroup() throws IOException {
    // The split moves the staying group to another parent, so an open find bar there is new too.
    TabGroups<String> groups = new TabGroups<>(List.of("a", "b"), (f, o) -> null).splits(true);
    App<Object> app = new App<>(
        new Object(),
        s -> groups
            .tabs(
                groups.main(),
                Tab::new,
                t -> Label.label(t)
                    .overlay(El.el().key("find").focusable().size(40, 10).autofocus("open")))
            .grow(1));
    Script.run("size 600 200\nframe", app, out);
    assertEquals("find", app.focused().key());

    Group<String> g0 = groups.main().active();
    Group<String> g1 = groups.split(g0, 1, g0, Side.RIGHT);
    Script.run("size 600 200\nframe", app, out); // within the frame
    assertSame(find(find(app.tree(), "group-1"), "tablist"), app.focused());
    assertSame(g1, groups.main().active());
  }

  @Test
  void keysInTheFrameOfASplitFindTheFocusOnTheNewBar() throws IOException {
    // Control+W right after the split closes the moved tab, also where the staying group shows a
    // tab with an open find bar that asks for the focus.
    TabGroups<String> groups = new TabGroups<>(List.of("a", "b"), (f, o) -> null).splits(true);
    Group<String> first = groups.main().active();
    List<Group<String>> closedIn = new ArrayList<>();
    groups.onCloseTab((group, index) -> {
      closedIn.add(group);
      groups.close(group, index);
    });
    App<Object> app = new App<>(new Object(), s -> {
      El tabs = groups.tabs(
          groups.main(),
          Tab::new,
          t -> t.equals("a")
              ? col()
                  .children(
                      Label.label(t).overlay(findBar("find")),
                      col()
                          .key("list")
                          .h(60)
                          .rows(
                              3,
                              20,
                              i -> i == 0
                                  ? Label.label("row").overlay(findBar("edit"))
                                  : Label.label("row")))
              : Label.label(t));
      return col().wFull().hFull().onKey(tabs.onKey()).child(tabs.grow(1));
    });
    groups.select(first, 1);
    Script.run("size 600 200\nframe\nfocus #tablist\nframe", app, out);
    Script.run("size 600 200\nkey \\ control\nkey W control\nframe", app, out);
    assertEquals(1, closedIn.size());
    assertNotSame(first, closedIn.getFirst()); // in the new group, so the split came first
    assertEquals(
        List.of(List.of("a")), groups.main().groups().stream().map(Group::tabs).toList());

    // Tab right after the split moves the focus on from the new bar, and it stays there.
    TabGroups<String> plain = new TabGroups<>(List.of("a", "b"), (f, o) -> null).splits(true);
    App<Object> other = new App<>(new Object(), s -> {
      El tabs = plain.tabs(plain.main(), Tab::new, t -> Label.label(t));
      return col().wFull().hFull().onKey(tabs.onKey()).child(tabs.grow(1));
    });
    Script.run("size 600 200\nframe\nfocus #tablist\nframe", other, out);
    Script.run("size 600 200\nkey \\ control\nkey Tab\nframe\nframe", other, out);
    assertEquals(2, plain.main().groups().size());
    assertNotSame(find(find(other.tree(), "group-1"), "tablist"), other.focused());
  }

  private static El findBar(String key) {
    return El.el().key(key).focusable().size(40, 10).autofocus("open");
  }

  @Test
  void activeGroupFollowsFocusPressAndDockAndClosedGroupHandsFocusToSibling() throws IOException {
    TabGroups<String> groups =
        new TabGroups<>(List.of("a", "b", "c", "d"), (f, o) -> null).splits(true);
    App<Object> app = new App<>(new Object(), s -> {
      El tabs = groups.tabs(
          groups.main(),
          Tab::new,
          t -> col()
              .children(
                  El.el().key("field-" + t).focusable().size(40, 20),
                  TextField.textField("", text -> {}).key("edit-" + t),
                  Label.label("body-" + t).key("lbl-" + t)));
      return col().wFull().hFull().onKey(tabs.onKey()).child(tabs.grow(1));
    });

    Script.run("size 600 200\nframe", app, out);
    Group<String> g0 = groups.main().active();
    Group<String> g1 = groups.split(g0, 1, g0, Side.RIGHT); // g0=[a,c,d], g1=[b]
    Script.run("size 600 200\nframe", app, out);
    assertSame(g1, groups.main().active());
    assertSame(find(find(app.tree(), "group-1"), "tablist"), app.focused());

    // Moving focus into g0 (onFocusIn) makes g0 active.
    Script.run("size 600 200\nfocus #field-a\nframe", app, out);
    assertSame(g0, groups.main().active());
    assertSame(find(app.tree(), "field-a"), app.focused());

    // Pressing the already-active tab of g1 makes g1 active while keeping focus in #field-a.
    Script.run("size 600 200\npress role=tab label=\"b\" at left+5\nframe", app, out);
    assertSame(g1, groups.main().active());
    assertSame(find(app.tree(), "field-a"), app.focused(), "focus stayed where it was");

    // A press into g0's text field, which keeps the press to itself, makes g0 active.
    Script.run("size 600 200\npress #edit-a\nframe", app, out);
    assertSame(g0, groups.main().active());

    // Pressing a non-focusable label inside g0 makes g0 active.
    Script.run("size 600 200\npress role=tab label=\"b\" at left+5\nframe", app, out);
    assertSame(g1, groups.main().active());
    Script.run("size 600 200\npress #lbl-a\nframe", app, out);
    assertSame(g0, groups.main().active());

    // Docking a tab into g1 makes the tab active, g1 active, and focuses g1's bar.
    groups.dock(g0, 1, g1, 1); // c -> g1
    Script.run("size 600 200\nframe", app, out);
    assertSame(g1, groups.main().active());
    assertEquals("c", g1.activeTab());
    assertSame(find(find(app.tree(), "group-1"), "tablist"), app.focused());

    // 3-way split: Split(g0, Split(g1, g2)); closing focused g2 gives focus to its sibling g1.
    Group<String> g2 = groups.split(g1, 1, g1, Side.RIGHT); // g1=[b], g2=[c]
    Script.run("size 600 200\nframe", app, out);
    assertSame(g2, groups.main().active());
    assertSame(find(find(app.tree(), "group-2"), "tablist"), app.focused());
    Script.run("size 600 200\nkey W control\nframe", app, out);
    assertEquals(List.of(g0, g1), groups.main().groups());
    assertSame(g1, groups.main().active());
    assertSame(find(find(app.tree(), "group-1"), "tablist"), app.focused());

    // dockAll on main's active group never docks into itself.
    List<String> before = List.copyOf(g1.tabs());
    groups.dockAll(g1);
    assertEquals(before, g1.tabs());
    assertNotEquals(g0, groups.main().active());

    // Keys go to the active group, also while the focus stays in another one.
    Script.run(
        "size 600 200\nfocus #field-a\npress role=tab label=\"b\" at left+5\nkey W control\nframe",
        app,
        out);
    assertEquals(List.of("a", "d"), g0.tabs(), "Control+W closed b, of the active group");
    assertEquals(List.of(g0), groups.main().groups());
  }

  @Test
  void edgeDropSplitsAGroupShowsZonePreviewAndRespectsRoomAndSingleTab() throws IOException {
    App<Docs> app = start();
    Docs d = app.state();
    d.groups.splits(true);
    String image = "tab-groups-drop-zone-dark";

    // At 400x160: width fits horizontal split, height 160 < 248 does not fit vertical split.
    Script.run("""
        size 400 160 at 100,100
        frame
        down 52,17
        move 80,17
        frame
        move 340,100
        frame
        snap %s
        move 50,100
        frame
        snap left
        move 200,45
        frame
        snap top-no-room
        move 340,100
        frame
        up 340,100
        frame""".formatted(image), app, out);
    String rightTree = Files.readString(out.resolve(image + ".tree.txt"));
    Golden.assertMatches(
        image, Png.decode(Files.readAllBytes(out.resolve(image + ".png"))), rightTree);
    assertEquals(1, d.groups.frames().size(), "dragging over content stayed in the window");
    assertTrue(line(rightTree, "#zone").contains("value=\"right\""), rightTree);
    assertTrue(line(tree("left"), "#zone").contains("value=\"left\""), tree("left"));
    assertFalse(
        tree("top-no-room").contains("#zone"),
        "without vertical room on its own group, top shows no center zone: " + tree("top-no-room"));
    assertEquals(List.of(List.of("a", "c", "d"), List.of("b")), order(d));
    assertSame(d.groups.main().groups().get(1), d.groups.main().active());
    assertSame(find(find(app.tree(), "group-1"), "tablist"), app.focused());

    // Dragging the lone tab of a split group: no zones on its own panel; Escape cancels a zone.
    Script.run("""
        size 400 300 at 100,100
        frame
        advance 1
        frame
        down role=tab label="b" at left+5
        move 250,17
        frame
        move 300,150
        frame
        snap own-lone
        move 100,260
        frame
        snap other-bottom
        key Escape
        frame
        snap escaped
        up 100,260
        frame""", app, out);
    assertFalse(tree("own-lone").contains("#zone"), "no zone on lone tab's own group");
    assertTrue(
        line(tree("other-bottom"), "#zone").contains("value=\"bottom\""), tree("other-bottom"));
    assertFalse(tree("escaped").contains("#zone"), "Escape clears the zone preview");
    assertEquals(List.of(List.of("a", "c", "d"), List.of("b")), order(d), "Escape kept layout");

    // Dropping a tab from the left group on the bottom edge of the 1-tab right group splits it.
    Script.run("""
        size 400 300 at 100,100
        down 8,17
        move 30,17
        frame
        move 300,260
        frame
        snap bottom-on-right
        up 300,260
        frame""", app, out);
    assertTrue(
        line(tree("bottom-on-right"), "#zone").contains("value=\"bottom\""),
        tree("bottom-on-right"));
    assertEquals(List.of(List.of("c", "d"), List.of("b"), List.of("a")), order(d));

    // The center of its own group of several tabs shows no zone and leaves order and active tab
    // unchanged.
    Script.run("""
        size 400 300 at 100,100
        advance 1
        frame
        down role=tab label="d" at left+5
        move 30,17
        frame
        move 100,160
        frame
        snap own-center
        up 100,160
        frame""", app, out);
    assertFalse(tree("own-center").contains("#zone"), tree("own-center"));
    assertEquals(List.of(List.of("c", "d"), List.of("b"), List.of("a")), order(d));
    assertEquals("c", d.groups.main().groups().getFirst().activeTab());
  }

  @Test
  void centerDropDocksOverContentWithItsOwnOnDrop() throws IOException {
    List<Drop> contentDrops = new ArrayList<>();
    TabGroups<String> groups =
        new TabGroups<>(List.of("a", "b", "c", "d"), (f, o) -> null).splits(true);
    App<Object> app = new App<>(new Object(), s -> {
      El tabs = groups.tabs(
          groups.main(),
          Tab::new,
          t -> t == null
              ? null
              : col()
                  .wFull()
                  .hFull()
                  .child(El.el().key("inner-" + t).wFull().hFull().onDrop(contentDrops::add)));
      return col().wFull().hFull().onKey(tabs.onKey()).child(tabs.grow(1));
    });
    Script.run("size 400 160\nframe", app, out);
    Group<String> left = groups.main().active();
    Group<String> right = groups.split(left, 1, left, Side.RIGHT); // left=[a,c,d], right=[b]
    groups.dock(left, 2, right, 1); // left=[a,c], right=[b,d]
    Script.run("""
        size 400 160
        frame
        down role=tab label="d" at left+5
        move 280,17
        frame
        move 100,100
        frame
        snap center
        up 100,100
        frame""", app, out);
    assertTrue(line(tree("center"), "#zone").contains("value=\"center\""), tree("center"));
    assertTrue(contentDrops.isEmpty(), "drop layer intercepted before content's own onDrop");
    assertEquals(List.of("a", "c", "d"), left.tabs());
    assertEquals(List.of("b"), right.tabs());
    assertEquals("d", left.activeTab());
    assertSame(left, groups.main().active());
    assertSame(find(find(app.tree(), "group-0"), "tablist"), app.focused());
  }

  @Test
  void tearOffWithSplitsLeavesAllWindowsAndEscapeRestoresSplitAndDivider() throws IOException {
    App<Docs> app = start();
    Docs d = app.state();
    d.groups.splits(true);

    // Split right and move the divider away from 0.5.
    Script.run("size 400 160 at 100,100\nframe", app, out);
    Group<String> left = d.groups.main().active();
    Group<String> right = d.groups.split(left, 1, left, Side.RIGHT); // [a,c,d] | [b]
    Script.run("""
        size 400 160 at 100,100
        frame
        down #divider
        move 150,80
        up 150,80
        frame
        snap before-tear""", app, out);
    String dividerBefore = line(tree("before-tear"), "#divider").replace(" hovered", "");
    assertFalse(dividerBefore.contains("share 0.5"), dividerBefore);

    // Tear off the only tab of the right split group by leaving the window; Escape restores it.
    Script.run("""
        size 400 160 at 100,100
        down role=tab label="b" at left+5
        move 200,17
        frame
        move screen 600,400
        frame
        move screen 180,200
        frame
        snap following-over-main
        key Escape
        frame""", app, out);
    assertTrue(
        tree("following-over-main").contains("#zone"),
        "a following window can drop into another group's zone: " + tree("following-over-main"));
    assertEquals(1, d.groups.frames().size(), "Escape closed the torn-off window");
    assertEquals(List.of(left, right), d.groups.main().groups(), "original split group restored");
    assertEquals(List.of(List.of("a", "c", "d"), List.of("b")), order(d));
    assertEquals(
        dividerBefore,
        line(app.tree().dump(), "#divider").replace(" hovered", ""),
        "divider position restored on Escape");

    // Tear off again, the first step already outside, and release outside: the emptied split
    // group is pruned on commit.
    Script.run("""
        size 400 160 at 100,100
        down role=tab label="b" at left+5
        move screen 600,400
        frame
        up screen 600,400
        frame""", app, out);
    assertEquals(2, d.groups.frames().size());
    assertEquals(List.of(left), d.groups.main().groups(), "emptied split group pruned on commit");
    assertEquals(List.of(List.of("a", "c", "d"), List.of("b")), order(d));

    // Place b's window adjacent to main (at 500,100). While it takes no drops, a release over it
    // tears nothing off and moves nothing.
    Script.run("""
        window b
        size 300 160 at 500,100
        frame
        window main""", app, out);
    Windows.Handle b = d.groups.frames().get(1).active().window();
    b.dropTarget(false);
    Script.run("""
        down role=tab label="c" at left+5
        move 80,17
        frame
        move screen 600,200
        frame
        up screen 600,200
        frame""", app, out);
    b.dropTarget(true);
    assertEquals(2, d.groups.frames().size(), "no window opened over a window that takes no drops");
    assertEquals(List.of(List.of("a", "c", "d"), List.of("b")), order(d));

    // Dragging straight over b does not tear off, shows zones in b, and docks into b's bar.
    Script.run("""
        window main
        down role=tab label="c" at left+5
        move 80,17
        frame
        move screen 600,200
        frame
        snap over-other-panel
        move screen 580,117
        frame
        up screen 580,117
        frame""", app, out);
    assertTrue(
        tree("b-over-other-panel").contains("#zone"),
        "other window offers drop zones: " + tree("b-over-other-panel"));
    assertEquals(2, d.groups.frames().size(), "no third window opened over another window");
    assertEquals(List.of(List.of("a", "d"), List.of("b", "c")), order(d));

    // Tearing off a tab and dropping it on another window's right edge splits that window directly
    // and closes the following window.
    Script.run("""
        window main
        down role=tab label="d" at left+5
        move 80,17
        frame
        move screen 50,400
        frame
        move screen 770,180
        frame
        snap cross-split-zone
        up screen 770,180
        frame""", app, out);
    assertTrue(
        line(tree("b-cross-split-zone"), "#zone").contains("value=\"right\""),
        tree("b-cross-split-zone"));
    assertEquals(2, d.groups.frames().size(), "following window closed on cross-window split");
    assertEquals(List.of(List.of("a"), List.of("b", "c"), List.of("d")), order(d));

    // Direct cross-window panel drop (without leaving the touching windows) raises the receiving
    // window for both center and edge releases.
    App<Docs> direct = start();
    Docs dd = direct.state();
    dd.groups.splits(true);
    Script.run("size 400 160 at 100,100\nframe", direct, out);
    Group<String> bGroup = dd.groups.toNewWindow(dd.groups.main().active(), 1); // b
    Script.run("""
        window b
        size 300 160 at 450,100
        frame
        window main""", direct, out);
    Windows.Handle directMain = direct.windows().of(direct);
    directMain.raise();
    // Center drop of c straight from main onto b's panel (screen 600,180) raises b above main.
    Script.run("""
        window main
        down role=tab label="c" at left+5
        move 80,17
        frame
        move screen 600,180
        frame
        up screen 600,180
        frame""", direct, out);
    assertEquals(List.of("b", "c"), bGroup.tabs());
    assertSame(
        bGroup.window(),
        direct.windows().place(direct, 360, 80).window(),
        "direct cross-window center drop raises the receiving window");

    // Edge drop of d straight from main onto b's right edge (screen 720,180) splits and raises b.
    directMain.raise();
    Script.run("""
        window main
        down role=tab label="d" at left+5
        move 80,17
        frame
        move screen 720,180
        frame
        up screen 720,180
        frame""", direct, out);
    assertEquals(2, bGroup.frame().groups().size());
    assertSame(
        bGroup.window(),
        direct.windows().place(direct, 360, 80).window(),
        "direct cross-window edge drop raises the receiving window");

    // Tearing off main's last remaining tab (a) and returning to main's empty panel docks it back.
    Script.run("""
        window main
        down role=tab label="a" at left+5
        move 80,17
        frame
        move screen 50,400
        frame
        move screen 250,180
        frame
        snap empty-main-center
        up screen 250,180
        frame""", direct, out);
    assertTrue(
        line(tree("empty-main-center"), "#zone").contains("value=\"center\""),
        tree("empty-main-center"));
    assertEquals(2, dd.groups.frames().size(), "following window closed on empty-main center drop");
    assertEquals(List.of("a"), dd.groups.main().active().tabs());

    // Without a window runner, dragging outside the app stays inside and never calls the opener.
    int[] opened = {0};
    TabGroups<String> standalone = new TabGroups<>(List.of("x", "y"), (f, o) -> {
          opened[0]++;
          return null;
        })
        .splits(true);
    App<Object> noRunner = new App<>(
        new Object(),
        s -> col()
            .wFull()
            .hFull()
            .child(standalone
                .tabs(standalone.main(), Tab::new, t -> Label.label(t))
                .grow(1)));
    noRunner.resize(400, 160, 1);
    noRunner.frame();
    noRunner.pointerDown(52, 17);
    noRunner.frame();
    noRunner.pointerMove(80, 17);
    noRunner.frame();
    noRunner.pointerMove(80, 250);
    noRunner.frame();
    noRunner.pointerUp(80, 250);
    noRunner.frame();
    assertEquals(0, opened[0], "without a window runner a drag stays inside");
    assertEquals(1, standalone.frames().size());
  }

  // Focus

  @Test
  void eachTabsContentRemembersItsOwnFocus() throws IOException {
    TabGroups<String> groups = new TabGroups<>(List.of("a", "b", "c"), (f, o) -> null);
    App<Object> app = new App<>(
        new Object(),
        s -> col()
            .wFull()
            .hFull()
            .children(
                El.el().key("tree").focusable().size(10, 10),
                groups
                    .tabs(
                        groups.main(),
                        Tab::new,
                        x -> col()
                            .children(
                                El.el()
                                    .key("refresh")
                                    .label("Refresh")
                                    .focusable()
                                    .size(10, 10),
                                x.equals("c") // still loading
                                    ? El.el()
                                    : El.el()
                                        .key("grid")
                                        .label("Grid " + x)
                                        .focusable()
                                        .defaultFocus()
                                        .size(10, 10)))
                    .grow(1)));
    Script.run("size 400 160\nframe\nfocus #refresh\nfocus #tree\nframe", app, out);
    app.post(() -> groups.select(groups.main().active(), 1)); // while the focus is outside
    Script.run("await\nframe", app, out);
    app.focus(find(app.tree(), "panel"));
    app.frame();
    assertEquals("Grid b", app.focused().label(), "b has not had the focus: its default");

    Script.run("key Tab control shift\nframe", app, out);
    assertEquals("Refresh", app.focused().label(), "back where it was in a");
    assertEquals(0, groups.main().active().active());

    app.post(() -> groups.select(groups.main().active(), 2));
    Script.run("focus #tree\nawait\nframe", app, out);
    app.focus(find(app.tree(), "panel"));
    app.frame(); // c's Refresh, waiting for a grid
    Script.run("key Tab control\nframe", app, out);
    assertEquals(0, groups.main().active().active(), "around to a");
    assertEquals("Refresh", app.focused().label(), "a's own memory, not its grid");
  }

  private static El find(El el, String key) {
    if (key.equals(el.key())) {
      return el;
    }
    for (El child : el.children()) {
      El found = find(child, key);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  // Without the handlers

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

  @Test
  void aWindowWhoseDraggedTabClosesTakesDropsAgain() throws IOException {
    Docs docs = new Docs();
    docs.app = new App<>(docs, s -> s.view(s.groups.main()));
    Script.run("""
        size 400 160 at 100,100
        frame
        down 53,17
        move 53,90
        frame
        """, docs.app, out);
    Group<String> floating = docs.groups.groupOf("b");
    assertTrue(docs.groups.isDragging());
    assertNotSame(floating.frame(), docs.groups.main());
    docs.app.post(() -> {
      docs.groups.add(floating, 1, "new query");
      docs.groups.close(floating, 0);
    });
    Script.run("await\nframe\nup 53,90\nframe", docs.app, out);
    assertFalse(docs.groups.isDragging());
    Windows.Handle window = floating.window();
    assertTrue(window.isOpen());
    window.moveTo(600, 400);
    Windows.Handle main = docs.app.windows().of(docs.app);
    Windows.Place place =
        docs.app.windows().place(docs.app, window.x() - main.x() + 20, window.y() - main.y() + 17);
    assertSame(window, place.window(), window.app().tree().dump());
  }

  @Test
  void anOverflowingGroupShowsItsOwnOverflowMenu() throws IOException {
    List<String> titles = new ArrayList<>();
    for (int i = 0; i < 15; i++) {
      titles.add("tab-" + i);
    }
    class Many {
      App<Many> app;
      final TabGroups<String> groups = new TabGroups<>(titles, this::open);

      Windows.Handle open(Frame<String> frame, Window.Options options) {
        return app.windows()
            .open(
                frame.active().activeTab(), 300, 160, options, new App<>(this, s -> s.view(frame)));
      }

      El view(Frame<String> frame) {
        return col()
            .wFull()
            .hFull()
            .child(groups.tabs(frame, Tab::new, s -> null).grow(1));
      }
    }
    Many many = new Many();
    many.app = new App<>(many, s -> s.view(s.groups.main()));
    Script.run("""
        size 400 200
        frame
        press #overflow
        frame
        advance 1
        frame
        wheel #menu 0 1000
        frame
        press #item-12
        frame""", many.app, out);
    assertEquals(12, many.groups.main().active().active());
    assertTrue(find(many.app.tree(), "tablist").scrollX() > 0);
  }

  @Test
  void escapeAfterTearOffKeepsPreviousActiveTabFocusAndDoesNotRaiseOtherWindow()
      throws IOException {
    class Tracked {
      App<Tracked> app;
      final TabGroups<String> groups =
          new TabGroups<>(List.of("a", "b", "c", "d"), this::open).splits(true);

      Windows.Handle open(Frame<String> frame, Window.Options options) {
        return app.windows()
            .open(
                frame.active().activeTab(), 300, 160, options, new App<>(this, s -> s.view(frame)));
      }

      El view(Frame<String> frame) {
        El tabs = groups.tabs(
            frame,
            Tab::new,
            t -> t == null ? null : El.el().key("field-" + t).focusable().size(40, 20));
        return col().wFull().hFull().onKey(tabs.onKey()).child(tabs.grow(1));
      }
    }
    Tracked t = new Tracked();
    t.app = new App<>(t, s -> s.view(s.groups.main()));
    // Open a second window for d overlapping main's right edge, raise main on top, focus #field-a
    // in main, tear off inactive b outside and hover over d's non-overlapping right half before
    // pressing Escape.
    Script.run("size 400 160 at 100,100\nframe", t.app, out);
    Group<String> detached = t.groups.toNewWindow(t.groups.main().active(), 3);
    Script.run("""
        window d
        size 300 160 at 350,100
        frame
        window main""", t.app, out);
    Windows.Handle mainWin = t.app.windows().of(t.app);
    mainWin.raise();
    Script.run("""
        window main
        focus #field-a
        frame
        down role=tab label="b" at left+5
        move 80,17
        frame
        move screen 520,400
        frame
        move screen 580,180
        frame
        key Escape
        frame""", t.app, out);
    assertEquals(List.of("a", "b", "c"), t.groups.main().active().tabs());
    assertEquals("a", t.groups.main().active().activeTab(), "previous active tab stays active");
    assertSame(find(t.app.tree(), "field-a"), t.app.focused(), "focus stays in a's field");
    // In the overlap (screen x=360, local x=260), main stayed on top rather than raising d.
    assertSame(
        mainWin,
        t.app.windows().place(t.app, 260, 80).window(),
        "cancelling must not raise another window under the pointer: " + detached.window());

    // Split with focus in left group; tear off right group's sole tab and press Escape:
    // left group stays active and keeps its focus.
    Group<String> left = t.groups.main().active();
    Group<String> right = t.groups.split(left, 2, left, Side.RIGHT); // left=[a,b], right=[c]
    t.app.invalidate();
    Script.run("""
        size 400 160 at 100,100
        frame
        focus #field-a
        frame
        down role=tab label="c" at left+5
        move 260,17
        frame
        move screen 520,400
        frame
        key Escape
        frame""", t.app, out);
    assertSame(left, t.groups.main().active(), "pre-press active group is restored");
    assertSame(find(t.app.tree(), "field-a"), t.app.focused(), "focus stays in left group");
    assertEquals(List.of("c"), right.tabs());

    // Focus in left group's sole tab while right group is active: tearing off left's tab and
    // pressing Escape keeps right active and restores focus to right's bar.
    t.groups.close(left, 1); // left=[a], right=[c]
    t.app.invalidate();
    Script.run("""
        size 400 160 at 100,100
        frame
        focus #field-a
        frame
        press role=tab label="c" at left+5
        frame
        down role=tab label="a" at left+5
        move 60,17
        frame
        move screen 520,400
        frame
        key Escape
        frame""", t.app, out);
    assertSame(right, t.groups.main().active(), "right group stays active on cancel");
    assertNotNull(t.app.focused(), "focus is restored");

    // Tearing off main window's lone focused tab and pressing Escape restores focus.
    t.groups.close(right, 0);
    t.app.invalidate();
    Script.run("""
        size 400 160 at 100,100
        frame
        focus #field-a
        frame
        down role=tab label="a" at left+5
        move 80,17
        frame
        move screen 520,400
        frame
        key Escape
        frame""", t.app, out);
    assertEquals(List.of("a"), left.tabs());
    assertNotNull(t.app.focused(), "focus is restored instead of left unset");
  }

  @Test
  void emptyBarHasNoContextMenuAndAlwaysProvidesOnKey() throws IOException {
    TabGroups<String> groups = new TabGroups<String>(List.of(), (f, o) -> null).splits(true);
    App<Object> app = new App<>(new Object(), s -> {
      El tabs = groups.tabs(groups.main(), Tab::new, t -> null);
      assertNotNull(tabs.onKey(), "empty unsplit frame still provides a non-null onKey");
      assertFalse(
          tabs.onKey().test(new com.rethinkj.ui.Key("W", com.rethinkj.ui.Key.CONTROL, false)));
      return col().wFull().hFull().onKey(tabs.onKey()).child(tabs.grow(1));
    });
    Script.run("""
        size 400 160
        frame
        press #bar right
        frame
        advance 1
        frame
        snap empty-right""", app, out);
    assertFalse(tree("empty-right").contains("role=menu"), tree("empty-right"));
  }

  @Test
  void mutationGuardsRejectDuplicatesClosedGroupsAndNullOpener() throws IOException {
    String a = new String("a");
    String b = new String("b");
    assertThrows(
        IllegalArgumentException.class, () -> new TabGroups<>(List.of(a, a), (f, o) -> null));

    TabGroups<String> nullOpener = new TabGroups<>(List.of(a, b), (f, o) -> null);
    Group<String> main = nullOpener.main().active();
    assertThrows(IllegalArgumentException.class, () -> nullOpener.add(main, 0, a));
    assertThrows(NullPointerException.class, () -> nullOpener.toNewWindow(main, 1));
    assertEquals(List.of(a, b), main.tabs(), "failed opener leaves source group untouched");
    assertEquals(1, nullOpener.frames().size());

    // Split, move activates group, then closing removes the split group and rejects further calls.
    Group<String> right = nullOpener.split(main, 1, main, Side.RIGHT);
    nullOpener.add(main, 1, "c");
    nullOpener.select(right, 0);
    assertSame(right, nullOpener.main().active());
    nullOpener.move(main, 0, 1);
    assertSame(main, nullOpener.main().active(), "move activates its group");
    nullOpener.close(right, 0);
    assertThrows(IllegalArgumentException.class, () -> nullOpener.select(right, 0));
    assertThrows(IllegalArgumentException.class, () -> nullOpener.close(right, 0));
    assertThrows(IllegalArgumentException.class, () -> nullOpener.move(right, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> nullOpener.toNewWindow(right, 0));

    // toNewWindow on an alone detached group is a no-op.
    App<Docs> app = start();
    Docs d = app.state();
    Script.run("size 400 160 at 100,100\nframe", app, out);
    Group<String> lone = d.groups.toNewWindow(d.groups.main().active(), 1);
    assertSame(lone, d.groups.toNewWindow(lone, 0));
    assertEquals(2, d.groups.frames().size());

    // Invalid dock destination throws before mutating or closing source group.
    assertThrows(
        IndexOutOfBoundsException.class,
        () -> d.groups.dock(lone, 0, d.groups.main().active(), -1));
    assertThrows(
        IndexOutOfBoundsException.class,
        () -> d.groups.dock(lone, 0, d.groups.main().active(), 99));
    assertThrows(
        IndexOutOfBoundsException.class,
        () -> d.groups.dock(d.groups.main().active(), 0, d.groups.main().active(), -1));
    assertEquals(List.of("b"), lone.tabs(), "source group unchanged after invalid dock");
    assertTrue(lone.window().isOpen(), "source window stayed open after invalid dock");

    // Opener inserting into source group at index 0 must not drop the wrong tab or duplicate it.
    class MutatingOpener {
      App<MutatingOpener> app;
      final TabGroups<String> groups = new TabGroups<>(List.of("a", "b"), this::open);

      Windows.Handle open(Frame<String> frame, Window.Options options) {
        groups.add(groups.main().active(), 0, "c");
        return app.windows()
            .open(
                frame.active().activeTab(), 300, 160, options, new App<>(this, s -> s.view(frame)));
      }

      El view(Frame<String> frame) {
        return col()
            .wFull()
            .hFull()
            .child(groups
                .tabs(frame, Tab::new, t -> t == null ? null : Label.label(t))
                .grow(1));
      }
    }
    MutatingOpener m = new MutatingOpener();
    m.app = new App<>(m, s -> s.view(s.groups.main()));
    Script.run("size 400 160 at 100,100\nframe", m.app, out);
    m.groups.toNewWindow(m.groups.main().active(), 1);
    assertEquals(List.of("c", "a"), m.groups.main().active().tabs());
    assertEquals(List.of("b"), m.groups.frames().get(1).active().tabs());

    // Opener cannot re-add the tab being transferred, and rollback survives a pruned source group.
    TabGroups<String>[] holder = new TabGroups[1];
    holder[0] = new TabGroups<>(List.of("a", "b", "c"), (frame, options) -> {
      assertThrows(
          IllegalArgumentException.class,
          () -> holder[0].add(holder[0].main().active(), 0, frame.active().activeTab()));
      // Close the other tab in the source split group so the source leaf is pruned, then fail.
      Group<String> src = holder[0].groupOf("b");
      holder[0].close(src, 0);
      return null;
    });
    Group<String> leftSplit = holder[0].main().active();
    Group<String> rightSplit = holder[0].split(leftSplit, 1, leftSplit, Side.RIGHT);
    holder[0].dock(leftSplit, 1, rightSplit, 1); // left=[a], right=[b, c]
    assertThrows(NullPointerException.class, () -> holder[0].toNewWindow(rightSplit, 1));
    assertEquals(List.of("a", "c"), leftSplit.tabs(), "rollback rescues c into surviving group");

    // Programmatic split followed by immediate dock back keeps measured size for UI splits.
    App<Docs> splitBack = start();
    splitBack.state().groups.splits(true);
    Script.run("size 400 160\nframe", splitBack, out);
    Group<String> g0 = splitBack.state().groups.main().active();
    Group<String> g1 = splitBack.state().groups.split(g0, 1, g0, Side.RIGHT);
    splitBack.state().groups.dock(g1, 0, g0, 1);
    Script.run("size 400 160\nfocus #tablist\nkey \\ control\nframe", splitBack, out);
    assertEquals(2, splitBack.state().groups.main().groups().size());
  }

  @Test
  void detachedWindowWithLeadingLineTabsTracksOwnTabBarOnMove() throws IOException {
    class WithHeaderTabs {
      App<WithHeaderTabs> app;
      final TabGroups<String> groups = new TabGroups<>(List.of("a", "b"), this::open);

      Windows.Handle open(Frame<String> frame, Window.Options options) {
        return app.windows()
            .open(
                frame.active().activeTab(), 300, 200, options, new App<>(this, s -> s.view(frame)));
      }

      El view(Frame<String> frame) {
        El header = Tabs.tabs(
            List.of(new Tab("Header", false, false)),
            0,
            i -> {},
            null,
            null,
            Tabs.options().variant(Tabs.Variant.LINE));
        return col()
            .wFull()
            .hFull()
            .children(
                header.h(40),
                groups
                    .tabs(frame, Tab::new, t -> t == null ? null : Label.label(t))
                    .grow(1));
      }
    }
    WithHeaderTabs state = new WithHeaderTabs();
    state.app = new App<>(state, s -> s.view(s.groups.main()));
    Script.run("size 400 200 at 100,100\nframe", state.app, out);
    Group<String> detached = state.groups.toNewWindow(state.groups.main().active(), 1);
    Script.run("""
        window b
        size 300 200 at 500,100
        frame
        down role=tab label="b" at left+5
        move screen 600,250
        frame
        up screen 600,250
        frame""", state.app, out);
    // Group's own tab sits below the 40px header (y=41, height=32, center=57), not at y=0.
    // Dragging its vertical center to screen y=250 places the window at 250 - 57 = 193.
    Windows.Handle w = detached.window();
    assertEquals(
        193,
        w.y(),
        2,
        "window positioned by TabGroups' own tab bar below the 40px header, y=" + w.y());
  }

  @Test
  void singleWindowConstructorSplitsWithoutTearOffOrWindowMenuItems() throws IOException {
    TabGroups<String> groups = new TabGroups<>(List.of("a", "b", "c")).splits(true);
    App<Object> app = new App<>(new Object(), s -> {
      El tabs = groups.tabs(groups.main(), Tab::new, t -> t == null ? null : Label.label(t));
      return col().wFull().hFull().onKey(tabs.onKey()).child(tabs.grow(1));
    });
    Script.run("""
        size 400 160 at 100,100
        frame
        down role=tab label="b" at left+5
        move 80,17
        frame
        move screen 600,400
        frame
        up screen 600,400
        frame
        press #tab-1 right
        frame
        advance 1
        frame
        snap single-menu
        key Escape
        frame""", app, out);
    assertEquals(1, groups.frames().size(), "no window opened without an opener");
    assertEquals(List.of("a", "b", "c"), groups.main().active().tabs());
    String menu = tree("single-menu");
    assertTrue(menu.contains("label=\"Split right\""), menu);
    assertFalse(menu.contains("Move to new window"), menu);
    assertFalse(menu.contains("Move back to main window"), menu);
    assertThrows(
        IllegalStateException.class, () -> groups.toNewWindow(groups.main().active(), 0));
  }

  @Test
  void tabMenuCloseActionsRespectClosableAndOnCloseTabAndSupportCustomItems() throws IOException {
    List<String> closeRequests = new ArrayList<>();
    List<String> duplicated = new ArrayList<>();
    int[] menuCalls = {0};
    TabGroups<String> groups = new TabGroups<>(List.of("pin", "a", "b", "c"));
    groups
        .onCloseTab((group, index) -> {
          String tab = group.tabs().get(index);
          closeRequests.add(tab);
          if (!tab.equals("keep")) {
            groups.close(group, index);
          }
        })
        .tabMenu((group, tab) -> {
          menuCalls[0]++;
          if (tab.equals("pin")) {
            return List.of(Menu.separator(), Menu.separator());
          }
          return List.of(
              Menu.separator(),
              Menu.item("Duplicate " + tab, () -> duplicated.add(tab)),
              Menu.separator());
        });
    App<Object> app = new App<>(new Object(), s -> {
      El tabs = groups.tabs(
          groups.main(),
          t -> new Tab(t, !t.equals("pin"), false),
          t -> t == null ? null : Label.label(t));
      return col().wFull().hFull().onKey(tabs.onKey()).child(tabs.grow(1));
    });
    Script.run("size 500 160\nframe", app, out);
    assertEquals(0, menuCalls[0], "tabMenu is not called while menu is closed");

    // On non-closable "pin": Close is disabled, Close others and Close to the right are enabled,
    // and separator-only custom items add no divider at all (exactly 3 menuitem children).
    Script.run("""
        size 500 160
        press #tab-0 right
        frame
        advance 1
        frame
        snap pin-menu""", app, out);
    String pinMenu = tree("pin-menu");
    assertTrue(line(pinMenu, "label=\"Close\"").contains("disabled"), pinMenu);
    assertFalse(line(pinMenu, "label=\"Close others\"").contains("disabled"), pinMenu);
    assertFalse(line(pinMenu, "label=\"Close to the right\"").contains("disabled"), pinMenu);
    El menuEl = findOverlay(app.tree(), "menu");
    assertNotNull(menuEl, pinMenu);
    assertEquals(3, menuEl.children().size(), "separator-only custom list adds no divider");
    assertTrue(menuEl.children().stream().allMatch(c -> "menuitem".equals(c.role())));
    Script.run("size 500 160\nkey Escape\nframe", app, out);

    // On "a" (index 1): boundary separators in custom items are trimmed (3 built-in + 1 sep + 1).
    Script.run("""
        size 500 160
        press #tab-1 right
        frame
        advance 1
        frame""", app, out);
    El aMenu = findOverlay(app.tree(), "menu");
    assertNotNull(aMenu);
    assertEquals(5, aMenu.children().size(), "exactly one divider before trimmed custom item");
    assertNull(aMenu.children().get(3).role(), "single separator between built-in and custom");
    assertEquals("menuitem", aMenu.children().get(4).role());
    Script.run("size 500 160\npress label=\"Duplicate a\"\nframe", app, out);
    assertEquals(List.of("a"), duplicated);

    Script.run("""
        size 500 160
        press #tab-1 right
        frame
        advance 1
        frame
        press label="Close to the right"
        frame""", app, out);
    assertEquals(List.of("b", "c"), closeRequests);
    assertEquals(List.of("pin", "a"), groups.main().active().tabs());

    // Add "keep" (deferred by onCloseTab) and "d"; without onCloseTabs, Close others stops on
    // "keep" so a single-dialog confirmation handler is not overwritten.
    groups.add(groups.main().active(), 2, "keep");
    groups.add(groups.main().active(), 3, "d");
    app.invalidate();
    closeRequests.clear();
    Script.run("""
        size 500 160
        frame
        press role=tab label="a" right
        frame
        advance 1
        frame
        press label="Close others"
        frame""", app, out);
    assertEquals(List.of("keep"), closeRequests, "stops on first deferred tab");
    assertEquals(List.of("pin", "a", "keep", "d"), groups.main().active().tabs());

    // With onCloseTabs, bulk close delivers the whole closable batch in one callback.
    List<List<String>> batches = new ArrayList<>();
    groups.onCloseTabs((group, batch) -> {
      batches.add(batch);
      for (String item : batch) {
        if (!item.equals("keep")) {
          groups.close(group, group.tabs().indexOf(item));
        }
      }
    });
    Script.run("""
        size 500 160
        press role=tab label="a" right
        frame
        advance 1
        frame
        press label="Close others"
        frame""", app, out);
    assertEquals(List.of(List.of("keep", "d")), batches);
    assertEquals(List.of("pin", "a", "keep"), groups.main().active().tabs());

    // Close on "a" closes only "a".
    closeRequests.clear();
    Script.run("""
        size 500 160
        press role=tab label="a" right
        frame
        advance 1
        frame
        press label="Close"
        frame""", app, out);
    assertEquals(List.of("a"), closeRequests);
    assertEquals(List.of("pin", "keep"), groups.main().active().tabs());
  }

  private static El findOverlay(El root, String key) {
    for (El overlay : root.shownOverlays()) {
      El found = find(overlay, key);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  @Test
  void dockAllToTargetGroupMergesInsideDetachedWindow() throws IOException {
    App<Docs> app = start();
    Docs d = app.state();
    d.groups.splits(true);
    Script.run("size 400 160 at 100,100\nframe", app, out);
    Group<String> detached = d.groups.toNewWindow(d.groups.main().active(), 1); // [b]
    d.groups.dock(d.groups.main().active(), 1, detached, 1); // [b, c]
    Group<String> split = d.groups.split(detached, 1, detached, Side.RIGHT); // [b] | [c]
    d.groups.dockAll(split, detached);
    assertEquals(2, d.groups.frames().size(), "detached window stays open");
    assertEquals(List.of(detached), detached.frame().groups());
    assertEquals(List.of("b", "c"), detached.tabs());
    assertEquals(List.of("a", "d"), d.groups.main().active().tabs());
    assertThrows(
        IllegalArgumentException.class,
        () -> d.groups.dockAll(split, d.groups.main().active()));
    // Repeating 1-arg dockAll on an already emptied/pruned group remains a no-op.
    d.groups.dockAll(split);
  }

  @Test
  void keyboardShortcutsAndFocusGroupSwitchBetweenSplitGroups() throws IOException {
    List<String> unhandled = new ArrayList<>();
    TabGroups<String> groups = new TabGroups<>(List.of("a", "b", "c")).splits(true);
    App<Object> app = new App<>(new Object(), s -> {
      El tabs = groups.tabs(groups.main(), Tab::new, t -> t == null ? null : Label.label(t));
      return col()
          .wFull()
          .hFull()
          .onKey(k -> {
            if (tabs.onKey().test(k)) {
              return true;
            }
            unhandled.add(k.name());
            return false;
          })
          .child(tabs.grow(1));
    });
    // Unsplit: Ctrl+1 and F6 bubble through unhandled.
    Script.run("size 600 200\nframe\nfocus #tablist\nkey 1 control\nkey F6\nframe", app, out);
    assertEquals(List.of("1", "F6"), unhandled);
    unhandled.clear();

    Group<String> g0 = groups.main().active();
    Group<String> g1 = groups.split(g0, 1, g0, Side.RIGHT); // g0=[a,c], g1=[b]
    Group<String> g2 = groups.split(g0, 1, g1, Side.RIGHT); // g0=[a], g1=[b], g2=[c]
    app.invalidate();
    Script.run("size 600 200\nframe", app, out);
    assertSame(g2, groups.main().active());

    // Ctrl+1 jumps to first group, Ctrl+2 to second, Ctrl+4 (out of range) bubbles.
    Script.run("size 600 200\nkey 1 control\nframe", app, out);
    assertSame(g0, groups.main().active());
    assertSame(find(find(app.tree(), "group-0"), "tablist"), app.focused());

    Script.run("size 600 200\nkey 2 control\nframe", app, out);
    assertSame(g1, groups.main().active());
    assertSame(find(find(app.tree(), "group-1"), "tablist"), app.focused());

    Script.run("size 600 200\nkey 4 control\nframe", app, out);
    assertEquals(List.of("4"), unhandled);
    assertSame(g1, groups.main().active());

    // F6 cycles forward around the end, Shift+F6 cycles backward.
    Script.run("size 600 200\nkey F6\nframe", app, out);
    assertSame(g2, groups.main().active());
    Script.run("size 600 200\nkey F6\nframe", app, out);
    assertSame(g0, groups.main().active());
    Script.run("size 600 200\nkey F6 shift\nframe", app, out);
    assertSame(g2, groups.main().active());

    // Programmatic focusGroup activates and focuses target group, rejects closed group.
    groups.focusGroup(g0);
    app.invalidate();
    Script.run("size 600 200\nframe", app, out);
    assertSame(g0, groups.main().active());
    assertSame(find(find(app.tree(), "group-0"), "tablist"), app.focused());
    groups.close(g2, 0);
    assertThrows(IllegalArgumentException.class, () -> groups.focusGroup(g2));

    // F6 from an active empty group in a split moves to the sole non-empty sibling.
    App<Docs> withEmpty = start();
    Docs wd = withEmpty.state();
    wd.groups.splits(true);
    Script.run("size 400 160 at 100,100\nframe", withEmpty, out);
    Group<String> ext = wd.groups.toNewWindow(wd.groups.main().active(), 0);
    wd.groups.closeAll(wd.groups.main());
    Group<String> emptyMain = wd.groups.main().active();
    Group<String> nonEmptySibling = wd.groups.split(ext, 0, emptyMain, Side.RIGHT);
    wd.groups.focusGroup(emptyMain);
    withEmpty.invalidate();
    Script.run("size 400 160\nframe\nkey F6\nframe", withEmpty, out);
    assertSame(nonEmptySibling, wd.groups.main().active());
    wd.groups.focusGroup(emptyMain);
    withEmpty.invalidate();
    Script.run("size 400 160\nframe\nkey F6 shift\nframe", withEmpty, out);
    assertSame(nonEmptySibling, wd.groups.main().active());
  }
}
