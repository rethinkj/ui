package com.rethinkj.ui.window;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.render.Pixels;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The pointer on a physical pixel reaches the box that draws that pixel, at whole and fractional
 * scales, where the scene snaps the boxes' edges to whole pixels; needs no window.
 */
class PointerPixelTest {

  private static final int BACK = 0xFF000000;
  private static final Map<Integer, String> KEYS =
      Map.of(0xFFFF0000, "a", 0xFF00FF00, "b", 0xFF0000FF, "c");

  @ParameterizedTest(name = "at {0}")
  @ValueSource(floats = {1, 1.25f, 1.5f, 1.75f, 2, 2.5f})
  void thePointerOnAPixelReachesTheBoxThatDrawsIt(float scale) {
    // Boxes at odd logical positions and widths, a pixel apart, so that their edges fall on every
    // fraction of a physical pixel at these scales.
    App<Object> app = new App<>(
        new Object(),
        s -> row()
            .wFull()
            .hFull()
            .fill(BACK)
            .padding(3, 0, 0, 1)
            .gap(1)
            .children(box("a", 0xFFFF0000, 7), box("b", 0xFF00FF00, 9), box("c", 0xFF0000FF, 3)));
    app.resize(40, 16, scale);
    app.frame();
    Pixels pixels = app.render().pixels();
    int y = pixels.height() / 2;
    List<String> drawn = new ArrayList<>();
    List<String> reached = new ArrayList<>();
    for (int x = 0; x < pixels.width(); x++) {
      drawn.add(x + " " + KEYS.get(pixels.argb(x, y)));
      app.pointerMove(Window.position(x, 1, scale), Window.position(y, 1, scale));
      app.frame();
      El hovered = app.hovered();
      reached.add(x + " " + (hovered == null ? null : hovered.key()));
    }
    assertEquals(drawn, reached);
  }

  private static El box(String key, int fill, float width) {
    return el().key(key).size(width, 10).fill(fill).onPress(() -> {});
  }
}
