package com.rethinkj.ui;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.motion.Spring;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The pointer's position: what it hovers, the looks of hovered, focused and pressed elements. */
class PointerTest {

  private static final int GREY = 0xFF808080;
  private static final int LIGHT = 0xFFC0C0C0;
  private static final int DARK = 0xFF404040;
  private static final int BLUE = 0xFF3B82F6;

  /** A plain box at 0,0 and a card at 50,0 with a box inside at its top left; 200 by 100. */
  private static final class Cards {
    int builds;
  }

  private static App<Cards> cards() {
    App<Cards> app = new App<>(new Cards(), state -> {
      state.builds++;
      return row().children(
          el().key("plain").size(50, 50).fill(GREY),
          el().key("card").size(100, 100).fill(GREY).whenHovered(e -> e.fill(LIGHT).radius(8))
              .child(el().key("inner").size(40, 40).fill(GREY)));
    });
    app.resize(200, 100, 1);
    app.frame();
    return app;
  }

  @Test
  void aHoverLookAppliesOnceThePointerIsOverItsElementOrInsideIt() {
    App<Cards> app = cards();
    assertEquals(1, app.state().builds);

    app.pointerMove(10, 10);
    assertFalse(app.frame(), "over a plain element nothing is rebuilt");
    assertEquals(1, app.state().builds);
    assertEquals("plain", app.hovered().key());

    app.pointerMove(120, 80);
    assertTrue(app.frame());
    assertEquals(2, app.state().builds, "reaching the card rebuilds once");
    El card = find(app, "card");
    assertEquals(LIGHT, card.fill());
    assertEquals(8, card.radius());

    app.pointerMove(130, 90);
    assertFalse(app.frame(), "moving within the card");
    app.pointerMove(60, 10);
    assertFalse(app.frame(), "onto the box inside: the card stays hovered");
    assertEquals(2, app.state().builds);
    assertEquals("inner", app.hovered().key());
    assertEquals(LIGHT, find(app, "card").fill());

    app.pointerLeave();
    assertTrue(app.frame());
    assertEquals(3, app.state().builds);
    assertEquals(GREY, find(app, "card").fill());
    assertEquals(0, find(app, "card").radius());
    assertNull(app.hovered());
    app.pointerMove(10, 10);
    assertFalse(app.frame(), "back over the plain element");
  }

  @Test
  void theAncestorsOfTheElementUnderThePointerAreHoveredToo() {
    App<Object> app = new App<>(new Object(), s ->
        el().key("outer").size(100, 100).whenHovered(e -> e.fill(LIGHT))
            .child(el().key("middle").size(60, 60)
                .child(el().key("inner").size(20, 20).whenHovered(e -> e.fill(DARK)))));
    app.resize(100, 100, 1);
    app.frame();

    app.pointerMove(10, 10);
    assertTrue(app.frame());
    assertEquals(LIGHT, find(app, "outer").fill());
    assertEquals(DARK, find(app, "inner").fill());

    app.pointerMove(50, 50);
    assertTrue(app.frame(), "inner is no longer hovered");
    assertEquals(LIGHT, find(app, "outer").fill());
    assertEquals(0, find(app, "inner").fill());
    assertSame(find(app, "middle"), app.hovered());
  }

  @Test
  void hoverFollowsWhatAScrollMovesUnderThePointer() {
    App<Object> app = new App<>(new Object(), s -> {
      El list = El.col().key("list").scroll().size(100, 40);
      for (int i = 0; i < 4; i++) {
        list.child(el().key("row-" + i).h(20).wFull().whenHovered(e -> e.fill(LIGHT)));
      }
      return list;
    });
    app.resize(100, 40, 1);
    app.frame();
    app.pointerMove(50, 10);
    app.frame();
    assertEquals(LIGHT, find(app, "row-0").fill());

    app.wheel(50, 10, 0, 20);
    assertTrue(app.frame());
    assertEquals(0, find(app, "row-0").fill());
    assertEquals(LIGHT, find(app, "row-1").fill(), "the row that came under the pointer");
  }

