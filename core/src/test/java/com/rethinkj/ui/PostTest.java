package com.rethinkj.ui;

import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tasks that other threads hand the app ({@link App#post}) and the work it runs on virtual threads
 * ({@link App#background}): they run on the app's thread at their turn among the input, and
 * {@link App#await} waits for them; the progress such work reports ({@link Work.Progress}), which
 * ends with the work, and {@link App#awaitProgress}, which lets it in while the work runs.
 */
class PostTest {

  private static final Duration LONG = Duration.ofSeconds(10);

  @TempDir
  Path out;

  /** A count that keys raise from the value their view saw, and what the tasks saw. */
  static final class Counter {
    int count;
    final List<String> log = new ArrayList<>();
    Object result;
    Exception failure;
    Work work;
  }

  private static El counter(Counter s) {
    int count = s.count; // a key handler sees its view's value, as a selection in a grid
    return el().wFull().hFull().focusable().key("counter").onKey(k -> {
      s.count = count + 1;
      s.log.add("key " + s.count);
      return true;
    });
  }

  private static App<Counter> start() {
    App<Counter> app = new App<>(new Counter(), PostTest::counter);
    app.resize(100, 100, 1);
    app.frame();
    app.focus(app.tree());
    app.frame();
    return app;
  }

  @Test
  void tasksOfTenThreadsRunOnceEachOnTheAppsThreadInTheOrderOfEachThread()
      throws InterruptedException {
    App<Counter> app = start();
    Thread appThread = Thread.currentThread();
    int threads = 10;
    int tasks = 1000;
    int[] next = new int[threads]; // the next task of each thread, touched on the app's thread only
    List<String> wrong = new ArrayList<>();
    AtomicInteger ran = new AtomicInteger();
    List<Thread> posters = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      int thread = t;
      posters.add(Thread.ofPlatform().start(() -> {
        for (int i = 0; i < tasks; i++) {
          int task = i;
          app.post(() -> {
            if (Thread.currentThread() != appThread || next[thread] != task) {
              wrong.add(
                  thread + "/" + task + " after " + next[thread] + " on " + Thread.currentThread());
            }
            next[thread] = task + 1;
            ran.incrementAndGet();
          });
        }
      }));
    }
    long end = System.nanoTime() + LONG.toNanos();
    for (int i = 0; ran.get() < threads * tasks && System.nanoTime() < end; i++) {
      app.pointerMove(i % 100, 50); // input between the tasks
      app.frame();
    }
    for (Thread poster : posters) {
      poster.join();
    }
    app.frame();

    assertEquals(List.of(), wrong);
    assertEquals(threads * tasks, ran.get(), "each task once");
    for (int t = 0; t < threads; t++) {
      assertEquals(tasks, next[t]);
    }
  }

  @Test
  void aKeyThenATaskThenAKeyEachSeeWhatTheOneBeforeLeft() throws InterruptedException {
    App<Counter> app = start();
    Counter s = app.state();
    app.keyDown(new Key("ArrowUp"));
    Thread other = Thread.ofPlatform()
        .start(() -> app.post(() -> {
          s.log.add("task saw " + s.count);
          s.count *= 10;
        }));
    other.join(); // posted before the second key comes
    app.keyDown(new Key("ArrowUp"));
    assertTrue(app.frame());

    assertEquals(
        List.of("key 1", "task saw 1", "key 11"),
        s.log,
        "the task sees the first key's count, and the second key a view built after the task");
    assertEquals(11, s.count);
  }

  @Test
  void aTaskPostedWhileAFrameRunsWaitsForTheNextFrame() {
    App<Counter> app = start();
    Counter s = app.state();
    app.post(() -> {
      s.log.add("first");
      app.post(() -> s.log.add("second")); // as a task that posts itself again would
    });
    app.frame();
    assertEquals(List.of("first"), s.log);
    app.frame();
    assertEquals(List.of("first", "second"), s.log);
  }

  @Test
  void anIdleAppDrawsNothingWithAQuietThreadAndOnceAfterAPost() throws InterruptedException {
    App<Counter> app = start();
    CountDownLatch quiet = new CountDownLatch(1);
    Thread idle = Thread.ofVirtual().start(() -> {
      try {
        quiet.await(); // a background thread that posts nothing
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    for (int i = 0; i < 3; i++) {
      assertFalse(app.frame(), "nothing to draw");
    }
    Thread poster = Thread.ofPlatform().start(() -> app.post(() -> app.state().count = 7));
    poster.join();
    assertTrue(app.frame(), "the task changed the state, and the frame draws it");
    assertFalse(app.frame(), "once");
    assertEquals(7, app.state().count);
    quiet.countDown();
    idle.join();
  }

  @Test
  void aBurstOfTasksWakesTheRunnerOnceUntilTheAppTakesIt() throws InterruptedException {
    App<Counter> app = start();
    AtomicInteger wakes = new AtomicInteger();
    app.onPost(wakes::incrementAndGet);
    Thread burst = Thread.ofPlatform().start(() -> {
      for (int i = 0; i < 100; i++) {
        app.post(() -> app.state().count++);
      }
    });
    burst.join();
    assertEquals(1, wakes.get());
    assertTrue(app.frame());
    assertEquals(100, app.state().count, "all of the burst in one frame");
    app.post(() -> app.state().count++);
    assertEquals(2, wakes.get(), "a task after the app took the burst wakes again");
  }

  @Test
  void aTaskThatThrowsEndsTheFrameAsAHandlerDoesAndTheInputAfterItWaits() {
    App<Counter> app = start();
    app.post(() -> {
      throw new IllegalStateException("broken");
    });
    app.keyDown(new Key("ArrowUp"));
    IllegalStateException thrown = assertThrows(IllegalStateException.class, app::frame);
    assertEquals("broken", thrown.getMessage());
    assertEquals(0, app.state().count);
    app.frame();
    assertEquals(1, app.state().count, "the key ran in the next frame");
  }

  // background

  @Test
  void theResultArrivesOnTheAppsThreadOnceAwaited() throws InterruptedException {
    App<Counter> app = start();
    Counter s = app.state();
    Thread appThread = Thread.currentThread();
    Thread[] ranOn = new Thread[2];
    app.background(
        "answer",
        () -> {
          ranOn[0] = Thread.currentThread();
          return 42;
        },
        result -> {
          ranOn[1] = Thread.currentThread();
          s.result = result;
        },
        failure -> s.failure = failure);
    app.await(LONG);
    assertTrue(app.frame());

    assertEquals(42, s.result);
    assertTrue(ranOn[0].isVirtual(), "computed on a virtual thread");
    assertSame(appThread, ranOn[1], "delivered on the app's thread");
  }

  @Test
  void aFailingComputationDeliversItsFailure() throws InterruptedException {
    App<Counter> app = start();
    Counter s = app.state();
    Exception error = new java.io.IOException("relation \"orders\" does not exist");
    app.background(
        "rows of orders",
        () -> {
          throw error;
        },
        result -> s.result = result,
        failure -> s.failure = failure);
    app.await(LONG);
    app.frame();

    assertSame(error, s.failure);
    assertEquals(null, s.result);
  }

  @Test
  void anErrorOfTheComputationIsThrownOnTheAppsThread() throws InterruptedException {
    App<Counter> app = start();
    app.background(
        "deep",
        () -> {
          throw new StackOverflowError("too deep");
        },
        result -> {},
        failure -> {});
    app.await(LONG);
    StackOverflowError thrown = assertThrows(StackOverflowError.class, app::frame);
    assertEquals("too deep", thrown.getMessage());
  }

  @Test
  void aCancelledComputationDeliversNothingAndIsInterrupted() throws InterruptedException {
    App<Counter> app = start();
    Counter s = app.state();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    Work work = app.background(
        "slow",
        () -> {
          started.countDown();
          try {
            Thread.sleep(LONG);
            return "late";
          } catch (InterruptedException e) {
            interrupted.countDown();
            throw e;
          }
        },
        result -> s.result = result,
        failure -> s.failure = failure);
    started.await();
    work.cancel();
    assertTrue(interrupted.await(LONG.toSeconds(), TimeUnit.SECONDS));
    app.await(LONG); // the cancelled work is not waited for
    assertFalse(app.frame(), "nothing arrived, and nothing was built for it");

    assertEquals(null, s.result);
    assertEquals(null, s.failure, "not even the interruption");
  }

  @Test
  void aCancelAfterTheResultIsQueuedStillKeepsItAway() throws InterruptedException {
    App<Counter> app = start();
    Counter s = app.state();
    Work first = app.background("first", () -> "first", r -> s.result = r, f -> s.failure = f);
    app.await(LONG); // its delivery is queued
    first.cancel(); // superseded before its turn
    app.background("second", () -> "second", r -> s.result = r, f -> s.failure = f);
    app.await(LONG);
    app.frame();

    assertEquals("second", s.result);
  }

  @Test
  void awaitNamesTheWorkThatStillRunsAfterItsTimeout() throws InterruptedException {
    App<Counter> app = start();
    CountDownLatch release = new CountDownLatch(1);
    Work work = app.background(
        "databases of staging",
        () -> {
          release.await();
          return List.of("shop");
        },
        r -> {},
        f -> {});
    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> app.await(Duration.ofMillis(50)));
    assertTrue(
        thrown.getMessage().contains("still runs: databases of staging."), thrown.getMessage());
    work.cancel();
    app.await(Duration.ofMillis(50));
    release.countDown();
  }

  // Scripts

  /** Work that a press starts and whose result is ready at once: still, only await lets it in. */
  @Test
  void aScriptLetsTheResultsInOnlyAtAwait() throws IOException {
    AtomicReference<App<Counter>> holder = new AtomicReference<>();
    App<Counter> app = new App<>(
        new Counter(),
        c -> el().wFull()
            .hFull()
            .key("load")
            .label(c.result == null ? "empty" : "loaded " + c.result)
            .onPress(() -> holder.get().background("rows", () -> 3, r -> c.result = r, f -> {})));
    holder.set(app);
    Script.run("size 100 100\nframe\npress #load\nframe\nframe\nsnap pressed\n", app, out);
    assertEquals("empty", app.tree().label(), "however fast the work was");
    Script.run("size 100 100\nawait\nframe\nsnap awaited\n", app, out);
    assertEquals("loaded 3", app.tree().label());
  }

  // Progress

  /** Reporting work that the test holds at a gate after its reports, as a script's work does. */
  private static Work reporting(
      App<Counter> app,
      String name,
      CountDownLatch reported,
      CountDownLatch gate,
      Work.Reporting<Object, Integer> before) {
    Counter s = app.state();
    return app.background(
        name,
        (Work.Progress<Integer> progress) -> {
          Object result = before.call(progress);
          reported.countDown();
          gate.await();
          return result;
        },
        (Integer done) -> s.log.add("progress " + done),
        result -> s.log.add("result " + result),
        failure -> s.log.add("failure " + failure));
  }

  @Test
  void reportsArriveInOrderAmongThePostsOfTheirThreadAndTheNewestOfARunRuns()
      throws InterruptedException {
    App<Counter> app = start();
    Counter s = app.state();
    CountDownLatch reported = new CountDownLatch(1);
    CountDownLatch gate = new CountDownLatch(1);
    reporting(app, "download", reported, gate, progress -> {
      app.post(() -> s.log.add("a"));
      progress.report(1);
      progress.report(2); // takes the place of 1, which waits right before it
      app.post(() -> s.log.add("b"));
      progress.report(3); // after b: in a place of its own
      return "done";
    });
    reported.await();
    assertTrue(app.frame());
    assertEquals(List.of("a", "progress 2", "b", "progress 3"), s.log);
    gate.countDown();
    app.await(LONG);
    app.frame();
    assertEquals("result done", s.log.getLast());
  }

  @Test
  void aReportThatTheAppTookKeepsItsValueAndTheNextRunsAfterIt() throws InterruptedException {
    App<Counter> app = start();
    Counter s = app.state();
    CountDownLatch first = new CountDownLatch(1);
    CountDownLatch on = new CountDownLatch(1);
    CountDownLatch second = new CountDownLatch(1);
    CountDownLatch gate = new CountDownLatch(1);
    reporting(app, "download", second, gate, progress -> {
      progress.report(1);
      first.countDown();
      on.await();
      progress.report(2);
      return "done";
    });
    first.await();
    app.holdPosts = true; // as a script does, so that the frame takes nothing on its own
    app.awaitProgress(LONG); // takes 1 for the next frame
    on.countDown();
    second.await(); // 2 is posted after the take: it does not change what was taken
    app.frame();
    assertEquals(List.of("progress 1"), s.log);
    app.awaitProgress(Duration.ZERO); // 2 waits already
    app.frame();
    assertEquals(List.of("progress 1", "progress 2"), s.log);
    gate.countDown();
    app.await(LONG);
  }

  @Test
  void cancelKeepsAwayTheReportsThatWaitAndDropsThoseMadeAfterIt() throws InterruptedException {
    App<Counter> app = start();
    Counter s = app.state();
    CountDownLatch reported = new CountDownLatch(1);
    CountDownLatch after = new CountDownLatch(1);
    AtomicReference<Boolean> sawCancel = new AtomicReference<>();
    AtomicReference<Work.Progress<Integer>> handle = new AtomicReference<>();
    Work work = app.background(
        "download",
        (Work.Progress<Integer> progress) -> {
          handle.set(progress);
          progress.report(1);
          reported.countDown();
          try {
            Thread.sleep(LONG);
          } catch (InterruptedException e) {
            sawCancel.set(progress.isCancelled());
            progress.report(2); // made after the cancel
            after.countDown();
            throw e;
          }
          return "late";
        },
        (Integer done) -> s.log.add("progress " + done),
        result -> s.log.add("result " + result),
        failure -> s.log.add("failure " + failure));
    reported.await();
    app.awaitProgress(LONG); // report 1 waits in the queue
    work.cancel();
    assertTrue(after.await(LONG.toSeconds(), TimeUnit.SECONDS));
    assertEquals(true, sawCancel.get(), "the loop can tell");
    handle.get().report(3); // also from another thread
    app.await(LONG);
    assertFalse(app.frame(), "nothing ran, and nothing was built for it");
    assertEquals(List.of(), s.log);
  }

  @Test
  void noReportRunsAfterTheResult() throws InterruptedException {
    App<Counter> app = start();
    Counter s = app.state();
    AtomicReference<Work.Progress<Integer>> handle = new AtomicReference<>();
    app.background(
        "download",
        (Work.Progress<Integer> progress) -> {
          handle.set(progress);
          progress.report(1);
          return "done";
        },
        (Integer done) -> s.log.add("progress " + done),
        result -> s.log.add("result " + result),
        failure -> s.log.add("failure " + failure));
    app.await(LONG);
    handle.get().report(2); // a handle that outlived its work: dropped where it is made
    assertTrue(app.frame());
    assertFalse(app.frame());
    assertEquals(List.of("progress 1", "result done"), s.log);
  }

  @Test
  void awaitProgressNamesTheWorkThatReportedNothingAndRefusesWhenNoneRuns()
      throws InterruptedException {
    App<Counter> app = start();
    IllegalStateException none =
        assertThrows(IllegalStateException.class, () -> app.awaitProgress(LONG));
    assertTrue(none.getMessage().startsWith("No work of the app runs"), none.getMessage());
    CountDownLatch release = new CountDownLatch(1);
    Work work = app.background(
        "rows of orders",
        () -> {
          release.await();
          return 1;
        },
        r -> {},
        f -> {});
    IllegalStateException quiet =
        assertThrows(IllegalStateException.class, () -> app.awaitProgress(Duration.ofMillis(50)));
    assertTrue(
        quiet.getMessage().contains("reported nothing new: rows of orders still runs."),
        quiet.getMessage());
    work.cancel();
    release.countDown();
  }

  /** A button that starts work which reports 50, waits at the gate and is done. */
  private static App<Counter> download(CountDownLatch gate) {
    AtomicReference<App<Counter>> holder = new AtomicReference<>();
    App<Counter> app = new App<>(
        new Counter(),
        c -> el().wFull()
            .hFull()
            .key("load")
            .label(c.result != null ? "done" : c.count + " of 100")
            .onPress(() -> c.work = holder
                .get()
                .background(
                    "download",
                    (Work.Progress<Integer> progress) -> {
                      progress.report(50);
                      gate.await();
                      return "file";
                    },
                    (Integer done) -> c.count = done,
                    r -> c.result = r,
                    f -> c.failure = f)));
    holder.set(app);
    return app;
  }

  @Test
  void aScriptShowsWorkHalfDoneAtAwaitProgressAndDoneAtAwait() throws IOException {
    CountDownLatch gate = new CountDownLatch(1);
    App<Counter> app = download(gate);
    Script.run(
        "size 100 100\nframe\npress #load\nframe\nframe\nsnap pressed\nawait progress\n"
            + "frame\nsnap half\n",
        app,
        out);
    assertTrue(
        Files.readString(out.resolve("pressed.tree.txt")).contains("label=\"0 of 100\""),
        "nothing before await progress");
    assertTrue(Files.readString(out.resolve("half.tree.txt")).contains("label=\"50 of 100\""));
    gate.countDown(); // as the next advance of a made-up download would
    Script.run("size 100 100\nawait\nframe\nsnap done\n", app, out);
    assertTrue(Files.readString(out.resolve("done.tree.txt")).contains("label=\"done\""));
  }

  @Test
  void awaitInAHandlerIsRefused() {
    App<Counter> app = start();
    app.post(() -> {
      try {
        app.await(LONG);
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
    });
    assertThrows(IllegalStateException.class, app::frame);
  }

  @Test
  void inputAfterAHandlerThatThrewGoesToATreeBuiltFromWhatItChanged() {
    App<int[]> app = new App<>(new int[1], s -> {
      int before = s[0];
      return el().size(100, 100).onKey(k -> {
        s[0] = before + 1; // from the state the tree was built from
        if (k.name().equals("A")) {
          throw new IllegalStateException("handler failed");
        }
        return true;
      });
    });
    app.resize(100, 100, 1);
    app.frame();
    app.keyDown(new Key("A"));
    app.keyDown(new Key("B"));
    assertThrows(IllegalStateException.class, app::frame);
    assertEquals(1, app.state()[0]);
    app.frame(); // B, after a rebuild
    assertEquals(2, app.state()[0]);
  }
}
