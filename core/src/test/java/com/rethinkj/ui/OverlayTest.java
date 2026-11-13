package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.motion.Spring;
import com.rethinkj.ui.render.Scene;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Overlays ({@link El#overlay}): placement beside the anchor, drawing above the tree, presses that
 * dismiss, modal overlays, the focus as they open and close, keys, the secondary button, the
 * delayed looks and handlers of hovering, presence and the tree dump. Boxes only, no text.
 */
class OverlayTest {

  @TempDir Path out;

  /** The state of the views: whether things are open, and what the handlers did. */
  static final class State {
    boolean open;
    boolean sub;
    final List<String> log = new ArrayList<>();
  }

  private static App<State> app(State s, Function<State, El> view, float width, float height) {
    App<State> app = new App<>(s, view);
    app.resize(width, height, 1);
    app.frame();
    return app;
  }

  private static El find(App<?> app, String key) {
    El found = find(app.tree(), key);
    if (found == null) {
      for (El overlay : app.tree().shownOverlays()) {
        found = found != null ? found : find(overlay, key);
      }
    }
    return found;
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

  private static void press(App<?> app, float x, float y) {
    app.pointerDown(x, y);
    app.pointerUp(x, y);
    app.frame();
  }

  /** Builds the view again, as after a change of the state that no handler made. */
  private static void rebuild(App<?> app) {
    app.resize(200, 200, 1);
    app.frame();
  }

  private static void key(App<?> app, String name, int modifiers) {
    app.keyDown(new Key(name, modifiers, false));
    app.frame();
  }

  /** A 60 by 50 box beside a 40 by 20 anchor at x, y in a window of 200 by 200. */
  private static float[] placed(float x, float y, Function<El, El> place) {
    El overlay = place.apply(el().key("pop").size(60, 50));
    App<State> app = app(new State(), s -> el().size(200, 200)
        .child(el().key("anchor").absolute(x, y).size(40, 20).overlay(overlay)), 200, 200);
    El pop = find(app, "pop");
    return new float[] {pop.x(), pop.y(), pop.width(), pop.height()};
  }

  private static void assertRect(float[] expected, float[] actual) {
    assertEquals(List.of(expected[0], expected[1], expected[2], expected[3]),
        List.of(actual[0], actual[1], actual[2], actual[3]));
  }

  @Test
  void anOverlayGoesBesideItsAnchorAndFlipsShiftsOrShrinksWhereItDoesNotFit() {
    // Below, at the start, 4 away.
    assertRect(new float[] {10, 34, 60, 50},
        placed(10, 10, o -> o.side(Side.BOTTOM, Style.Align.START, 4)));
    // No room below: above.
    assertRect(new float[] {10, 96, 60, 50},
        placed(10, 150, o -> o.side(Side.BOTTOM, Style.Align.START, 4)));
    // No room at the start: aligned with the anchor's end.
    assertRect(new float[] {130, 34, 60, 50},
        placed(150, 10, o -> o.side(Side.BOTTOM, Style.Align.START, 4)));
    // Centred along the side, and moved into the window where it sticks out.
    assertRect(new float[] {0, 34, 60, 50},
        placed(0, 10, o -> o.side(Side.BOTTOM, Style.Align.CENTER, 4)));
    // To the right, or the left where the right has no room.
    assertRect(new float[] {54, 10, 60, 50},
        placed(10, 10, o -> o.side(Side.RIGHT, Style.Align.START, 4)));
    assertRect(new float[] {106, 10, 60, 50},
        placed(170, 10, o -> o.side(Side.RIGHT, Style.Align.START, 4)));
    // Room on neither side: as near as it fits, over the anchor.
    assertRect(new float[] {10, 150, 60, 50},
        placed(10, 90, o -> o.size(60, 50).side(Side.BOTTOM, Style.Align.START, 200)));
    // At a point, flipped up and to the left in the corner.
    assertRect(new float[] {135, 145, 60, 50}, placed(10, 10, o -> o.atPoint(195, 195)));
    // In the middle of the window.
    assertRect(new float[] {70, 75, 60, 50}, placed(10, 10, El::centerInWindow));
    // At least as wide as the anchor.
    assertRect(new float[] {10, 30, 40, 50},
        placed(10, 10, o -> o.w(20).side(Side.BOTTOM, Style.Align.STRETCH, 0)));
  }

  @Test
  void anOverlayLargerThanTheWindowIsLimitedToItAndScrolls() {
    El big = col().key("big").scroll().child(el().size(50, 500));
    App<State> app = app(new State(), s -> el().size(200, 200)
        .child(el().absolute(10, 10).size(40, 20).overlay(big)), 200, 200);
    El shown = find(app, "big");
    assertEquals(0, shown.y());
    assertEquals(200, shown.height());
    app.wheel(20, 100, 0, 120);
    app.frame();
    assertEquals(120, shown.scrollY());
  }

  /** Ten rows in a scroll area at 20,20 of 100 by 60; row 1 has a box of 80 by 40 below it. */
  private static El scrolled(State s) {
    El area = col().key("area").absolute(20, 20).size(100, 60).scroll().radius(8);
    for (int i = 0; i < 10; i++) {
      El r = el().key("row-" + i).size(100, 20).fill(0xFF333333);
      if (i == 1) {
        r.overlay(el().key("pop").size(80, 40).fill(0xFF3B82F6).radius(6));
      }
      area.child(r);
    }
    return el().size(200, 200).fill(0xFF101010).children(area,
        el().key("later").absolute(0, 150).size(200, 50).fill(0xFF22C55E));
  }

  @Test
  void anOverlayFollowsItsScrolledAnchorIsDrawnLastWithoutItsClipAndHidesWithIt() {
    App<State> app = app(new State(), OverlayTest::scrolled, 200, 200);
    El pop = find(app, "pop");
    assertEquals(20, pop.x());
    assertEquals(60, pop.y(), "below row 1, across the bottom of the area's clip");
    Scene scene = app.scene();
    int last = scene.count() - 1;
    assertEquals(20, scene.get(last, Scene.X));
    assertEquals(60, scene.get(last, Scene.Y));
    assertEquals(0, scene.clipOf(last), "free of the area's clip, after the box drawn later");

    app.wheel(50, 50, 0, 30);
    app.frame();
    assertEquals(30, find(app, "pop").y(), "follows the row as shown");

    app.wheel(110, 50, 0, 40); // beside the box; row 1 ends at -10, above the area: clipped away
    app.frame();
    assertTrue(app.tree().shownOverlays().isEmpty());
    assertFalse(app.render().tree().contains("overlay"));
  }

  /** A menu button, another button and, while open, a menu below the first with one item. */
  private static El menuButton(State s) {
    El open = el().key("open").size(40, 20).onPress(() -> {
      s.log.add("open");
      s.open = !s.open;
    });
    if (s.open) {
      El item = el().key("item").size(60, 20).onPress(() -> s.log.add("item"));
      if (s.sub) {
        item.overlay(el().key("sub").size(50, 30).side(Side.RIGHT, Style.Align.START, 0)
            .onDismiss(() -> {
              s.log.add("sub dismissed");
              s.sub = false;
            })
            .child(el().key("subitem").size(50, 30).onPress(() -> s.log.add("subitem"))));
      }
      open.overlay(col().key("menu").padding(5).onDismiss(() -> {
        s.log.add("dismissed");
        s.open = false;
        s.sub = false;
      }).children(item, el().key("second").size(60, 20).onPress(() -> s.log.add("second"))));
    }
    return row().size(200, 200).gap(20).children(open,
        el().key("other").size(40, 20).onPress(() -> s.log.add("other")));
  }

  @Test
  void aPressOutsideDismissesAndGoesNoFurtherAndOneInsideGoesToTheOverlay() {
    State s = new State();
    App<State> app = app(s, OverlayTest::menuButton, 200, 200);
    press(app, 20, 10);
    assertTrue(s.open);
    // The item lies over the other button's column? No: below both; the menu is at 0,20.
    press(app, 30, 35);
    assertEquals(List.of("open", "item"), s.log);
    s.log.clear();

    press(app, 80, 10); // the other button
    assertEquals(List.of("dismissed"), s.log, "the press closes the menu and does nothing else");
    assertFalse(s.open);
    s.log.clear();

    press(app, 20, 10);
    press(app, 20, 10); // the menu button again: closes, does not open again
    assertEquals(List.of("open", "dismissed"), s.log);
    assertFalse(s.open);
  }

  @Test
  void aPressInAMenuDismissesItsSubmenuAndCountsThere() {
    State s = new State();
    s.open = true;
    s.sub = true;
    App<State> app = app(s, OverlayTest::menuButton, 200, 200);
    assertEquals(2, app.tree().shownOverlays().size());
    assertEquals("sub", app.tree().shownOverlays().get(1).key(), "the submenu above its menu");
    press(app, 30, 55); // the second item, below the submenu
    assertEquals(List.of("sub dismissed", "second"), s.log);

    s.sub = true;
    s.log.clear();
    rebuild(app);
    press(app, 150, 150);
    assertEquals(List.of("sub dismissed", "dismissed"), s.log, "the topmost first");
    assertTrue(app.tree().shownOverlays().isEmpty());
  }

  @Test
  void theWheelOutsideDismissesAndScrollsButNotOutsideAModalOverlay() {
    State s = new State();
    s.open = true;
    App<State> app = app(s, st -> {
      El area = scrolled(st).children().getFirst();
      if (st.open) {
        area.overlay(el().key("menu").size(50, 30).onDismiss(() -> {
          st.log.add("dismissed");
          st.open = false;
        }).atPoint(150, 20));
      }
      return el().size(200, 200).child(area);
    }, 200, 200);
    app.wheel(50, 50, 0, 20);
    app.frame();
    assertEquals(List.of("dismissed"), s.log);
    assertEquals(20, find(app, "area").scrollY(), "and scrolls");
  }

  /** A box with a hover look and a press handler behind a modal dialog with two buttons. */
  private static El modal(State s) {
    El root = col().size(200, 200).onKey(k -> {
      s.log.add("root " + k.name());
      return true;
    }).children(
        el().key("behind").size(60, 20).focusable().onPress(() -> s.log.add("behind"))
            .whenHovered(e -> e.fill(0xFF888888)),
        col().key("area").size(100, 40).scroll().child(el().size(100, 200)));
    if (s.open) {
      root.overlay(col().key("dialog").centerInWindow().modal().scrim(0x80000000).padding(10)
          .gap(10).onDismiss(() -> s.log.add("dismissed")).children(
              el().key("ok").size(40, 20).focusable().onPress(() -> s.log.add("ok")),
              el().key("cancel").size(40, 20).focusable()));
    }
    return root;
  }

  @Test
  void aModalOverlayTakesThePointerTheWheelTheFocusAndTheKeys() {
    State s = new State();
    s.open = true;
    App<State> app = app(s, OverlayTest::modal, 200, 200);
    El behind = find(app, "behind");
    app.pointerMove(20, 10);
    app.frame();
    assertNull(app.hovered(), "nothing behind it is hovered");
    press(app, 20, 10);
    assertEquals(List.of("dismissed"), s.log, "a press behind it only dismisses it");
    app.wheel(50, 40, 0, 50);
    app.frame();
    assertEquals(0, find(app, "area").scrollY(), "the wheel behind it does nothing");
    assertEquals(List.of("dismissed"), s.log, "and dismisses nothing");
    assertThrows(IllegalArgumentException.class, () -> app.focus(find(app, "behind")));
    assertEquals("ok", app.focused().key(), "it took the focus as it opened");
    key(app, "Tab", 0);
    assertEquals("cancel", app.focused().key());
    key(app, "Tab", 0);
    assertEquals("ok", app.focused().key(), "around inside it");
    key(app, "Tab", Key.SHIFT);
    assertEquals("cancel", app.focused().key());
    key(app, "X", 0);
    assertEquals(List.of("dismissed"), s.log, "keys end at the dialog: the root sees none");
    assertTrue(app.render().tree().contains("\noverlay center modal scrim on 0,0 200x200\n"));
    assertFalse(behind.isExiting());
  }

  /** A focusable opener, a focusable element after it, and an overlay of the opener. */
  private static El opener(State s) {
    El open = el().key("opener").size(40, 20).focusable().onPress(() -> s.open = true);
    if (s.open) {
      open.overlay(col().key("pop").onKey(k -> {
        s.log.add("pop " + k.name());
        if (k.name().equals("Escape")) {
          s.open = false;
          return true;
        }
        return false;
      }).children(
          el().key("first").size(40, 20).focusable(),
          el().key("second").size(40, 20).focusable()));
    }
    return row().size(200, 200).gap(10).onKey(k -> {
      s.log.add("root " + k.name());
      return false;
    }).children(
        row().key("anchors").onKey(k -> {
          s.log.add("anchors " + k.name());
          return false;
        }).child(open),
        el().key("after").size(40, 20).focusable());
  }

  @Test
  void anOverlayTakesTheFocusAsItOpensAndGivesItBackAsItCloses() {
    State s = new State();
    App<State> app = app(s, OverlayTest::opener, 200, 200);
    key(app, "Tab", 0);
    assertEquals("opener", app.focused().key());
    key(app, "Enter", 0);
    assertEquals("first", app.focused().key(), "its first focusable element");
    assertEquals(0, app.scene().count(), "without a ring, the only instance there could be");
    key(app, "Tab", 0);
    assertEquals("second", app.focused().key());
    key(app, "Tab", 0);
    assertEquals("after", app.focused().key(), "Tab goes on after the overlay's anchor");
    key(app, "Tab", Key.SHIFT);
    key(app, "Tab", Key.SHIFT);
    key(app, "Tab", Key.SHIFT);
    assertEquals("opener", app.focused().key(), "and back through it");
    key(app, "Tab", 0);
    s.log.clear();
    key(app, "F5", 0);
    assertEquals(List.of("pop F5", "anchors F5", "root F5"), s.log,
        "keys go from the overlay on to its anchor's ancestors");
    key(app, "Escape", 0);
    assertFalse(s.open);
    assertEquals("opener", app.focused().key(), "back where it was before");
    assertEquals(1, app.scene().count(), "with the ring it had");
  }

  @Test
  void theFocusGoesBackOnlyWhileItIsStillInsideTheOverlayOrNowhere() {
    State s = new State();
    App<State> app = app(s, OverlayTest::opener, 200, 200);
    press(app, 20, 10);
    assertEquals("first", app.focused().key());
    app.focus(find(app, "after"));
    app.frame();
    s.open = false;
    rebuild(app);
    assertEquals("after", app.focused().key(), "moved away before it closed: stays");
  }

  @Test
  void theSecondaryButtonAndTheMenuKeyRunTheNearestHandlerWithAPosition() throws Exception {
    List<String> log = new ArrayList<>();
    App<List<String>> app = new App<>(log, l -> col().size(200, 200)
        .onSecondaryPress((x, y) -> l.add("root " + x + "," + y))
        .children(
            el().key("row").size(100, 20).focusable().onPress(() -> l.add("press"))
                .onSecondaryPress((x, y) -> l.add("row " + x + "," + y)),
            el().key("plain").size(100, 20)));
    app.resize(200, 200, 1);
    app.frame();
    app.secondaryDown(30, 10);
    app.secondaryUp(32, 12);
    app.frame();
    assertEquals(List.of("row 32.0,12.0"), log, "with the release's position, and no press");
    assertEquals("row", app.focused().key(), "and it focuses as a press does");
    app.secondaryDown(30, 30);
    app.secondaryUp(30, 10); // released on another element: nothing
    app.frame();
    Script.run("size 200 200\nframe\npress #plain right\nframe\n", app, out);
    assertEquals(List.of("row 32.0,12.0", "root 50.0,30.0"), log);
    log.clear();
    app.focus(find(app, "row"));
    app.frame();
    key(app, "ContextMenu", 0);
    key(app, "F10", Key.SHIFT);
    assertEquals(List.of("row 0.0,20.0", "row 0.0,20.0"), log, "at its bottom left");
    assertThrows(Script.Failure.class,
        () -> Script.run("press #row right clicks 2\n", app, out));
  }

  @Test
  void aLookAfterAStayShowsATooltipWhenTheClockSaysAndAPressHidesIt() {
    State s = new State();
    App<State> app = app(s, st -> el().size(200, 200).child(el().key("anchor").size(40, 20)
        .whenHoveredFor(0.5, e -> e.overlay(el().key("tip").size(60, 16)
            .side(Side.BOTTOM, Style.Align.START, 4)))), 200, 200);
    assertEquals(Double.POSITIVE_INFINITY, app.nextChange());
    app.pointerMove(10, 10);
    app.frame();
    assertEquals(0.5, app.nextChange(), "when the look is due, for an idle window to wait");
    app.advance(0.4);
    assertFalse(app.frame(), "nothing new to draw while it waits");
    assertTrue(app.tree().shownOverlays().isEmpty());
    app.advance(0.1);
    assertTrue(app.frame());
    assertEquals("tip", app.tree().shownOverlays().getFirst().key());
    assertEquals(24, find(app, "tip").y());
    assertEquals(Double.POSITIVE_INFINITY, app.nextChange());

    app.pointerDown(10, 10);
    app.pointerUp(10, 10);
    app.frame();
    assertTrue(app.tree().shownOverlays().isEmpty(), "a press hides it");
    app.advance(2);
    app.frame();
    assertTrue(app.tree().shownOverlays().isEmpty(), "until the pointer comes back");
    app.pointerMove(100, 100);
    app.frame();
    app.pointerMove(10, 10);
    app.frame();
    assertEquals(app.time() + 0.5, app.nextChange());
  }

  @Test
  void aStayInARowGoesOnThroughAFrameWhoseKeysBuildTheViewBetweenThem() {
    State s = new State();
    App<State> app = app(s, st -> el().size(200, 200).focusable().onKey(k -> {
      st.log.add(k.name());
      return true;
    }).child(col().key("list").size(100, 100).rows(10, 20, i -> el().key("cell")
        .whenHoveredFor(0.5, e -> e.overlay(el().key("tip").size(60, 16))))), 200, 200);
    app.focus(app.tree());
    app.pointerMove(10, 30); // row 1
    app.frame();
    app.advance(0.3);
    app.keyDown(new Key("A")); // taken: the view is built again before the next key, rows later
    app.keyDown(new Key("B"));
    app.frame();
    assertEquals(List.of("A", "B"), s.log);
    assertEquals(0.5, app.nextChange(), 1e-9, "the stay began at 0, not with the keys");
    app.advance(0.2);
    app.frame();
    assertEquals("tip", app.tree().shownOverlays().getFirst().key());
  }

  @Test
  void aHandlerAfterAStayRunsOnceAndRebuildsTheView() {
    State s = new State();
    App<State> app = app(s, st -> el().size(200, 200).children(
        el().key("now").size(40, 20).onHoverFor(0, () -> st.log.add("now")),
        el().key("later").absolute(100, 0).size(40, 20).onHoverFor(0.3, () -> {
          st.log.add("later");
          st.open = true;
        }).overlay(st.open ? el().key("opened").size(10, 10) : null)), 200, 200);
    app.pointerMove(10, 10);
    app.frame();
    app.pointerMove(12, 10);
    app.frame();
    assertEquals(List.of("now"), s.log, "once per stay");
    app.pointerMove(110, 10);
    app.frame();
    assertEquals(0.3, app.nextChange(), 1e-9);
    app.advance(0.3);
    assertTrue(app.frame());
    assertEquals(List.of("now", "later"), s.log);
    assertEquals("opened", app.tree().shownOverlays().getFirst().key(), "in the same frame");
  }

  @Test
  void anOverlayWithAKeyAndMotionFadesInAndOutAndTakesNoInputMeanwhile() {
    State s = new State();
    App<State> app = app(s, st -> el().size(200, 200).child(el().key("anchor").size(40, 20)
        .overlay(st.open ? el().key("pop").size(60, 40).fill(0xFFFFFFFF).motion(Spring.SNAP)
            .onPress(() -> st.log.add("pop")) : null)), 200, 200);
    s.open = true;
    rebuild(app);
    El pop = find(app, "pop");
    assertEquals(0, pop.opacity(), "it fades in");
    assertEquals(20, pop.y(), "where it belongs, from the first frame");
    app.advance(1);
    app.frame();
    assertEquals(1, find(app, "pop").opacity());
    s.open = false;
    rebuild(app);
    app.advance(0.05);
    app.frame();
    String tree = app.render().tree();
    assertTrue(tree.contains("\n  el #pop 0,20 60x40 exiting\n"), tree);
    press(app, 10, 30);
    assertEquals(List.of(), s.log, "a leaving overlay takes no input");
    app.advance(1);
    app.frame();
    assertTrue(app.tree().shownOverlays().isEmpty(), "gone once invisible");
  }

  @Test
  void theDumpListsOverlaysAfterTheTreeWithTheirAnchor() {
    State s = new State();
    s.open = true;
    s.sub = true;
    App<State> app = app(s, OverlayTest::menuButton, 200, 200);
    assertEquals("""
        el 0,0 200x200
          el #open 0,0 40x20
          el #other 60,0 40x20
        overlay bottom start on #open 0,0 40x20
          el #menu 0,20 70x50
            el #item 5,25 60x20
            el #second 5,45 60x20
        overlay right start on #item 5,25 60x20
          el #sub 65,25 50x30
            el #subitem 65,25 50x30
        """, app.render().tree());
    assertEquals(Headless.render(OverlayTest.menuButton(s), 200, 200, 1).tree(),
        app.render().tree(), "Headless places them as the app does");
  }
}
