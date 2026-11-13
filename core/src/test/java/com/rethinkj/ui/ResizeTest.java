package com.rethinkj.ui;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
    assertEquals(List.of(), told, "told in the next frame, as a handler of input");
    app.frame();
    assertEquals(List.of("200.0x20.0", "item 30.0", "item 30.0", "item 30.0"), told);
    told.clear();
    app.invalidate();
    app.frame();
    app.frame();
    assertEquals(List.of(), told, "the same sizes are not told again");
    app.resize(150, 100, 1);
    app.frame();
    app.frame();
    assertEquals(List.of("150.0x20.0"), told, "only what changed");
  }
}
