package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.motion.Spring;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link El#cycle}: looks that go round on the app's clock while their element is shown. */
class CycleTest {

  /** What the view shows: whether the piece is there, and where the list is scrolled to. */
  static final class Shown {
    boolean piece = true;
    boolean cycles = true;
    float opacity = 1;
    float top = 10;
    final List<Double> phases = new ArrayList<>();
  }

  /** A track of 100 by 4 whose piece, laid out over the whole track, shows a fifth of it. */
  private static El view(Shown s) {
    El piece =
        el().key("piece").absolute(0, 0).wFull().hFull().fill(0xFF0000FF).motion(Spring.SETTLE);
    if (s.cycles) {
      piece.cycle(2, (e, phase) -> {
        s.phases.add(phase);
        e.place(e.x() + (float) phase * e.width(), e.y(), e.width() / 5, e.height());
      });
    }
    El track = el().key("track")
        .absolute(10, s.top)
        .w(100)
        .h(4)
        .opacity(s.opacity)
        .children(s.piece ? new El[] {piece} : new El[0]);
    return el().wFull().hFull().children(track);
  }

  private static El piece(App<?> app) {
    return app.tree().children().getFirst().children().getFirst();
  }

  private static App<Shown> app(Shown s) {
    App<Shown> app = new App<>(s, CycleTest::view);
    app.resize(200, 100, 1);
    return app;
  }

  @Test
  void thePhaseFollowsTheClockFromTheFrameTheElementAppearedIn() {
    Shown s = new Shown();
    App<Shown> app = app(s);
    app.advance(5); // the clock does not start at the element
    assertTrue(app.frame());
    assertEquals(0, s.phases.getLast());
    assertEquals(0, piece(app).x());
    assertEquals(20, piece(app).width());
    assertTrue(app.isMoving());
    assertEquals(app.time(), app.nextChange());

    app.advance(0.5);
    assertTrue(app.frame(), "a frame without input draws");
    assertEquals(0.25, s.phases.getLast(), 1e-9);
    assertEquals(25, piece(app).x(), 1e-4, "from the laid-out rectangle, not last frame's");
    assertEquals(20, piece(app).width(), 1e-4);

    app.advance(1.75);
    app.invalidate(); // a rebuild goes on where it was
    assertTrue(app.frame());
    assertEquals(0.125, s.phases.getLast(), 1e-9);
    assertEquals(12.5, piece(app).x(), 1e-4);
  }

  @Test
  void withoutAShownCycleTheAppIsIdle() {
    Shown s = new Shown();
    s.piece = false;
    App<Shown> app = app(s);
    assertTrue(app.frame());
    assertFalse(app.isMoving());
    assertEquals(Double.POSITIVE_INFINITY, app.nextChange());
    app.advance(1);
    assertFalse(app.frame());
  }

  @Test
  void itEndsWhenTheElementLeavesTheTree() {
    Shown s = new Shown();
    App<Shown> app = app(s);
    app.frame();
    s.piece = false;
    app.invalidate();
    assertTrue(app.frame());
    for (int frame = 0; app.isMoving(); frame++) { // it fades out, going round on the way
      assertTrue(frame < 60, "still there after a second");
      app.advance(1.0 / 60);
      app.frame();
    }
    int asked = s.phases.size();
    app.advance(1);
    assertFalse(app.frame());
    assertEquals(asked, s.phases.size());
  }

  @Test
  void anElementOutsideTheWindowOrUnderAnInvisibleAncestorCostsNothing() {
    Shown s = new Shown();
    s.top = 200;
    App<Shown> app = app(s);
    assertTrue(app.frame());
    assertFalse(app.isMoving(), "below the window");
    app.advance(1);
    assertFalse(app.frame());

    s.top = 10;
    s.opacity = 0;
    app.invalidate();
    app.frame();
    assertFalse(app.isMoving(), "under an ancestor at opacity 0");

    s.opacity = 1;
    app.invalidate();
    app.frame();
    assertTrue(app.isMoving());
  }

  @Test
  void aPieceThatLearnsItsPlaceSpringsFromWhereTheLookShowedIt() {
    Shown s = new Shown();
    App<Shown> app = app(s);
    app.frame();
    app.advance(1); // phase 0.5: the piece at 50, 20 wide
    app.frame();
    assertEquals(50, piece(app).x(), 1e-4);

    s.cycles = false; // now it fills the track
    app.invalidate();
    assertTrue(app.frame());
    assertEquals(50, piece(app).x(), 1e-4, "no jump");
    assertEquals(20, piece(app).width(), 1e-4);
    while (app.isMoving()) {
      app.advance(1.0 / 60);
      app.frame();
    }
    assertEquals(0, piece(app).x());
    assertEquals(100, piece(app).width());
  }

  @Test
  void rowsOfAVirtualListCycleWhileBuiltAndKeepTheirPhase() {
    List<Double> seen = new ArrayList<>();
    App<Object> app = new App<>(
        new Object(),
        state -> col()
            .key("list")
            .w(100)
            .h(40)
            .rows(
                1000,
                20,
                i -> el().key("row-" + i)
                    .cycle(1, i == 0 ? (e, phase) -> seen.add(phase) : (e, phase) -> {})));
    app.resize(100, 40, 1);
    app.frame();
    assertTrue(app.isMoving());
    app.advance(0.25);
    app.frame();
    assertEquals(0.25, seen.getLast(), 1e-9);

    El list = app.tree();
    app.scrollToRow(list, 500);
    app.frame();
    int asked = seen.size();
    assertTrue(app.isMoving(), "the rows in view cycle");
    app.advance(0.25);
    app.frame();
    assertEquals(asked, seen.size(), "row 0 is not built");

    app.scrollToRow(app.tree(), 0);
    app.frame(); // built anew: its phase starts again, as it is a new element of the list
    app.advance(0.25);
    app.frame();
    assertEquals(0.25, seen.getLast(), 1e-9);
  }

  @Test
  void twoPeriodsGoRoundEachAtItsOwnPace() {
    double[] phases = new double[2];
    App<Object> app = new App<>(
        new Object(),
        state -> col()
            .wFull()
            .hFull()
            .children(
                el().w(10).h(10).cycle(1, (e, phase) -> phases[0] = phase),
                el().w(10).h(10).cycle(0.4, (e, phase) -> phases[1] = phase)));
    app.resize(100, 100, 1);
    app.frame();
    app.advance(0.5);
    app.frame();
    assertEquals(0.5, phases[0], 1e-9);
    assertEquals(0.25, phases[1], 1e-9);
  }

  @Test
  void headlessShowsPhaseZero() {
    Shown s = new Shown();
    El root = view(s);
    Headless.render(root, 200, 100, 1);
    assertEquals(List.of(0.0), s.phases);
    assertEquals(20, root.children().getFirst().children().getFirst().width());
  }

  @Test
  void aPeriodMustBePositive() {
    assertThrows(IllegalArgumentException.class, () -> el().cycle(0, (e, p) -> {}));
  }
}
