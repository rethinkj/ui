package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.rethinkj.ui.motion.Spring;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link El#onResize}: told the size layout gave, at first and when it changes. */
class ResizeTest {

  @Test
  void anElementIsToldItsSizeWhenItChanges() {
    List<String> told = new ArrayList<>();
    int[] items = {3};
    App<Object> app = new App<>(new Object(), s -> {
      El bar = row().wFull().h(20).onResize((w, h) -> told.add(w + "x" + h));
      for (int i = 0; i < items[0]; i++) {
        bar.child(el().w(30).shrink(0).onResize((w, h) -> told.add("item " + w)));
      }
      return bar;
    });
    app.resize(200, 100, 1);
    app.frame();
    assertEquals(
        List.of("200.0x20.0", "item 30.0", "item 30.0", "item 30.0"),
        told,
        "told in the frame that laid it out");
    told.clear();
    app.invalidate();
    app.frame();
    assertEquals(List.of(), told, "the same sizes are not told again");
    app.resize(150, 100, 1);
    app.frame();
    assertEquals(List.of("150.0x20.0"), told, "only what changed");
  }

  @Test
  void aViewThatNeverSettlesStillEndsEachFrame() {
    int[] width = {40};
    int[] told = {0};
    App<int[]> app = new App<>(
        width,
        s -> el().size(s[0], 20).onResize((w, h) -> {
          if (w >= 80) {
            s[0] = s[0] == 120 ? 80 : s[0] + 1; // never the size it was told
            if (++told[0] > 50) {
              throw new IllegalStateException("the frame runs the handler without end");
            }
          }
        }));
    app.resize(200, 100, 1);
    app.frame();
    app.post(() -> width[0] = 80);
    app.post(() -> width[0] = width[0] > 80 ? 100 : width[0]); // the queue goes on after a rebuild
    app.frame();
    assertEquals(3, told[0], "three rounds at the end of the frame, the rest in the next");
  }

  @Test
  void aPressAfterInvalidateGoesToWhatTheFrameShowed() {
    float[] width = {0}; // the bar's width as told
    List<String> pressed = new ArrayList<>();
    App<float[]> app = new App<>(
        width,
        s -> row()
            .wFull()
            .h(20)
            .onResize((w, h) -> s[0] = w)
            .child(
                s[0] >= 150
                    ? el().key("wide").size(50, 20).onPress(() -> pressed.add("wide"))
                    : el().size(50, 20)));
    app.resize(200, 100, 1);
    app.frame();
    app.resize(100, 100, 1);
    app.invalidate();
    app.pointerDown(10, 10);
    app.pointerUp(10, 10);
    app.frame();
    assertEquals(List.of("wide"), pressed, "the press hit the button the last frame showed");
    assertFalse(app.tree().dump().contains("#wide"), "and the frame ends with the new width");
  }

  @Test
  void anElementWithMotionIsToldTheSizeLayoutGaveNotTheStepsOfItsSpring() {
    List<Float> told = new ArrayList<>();
    int[] width = {40};
    App<Object> app = new App<>(
        new Object(),
        s -> el().size(width[0], 20).motion(Spring.DRIFT).onResize((w, h) -> told.add(w)));
    app.resize(100, 100, 1);
    app.frame();
    app.frame();
    assertEquals(List.of(40f), told);
    told.clear();
    width[0] = 80;
    app.invalidate();
    app.frame();
    app.frame();
    app.advance(5);
    app.frame();
    app.frame();
    assertEquals(80, app.tree().width());
    assertEquals(List.of(80f), told);
  }

  @Test
  void anElementThatComesBackIsToldItsSizeAgain() {
    List<Float> told = new ArrayList<>();
    boolean[] shown = {true};
    App<Object> app = new App<>(new Object(), s -> {
      El root = col().size(100, 100);
      if (shown[0]) {
        root.child(el().key("panel").size(80, 20).onResize((w, h) -> told.add(w)));
      }
      return root;
    });
    app.resize(100, 100, 1);
    app.frame();
    app.frame();
    shown[0] = false;
    app.invalidate();
    app.frame();
    shown[0] = true;
    app.invalidate();
    app.frame();
    app.frame();
    assertEquals(List.of(80f, 80f), told);
  }
}
