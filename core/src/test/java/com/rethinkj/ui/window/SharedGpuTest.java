package com.rethinkj.ui.window;

import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.gpu.Device;
import com.rethinkj.ui.gpu.GpuException;
import com.rethinkj.ui.gpu.RenderPipeline;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.render.Scene;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The windows of one loop draw with {@link GpuPresenter}s on one GPU device: three real windows,
 * closed in each of the six orders, the first among them, make one device and compile one
 * pipeline, and the windows left after each close draw on. Skipped like {@link GpuPresenterTest}.
 */
@Timeout(120)
class SharedGpuTest {

  private static final String TITLE = "rethinkj shared GPU test";

  @BeforeEach
  void needsWindows() {
    Desktop.assumeWindows();
  }

  /** A presenter for the window, or the test is skipped with the reason. */
  static GpuPresenter presenter(Window window) {
    try {
      return new GpuPresenter(window);
    } catch (UnsatisfiedLinkError e) {
      assumeTrue(false, "Skipped: wgpu-native cannot be loaded. " + e.getMessage());
    } catch (GpuException e) {
      if (e.kind() == GpuException.Kind.ADAPTER_UNAVAILABLE) {
        assumeTrue(false, "Skipped: no adapter can present to the window. " + e.wgpuMessage());
      }
      throw e;
    }
    throw new AssertionError("unreachable");
  }

  /** What the three windows show: a colour that a count picks. */
  static final class Shared {
    int count;
  }

  private static El view(Shared s) {
    return row()
        .key("count-" + s.count)
        .wFull()
        .hFull()
        .fill(s.count % 2 == 0 ? 0xFF2050A0 : 0xFFA05020);
  }

  /** A {@link GpuPresenter} that counts its frames and says when it closes. */
  static final class Counting implements WindowLoop.Presenter, AutoCloseable {
    final GpuPresenter gpu;
    final List<String> log;
    final String name;

    Counting(String name, Window window, List<String> log) {
      this.name = name;
      this.log = log;
      this.gpu = presenter(window);
    }

    @Override
    public void present(Scene scene, float width, float height, float scale) {
      gpu.present(scene, width, height, scale);
      log.add(name + " presents " + gpu.frames() + " pending " + gpu.pending());
    }

    @Override
    public boolean pending() {
      return gpu.pending();
    }

    @Override
    public void close() {
      gpu.close();
      log.add(name + " closed");
    }
  }

  @Test
  void threeWindowsShareOneDeviceAndOnePipelineInEveryOrderOfClosing() {
    int[][] orders = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
    for (int[] order : orders) {
      closeInOrder(order);
    }
  }

  /** Opens three windows, lets them draw, and closes them in {@code order}. */
  private static void closeInOrder(int[] order) {
    String which = "closing " + order[0] + ", " + order[1] + ", " + order[2];
    long devices = Device.created();
    long pipelines = RenderPipeline.created();
    Shared s = new Shared();
    List<String> log = new ArrayList<>();
    List<Counting> presenters = new ArrayList<>();
    try (Window a = Window.open(TITLE, 120, 80, Window.options().position(100, 100));
        Counting first = new Counting("0", a, log)) {
      Desktop.settle();
      presenters.add(first);
      App<Shared> appA = new App<>(s, SharedGpuTest::view);
      AtomicReference<WindowLoop> loop = new AtomicReference<>();
      loop.set(new WindowLoop(a, appA, first).presenters(w -> {
        Counting c = new Counting(String.valueOf(presenters.size()), w, log);
        presenters.add(c);
        return c;
      }));
      List<Windows.Handle> handles = new ArrayList<>();
      handles.add(loop.get().windows().of(appA));
      handles.add(loop.get()
          .open(
              TITLE + " 1",
              120,
              80,
              Window.options().position(300, 100),
              new App<>(s, SharedGpuTest::view)));
      handles.add(loop.get()
          .open(
              TITLE + " 2",
              120,
              80,
              Window.options().position(500, 100),
              new App<>(s, SharedGpuTest::view)));
      long[] seen = new long[3];
      int[] closed = new int[1];
      loop.get().waits((millis, holdBack) -> {
        if (millis != -1) {
          return; // not idle yet
        }
        // Idle: every open window has drawn the count, also after the window before closed.
        for (int i = 0; i < 3; i++) {
          if (handles.get(i).isOpen()) {
            long frames = presenters.get(i).gpu.frames();
            assertTrue(
                frames > seen[i],
                which + ": window " + i + " drew " + frames + " frames, as before the last close: "
                    + log);
            seen[i] = frames;
          }
        }
        // A task of the window closes it, after the pass, and the others draw the new count.
        Windows.Handle next = handles.get(order[closed[0]++]);
        next.app().post(() -> {
          s.count++;
          next.close();
        });
      });
      Desktop.run(loop.get(), () -> which + ": the three windows close: " + log);
      assertEquals(3, closed[0], which);
      assertEquals(
          List.of(
              String.valueOf(order[0]) + " closed",
              String.valueOf(order[1]) + " closed",
              String.valueOf(order[2]) + " closed"),
          log.stream().filter(l -> l.endsWith(" closed")).toList(),
          which + ": each presenter closed with its window");
    }
    assertEquals(1, Device.created() - devices, which + ": one device for the three windows");
    assertEquals(1, RenderPipeline.created() - pipelines, which + ": one pipeline");
  }

  @Test
  void aWindowOpenedAfterAllOthersClosedMakesANewDevice() {
    long devices = Device.created();
    try (Window a = Window.open(TITLE, 120, 80);
        GpuPresenter gpu = presenter(a)) {
      Desktop.settle();
      Shared s = new Shared();
      App<Shared> app = new App<>(s, SharedGpuTest::view);
      app.resize(a.width(), a.height(), a.scale());
      app.frame();
      gpu.present(app.scene(), a.width(), a.height(), a.scale());
      assertEquals(1, gpu.frames());
    }
    assertEquals(1, Device.created() - devices);
    try (Window b = Window.open(TITLE + " later", 120, 80);
        GpuPresenter gpu = presenter(b)) {
      Desktop.settle();
      App<Shared> app = new App<>(new Shared(), SharedGpuTest::view);
      app.resize(b.width(), b.height(), b.scale());
      app.frame();
      gpu.present(app.scene(), b.width(), b.height(), b.scale());
      assertEquals(1, gpu.frames(), "the new window draws on a device of its own");
    }
    assertEquals(2, Device.created() - devices, "the first device closed with its window");
  }
}
