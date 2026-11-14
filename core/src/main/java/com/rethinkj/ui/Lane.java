package com.rethinkj.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Work that runs one piece after another, as the calls on one database connection, which only one
 * thread may use at a time: {@link App#lane} makes one, and the app keeps it in its state, one per
 * connection. Its {@link #background} methods are {@link App#background}'s, but a piece of work
 * begins only when every piece started before it on the lane has ended, that is, its computation
 * has returned or thrown, also one that was {@linkplain Work#cancel cancelled} while it ran.
 *
 * <pre>{@code
 * Lane connection = app.lane("staging"); // in the state, beside the connection
 *
 * void run(String sql) { // a handler, on the app's thread
 *   if (query != null) {
 *     query.cancel(); // interrupts it; the next begins once it has returned
 *   }
 *   query = connection.background("query", () -> db.query(sql),
 *       rows -> { this.rows = rows; query = null; },
 *       error -> { this.error = error.getMessage(); query = null; });
 * }
 * }</pre>
 *
 * <p>Each piece runs on a virtual thread of its own, started when its turn comes, so that the
 * interrupt of a cancelled piece never reaches the next. Its result, failure and reports arrive
 * as with {@link App#background}, and {@link App#await} waits for every piece that is neither
 * delivered nor cancelled, also one that has not begun. Cancelling a piece that has not begun
 * takes it off the lane without ever running its computation; cancelling the one that runs
 * interrupts it, and the next begins when it has really returned. A computation that ignores the
 * interrupt therefore holds the lane until it returns: {@link #pending} says what waits behind it.
 *
 * <p>A lane belongs to the app it was made from, on whose thread its methods are called and its
 * handlers run. The apps of several windows of one state run on one thread, so a handler of any
 * of them may start work on it; its answers arrive in the app it was made from, and the others
 * show them as they show any change of the state.
 */
public final class Lane {

  private final App<?> app;
  private final String name;
  private Work current; // the piece whose computation runs or may still run; guarded by this
  private final ArrayDeque<Work> waiting = new ArrayDeque<>(); // guarded by this

  Lane(App<?> app, String name) {
    this.app = app;
    this.name = Objects.requireNonNull(name, "name");
  }

  /** The name {@link App#lane} was given. */
  public String name() {
    return name;
  }

  /**
   * {@link App#background(String, Callable, Consumer, Consumer)} on this lane: {@code compute}
   * begins once the work started before it here has ended.
   */
  public <T> Work background(
      String name,
      Callable<? extends T> compute,
      Consumer<? super T> onResult,
      Consumer<? super Exception> onFailure) {
    Objects.requireNonNull(compute, "compute");
    return app.start(this, name, work -> compute, onResult, onFailure);
  }

  /**
   * {@link App#background(String, Work.Reporting, Consumer, Consumer, Consumer)} on this lane:
   * {@code compute} begins once the work started before it here has ended.
   */
  public <T, P> Work background(
      String name,
      Work.Reporting<? extends T, P> compute,
      Consumer<? super P> onProgress,
      Consumer<? super T> onResult,
      Consumer<? super Exception> onFailure) {
    return app.start(this, name, compute, onProgress, onResult, onFailure);
  }

  /**
   * The work whose computation has not ended, in the order it runs: first the piece that runs, if
   * one does, also one that was cancelled and has not returned yet ({@link Work#isRunning}), then
   * those that wait for their turn. Empty when the lane is idle.
   */
  public synchronized List<Work> pending() {
    List<Work> all = new ArrayList<>(waiting.size() + 1);
    if (current != null) {
      all.add(current);
    }
    all.addAll(waiting);
    return all;
  }

  @Override
  public String toString() {
    return name;
  }

  /** Starts the work now if the lane is idle, else after the work before it. */
  synchronized void submit(Work work) {
    if (current == null) {
      current = work;
      work.thread.start();
    } else {
      waiting.add(work);
    }
  }

  /** Takes the work off the lane if it has not begun; whether it did. */
  synchronized boolean withdraw(Work work) {
    return waiting.remove(work);
  }

  /** The computation of the current work has ended, on its thread: the next begins. */
  synchronized void next() {
    current = waiting.poll();
    if (current != null) {
      current.thread.start();
    }
  }
}
