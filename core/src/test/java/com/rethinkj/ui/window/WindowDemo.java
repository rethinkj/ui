package com.rethinkj.ui.window;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Examples;
import com.rethinkj.ui.Examples.Lights;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.WindowPart;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.render.Scene;
import com.rethinkj.ui.window.WindowEvent.KeyDown;
import com.rethinkj.ui.window.WindowEvent.PointerMove;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A frameless window with a title bar of its own, and the light switch below it, drawn on the GPU
 * by {@link GpuPresenter}. The title bar is a drag area with minimize, maximize and close buttons;
 * a click on the switch toggles it, Escape closes the window. Every event but pointer moves and
 * live redraws, and every frame presented, is printed with the seconds since the start, as is a
 * frame the presenter could not show yet.
 *
 * <p>Build with {@code mvn -pl core -am test-compile}, then run from the repository root (on
 * Linux, with {@code :} between the class path entries and {@code natives-linux-x64} in place of
 * {@code natives-windows-x64}):
 *
 * <pre>
 * java --enable-native-access=ALL-UNNAMED
 *     -cp "core/target/classes;core/target/test-classes;natives-windows-x64/target/classes"
 *     com.rethinkj.ui.window.WindowDemo [options]
 * </pre>
 *
 * <pre>
 * --size=W,H          size in logical pixels (default 640,400)
 * --pos=X,Y           position on the desktop
 * --framed            with the system's frame instead of the drawn title bar
 * --progress=V        taskbar progress from 0 to 1
 * --exit-after=S      stop after S seconds, from another thread
 * --moves             print pointer moves and live redraws too
 * --cpu-png=FILE      when stopped, write the last frame as the CPU renderer draws it
 * </pre>
 */
public final class WindowDemo {

  static final float TITLE_BAR = 36;
  static final int BAR = 0xFF1E2230;
  static final int BUTTON = 0xFF262B3A;
  static final int CLOSE_BUTTON = 0xFF4A2830;
  static final int GLYPH = 0xFFE6E8EE;

  private WindowDemo() {}

  /** The demo's state: the switch, and the window, whose state the maximize button shows. */
  static final class State {
    final Lights lights = new Lights();
    final Window window;

    State(Window window) {
      this.window = window;
    }
  }

  public static void main(String[] args) throws Exception {
    float width = 640;
    float height = 400;
    int[] position = null;
    boolean framed = false;
    float progress = -1;
    double exitAfter = 0;
    boolean moves = false;
    Path cpuPng = null;
    for (String arg : args) {
      String value = arg.substring(arg.indexOf('=') + 1);
      if (arg.startsWith("--size=")) {
        width = Float.parseFloat(value.split(",")[0]);
        height = Float.parseFloat(value.split(",")[1]);
      } else if (arg.startsWith("--pos=")) {
        position = new int[] {
            Integer.parseInt(value.split(",")[0]), Integer.parseInt(value.split(",")[1])};
      } else if (arg.equals("--framed")) {
        framed = true;
      } else if (arg.startsWith("--progress=")) {
        progress = Float.parseFloat(value);
      } else if (arg.startsWith("--exit-after=")) {
        exitAfter = Double.parseDouble(value);
      } else if (arg.equals("--moves")) {
        moves = true;
      } else if (arg.startsWith("--cpu-png=")) {
        cpuPng = Path.of(value);
      } else {
        throw new IllegalArgumentException("Unknown option " + arg);
      }
    }

    long start = System.nanoTime();
    Window.Options options = Window.options().minimumSize(360, 240);
    if (!framed) {
      options.frameless();
    }
    try (Window window = Window.open("rethinkj window demo", width, height, options)) {
      if (position != null) {
        window.moveTo(position[0], position[1]);
      }
      log(start, "open: " + window.width() + "x" + window.height() + " at " + window.x() + ","
          + window.y() + ", scale " + window.scale() + ", " + window.pixelWidth() + "x"
          + window.pixelHeight() + " pixels, " + window.refreshRate() + " Hz, " + window.handle());
      if (progress >= 0) {
        log(start, "progress " + progress + ": "
            + window.progress(Window.Progress.NORMAL, progress));
      }
      State state = new State(window);
      App<State> app = new App<>(state, WindowDemo::view);
      try (GpuPresenter gpu = new GpuPresenter(window)) {
        log(start, "renderer " + gpu.adapter() + ", surface " + gpu.surfaceFormat());
        WindowLoop.Presenter presenter = new WindowLoop.Presenter() {
          @Override
          public void present(Scene scene, float w, float h, float scale) {
            long before = gpu.frames();
            gpu.present(scene, w, h, scale);
            String what = gpu.frames() > before ? "present " + gpu.frames()
                : gpu.pending() ? "pending" : "not shown";
            log(start, what + ": " + w + "x" + h + " @" + scale + ", " + window.pixelWidth()
                + "x" + window.pixelHeight() + " pixels, lights "
                + (state.lights.on ? "on" : "off") + (app.isMoving() ? ", moving" : ""));
          }

          @Override
          public boolean pending() {
            return gpu.pending();
          }
        };
        WindowLoop loop = new WindowLoop(window, app, presenter);
        boolean printMoves = moves;
        loop.listener(event -> {
          boolean move = event instanceof PointerMove
              || event instanceof WindowEvent.Redraw redraw && redraw.live();
          if (printMoves || !move) {
            log(start, event.toString());
          }
          if (event instanceof KeyDown down && down.key().name().equals("Escape")) {
            loop.stop();
          }
        });
        if (exitAfter > 0) {
          long millis = (long) (exitAfter * 1000);
          Thread.ofPlatform().daemon().start(() -> {
            try {
              Thread.sleep(millis);
            } catch (InterruptedException e) {
              return;
            }
            loop.stop();
          });
        }
        loop.run();
        log(start, "stopped after " + gpu.frames() + " frames");
        if (cpuPng != null) {
          Pixels pixels = app.render().pixels();
          Files.write(cpuPng, Png.encode(pixels));
          log(start, "wrote the CPU's " + pixels.width() + "x" + pixels.height() + " image of the"
              + " last frame to " + cpuPng.toAbsolutePath());
        }
      }
    }
    log(start, "closed");
  }

