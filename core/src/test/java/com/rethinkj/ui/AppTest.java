package com.rethinkj.ui;

import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.Examples.Lights;
import com.rethinkj.ui.motion.Spring;
import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppTest {

  @Test
  void aPressOnTheSwitchChangesStateTreeAndImage() {
    TextLibraries.assumeLoaded();
    App<Lights> app = new App<>(new Lights(), Examples::lights);
    app.textRendering(TextRendering.LINUX);
    app.resize(200, 120, 1);
    assertTrue(app.frame());
    Golden.assertMatches("switch-off", app.render().pixels(), app.render().tree());

    // The knob has no handler of its own, so the press goes to the switch around it.
    app.pointerDown(50, 60);
    app.pointerUp(50, 60);
    assertFalse(app.state().on, "handlers run in frame(), not while input arrives");
    assertTrue(app.frame());

    assertTrue(app.state().on);
    app.advance(1); // the knob and the colour spring to their new values
    app.resize(200, 120, 2);
    app.frame();
    Golden.assertMatches("switch-on", app.render().pixels(), app.render().tree());
  }

  @Test
  void anActionRunsInTheFirstFrameOnceTheClockReachesItsTime() {
    List<String> log = new ArrayList<>();
    int[] views = {0};
    App<List<String>> app = new App<>(log, l -> {
      views[0]++;
      return el().size(10, 10);
    });
    app.resize(100, 100, 1);
    app.frame();
    assertEquals(Double.POSITIVE_INFINITY, app.nextChange());

    app.after(1.5, () -> log.add("later"));
    app.after(0.5, () -> {
      log.add("first");
      app.after(0, () -> log.add("asked by first"));
    });
    app.after(0.5, () -> log.add("second"));
    assertEquals(0.5, app.nextChange());
    app.windowActive(false); // input, which waits in the queue for the next frame
    assertFalse(app.frame(), "nothing to draw");
    assertEquals(0.5, app.nextChange(), "also while the window is inactive");
    app.advance(0.4);
    assertFalse(app.frame(), "not due yet");
    assertEquals(List.of(), log);

    app.advance(0.1);
    assertTrue(app.frame());
    assertEquals(List.of("first", "second"), log, "in the order asked for");
    assertEquals(3, views[0], "built again before the second, as for the next input, and after");
    assertEquals(0.5, app.nextChange(), "what the first asked for is due now");
    assertTrue(app.frame());
    assertEquals(List.of("first", "second", "asked by first"), log);
    assertEquals(1.5, app.nextChange());

    app.advance(10);
    app.frame();
    assertEquals(List.of("first", "second", "asked by first", "later"), log, "once each");
    assertEquals(Double.POSITIVE_INFINITY, app.nextChange());
    assertFalse(app.frame());
  }

  @Test
  void aDueActionRunsAfterTheInputAndTheTasksThatCameBeforeItsFrame() {
    List<String> log = new ArrayList<>();
    App<List<String>> app = new App<>(
        log,
        l -> el().size(10, 10).onKey(k -> {
          log.add("key " + k.name() + " sees " + l.size());
          return true;
        }));
    app.resize(100, 100, 1);
    app.frame();
    app.after(1, () -> log.add("action"));
    app.advance(1); // due from here; the frame below is the first to begin at its time
    app.post(() -> log.add("task"));
    app.keyDown(new Key("A", 0, false)); // without focus, the root gets it
    app.post(() -> log.add("second task"));
    assertEquals(1, app.nextChange());
    app.frame();
    assertEquals(
        List.of("task", "key A sees 1", "second task", "action"),
        log,
        "the input and the tasks in the order they came, then the action");
  }

  @Test
  void aKeyThatNothingTakesRunsWhatTheRunnerHandedOnWithIt() {
    List<String> log = new ArrayList<>();
    App<List<String>> app = new App<>(
        log,
        l -> el().size(100, 100)
            .onKey(k -> k.name().equals("A"))
            .child(el().key("button").size(20, 20).focusable().onPress(() -> l.add("press"))));
    app.resize(100, 100, 1);
    app.frame();
    Key altSpace = new Key("Space", Key.ALT, false);
    app.keyDown(altSpace, () -> log.add("untaken Space"));
    app.keyDown(new Key("A", 0, false), () -> log.add("untaken A")); // the root takes it
    app.frame();
    assertEquals(List.of("untaken Space"), log);

    log.clear();
    app.keyDown(new Key("Tab", 0, false), () -> log.add("untaken Tab")); // focuses the button
    app.keyDown(new Key("Space", 0, false), () -> log.add("untaken press")); // presses it
    app.keyDown(altSpace, () -> log.add("untaken Space")); // the button takes no Alt+Space
    app.frame();
    assertEquals(List.of("press", "untaken Space"), log);
  }

  @Test
  void aScriptRunsAnActionWithAdvanceAndFrameAndItsAwaitDoesNotWaitForIt(@TempDir Path out)
      throws Exception {
    List<String> log = new ArrayList<>();
    App<List<String>> app = new App<>(log, l -> el().size(10, 10));
    Script.run("size 100 100\nframe", app, out);
    app.after(0.5, () -> log.add("later"));
    long start = System.nanoTime();
    Script.run("size 100 100\nawait 5\nframe\nadvance 0.4\nframe", app, out);
    assertTrue(System.nanoTime() - start < 2_000_000_000L, "await waited for no action");
    assertEquals(List.of(), log, "not due yet");
    Script.run("size 100 100\nadvance 0.1\nframe", app, out);
    assertEquals(List.of("later"), log);
  }

  @Test
  void nothingHappensWithoutInput() {
    App<Lights> app = new App<>(new Lights(), Examples::switchAlone);
    app.resize(200, 120, 1);
    assertTrue(app.frame());
    assertFalse(app.frame());

    app.pointerDown(10, 10);
    app.pointerUp(10, 10);
    assertFalse(app.frame(), "a press beside the switch reaches no handler");
  }

  @Test
  void aPressCountsOnlyWhenItEndsWhereItBegan() {
    App<Lights> app = new App<>(new Lights(), Examples::switchAlone);
    app.resize(200, 120, 1);
    app.frame();

    app.pointerDown(90, 60);
    app.pointerUp(10, 10);
    app.pointerDown(10, 10);
    app.pointerUp(90, 60);

    assertFalse(app.frame());
    assertFalse(app.state().on);
  }

  @Test
  void handlersOfAnyPointerDownSeeEveryPressAndTakeNothingFromIt() {
    List<String> log = new ArrayList<>();
    boolean[] open = {true};
    App<List<String>> app = new App<>(
        log,
        l -> El.col()
            .size(100, 100)
            .child(el().key("bar").size(10, 10).onAnyPointerDown(() -> l.add("any")))
            .child(el().key("list")
                .size(100, 50)
                .scroll()
                .scrollbars(new Scrollbars(6, 2, 3, 24, 0xFF5A5A66, 0xFF7A7A88, 0xFFA1A1AA))
                .child(el().size(100, 500)))
            .child(el().size(40, 40).onPress(() -> l.add("press")))
            .overlay(
                !open[0]
                    ? null
                    : el().size(20, 20)
                        .atPoint(80, 80)
                        .onDismiss(() -> {
                          l.add("dismissed");
                          open[0] = false;
                        })
                        .onAnyPointerDown(() -> l.add("overlay"))));
    app.resize(100, 100, 1);
    app.frame();
    app.secondaryDown(85, 85); // inside the overlay
    app.secondaryUp(85, 85);
    press(app, 20, 70); // on the element with onPress, outside the overlay: dismisses only
    press(app, 20, 70); // now it presses
    press(app, 97, 55); // on the track of the list's scrollbar, which takes the press alone
    app.frame();
    assertEquals(
        List.of("any", "overlay", "any", "overlay", "dismissed", "any", "press", "any"), log);
    assertTrue(app.tree().children().get(1).scrollY() > 0, "the bar paged the list");
  }

  @Test
  void handlersOfAnyKeyDownSeeEveryKeyFirstAndTakeNothingFromIt() {
    List<String> log = new ArrayList<>();
    App<List<String>> app = new App<>(
        log,
        l -> El.col()
            .size(100, 100)
            .onKey(k -> l.add("root " + k.name()))
            .child(el().key("any").size(10, 10).onAnyKeyDown(k -> l.add("any " + k.name())))
            .child(el().key("taker")
                .size(40, 40)
                .focusable()
                .onKey(k -> k.name().equals("C") && l.add("taken C")))
            .overlay(
                el().size(20, 20).atPoint(80, 80).onAnyKeyDown(k -> l.add("overlay " + k.name()))));
    app.resize(100, 100, 1);
    app.frame();
    app.focus(app.tree().children().get(1));
    app.keyDown(new Key("C", Key.ALT, false)); // the focused element takes it
    app.keyDown(new Key("D", 0, false)); // nobody does: on to the root
    app.frame();
    assertEquals(List.of("any C", "overlay C", "taken C", "any D", "overlay D", "root D"), log);
  }

  @Test
  void theElementOnTopGetsThePressAndRoundedCornersAreNotPartOfIt() {
    List<String> pressed = new ArrayList<>();
    App<List<String>> app = new App<>(
        pressed,
        log -> el().size(100, 100)
            .onPress(() -> log.add("root"))
            .child(el().size(60, 60).absolute(0, 0).onPress(() -> log.add("below")))
            .child(el().size(60, 60)
                .radius(30)
                .absolute(20, 20)
                .onPress(() -> log.add("round"))
                .child(el().size(10, 10).absolute(25, 25))));
    app.resize(100, 100, 1);
    app.frame();

    press(app, 50, 50); // on the small child, which has no handler: goes to "round"
    press(app, 30, 30); // inside both, "round" is on top
    press(app, 22, 22); // in the cut-off corner of "round": "below" shows through
    press(app, 90, 90); // only the root
    app.frame();

    assertEquals(List.of("round", "round", "below", "root"), pressed);
  }

  @Test
  void aPressHandlerIsToldHowManyPressesItCompletes() {
    List<String> pressed = new ArrayList<>();
    App<List<String>> app = new App<>(
        pressed,
        log -> el().size(100, 100).focusable().onPress(clicks -> log.add("clicks " + clicks)));
    app.resize(100, 100, 1);
    app.frame();

    press(app, 50, 50);
    app.pointerDown(50, 50, 2, 0);
    app.pointerUp(50, 50);
    app.pointerDown(50, 50, 3, Key.SHIFT);
    app.pointerUp(50, 50);
    app.frame();
    app.keyDown(new Key("Enter"));
    app.frame();

    assertEquals(List.of("clicks 1", "clicks 2", "clicks 3", "clicks 1"), pressed);
  }

  private static void press(App<?> app, float x, float y) {
    app.pointerDown(x, y);
    app.pointerUp(x, y);
  }

  @Test
  void anyKeyDownInTheRowsOfAListGetsEveryKeyOfAFrame() {
    List<String> seen = new ArrayList<>();
    App<Void> app = new App<>(
        null,
        s -> El.col()
            .size(100, 100)
            .onKey(k -> true) // each key is taken, so the next one gets a tree built between them
            .rows(1, 20, i -> el().onAnyKeyDown(k -> seen.add(k.name()))));
    app.resize(100, 100, 1);
    app.frame();
    app.keyDown(new Key("A"));
    app.keyDown(new Key("B"));
    app.frame();
    assertEquals(List.of("A", "B"), seen);
  }

  @Test
  void anElementThatIsLeavingGetsNoneOfTheHooksOfAllInput() {
    List<String> seen = new ArrayList<>();
    App<boolean[]> app = new App<>(new boolean[] {true}, s -> {
      El root = el().size(100, 100);
      if (s[0]) {
        root.child(el().key("gone")
            .size(20, 20)
            .motion(Spring.SNAP)
            .onAnyKeyDown(k -> seen.add("key"))
            .onAnyPointerDown(() -> seen.add("pointer"))
            .onPressOutside(() -> seen.add("outside")));
      }
      return root;
    });
    app.resize(100, 100, 1);
    app.frame();
    app.advance(1);
    app.frame();
    app.post(() -> app.state()[0] = false);
    app.frame();
    assertTrue(app.tree().dump().contains("exiting"), "it fades out");
    app.keyDown(new Key("A"));
    app.pointerDown(80, 80);
    app.frame();
    assertEquals(List.of(), seen);
  }

  @Test
  void aPressEndsWhenItsElementLeavesAndOneWithItsIdentityComesBack() {
    App<int[]> app = new App<>(new int[2], s -> {
      El root = el().size(100, 100);
      if (s[0] != 1) {
        root.child(el().key("button").size(40, 40).onPress(() -> s[1]++));
      }
      return root;
    });
    app.resize(100, 100, 1);
    app.frame();
    app.pointerDown(10, 10);
    app.frame();
    app.post(() -> app.state()[0] = 1); // the button leaves
    app.frame();
    assertNull(app.pressed());
    app.post(() -> app.state()[0] = 2); // and a new one with the same key comes
    app.frame();
    app.pointerUp(10, 10);
    app.frame();
    assertEquals(0, app.state()[1], "the release presses nothing");
  }

  @Test
  void aSecondaryPressEndsWhenItsElementLeavesAndOneWithItsIdentityComesBack() {
    App<int[]> app = new App<>(new int[2], s -> {
      El root = el().size(100, 100);
      if (s[0] != 1) {
        root.child(el().key("button").size(40, 40).onSecondaryPress((x, y) -> s[1]++));
      }
      return root;
    });
    app.resize(100, 100, 1);
    app.frame();
    app.secondaryDown(10, 10);
    app.frame();
    app.post(() -> app.state()[0] = 1);
    app.frame();
    app.post(() -> app.state()[0] = 2);
    app.frame();
    app.secondaryUp(10, 10);
    app.frame();
    assertEquals(0, app.state()[1]);
  }
}
