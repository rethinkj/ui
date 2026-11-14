package com.rethinkj.ui;

/**
 * A computation that {@link App#background} runs on a virtual thread of its own, until it hands
 * its result or failure to the app or is cancelled. Work started with a {@link Reporting}
 * computation also reports its progress on the way, through a {@link Progress}. Work started on a
 * {@link Lane} begins once the work before it on that lane has ended.
 *
 * <p>The work's computation ends when it returns or throws, which may be after a {@link #cancel}:
 * cancelling interrupts it, and what it does after that is up to it. {@link #onEnd} tells the app
 * when that happened, for an app that keeps a resource, such as a connection, that the next work
 * must not use while this one still does.
 */
public final class Work {

  private final String name;
  private final App<?> app;
  final Lane lane; // the lane it runs on, or null
  volatile boolean cancelled; // read on the app's thread when the delivery's turn comes
  volatile boolean ended; // the delivery is posted: reports made from now on are dropped
  volatile boolean delivered; // a handler of the delivery ran: no report runs after it
  Thread thread; // set before the thread starts, which a lane does later
  private boolean over; // the computation returned or threw, or never will; guarded by this
  private Runnable onEnd; // guarded by this

  Work(String name, App<?> app, Lane lane) {
    this.name = name;
    this.app = app;
    this.lane = lane;
  }

  /** The name {@link App#background} was given, which {@link App#await} names while it runs. */
  public String name() {
    return name;
  }

  /**
   * Stops the work from delivering: once this returns, neither its result nor its failure reaches
   * a handler, even if it has finished and its delivery waits in the queue, and no report of its
   * progress does either, also one that already waits. Call it on the app's thread, in a handler
   * or a posted task, as when the user closes the tab that asked for it or asks again; elsewhere a
   * delivery may already be running. It also interrupts the thread, which ends a {@code sleep}, a
   * wait or a blocking read of a socket, and {@link Progress#isCancelled} says so to a loop that
   * does not block; what the computation does after that is wasted. {@link App#await} no longer
   * waits for it, unless it has an {@link #onEnd} handler, whose run it waits for. Work on a
   * {@link Lane} that has not begun is taken off the lane: its computation never runs, and the
   * work counts as ended at once. Cancelling again, or after the delivery, does nothing.
   */
  public void cancel() {
    if (cancelled) {
      return;
    }
    cancelled = true;
    if (lane != null && lane.withdraw(this)) {
      end(); // it never began
      return;
    }
    thread.interrupt();
    synchronized (this) {
      if (onEnd != null && !over) {
        return; // await waits for its end, which end() then tells it
      }
    }
    app.finished(this);
  }

  /**
   * Runs {@code handler} on the app's thread, as a {@link App#post posted} task, once the
   * computation has ended: returned or thrown, after {@code onResult} or {@code onFailure} if they
   * run, and also after a {@link #cancel}, when neither runs, once the cancelled computation has
   * returned; for work on a {@link Lane} that is cancelled before it began, right after the
   * cancel. Registered after the end, it is posted at once, and so runs in the next frame. Several
   * handlers run in the order they were registered. Call it on the app's thread.
   *
   * <p>{@link App#await} waits for the end of work that has such a handler, also when it is
   * cancelled: so a script shows what the handler does. A computation that ignores the interrupt
   * is then waited for until it returns.
   */
  public void onEnd(Runnable handler) {
    java.util.Objects.requireNonNull(handler, "handler");
    synchronized (this) {
      if (!over) {
        Runnable before = onEnd;
        onEnd = before == null
            ? handler
            : () -> {
              before.run();
              handler.run();
            };
        if (cancelled) {
          app.waitFor(this); // forgotten by await at the cancel: wait for the end after all
        }
        return;
      }
    }
    app.post(handler);
  }

  /**
   * Whether the computation runs: it has begun and has neither returned nor thrown, also after a
   * {@link #cancel} that it has not answered yet. False for work on a {@link Lane} that waits for
   * its turn.
   */
  public boolean isRunning() {
    synchronized (this) {
      return !over && thread.getState() != Thread.State.NEW;
    }
  }

  /** The computation has ended or never will: posts the {@link #onEnd} handlers, on any thread. */
  void end() {
    Runnable handler;
    synchronized (this) {
      over = true;
      handler = onEnd;
    }
    if (handler != null) {
      app.post(handler);
    }
    app.finished(this);
  }

  @Override
  public String toString() {
    return name;
  }

  /**
   * What the computation of {@link App#background(String, Reporting, java.util.function.Consumer,
   * java.util.function.Consumer, java.util.function.Consumer)} reports its progress on, from the
   * work's thread: how many bytes came, how many rows were read.
   *
   * @param <P> the type of a report
   */
  public interface Progress<P> {

    /**
     * Hands the app a report, which runs the work's {@code onProgress} handler on the app's thread
     * as a {@link App#post posted} task does: at its turn among the input, with the view built
     * again after it. It never blocks. Reports end with the work: one made after {@link
     * Work#cancel} or after the result was handed over is dropped here, and one that waits when
     * the work is cancelled or its result or failure has reached its handler never runs.
     *
     * <p>Reports are coalesced: a report that waits in the queue takes the value of the reports
     * its work makes after it, as long as nothing else joined the queue after it and the app has
     * not taken it for a frame. So a download that reports every 8 KB runs its handler about once
     * per frame, with the newest value, and a report keeps its place among the tasks the work's
     * thread {@link App#post posts}. Report a value that stands for the whole progress, such as
     * the bytes so far, rather than a step to add.
     */
    void report(P value);

    /**
     * Whether the work was {@link Work#cancel cancelled}: a loop that does not block, and so does not see
     * the interrupt, stops early with it.
     */
    boolean isCancelled();
  }

  /**
   * A computation that reports its progress, for {@link App#background(String, Reporting,
   * java.util.function.Consumer, java.util.function.Consumer, java.util.function.Consumer)}.
   *
   * @param <T> the type of its result
   * @param <P> the type of a report
   */
  @FunctionalInterface
  public interface Reporting<T, P> {

    /** Computes the result on the work's thread, reporting on {@code progress} on the way. */
    T call(Progress<P> progress) throws Exception;
  }
}