  static El view(State state) {
    boolean maximized = state.window.isOpen() && state.window.isMaximized();
    El bar =
        row().key("title-bar")
            .wFull()
            .h(TITLE_BAR)
            .shrink(0)
            .align(Style.Align.CENTER)
            .fill(BAR)
            .windowPart(WindowPart.DRAG)
            .child(el().key("icon").size(14, 14).radius(4).fill(0xFF3B82F6).margin(0, 10, 0, 12))
            .child(el().key("title").size(120, 6).radius(3).fill(0xFF4A5068))
            .child(el().grow(1))
            .child(button("minimize", WindowPart.MINIMIZE, BUTTON, minimizeGlyph()))
            .child(button("maximize", WindowPart.MAXIMIZE, BUTTON,
                maximized ? restoreGlyph() : maximizeGlyph()))
            .child(button("close", WindowPart.CLOSE, CLOSE_BUTTON, closeGlyph()));
    El body = Examples.switchAlone(state.lights).grow(1);
    return col().wFull().hFull().fill(0xFF101014).child(bar).child(body);
  }

  private static El button(String key, WindowPart part, int fill, El glyph) {
    return el().key(key).size(46, TITLE_BAR).center().fill(fill).windowPart(part).child(glyph);
  }

  private static El minimizeGlyph() {
    return el().size(10, 1).fill(GLYPH);
  }

  private static El maximizeGlyph() {
    return el().size(10, 10).border(1, GLYPH);
  }

  private static El restoreGlyph() {
    return el().size(10, 10)
        .child(el().size(8, 8).absolute(2, 0).border(1, GLYPH))
        .child(el().size(8, 8).absolute(0, 2).border(1, GLYPH).fill(BUTTON));
  }

  /** An X from two diagonals of small squares. */
  private static El closeGlyph() {
    El glyph = el().size(10, 10);
    for (int i = 0; i <= 8; i++) {
      glyph.child(el().size(2, 2).absolute(i, i).fill(GLYPH));
      glyph.child(el().size(2, 2).absolute(8 - i, i).fill(GLYPH));
    }
    return glyph;
  }

  private static void log(long start, String text) {
    System.out.printf("%8.3f  %s%n", (System.nanoTime() - start) / 1e9, text);
    System.out.flush();
  }
}