  @Test
  void pressedAndFocusedLooksAndTheOrderInWhichTheyWin() {
    List<String> log = new ArrayList<>();
    App<List<String>> app = new App<>(log, l -> row().children(
        el().key("button").size(50, 50).fill(GREY).focusable().onPress(() -> l.add("press"))
            .whenHovered(e -> e.fill(LIGHT).radius(4))
            .whenFocused(e -> e.fill(BLUE).border(2, DARK))
            .whenPressed(e -> e.fill(DARK)),
        el().key("other").size(50, 50).focusable()));
    app.resize(100, 50, 1);
    app.frame();
    El button = find(app, "button");

    app.pointerDown(25, 25);
    app.frame();
    button = find(app, "button");
    assertSame(button, app.pressed());
    assertEquals(DARK, button.fill(), "pressed wins over focused, which the press gave it");
    assertEquals(DARK, button.borderColor(), "what only the focused look sets stays");
    assertEquals(0, button.radius(), "presses do not move the pointer: not hovered");

    app.pointerMove(25, 25);
    app.frame();
    assertEquals(DARK, find(app, "button").fill(), "pressed wins over hovered");
    assertEquals(4, find(app, "button").radius());

    app.pointerUp(25, 25);
    app.frame();
    assertEquals(List.of("press"), log);
    assertNull(app.pressed());
    assertEquals(BLUE, find(app, "button").fill(), "focused wins over hovered");

    app.pointerLeave();
    app.frame();
    assertEquals(BLUE, find(app, "button").fill());
    assertEquals(0, find(app, "button").radius());

    app.focus(find(app, "other"));
    app.frame();
    assertEquals(GREY, find(app, "button").fill());
    assertEquals(0, find(app, "button").style().borderTop.value(), "the border went with focus");
  }

  @Test
  void aPressedLookStaysUntilThePointerIsUpWhereverThatIs() {
    App<Object> app = new App<>(new Object(), s ->
        row().children(el().key("a").size(50, 50).onPress(() -> {}).whenPressed(e -> e.fill(DARK)),
            el().key("b").size(50, 50)));
    app.resize(100, 50, 1);
    app.frame();
    app.pointerDown(25, 25);
    app.pointerMove(75, 25);
    app.frame();
    assertEquals(DARK, find(app, "a").fill());
    app.pointerUp(75, 25);
    assertTrue(app.frame());
    assertEquals(0, find(app, "a").fill());

    app.pointerDown(75, 25); // no handler there: nothing is pressed, nothing rebuilt
    assertFalse(app.frame());
    assertNull(app.pressed());
  }

  @Test
  void withMotionALookSpringsInAndOut() {
    App<Object> app = new App<>(new Object(), s ->
        el().key("box").size(50, 50).fill(GREY).motion(Spring.SNAP)
            .whenHovered(e -> e.fill(LIGHT).w(70)));
    app.resize(100, 50, 1);
    app.frame();

    app.pointerMove(10, 10);
    assertTrue(app.frame());
    assertEquals(GREY, app.tree().fill(), "springs from where it was");
    assertTrue(app.isMoving());
    app.advance(0.05);
    app.frame();
    int halfway = app.tree().fill();
    assertNotEquals(GREY, halfway);
    assertNotEquals(LIGHT, halfway);
    assertTrue(app.tree().width() > 50 && app.tree().width() < 70, "" + app.tree().width());
    app.advance(1);
    app.frame();
    assertEquals(LIGHT, app.tree().fill());
    assertEquals(70, app.tree().width());

    app.pointerLeave();
    app.frame();
    app.advance(0.05);
    app.frame();
    assertTrue(app.tree().width() > 50 && app.tree().width() < 70, "and back");
    app.advance(1);
    app.frame();
    assertEquals(GREY, app.tree().fill());
  }

  @Test
  void aLookThatChangesChildrenOrHandlersFails() {
    App<Object> app = new App<>(new Object(), s ->
        el().key("card").role("button").size(50, 50).whenHovered(e -> e.child(el())));
    app.resize(50, 50, 1);
    app.frame();
    app.pointerMove(10, 10);
    IllegalStateException e = assertThrows(IllegalStateException.class, app::frame);
    assertEquals(
        "The whenHovered look of el #card role=button changed its children. A look may change"
            + " only how its element looks and its layout properties; build the children, the key"
            + " and the handlers in the view.",
        e.getMessage());

    App<Object> handlers = new App<>(new Object(), s ->
        el().size(50, 50).focusable().whenFocused(f -> f.onPress(() -> {})));
    handlers.resize(50, 50, 1);
    handlers.frame();
    handlers.focus(handlers.tree());
    e = assertThrows(IllegalStateException.class, handlers::frame);
    assertTrue(e.getMessage().startsWith("The whenFocused look of el changed its handlers."),
        e.getMessage());
  }

