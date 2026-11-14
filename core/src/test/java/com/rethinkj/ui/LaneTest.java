package com.rethinkj.ui;

import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.window.Window;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Work on a {@link Lane}, which runs one piece after another, and {@link Work#onEnd}, which tells
 * the app that a computation has really ended, also after a cancel.
 */
class LaneTest {

  private static final Duration LONG = Duration.ofSeconds(10);
  private static final Duration SHORT = Duration.ofMillis(50);

  @TempDir
  Path out;

  /** What the handlers saw, in their order, on the app's thread. */
  static final class Log {
    final List<String> log = new ArrayList<>();
    Lane lane;
    Work work;
  }

  private static App<Log> start() {
    App<Log> app = new App<>(new Log(), s -> el().wFull().hFull());
    app.resize(100, 100, 1);
    app.frame();
    app.state().lane = app.lane("connection");
    return app;
  }

  /** Starts a piece on the app's lane whose handlers log its result or failure. */
  private static Work piece(App<Log> app, String name, Callable<Object> compute) {
    Log s = app.state();
    return s.lane.background(
        name, compute, r -> s.log.add(name + " " + r), f -> s.log.add(name + " failed " + f));
  }

  /** Waits on the latch and ignores the interrupt, as a driver that finishes its statement. */
  private static void ignoringInterrupts(CountDownLatch latch) {
    boolean interrupted = false;
    while (true) {
      try {
        latch.await();
        break;
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void threePiecesNeverOverlapAndRunInTheOrderTheyWereStarted() throws InterruptedException {
    App<Log> app = start();
    AtomicInteger runs = new AtomicInteger();
    AtomicInteger most = new AtomicInteger();
    List<String> order = Collections.synchronizedList(new ArrayList<>());
    for (String name : List.of("a", "b", "c")) {
      piece(app, name, () -> {
        most.accumulateAndGet(runs.incrementAndGet(), Math::max);
        order.add(name);
        Thread.sleep(20);
        runs.decrementAndGet();
        return "done";
      });
    }
    app.await(LONG); // also for the pieces that had not begun
    app.frame();

    assertEquals(1, most.get(), "never two at once");
    assertEquals(List.of("a", "b", "c"), order);
    assertEquals(List.of("a done", "b done", "c done"), app.state().log);
    assertEquals(List.of(), app.state().lane.pending());
  }

  @Test
  void aWaitingPieceThatIsCancelledNeverRunsAndTheNextGoesOn() throws InterruptedException {
    App<Log> app = start();
    CountDownLatch gate = new CountDownLatch(1);
    AtomicInteger ranSecond = new AtomicInteger();
    Work first = piece(app, "first", () -> {
      gate.await();
      return 1;
    });
    Work second = piece(app, "second", () -> ranSecond.incrementAndGet());
    Work third = piece(app, "third", () -> 3);
    assertEquals(List.of(first, second, third), app.state().lane.pending());
    assertFalse(second.isRunning(), "it waits for its turn");

    second.cancel();
    assertEquals(List.of(first, third), app.state().lane.pending());
    gate.countDown();
    app.await(LONG);
    app.frame();

    assertEquals(0, ranSecond.get(), "its computation never ran");
    assertEquals(List.of("first 1", "third 3"), app.state().log);
  }

  @Test
  void theNextPieceBeginsOnlyOnceTheCancelledOneHasReturned() throws InterruptedException {
    App<Log> app = start();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch nextBegan = new CountDownLatch(1);
    AtomicReference<Boolean> interrupted = new AtomicReference<>();
    Work slow = piece(app, "slow", () -> {
      started.countDown();
      ignoringInterrupts(release);
      interrupted.set(Thread.currentThread().isInterrupted());
      return "late";
    });
    Work next = piece(app, "next", () -> {
      nextBegan.countDown();
      return "rows";
    });
    started.await();
    slow.cancel();

    assertFalse(nextBegan.await(SHORT.toMillis(), TimeUnit.MILLISECONDS), "the lane is held");
    assertTrue(slow.isRunning(), "cancelled, but its computation runs on");
    assertEquals(List.of(slow, next), app.state().lane.pending());
    release.countDown();
    assertTrue(nextBegan.await(LONG.toSeconds(), TimeUnit.SECONDS));
    app.await(LONG);
    app.frame();

    assertTrue(interrupted.get(), "the cancel interrupted it");
    assertFalse(slow.isRunning());
    assertEquals(List.of("next rows"), app.state().log, "nothing of the cancelled piece");
  }

  @Test
  void aComputationThatThrowsDoesNotStopTheLane() throws InterruptedException {
    App<Log> app = start();
    IOException broken = new IOException("connection reset");
    piece(app, "first", () -> {
      throw broken;
    });
    piece(app, "deep", () -> {
      throw new StackOverflowError("too deep");
    });
    piece(app, "last", () -> "rows");
    app.await(LONG);
    StackOverflowError thrown = assertThrows(StackOverflowError.class, app::frame);
    assertEquals("too deep", thrown.getMessage(), "on the app's thread, as without a lane");
    app.frame();

    assertEquals(List.of("first failed " + broken, "last rows"), app.state().log);
  }

  @Test
  void twoLanesRunSideBySide() throws InterruptedException {
    App<Log> app = start();
    Lane other = app.lane("other connection");
    CountDownLatch both = new CountDownLatch(2);
    Callable<Object> meet = () -> {
      both.countDown();
      return both.await(LONG.toSeconds(), TimeUnit.SECONDS) ? "met" : "alone";
    };
    piece(app, "here", meet);
    Log s = app.state();
    other.background("there", meet, r -> s.log.add("there " + r), f -> {});
    app.await(LONG);
    app.frame();

    assertEquals(List.of("here met", "there met"), s.log.stream().sorted().toList());
  }

  @Test
  void onEndRunsAfterTheResultAndAfterTheFailure() throws InterruptedException {
    App<Log> app = start();
    Log s = app.state();
    piece(app, "ok", () -> 1).onEnd(() -> s.log.add("ok ended"));
    Work failing = app.background(
        "failing",
        () -> {
          throw new IOException("no such table");
        },
        r -> {},
        f -> s.log.add("failing failed"));
    failing.onEnd(() -> s.log.add("failing ended"));
    app.await(LONG);
    app.frame();

    assertEquals(List.of("ok 1", "ok ended", "failing failed", "failing ended"), s.log);
  }

  @Test
  void onEndOfACancelledComputationRunsOnceItReturnedAndAwaitWaitsForIt()
      throws InterruptedException {
    App<Log> app = start();
    Log s = app.state();
    CountDownLatch started = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    Callable<Object> stubborn = () -> {
      started.countDown();
      ignoringInterrupts(release);
      return "late";
    };
    Work before = app.background("before", stubborn, r -> s.log.add("result"), f -> {});
    Work after = app.background("after", stubborn, r -> s.log.add("result"), f -> {});
    started.await();
    before.onEnd(() -> s.log.add("before ended"));
    before.cancel();
    after.cancel();
    after.onEnd(() -> s.log.add("after ended")); // registered after the cancel

    IllegalStateException held = assertThrows(IllegalStateException.class, () -> app.await(SHORT));
    assertTrue(held.getMessage().contains("still runs: before, after."), held.getMessage());
    app.frame();
    assertEquals(List.of(), s.log, "not before the computations returned");
    release.countDown();
    app.await(LONG);
    app.frame();

    assertEquals(List.of("after ended", "before ended"), s.log.stream().sorted().toList());
  }

  @Test
  void onEndOfAPieceCancelledBeforeItBeganRunsAtTheNextFrame() throws InterruptedException {
    App<Log> app = start();
    Log s = app.state();
    CountDownLatch gate = new CountDownLatch(1);
    piece(app, "first", () -> {
      gate.await();
      return 1;
    });
    Work waiting = piece(app, "waiting", () -> 2);
    waiting.onEnd(() -> s.log.add("waiting ended"));
    waiting.cancel();
    app.frame();
    assertEquals(List.of("waiting ended"), s.log, "without waiting for the piece before it");
    gate.countDown();
    app.await(LONG);
    app.frame();

    assertEquals(List.of("waiting ended", "first 1"), s.log);
  }

  @Test
  void onEndRegisteredAfterTheEndRunsAtTheNextFrame() throws InterruptedException {
    App<Log> app = start();
    Log s = app.state();
    Work work = piece(app, "done", () -> 1);
    app.await(LONG);
    app.frame();
    work.onEnd(() -> s.log.add("ended"));
    assertEquals(List.of("done 1"), s.log, "not at once");
    app.frame();

    assertEquals(List.of("done 1", "ended"), s.log);
    assertFalse(work.isRunning());
  }

  @Test
  void cancelledWorkWithoutOnEndIsNotWaitedFor() throws InterruptedException {
    App<Log> app = start();
    CountDownLatch release = new CountDownLatch(1);
    Work work = app.background(
        "stubborn",
        () -> {
          ignoringInterrupts(release);
          return 1;
        },
        r -> {},
        f -> {});
    work.cancel();
    app.await(SHORT); // as before lanes and onEnd
    release.countDown();
  }

  // Scripts

  /** A press starts three pieces on a lane; the label counts the answers. */
  @Test
  void aScriptsAwaitShowsEveryPieceOfALaneDone() throws IOException {
    AtomicReference<App<Log>> holder = new AtomicReference<>();
    App<Log> app = new App<>(
        new Log(),
        s -> el().wFull().hFull().key("run").label(s.log.size() + " done").onPress(() -> {
          for (int i = 1; i <= 3; i++) {
            int n = i;
            s.lane.background(
                "query " + n,
                () -> {
                  Thread.sleep(10);
                  return n;
                },
                r -> s.log.add("query " + r),
                f -> {});
          }
        }));
    holder.set(app);
    app.state().lane = app.lane("connection");
    Script.run("size 100 100\nframe\npress #run\nframe\nawait\nframe\nsnap done\n", app, out);

    assertTrue(Files.readString(out.resolve("done.tree.txt")).contains("label=\"3 done\""));
    assertEquals(List.of("query 1", "query 2", "query 3"), app.state().log);
  }

  /** The second piece reports once the first has ended; await progress lets both in. */
  @Test
  void aScriptsAwaitProgressTakesTheReportOfAPieceThatBeganAfterAnother() throws IOException {
    CountDownLatch gate = new CountDownLatch(1);
    App<Log> app = new App<>(
        new Log(),
        s -> el().wFull().hFull().key("run").label(String.join(", ", s.log)).onPress(() -> {
          s.lane.background("first", () -> "rows", r -> s.log.add("first " + r), f -> {});
          s.lane.background(
              "export",
              (Work.Progress<Integer> progress) -> {
                progress.report(50);
                gate.await();
                return "file";
              },
              (Integer done) -> s.log.add("export " + done + "%"),
              r -> s.log.add("export " + r),
              f -> {});
        }));
    app.state().lane = app.lane("connection");
    Script.run(
        "size 100 100\nframe\npress #run\nframe\nawait progress\nframe\nsnap half\n", app, out);
    assertTrue(
        Files.readString(out.resolve("half.tree.txt")).contains("label=\"first rows, export 50%\""),
        Files.readString(out.resolve("half.tree.txt")));
    gate.countDown();
    Script.run("size 100 100\nawait\nframe\nsnap done\n", app, out);
    assertEquals(List.of("first rows", "export 50%", "export file"), app.state().log);
  }

  /** Cancelled while it runs, the slow query's onEnd shows only after its computation returned. */
  @Test
  void aScriptShowsOnEndOfACancelledPieceAndTheNextAfterIt() throws IOException {
    CountDownLatch release = new CountDownLatch(1);
    App<Log> app = new App<>(
        new Log(),
        s -> el().wFull()
            .hFull()
            .children(
                el().key("run").w(10).h(10).onPress(() -> {
                  s.work = s.lane.background(
                      "slow",
                      () -> {
                        ignoringInterrupts(release);
                        return "late";
                      },
                      r -> s.log.add("slow " + r),
                      f -> {});
                  s.lane.background("next", () -> "rows", r -> s.log.add("next " + r), f -> {});
                }),
                el().key("cancel").w(10).h(10).onPress(() -> {
                  s.work.onEnd(() -> s.log.add("slow ended"));
                  s.work.cancel();
                }),
                el().key("log")
                    .w(10)
                    .h(10)
                    .label(String.join(", ", s.log) + " pending "
                        + s.lane.pending().size())));
    app.state().lane = app.lane("connection");
    Script.run(
        "size 100 100\nframe\npress #run\nframe\npress #cancel\nframe\nsnap cancelling\n",
        app,
        out);
    assertTrue(
        Files.readString(out.resolve("cancelling.tree.txt")).contains("label=\" pending 2\""),
        Files.readString(out.resolve("cancelling.tree.txt")));
    release.countDown();
    Script.run("size 100 100\nawait\nframe\nsnap ended\n", app, out);
    assertTrue(
        Files.readString(out.resolve("ended.tree.txt"))
            .contains("label=\"slow ended, next rows pending 0\""),
        Files.readString(out.resolve("ended.tree.txt")));
  }

  /** A second window of the same state starts work on the first app's lane: both show it. */
  @Test
  void aLaneMadeByOneAppTakesWorkFromAnotherWindowOfTheSameState() throws IOException {
    Log state = new Log();
    App<Log> second = new App<>(
        state,
        s -> el().wFull()
            .hFull()
            .key("query")
            .label("second " + s.log)
            .onPress(
                () -> s.lane.background("query", () -> 7, r -> s.log.add("rows " + r), f -> {})));
    AtomicReference<App<Log>> holder = new AtomicReference<>();
    App<Log> main = new App<>(
        state,
        s -> el().wFull()
            .hFull()
            .key("open")
            .label("main " + s.log)
            .onPress(
                () -> holder.get().windows().open("Second", 100, 100, Window.options(), second)));
    holder.set(main);
    state.lane = main.lane("connection");
    Script.run(
        "size 100 100\nframe\npress #open\nframe\nwindow Second\nframe\npress #query\nframe\n"
            + "await\nframe\nsnap done\n",
        main,
        out);

    assertTrue(Files.readString(out.resolve("done.tree.txt")).contains("label=\"main [rows 7]\""));
    assertTrue(Files.readString(out.resolve("second-done.tree.txt"))
        .contains("label=\"second [rows 7]\""));
  }
}
