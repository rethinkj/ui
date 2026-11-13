package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Checkbox.checkbox;
import static com.rethinkj.ui.kit.Toggle.toggle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.text.TextRendering;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A toggle or checkbox in a column takes presses, and draws its ring, only as far as the control
 * and its text reach, whether the column stretches its children or centres them.
 */
class PressAreaTest {

  private static final Theme T = Theme.DARK;

  static Stream<Arguments> controls() {
    return Stream.of(
        Arguments.of("toggle", (Function<Runnable, El>) h -> toggle(T, false, "Autocommit", h)),
        Arguments.of("checkbox", (Function<Runnable, El>) h -> checkbox(T, false, "SSL", h)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("controls")
  void aControlInAColumnIsAsWideAsItself(String name, Function<Runnable, El> control) {
    TextLibraries.assumeLoaded();
    int[] presses = {0};
    Style.Align[] align = {null}; // the column's default: stretch
    App<Object> app = new App<>(
        new Object(),
        s -> col()
            .wFull()
            .hFull()
            .padding(20)
            .align(align[0])
            .child(control.apply(() -> presses[0]++).key("c")));
    app.textRendering(TextRendering.LINUX);
    app.resize(400, 120, 1);
    app.frame();
    El c = app.tree().children().getFirst();
    float content = c.width();
    assertTrue(content < 200, name + " is " + content + " wide in a column of 360");

    float y = c.y() + c.height() / 2;
    press(app, 360, y); // in the column, beyond the text
    assertEquals(0, presses[0], "a press beside the control does nothing");
    press(app, c.x() + content - 12, y); // on the end of the text
    assertEquals(1, presses[0]);

    align[0] = Style.Align.CENTER;
    app.resize(400, 120, 1);
    app.frame();
    c = app.tree().children().getFirst();
    assertEquals(content, c.width());
    assertEquals(200, c.x() + c.width() / 2, 0.5f, "centred in a column that centres");
  }

  private static void press(App<?> app, float x, float y) {
    app.pointerDown(x, y);
    app.pointerUp(x, y);
    app.frame();
  }
}
