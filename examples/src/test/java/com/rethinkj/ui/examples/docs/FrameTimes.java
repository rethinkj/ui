package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.El.text;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowEvent;
import com.rethinkj.ui.window.WindowLoop;
import java.util.ArrayList;
import java.util.List;

/** The example of the page about frame times: it opens a window, so the tests do not run it. */
final class FrameTimes {

  private FrameTimes() {}

  static El rows(Object state) {
    return col()
        .wFull()
        .hFull()
        .rows(1_000_000, 24, index -> row().h(24).child(text("Row " + index)));
  }

  // docs:start frame-times
  static void measure() {
    App<Object> app = new App<>(new Object(), FrameTimes::rows); // a million rows
    List<Long> times = new ArrayList<>();
    try (Window window = Window.open("Frame times", 400, 300, Window.options());
        GpuPresenter presenter = new GpuPresenter(window)) {
      WindowLoop loop = new WindowLoop(window, app, presenter);
      loop.frames(frame -> {
        if (app.tree().scrollY() < times.size() * WindowLoop.WHEEL_STEP) {
          return; // a frame the window asked for before the step's
        }
        times.add(frame.input() + frame.build() + frame.layout() + frame.draw());
        if (times.size() <= 300) {
          window.post(new WindowEvent.Wheel(0, -1, 200, 150)); // the next step down
        } else {
          loop.stop();
        }
      });
      loop.run();
    }
    if (times.size() < 2) {
      return; // the window closed before the first step
    }
    List<Long> steps = times.subList(1, times.size()).stream().sorted().toList(); // not the first
    System.out.printf(
        "median %.2f ms, p99 %.2f ms, max %.2f ms%n",
        steps.get(steps.size() / 2) / 1e6,
        steps.get(steps.size() * 99 / 100) / 1e6,
        steps.getLast() / 1e6);
  }
  // docs:end frame-times
}
