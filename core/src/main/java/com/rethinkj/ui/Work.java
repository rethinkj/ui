package com.rethinkj.ui;

/**
 * A computation that {@link App#background} runs on a virtual thread of its own, until it hands
 * its result or failure to the app or is cancelled.
 */
public final class Work {

  private final String name;
  private final App<?> app;
  volatile boolean cancelled; // read on the app's thread when the delivery's turn comes
  Thread thread; // set before the thread starts

  Work(String name, App<?> app) {
    this.name = name;
    this.app = app;
  }

  /** The name {@link App#background} was given, which {@link App#await} names while it runs. */
  public String name() {
    return name;
  }

  /**
   * Stops the work from delivering: once this returns, neither its result nor its failure reaches
   * a handler, even if it has finished and its delivery waits in the queue. Call it on the app's
   * thread, in a handler or a posted task, as when the user closes the tab that asked for it or
   * asks again; elsewhere a delivery may already be running. It also interrupts the thread, which
   * ends a {@code sleep}, a wait or a blocking read of a socket; what the computation does after
   * that is wasted, as {@link App#await} no longer waits for it. Cancelling again, or after the
   * delivery, does nothing.
   */
  public void cancel() {
    cancelled = true;
    thread.interrupt();
    app.finished(this);
  }

  @Override
  public String toString() {
    return name;
  }
}
