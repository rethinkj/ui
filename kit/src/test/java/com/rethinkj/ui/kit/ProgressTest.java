package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Bars, running bars, lines, spinners and a busy button: what a dump says and how they move. */
class ProgressTest {

  private static final Theme T = Theme.DARK;

  /** What the views show. */
  static final class Shown {
    double fraction = Double.NaN; // running
    boolean busy = true;
    int pressed;
  }

  private static void run(App<?> app, String steps) throws IOException {
    Script.run("size 320 200\nframe\n" + steps + "\nframe", app, Path.of("target/progress-test"));
  }

  @Test
  void theValueIsInWholePercentAndHundredOnlyWhenDone() {
    assertEquals("0%", Progress.percent(Double.NaN));
    assertEquals("0%", Progress.percent(-1));
    assertEquals("42%", Progress.percent(0.42));
    assertEquals("99%", Progress.percent(0.999));
    assertEquals("100%", Progress.percent(1));
    assertEquals("100%", Progress.percent(7));
  }

  @Test
  void aDumpSaysRoleValueAndStateAndNothingTakesTheFocus() throws IOException {
    TextLibraries.assumeLoaded();
    App<Object> app = new App<>(new Object(), s -> col().gap(8).children(
        Progress.bar(T, 0.42).key("bar").label("Export"),
        Progress.bar(T, 0.5, Progress.State.ERROR).key("failed"),
        Progress.indeterminate(T).key("running"),
        Progress.labeled(T, "orders.csv", 0.25, "20 MB of 80 MB").key("labeled"),
        Progress.spinner(T).key("spinner")));
    run(app, "key Tab");
    String dump = app.tree().dump();
    assertTrue(StructureTest.line(dump, "#bar").contains("role=progressbar label=\"Export\""));
    assertTrue(StructureTest.line(dump, "#bar").endsWith(" value=\"42%\""), dump);
    assertTrue(StructureTest.line(dump, "#failed").endsWith(" invalid value=\"50%\""), dump);
    assertFalse(StructureTest.line(dump, "#running").contains("value="), dump);
    assertTrue(StructureTest.line(dump, "#labeled")
        .contains("role=progressbar label=\"orders.csv\""), dump);
    assertTrue(StructureTest.line(dump, "#spinner")
        .contains("role=progressbar label=\"Loading\" "), dump);
    assertNull(app.focused(), "none takes the focus");
    assertTrue(app.isMoving(), "the running bar and the spinner go round");
  }

  @Test
  void aBusyButtonKeepsItsPlaceForTabButTakesNoPress() throws IOException {
    TextLibraries.assumeLoaded();
    Shown s = new Shown();
    App<Shown> app = new App<>(s, st -> col().child(Button.button(T, Button.Variant.PRIMARY,
        Symbol.PLAY, "Run", st.busy, () -> st.pressed++).key("run")));
    run(app, "key Tab\nframe\nkey Enter\nframe\npress role=button busy");
    assertEquals(0, s.pressed);
    assertEquals("run", app.focused().key());
    String dump = app.tree().dump();
    assertTrue(StructureTest.line(dump, "#run").contains(" busy"), dump);
    assertTrue(dump.contains("role=progressbar label=\"Loading\""), dump);

    s.busy = false;
    app.invalidate();
    run(app, "press role=button label=\"Run\"");
    assertEquals(1, s.pressed);
    assertFalse(StructureTest.line(app.tree().dump(), "#run").contains(" busy"));
    assertFalse(app.isMoving(), "no spinner, nothing goes round");
  }

  @Test
  void aRunningBarThatLearnsItsValueSpringsFromWhereItRan() {
    Shown s = new Shown();
    App<Shown> app = new App<>(s, st -> col().w(300).child(
        (Double.isNaN(st.fraction) ? Progress.indeterminate(T) : Progress.bar(T, st.fraction))
            .key("bar").w(200)));
    app.resize(300, 100, 1);
    app.frame();
    app.advance(T.motion().progress() / 2);
    app.frame();
    El piece = fill(app);
    float x = piece.x(), width = piece.width();
    assertTrue(x > 20 && width > 40 && x + width < 200, x + " " + width);

    s.fraction = 0.25;
    app.invalidate();
    app.frame();
    assertEquals(x, fill(app).x(), 1e-3, "no jump");
    assertEquals(width, fill(app).width(), 1e-3);
    for (int frame = 0; app.isMoving(); frame++) {
      assertTrue(frame < 120);
      app.advance(1.0 / 60);
      app.frame();
    }
    assertEquals(0, fill(app).x());
    assertEquals(50, fill(app).width(), 1e-3);
  }

  private static El fill(App<?> app) {
    return app.tree().children().getFirst().children().getFirst();
  }
}