  @Test
  void theCursorIsInheritedAndDefaultWhereNoneIsSet() {
    App<Object> app = new App<>(new Object(), s -> row().children(
        el().key("link").size(50, 50).cursor(Cursor.POINTER)
            .child(el().key("icon").size(20, 20)),
        el().key("field").size(50, 50).cursor(Cursor.TEXT),
        el().key("plain").size(50, 50)));
    app.resize(200, 50, 1);
    app.frame();
    assertEquals(Cursor.DEFAULT, app.cursor(), "the pointer has not come in yet");

    app.pointerMove(10, 10);
    assertFalse(app.frame(), "a cursor is no look: nothing to rebuild");
    assertEquals("icon", app.hovered().key());
    assertEquals(Cursor.POINTER, app.cursor(), "the icon inherits the link's");
    app.pointerMove(75, 10);
    assertEquals(Cursor.POINTER, app.cursor(), "the move takes effect in the next frame");
    app.frame();
    assertEquals(Cursor.TEXT, app.cursor());
    app.pointerMove(125, 10);
    app.frame();
    assertEquals(Cursor.DEFAULT, app.cursor());
    app.pointerMove(10, 10);
    app.frame();
    app.pointerLeave();
    app.frame();
    assertEquals(Cursor.DEFAULT, app.cursor());
  }

  /** Inserts an element before the button, keyed or not, when a key comes. */
  private static final class Insert {
    boolean inserted;
    List<String> log = new ArrayList<>();
  }

  @Test
  void aPressSurvivesARebuildThatShiftsTheChildIndexes() {
    App<Insert> app = new App<>(new Insert(), s -> {
      El root = row().onKey(k -> s.inserted = true);
      if (s.inserted) {
        root.child(el().key("new").size(20, 20));
      }
      return root.child(el().key("button").size(40, 20).onPress(() -> s.log.add("press")));
    });
    app.resize(100, 20, 1);
    app.frame();

    app.pointerDown(10, 10);
    app.keyDown(new Key("A", 0, false));
    app.frame(); // the button is now the second child, from 20 to 60
    assertEquals("button", app.pressed().key());
    app.pointerUp(30, 10);
    app.frame();
    assertEquals(List.of("press"), app.state().log);
  }

  @Test
  void aPressSurvivesALeavingSiblingThatIsTakenOut() {
    Insert state = new Insert();
    state.inserted = true;
    App<Insert> app = new App<>(state, s -> {
      El root = row();
      if (s.inserted) {
        root.child(el().key("badge").size(20, 20).motion(Spring.SNAP));
      }
      return root.child(el().size(40, 20).onPress(() -> s.log.add("press"))); // no key
    });
    app.resize(100, 20, 1);
    app.frame();
    state.inserted = false;
    app.resize(100, 20, 1);
    app.frame(); // the badge leaves but stays in the tree before the button
    assertEquals(2, app.tree().children().size());

    app.pointerDown(10, 10);
    app.advance(1);
    app.frame(); // the badge has faded out and is taken out
    assertEquals(1, app.tree().children().size());
    app.pointerUp(10, 10);
    app.frame();
    assertEquals(List.of("press"), state.log);
  }

  @Test
  void theTreeMarksTheElementAPressUnderThePointerWouldGoTo() {
    App<Examples.Lights> app = new App<>(new Examples.Lights(), Examples::switchAlone);
    app.resize(200, 120, 1);
    app.frame();
    app.pointerMove(100, 60); // on the knob, which has no handler of its own
    app.frame();
    assertEquals(
        """
        el 0,0 200x120
          el #switch role=switch label="Lights" 81,49 38x22 checked=false hovered
            el #knob 83,51 18x18
        """,
        app.tree().dump());
    assertEquals(Cursor.POINTER, app.cursor());
    app.pointerMove(10, 10);
    app.frame();
    assertFalse(app.tree().dump().contains("hovered"), app.tree().dump());
  }

  private static El find(App<?> app, String key) {
    List<El> todo = new ArrayList<>(List.of(app.tree()));
    while (!todo.isEmpty()) {
      El el = todo.removeLast();
      if (key.equals(el.key())) {
        return el;
      }
      todo.addAll(el.children());
    }
    return null;
  }

  @Test
  void aRebuildThatMovesOtherElementsUnderTheStillPointerMovesTheHoverLookToThem() {
    boolean[] pushed = {false};
    App<Object> app = new App<>(new Object(), s -> El.col().wFull().hFull()
        .onKey(k -> pushed[0] = true)
        .children(
            el().key("spacer").h(pushed[0] ? 40 : 0).shrink(0),
            el().key("a").h(40).shrink(0).fill(GREY).whenHovered(e -> e.fill(BLUE)),
            el().key("b").h(40).shrink(0).fill(GREY).whenHovered(e -> e.fill(BLUE))));
    app.resize(100, 200, 1);
    app.frame();
    app.pointerMove(50, 60);
    app.frame();
    assertEquals(BLUE, find(app, "b").fill());

    app.keyDown(new Key("Space")); // a key, not the pointer, moves a under it
    assertTrue(app.frame());
    assertEquals(BLUE, find(app, "a").fill(), "the look is where the pointer is now");
    assertEquals(GREY, find(app, "b").fill());
    assertEquals("a", app.hovered().key());
    assertFalse(app.frame(), "and nothing is left to do: an idle window would show this");
  }
}
