package com.rethinkj.ui;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The work of an {@link App}: the queue of its input in the order it came, the tasks other threads
 * {@link App#post post} until the app's thread takes them, the {@link App#background} work, its
 * reports, and the actions of {@link App#after}. Each entry of the queue returns whether it may
 * have changed something that the next rebuild shows.
 *
 * <p>Everything here belongs to the app's thread, except what other threads touch to post: {@link
 * #post}, a {@link Work.Progress#report report}, a work's end, and what {@link #onPost} set.
 */
final class Scheduler {

  // Tells the app that a handler of its own runs, before a task, an action, a delivery or a
  // report runs: one that throws may have changed something.
  private final Runnable handling;
  // Input in the order it came, dispatched by App#frame.
  private final ArrayDeque<BooleanSupplier> queued = new ArrayDeque<>();
  // What a handler queues while an input runs, such as a focus or a scroll: it runs next.
  private final List<BooleanSupplier> follow = new ArrayList<>();
  // What the El#onResize handlers asked for: it runs first in the next frame, in the order asked.
  private final List<BooleanSupplier> asked = new ArrayList<>();
  private boolean dispatching; // a handler runs
  // What post() took on any thread, until the app's thread moves it into queued.
  private final ConcurrentLinkedQueue<BooleanSupplier> posted = new ConcurrentLinkedQueue<>();
  private final AtomicBoolean woken = new AtomicBoolean(); // wake ran for what posted holds
  private volatile Runnable wake;
  boolean holdPosts; // for Script: posted tasks join the queue only at await()
  private final Set<Work> running = new LinkedHashSet<>(); // of background(); guarded by itself
  // Counts what joined the queue, posted, enqueued or taken: a report takes the place of a report
  // of its work that waits only while nothing joined after that one (Work.Progress#report).
  private final AtomicLong joined = new AtomicLong();
  private final AtomicLong reports = new AtomicLong(); // made by the app's work so far
  private long reportsTaken; // reports when the app last took what was posted
  // Notified at each report and each end of work, of every app: awaitProgress waits for several.
  private static final Object REPORTED = new Object();
  // Actions to run when the clock reaches their time, the earliest first; see App#after.
  private final PriorityQueue<Timer> timers = new PriorityQueue<>();
  private long timersAsked;

  Scheduler(Runnable handling) {
    this.handling = handling;
  }

  /** Whether a handler runs, between {@link #beginHandler} and its end. */
  boolean dispatching() {
    return dispatching;
  }

  /** Whether input waits in the queue. */
  boolean waiting() {
    return !queued.isEmpty();
  }

  /** Queues input for the next frame, or what a handler asks for right after that handler. */
  void enqueue(BooleanSupplier input) {
    joined.incrementAndGet();
    if (dispatching) {
      follow.add(input);
      return;
    }
    if (!holdPosts) {
      takePosted(); // what other threads posted before this input comes before it
    }
    queued.add(input);
  }

  /** Queues a handler the frame runs on its own, such as an El#onRowsBuilt, after the input. */
  void add(BooleanSupplier handler) {
    queued.add(handler);
  }

  /** Queues a handler before all input, to run next. */
  void addFirst(BooleanSupplier handler) {
    queued.addFirst(handler);
  }

  /** Takes the next input out of the queue. */
  BooleanSupplier poll() {
    return queued.poll();
  }

  /** A handler of the app begins: what it asks for follows it. */
  void beginHandler() {
    dispatching = true;
  }

  /** The handler has ended, or thrown: what it asked for runs next, in the order asked. */
  void endHandler() {
    dispatching = false;
    for (int i = follow.size() - 1; i >= 0; i--) {
      queued.addFirst(follow.get(i));
    }
    follow.clear();
  }

  /**
   * The handler, of {@link El#onResize}, has ended, or thrown: what it asked for waits until
   * {@link #takeAsked}.
   */
  void endResizeHandler() {
    dispatching = false;
    asked.addAll(follow);
    follow.clear();
  }

  /** Queues what the El#onResize handlers asked for first, as after their handler. */
  void takeAsked() {
    for (int i = asked.size() - 1; i >= 0; i--) {
      queued.addFirst(asked.get(i)); // first in the next frame, as after their handler
    }
    asked.clear();
  }

  /**
   * At the start of a frame: queues what other threads posted, unless a script holds it, and then
   * the actions of {@link App#after} that are due at {@code time}, as tasks.
   */
  void takeArrived(double time) {
    if (!holdPosts) {
      takePosted();
    }
    while (!timers.isEmpty() && timers.peek().at() <= time) {
      Runnable action = timers.poll().action();
      queued.add(() -> {
        handling.run(); // before it runs, as for a task
        action.run();
        return true;
      });
    }
  }

  /** When the next action of {@link App#after} is due; positive infinity if none is. */
  double nextDue() {
    return timers.isEmpty() ? Double.POSITIVE_INFINITY : timers.peek().at();
  }

  /** Runs {@code action} once the app's clock reaches {@code at}; see {@link App#after}. */
  void after(double at, Runnable action) {
    timers.add(new Timer(at, timersAsked++, Objects.requireNonNull(action, "action")));
  }

  /** An action asked for with {@link App#after}, due at a time of the clock. */
  private record Timer(double at, long order, Runnable action) implements Comparable<Timer> {
    @Override
    public int compareTo(Timer other) {
      int byTime = Double.compare(at, other.at);
      return byTime != 0 ? byTime : Long.compare(order, other.order);
    }
  }

  // ---- Work off the app's thread

  /** Hands the app a task to run on its thread; on any thread. See {@link App#post}. */
  void post(Runnable task) {
    Objects.requireNonNull(task, "task");
    offer(() -> {
      handling.run(); // before it runs: a task that throws may have changed something
      task.run();
      return true;
    });
  }

  /** Posts a task; returns the count of {@link #joined} that it made. */
  private long offer(BooleanSupplier task) {
    posted.add(task);
    long count = joined.incrementAndGet();
    Runnable w = wake;
    if (w != null && woken.compareAndSet(false, true)) {
      w.run();
    }
    return count;
  }

  /** Moves what other threads posted into the queue; on the app's thread. */
  void takePosted() {
    woken.set(false); // before the tasks are taken: one posted after them wakes again
    reportsTaken = reports.get(); // before: a report made during the take counts as not taken
    joined.incrementAndGet(); // what is taken keeps its value: a later report is posted anew
    for (BooleanSupplier task; (task = posted.poll()) != null; ) {
      queued.add(task);
    }
  }

  /** Sets what {@link #post} calls when a task arrives; see {@link App#onPost}. */
  void onPost(Runnable wake) {
    this.wake = wake;
  }

  <T, P> Work start(
      Lane lane,
      String name,
      Work.Reporting<? extends T, P> compute,
      Consumer<? super P> onProgress,
      Consumer<? super T> onResult,
      Consumer<? super Exception> onFailure) {
    Objects.requireNonNull(compute, "compute");
    Objects.requireNonNull(onProgress, "onProgress");
    return start(
        lane,
        name,
        work -> {
          Reporter<P> progress = new Reporter<>(work, onProgress);
          return () -> compute.call(progress);
        },
        onResult,
        onFailure);
  }

  <T> Work start(
      Lane lane,
      String name,
      Function<Work, Callable<? extends T>> computation,
      Consumer<? super T> onResult,
      Consumer<? super Exception> onFailure) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(onResult, "onResult");
    Objects.requireNonNull(onFailure, "onFailure");
    Work work = new Work(name, this, lane);
    Callable<? extends T> compute = computation.apply(work);
    synchronized (running) {
      running.add(work);
    }
    work.thread = Thread.ofVirtual().name(name).unstarted(() -> {
      T result = null;
      Throwable failure = null;
      try {
        result = compute.call();
      } catch (Throwable t) {
        failure = t;
      }
      T r = result;
      Throwable f = failure;
      work.ended = true; // before the delivery is posted: no report comes after it
      try {
        offer(() -> {
          if (work.cancelled) {
            return false; // costs no rebuild
          }
          work.delivered = true; // before the handlers: a report that waits does not run
          handling.run();
          if (f == null) {
            onResult.accept(r);
          } else if (f instanceof Exception e) {
            onFailure.accept(e);
          } else if (f instanceof Error e) {
            throw e;
          } else {
            throw new IllegalStateException(name + " threw", f);
          }
          return true;
        });
      } finally {
        work.end(); // after the offer: onEnd, and await(), find its delivery posted
        if (lane != null) {
          lane.next(); // after the end: the app hears of it before the next piece's answers
        }
      }
    });
    if (lane == null) {
      work.thread.start();
    } else {
      lane.submit(work);
    }
    return work;
  }

  /** The {@link Work.Progress} of one work, which posts its reports. */
  private final class Reporter<P> implements Work.Progress<P> {

    private final Work work;
    private final Consumer<? super P> onProgress;
    private Report<P> last; // the report posted last; guarded by this

    Reporter(Work work, Consumer<? super P> onProgress) {
      this.work = work;
      this.onProgress = onProgress;
    }

    @Override
    public void report(P value) {
      if (work.cancelled || work.ended) {
        return; // dropped where it is made
      }
      synchronized (this) {
        Report<P> waiting = last;
        if (waiting != null && waiting.joined == joined.get()) {
          waiting.value = value; // nothing joined the queue after it, and it was not taken
        } else {
          Report<P> report = new Report<>(value);
          last = report;
          report.joined = offer(() -> run(report)); // run() waits for this lock
        }
      }
      reports.incrementAndGet();
      synchronized (REPORTED) {
        REPORTED.notifyAll();
      }
    }

    private boolean run(Report<P> report) {
      P value;
      synchronized (this) {
        value = report.value;
      }
      if (work.cancelled || work.delivered) {
        return false; // the reports ended with the work, and cost no rebuild
      }
      handling.run();
      onProgress.accept(value);
      return true;
    }

    @Override
    public boolean isCancelled() {
      return work.cancelled;
    }
  }

  /** A report in the queue, whose value newer reports may take. */
  private static final class Report<P> {
    P value;
    long joined; // the count of joined right after it was posted

    Report(P value) {
      this.value = value;
    }
  }

  /** A cancelled work got an {@link Work#onEnd} handler: {@link #await} waits for its end. */
  void waitFor(Work work) {
    synchronized (running) {
      running.add(work);
    }
  }

  /**
   * The work has posted its delivery, ended or was cancelled: {@link #await} waits no longer for
   * it.
   */
  void finished(Work work) {
    synchronized (running) {
      if (running.remove(work)) {
        running.notifyAll();
      }
    }
    synchronized (REPORTED) {
      REPORTED.notifyAll(); // awaitProgress need not wait for a report of work that ended
    }
  }

  /** Waits for the {@code waited} work, then takes what was posted; see {@link App#await}. */
  void await(Predicate<Work> waited, Duration timeout) throws InterruptedException {
    if (dispatching) {
      throw new IllegalStateException("await blocks the app's thread: call it between frames");
    }
    long nanos = timeout.toNanos();
    long start = System.nanoTime();
    synchronized (running) {
      while (running.stream().anyMatch(waited)) {
        long left = nanos - (System.nanoTime() - start);
        if (left <= 0) {
          List<String> names = running.stream().filter(waited).map(Work::name).toList();
          throw new IllegalStateException("After " + timeout.toMillis() + " ms, the app's work"
              + " still runs: " + String.join(", ", names) + ". Work that App.background started"
              + " ends when it has delivered or is cancelled, and cancelled work with an onEnd"
              + " handler when its computation has returned.");
        }
        TimeUnit.NANOSECONDS.timedWait(running, left);
      }
    }
    takePosted();
  }

  /**
   * {@link App#awaitProgress(Duration)} for the apps of a {@link Script}, by their schedulers: a
   * report of any will do.
   */
  static void awaitProgress(List<Scheduler> apps, Duration timeout) throws InterruptedException {
    for (Scheduler app : apps) {
      if (app.dispatching) {
        throw new IllegalStateException(
            "awaitProgress blocks the app's thread: call it between frames");
      }
    }
    long nanos = timeout.toNanos();
    long start = System.nanoTime();
    synchronized (REPORTED) {
      while (!reportWaits(apps)) {
        List<String> names = new ArrayList<>();
        for (Scheduler app : apps) {
          synchronized (app.running) {
            app.running.forEach(work -> names.add(work.name()));
          }
        }
        if (names.isEmpty()) {
          throw new IllegalStateException("No work of the app runs, and nothing it reported waits"
              + " to be let in. Waiting for progress needs work that App.background started with"
              + " a Work.Progress and that still runs; its end is what await waits for.");
        }
        long left = nanos - (System.nanoTime() - start);
        if (left <= 0) {
          throw new IllegalStateException("After " + timeout.toMillis() + " ms, the app's work"
              + " has reported nothing new: " + String.join(", ", names) + " still runs. Work"
              + " reports on the Work.Progress that App.background hands it.");
        }
        TimeUnit.NANOSECONDS.timedWait(REPORTED, left);
      }
    }
    for (Scheduler app : apps) {
      app.takePosted();
    }
  }

  private static boolean reportWaits(List<Scheduler> apps) {
    for (Scheduler app : apps) {
      if (app.reports.get() > app.reportsTaken) {
        return true;
      }
    }
    return false;
  }
}
