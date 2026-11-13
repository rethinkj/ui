package com.rethinkj.ui.window;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.render.Scene;
import com.rethinkj.ui.window.WindowEvent.AppearanceChanged;
import com.rethinkj.ui.window.WindowEvent.CloseRequested;
import com.rethinkj.ui.window.WindowEvent.FocusChanged;
import com.rethinkj.ui.window.WindowEvent.KeyDown;
import com.rethinkj.ui.window.WindowEvent.KeyUp;
import com.rethinkj.ui.window.WindowEvent.PointerDown;
import com.rethinkj.ui.window.WindowEvent.PointerLeave;
import com.rethinkj.ui.window.WindowEvent.PointerMove;
import com.rethinkj.ui.window.WindowEvent.PointerUp;
import com.rethinkj.ui.window.WindowEvent.Redraw;
import com.rethinkj.ui.window.WindowEvent.Resized;
import com.rethinkj.ui.window.WindowEvent.ScaleChanged;
import com.rethinkj.ui.window.WindowEvent.TextEditing;
import com.rethinkj.ui.window.WindowEvent.TextInput;
import com.rethinkj.ui.window.WindowEvent.Wheel;
import com.rethinkj.ui.render.GpuPresenter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Runs {@link App}s in {@link Window}s, one app per window, on the windows' thread: the window
 * given to the constructor, and those that {@link #open} or the apps' handlers open through {@link
 * App#windows()} ({@link Windows}, which also says how apps of one state see each other's
 * changes). For each window the loop feeds its app the window's pointer, wheel, key and text input
 * and its size, with the right button as the secondary one ({@link App#secondaryDown}), lets it build frames, hands each new frame to a {@link Presenter}, which draws it,
 * and shows the {@link App#cursor} the app asks for.
 *
 * <p>A step of the wheel scrolls {@link #WHEEL_STEP}, 48 logical pixels: three lines of 16 pixels, as
 * Windows scrolls three lines per notch unless the user changed it, and within the 40 to 60 pixels
 * that browsers and toolkits scroll per notch on Linux. Touchpads and precise wheels report parts
 * of a step, which scroll that part of the distance. Rolling the wheel towards the user scrolls
 * towards the end, so that the content moves up.
 *
 * <p>Between a press and its release, what another window of the loop reports of the pointer
 * goes to the window the press began in, at the same point of the screen: SDL hands the pointer
 * to a window that opens under it, as one that follows a drag does.
 *
 * <p>The loop draws only when there is something new: when {@link App#frame} says so, when the
 * window asks for a {@link WindowEvent.Redraw}, or when the presenter could not show the last frame
 * ({@link Presenter#pending}). An idle window waits for events and does nothing else, except for
 * waking when the caret of a focused text field blinks ({@link App#nextChange}), which it does only
 * while the window is active ({@link App#windowActive}), when an action the app asked for with
 * {@link App#after} is due, and when another thread {@link App#post posts} a task: the post pushes
 * an event that ends the wait at once, the tasks that arrived until the frame takes them run in
 * that one frame, and a thread that posts nothing costs nothing. While something moves, the loop
 * advances the app's clock by the time that passed and draws a frame per refresh of the display.
 *
 * <p>Input, and a task posted, that arrives less than a refresh after the last frame drawn waits
 * for that refresh, and whatever else arrives meanwhile goes into the same frame: a fast wheel, a
 * drag or a stream of tasks draws one frame per refresh, not one per event. Presenting does not
 * pace the loop where the display does not hold back a frame, as on a machine whose display is
 * virtual, and frames the display replaces before it shows them cost processor and graphics time
 * for nothing. Input or a task after a pause of a refresh or more is drawn at once.
 *
 * <p>The loop also gives the app the system's clipboard ({@link App#clipboard}) and its fonts for
 * characters that the app's fonts lack ({@link App#systemFonts}, unless {@link #systemFonts} turned
 * that off), turns the window's text input on while the focused element takes text and tells it
 * where ({@link App#inputArea}, {@link Window#textInputArea}), and passes on the compositions of
 * input methods. Alt+Space that no key handler takes opens the system's window menu ({@link
 * Window#showSystemMenu}), on a frameless window too.
 *
 * <pre>{@code
 * try (Window window = Window.open("Lights", 480, 320, Window.options().frameless());
 *     GpuPresenter presenter = new GpuPresenter(window)) {
 *   new WindowLoop(window, new App<>(state, View::lights), presenter).run();
 * }
 * }</pre>
 */
public final class WindowLoop {

  /** Logical pixels per step of the wheel; see the class's description. */
  public static final float WHEEL_STEP = 48;

  /** Draws a frame into the window. */
  @FunctionalInterface
  public interface Presenter {
    /**
     * Draws a frame, on the window's thread. During a move or resize on Windows, this is called
     * from inside the system's loop and should not wait long.
     *
     * @param scene what to draw, in logical pixels
     * @param width the logical width the scene was laid out for
     * @param height the logical height
     * @param scale physical pixels per logical pixel
     */
    void present(Scene scene, float width, float height, float scale);

    /**
     * Whether the last frame could not be shown and should be offered again, for example because
     * the window's surface was out of date while the window was resized. The loop then calls
     * {@link #present} with the newest frame after one refresh of the display, even if nothing
     * changed. False unless a presenter says otherwise.
     */
    default boolean pending() {
      return false;
    }
  }

  // The windows in the order they opened, and the same front first, as far as the loop knows.
  private final List<Entry> entries = new ArrayList<>();
  private final List<Entry> front = new ArrayList<>();
  private final List<Entry> closing = new ArrayList<>();
  private final LoopWindows windows = new LoopWindows();
  private Function<Window, Presenter> presenters;
  private WindowListener listener;
  private Consumer<SystemAppearance> appearance;
  private Waits waits = (millis, holdBack) -> {};
  private volatile boolean running;
  private boolean systemFonts = true;
  private long clock;
  private boolean drawing; // a pass draws frames; closes wait for its end
  private Entry pressed; // the window the left button went down in, until it is up
  // During that press: a window that the app moves along reported the pointer (see route), and
  // the system's pointer as the loop last read it.
  private boolean tracking;
  private float[] polled;
  private boolean captured; // the loop captured the pointer for a press that moved on
  private Entry releasedIn; // the window whose app got the release, until it draws
  private boolean focusAfterRelease;

  /**
   * One window of the loop with its app and presenter, and what pacing needs: when its last frame
   * presented began to be built, and its display's refresh.
   */
  private final class Entry implements Windows.Handle {
    final Window window;
    final App<?> app;
    final Presenter presenter;
    final boolean owned; // opened by the loop, which closes it
    Runnable onClose;
    boolean dropTarget = true;
    // Moved by the app during the press that is down, as a window that follows it, or during the
    // last one: the frame that runs the release still asks where on the screen it was (place).
    boolean moved;
    float width = -1;
    float height = -1;
    float scale = -1;
    long presented;
    long started;
    long frameNanos;
    boolean open = true;
    boolean fresh = true; // opened since the last pass, which draws its first frame
    // Opened under the pointer during a press (Window.Options.underPointer): shown without
    // the focus, which it takes after the frame that handles the release.
    boolean focusAfterPress;
    // Input or a posted task arrived that no frame has handled yet; posts set it on their thread.
    volatile boolean input;

    Entry(Window window, App<?> app, Presenter presenter, boolean owned) {
      this.window = window;
      this.app = app;
      this.presenter = presenter;
      this.owned = owned;
    }

    @Override
    public App<?> app() {
      return app;
    }

    @Override
    public String title() {
      return window.title();
    }

    @Override
    public boolean isOpen() {
      return open && window.isOpen();
    }

    @Override
    public void close() {
      if (!open) {
        return;
      }
      open = false;
      front.remove(this);
      if (drawing) {
        closing.add(this); // after the pass: its app may be in the middle of a frame
      } else {
        shut(this);
      }
    }

    @Override
    public void raise() {
      if (isOpen()) {
        window.raise();
        toFront(this);
      }
    }

    @Override
    public void moveTo(int x, int y) {
      if (isOpen()) {
        window.moveTo(x, y);
        moved = pressed != null;
      }
    }

    @Override
    public int x() {
      return window.isOpen() ? window.x() : 0;
    }

    @Override
    public int y() {
      return window.isOpen() ? window.y() : 0;
    }

    @Override
    public float width() {
      return window.width();
    }

    @Override
    public float height() {
      return window.height();
    }

    @Override
    public float screenScale() {
      return window.unitsPerLogical();
    }

    @Override
    public void onCloseRequest(Runnable handler) {
      onClose = handler;
    }

    @Override
    public void dropTarget(boolean on) {
      dropTarget = on;
    }

    @Override
    public Window window() {
      return window;
    }
  }

  /** The loop's {@link Windows}, which its apps get. */
  private final class LoopWindows implements Windows {
    @Override
    public Handle open(String title, float width, float height, Window.Options options,
        App<?> app) {
      Objects.requireNonNull(app, "app");
      if (of(app) != null) {
        throw new IllegalArgumentException("The app runs in a window already");
      }
      Window window = Window.open(title, width, height, options);
      Presenter presenter;
      try {
        presenter = presenters().apply(window);
      } catch (RuntimeException | Error e) {
        window.close();
        throw e;
      }
      Entry entry = new Entry(window, app, presenter, true);
      entry.focusAfterPress = options.isUnderPointer() && pressed != null;
      add(entry);
      if (running) {
        start(entry);
      }
      return entry;
    }

    @Override
    public List<Handle> all() {
      List<Handle> all = new ArrayList<>();
      for (Entry entry : entries) {
        if (entry.isOpen()) {
          all.add(entry);
        }
      }
      return all;
    }

    @Override
    public Handle of(App<?> app) {
      Entry entry = entry(app);
      return entry != null && entry.isOpen() ? entry : null;
    }

    @Override
    public Place place(App<?> app, float x, float y) {
      Entry from = entry(app);
      if (from == null || !from.isOpen()) {
        return null;
      }
      float upl = from.window.unitsPerLogical();
      float sx = from.window.x() + x * upl;
      float sy = from.window.y() + y * upl;
      // On Wayland no client knows where its windows are: only the drag's own window counts.
      boolean known = Window.knowsPositions();
      if (from.moved && known) { // a window that follows the pointer: see route
        float[] at = Window.pointer();
        sx = at[0];
        sy = at[1];
      }
      for (Entry entry : front) {
        if (!entry.dropTarget || !entry.isOpen() || !known && entry != from) {
          continue;
        }
        float u = entry.window.unitsPerLogical();
        float lx = (sx - entry.window.x()) / u;
        float ly = (sy - entry.window.y()) / u;
        if (lx >= 0 && ly >= 0 && lx < entry.window.width() && ly < entry.window.height()) {
          return new Place(sx, sy, entry, lx, ly);
        }
      }
      return new Place(sx, sy, null, Float.NaN, Float.NaN);
    }
  }

  /**
   * A loop of one window, which runs {@code app} and draws with {@code presenter}; more open with
   * {@link #open} or from the apps' handlers ({@link App#windows()}).
   */
  public WindowLoop(Window window, App<?> app, Presenter presenter) {
    add(new Entry(Objects.requireNonNull(window, "window"), Objects.requireNonNull(app, "app"),
        Objects.requireNonNull(presenter, "presenter"), false));
  }

  /** Runs {@code app} in {@code window} until the window asks to close. */
  public static void run(Window window, App<?> app, Presenter presenter) {
    new WindowLoop(window, app, presenter).run();
  }

  /**
   * Opens another window that runs {@code app}, as an app's handler does with {@link
   * App#windows()}; also before {@link #run}. The loop closes it, with its presenter, when it ends.
   */
  public Windows.Handle open(String title, float width, float height, Window.Options options,
      App<?> app) {
    return windows.open(title, width, height, options, app);
  }

  /** The loop's windows, which its apps also get as {@link App#windows()}. */
  public Windows windows() {
    return windows;
  }

  /**
   * What draws into the windows that open later ({@link #open}, {@link Windows#open}); a {@link
   * com.rethinkj.ui.render.GpuPresenter} unless set. A presenter that is {@link AutoCloseable} is
   * closed before its window.
   *
   * @return this loop
   */
  public WindowLoop presenters(Function<Window, Presenter> factory) {
    this.presenters = Objects.requireNonNull(factory, "factory");
    return this;
  }

  private Function<Window, Presenter> presenters() {
    return presenters != null ? presenters : GpuPresenter::new;
  }

  /**
   * Also gives every event of every window to {@code listener}, before the loop handles it, for
   * what the loop does not, such as the middle button.
   *
   * @return this loop
   */
  public WindowLoop listener(WindowListener listener) {
    this.listener = listener;
    return this;
  }

  /** What the loop does between frames, for tests, which wait for a state instead of a time. */
  @FunctionalInterface
  interface Waits {
    /**
     * Told on the loop's thread right before it waits for events.
     *
     * @param millis the longest the wait may take, or -1 when only an event ends it: nothing
     *     moves, nothing is pending and nothing is due on any app's clock. Events that arrived
     *     while the frames were drawn may already be there and end it at once.
     * @param holdBack whether input or a posted task waits for the next refresh of its window
     */
    void waiting(long millis, boolean holdBack);
  }

  /** Tells {@code waits} of each wait; see {@link Waits}. */
  WindowLoop waits(Waits waits) {
    this.waits = Objects.requireNonNull(waits, "waits");
    return this;
  }

  /**
   * Hands the system's appearance to the apps: {@code handler} gets {@link
   * Window#systemAppearance()} once when {@link #run} starts and again after each {@link
   * WindowEvent.AppearanceChanged}, as a task of the first app ({@link App#post}), so that it may
   * change the app's state and the view is built anew; the other apps of that state then build
   * theirs too. An
   * app that follows the system chooses its theme there; nothing changes by itself.
   *
   * <pre>{@code
   * new WindowLoop(window, app, presenter)
   *     .appearance(a -> app.state().dark = a.dark())
   *     .run();
   * }</pre>
   *
   * @param handler the handler, or null for none
   * @return this loop
   */
  public WindowLoop appearance(Consumer<SystemAppearance> handler) {
    this.appearance = handler;
    return this;
  }

  /**
   * Whether {@link #run} gives the apps the system's fonts ({@link App#systemFonts}), as it does
   * unless this turns it off, for an app that has to look the same on every machine.
   *
   * @return this loop
   */
  public WindowLoop systemFonts(boolean on) {
    this.systemFonts = on;
    return this;
  }

  /**
   * Runs until the last window closes, its close request ends the loop ({@link
   * Windows.Handle#onCloseRequest}) or {@link #stop} is called, on the windows' thread. Meanwhile
   * the loop is each window's listener, answers its {@link Window#parts} from its app and wakes for
   * the apps' posted tasks ({@link App#onPost}); afterwards it does none of that, and tasks posted
   * later wait in the apps until they run frames again. The windows given to the constructor stay
   * open unless a request or a handler closed them; the loop closes those it opened, each after its
   * presenter. What a listener, a presenter or a posted task throws ends the loop and comes out of
   * here.
   *
   * <p>Each pass of the loop builds a frame for each window whose input does not wait for its
   * display's next refresh, and presents those with something new. When an app ran a handler,
   * a task or an action of {@link App#after} ({@link App#ranHandlers}), the loop invalidates the
   * other apps of the same state object, which build their views again in the same pass, so that
   * windows that show one state show the same. Then the loop waits once for all windows: for the earliest of the
   * refreshes that input waits for, the next frame of each window that moves (by its own
   * display's refresh), and the earliest {@link App#nextChange} of all apps; for events alone when
   * there is none of these.
   */
  public void run() {
    if (running) {
      throw new IllegalStateException("The loop runs already");
    }
    running = true;
    try {
      for (Entry entry : List.copyOf(entries)) {
        start(entry);
      }
      handOn(Window.systemAppearance());
      clock = System.nanoTime();
      while (running && !open().isEmpty()) {
        long now = System.nanoTime();
        List<Entry> open = open();
        long[] early = new long[open.size()];
        boolean held = false;
        for (int i = 0; i < open.size(); i++) {
          Entry e = open.get(i);
          early[i] = holdBack(now - e.started, e.frameNanos, e.input);
          held |= early[i] > 0;
        }
        pass(open, early);
        if (!running || open().isEmpty()) {
          break;
        }
        long millis = -1;
        for (int i = 0; i < open.size(); i++) {
          Entry e = open.get(i);
          if (!e.isOpen()) {
            continue;
          }
          long wait;
          if (early[i] > 0) { // more input may come before the frame can be shown
            // A post ends this wait early, and the next turn waits for the rest of the refresh.
            wait = Math.max(1, (early[i] + 999_999) / 1_000_000);
          } else if (e.app.isMoving() || e.presenter.pending()) {
            wait = Math.max(0, (e.frameNanos - (System.nanoTime() - e.presented)) / 1_000_000);
          } else {
            double change = e.app.nextChange() - e.app.time();
            wait = change < Double.POSITIVE_INFINITY
                ? Math.max(1, (long) Math.ceil(change * 1000)) : -1;
          }
          if (wait >= 0 && (millis < 0 || wait < millis)) {
            millis = wait;
          }
        }
        if (polls()) { // a refresh at most, then the pointer is read again
          long refresh = Math.max(1, pressed.frameNanos / 1_000_000);
          millis = millis < 0 ? refresh : Math.min(millis, refresh);
        }
        waits.waiting(millis, held);
        if (millis < 0) {
          Window.waitEvents();
        } else {
          Window.waitEvents(millis);
        }
        poll();
      }
    } finally {
      running = false;
      for (Entry entry : List.copyOf(entries)) {
        entry.app.onPost(null);
        if (entry.owned) {
          entry.close();
        } else if (entry.window.isOpen()) {
          entry.window.listener(null);
          entry.window.parts(null);
        }
      }
      shutClosed();
    }
  }

  /**
   * Draws the windows whose input waits for no refresh, and those whose app another app
   * invalidated: later in the same round, or in another round when they came before it, up to
   * three rounds, so that apps whose handlers run in turn come to an end.
   */
  private void pass(List<Entry> open, long[] early) {
    tick();
    Set<Entry> held = new HashSet<>();
    Set<Entry> due = new HashSet<>();
    for (int i = 0; i < open.size(); i++) {
      (early[i] > 0 ? held : due).add(open.get(i));
    }
    drawing = true;
    try {
      for (int round = 0; round < 3 && !due.isEmpty(); round++) {
        Set<Entry> again = new HashSet<>();
        // Windows that a handler opens during the round join it, as fresh ones.
        for (int i = 0; i < entries.size() && running; i++) {
          Entry e = entries.get(i);
          if (!e.isOpen() || !due.contains(e) && !e.fresh) {
            continue;
          }
          e.fresh = false;
          e.input = false;
          draw(e, false);
          if (e == releasedIn) {
            releasedIn = null;
            focusAfterRelease = true;
          }
          for (Entry other : invalidateOthers(e)) {
            if (!held.contains(other)) {
              (entries.indexOf(other) < i ? again : due).add(other);
            }
          }
        }
        due = again;
      }
    } finally {
      drawing = false;
      shutClosed();
      if (focusAfterRelease) { // the frame that handled the release has run
        focusAfterRelease = false;
        for (Entry e : List.copyOf(entries)) {
          if (e.focusAfterPress) {
            e.focusAfterPress = false;
            e.raise(); // if it is still open: shown without the focus, it takes it now
          }
        }
      }
    }
  }

  /**
   * After a frame of {@code e}: when it ran a handler, a task or an action ({@link
   * App#ranHandlers}), the other open apps of the same state build their views again, and are
   * returned, to be drawn.
   */
  private List<Entry> invalidateOthers(Entry e) {
    if (!e.app.ranHandlers()) {
      return List.of();
    }
    List<Entry> others = new ArrayList<>();
    for (Entry other : entries) {
      if (other != e && other.isOpen() && !other.fresh && other.app.state() == e.app.state()) {
        other.app.invalidate();
        others.add(other);
      }
    }
    return others;
  }

  /** Advances every app's clock by the time since the last tick. */
  private void tick() {
    long now = System.nanoTime();
    double seconds = Math.max(0, now - clock) / 1e9;
    clock = now;
    for (Entry e : entries) {
      if (e.isOpen()) {
        e.app.advance(seconds);
      }
    }
  }

  /**
   * How long input waits before the loop draws it, in nanoseconds: until a refresh has passed since
   * the last frame drawn, or 0 to draw at once.
   *
   * @param sinceFrame the time since the building of the last frame drawn began
   * @param input whether input or a posted task arrived since then that no frame has handled
   */
  static long holdBack(long sinceFrame, long frameNanos, boolean input) {
    return input ? Math.max(frameNanos - sinceFrame, 0) : 0;
  }

  /**
   * Whether the key opens the system's window menu when the app does not take it: Alt+Space, not
   * its repeats. SDL keeps the system from seeing it (its menu mnemonics are off), on a framed
   * window too.
   */
  static boolean opensSystemMenu(Key key) {
    return key.name().equals("Space") && key.modifiers() == Key.ALT && !key.repeat();
  }

  /** Ends {@link #run} after the event it handles now. This may be called on any thread. */
  public void stop() {
    running = false;
    Window.wake();
  }

  public boolean isRunning() {
    return running;
  }

  private void add(Entry entry) {
    entries.add(entry);
    front.addFirst(entry);
  }

  /** Makes the loop the window's listener and the app's runner. */
  private void start(Entry e) {
    App<?> app = e.app;
    app.clipboard(new SystemClipboard());
    app.systemFonts(systemFonts);
    app.windows(windows);
    e.window.listener(event -> event(e, event));
    e.window.parts(app::windowPart);
    app.onPost(() -> { // a task from another thread ends the wait
      e.input = true;
      Window.wake();
    });
    refreshRate(e);
  }

  private Entry entry(App<?> app) {
    for (Entry e : entries) {
      if (e.app == app) {
        return e;
      }
    }
    return null;
  }

  private List<Entry> open() {
    List<Entry> open = new ArrayList<>();
    for (Entry e : List.copyOf(entries)) {
      if (e.isOpen()) {
        open.add(e);
      } else if (e.open) { // its owner closed the window: the loop lets go of it
        e.close();
      }
    }
    return open;
  }

  private void toFront(Entry e) {
    if (e.isOpen()) {
      front.remove(e);
      front.addFirst(e);
    }
  }

  private void shutClosed() {
    while (!closing.isEmpty()) {
      shut(closing.removeFirst());
    }
  }

  /** Lets go of a window that closed: its presenter first, then the window. */
  private void shut(Entry e) {
    entries.remove(e);
    e.app.onPost(null);
    if (!e.window.isOpen()) {
      return;
    }
    e.window.listener(null);
    e.window.parts(null);
    try {
      if (e.presenter instanceof AutoCloseable closeable) {
        closeable.close();
      }
    } catch (RuntimeException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalStateException("Closing the presenter failed", ex);
    } finally {
      e.window.close();
    }
  }

  private void event(Entry e, WindowEvent event) {
    WindowListener current = listener;
    if (current != null) {
      current.event(event);
    }
    if (!e.isOpen()) {
      return;
    }
    if (pressed != null && pressed != e && route(e, event)) {
      return;
    }
    App<?> app = e.app;
    if (event instanceof PointerDown || event instanceof PointerUp || event instanceof PointerMove
        || event instanceof PointerLeave || event instanceof Wheel || event instanceof KeyDown
        || event instanceof KeyUp || event instanceof TextInput || event instanceof TextEditing) {
      e.input = true;
    }
    switch (event) {
      case PointerDown down when down.button() == PointerButton.LEFT -> {
        for (Entry each : entries) {
          each.moved = false;
        }
        pressed = e;
        app.pointerDown(down.x(), down.y(), down.clicks(), down.modifiers());
        textInput(e); // on before the frame when the press lands in a field: see App.inputArea
      }
      case PointerUp up when up.button() == PointerButton.LEFT -> {
        released(e);
        app.pointerUp(up.x(), up.y());
      }
      case PointerDown down when down.button() == PointerButton.RIGHT ->
          app.secondaryDown(down.x(), down.y());
      case PointerUp up when up.button() == PointerButton.RIGHT -> app.secondaryUp(up.x(), up.y());
      case PointerMove move -> app.pointerMove(move.x(), move.y());
      case PointerLeave leave -> app.pointerLeave();
      // The window's dy is positive away from the user, the app's towards the end.
      case Wheel wheel ->
          app.wheel(wheel.x(), wheel.y(), wheel.dx() * WHEEL_STEP, -wheel.dy() * WHEEL_STEP);
      case KeyDown down -> app.keyDown(down.key(),
          opensSystemMenu(down.key()) ? e.window::showSystemMenu : null);
      case KeyUp up -> app.keyUp(up.key());
      case TextInput text -> app.textInput(text.text());
      case TextEditing editing -> app.textComposition(editing.text(), editing.cursor());
      case FocusChanged focus -> {
        app.windowActive(focus.focused());
        if (focus.focused()) {
          toFront(e);
        }
      }
      case Resized resized -> resize(e);
      case ScaleChanged changed -> {
        resize(e);
        refreshRate(e);
      }
      case Redraw redraw -> {
        if (!drawing) { // in a pass, the frame comes anyway
          tick();
          draw(e, !redraw.live());
          // The frame may have run a task that arrived, which the others show at once too.
          for (Entry other : invalidateOthers(e)) {
            draw(other, false);
          }
        }
      }
      case CloseRequested close -> {
        if (e.onClose != null) {
          app.post(e.onClose);
        } else if (open().size() > 1) {
          e.close();
        } else {
          running = false;
        }
      }
      // Every window gets it; the first open one hands it on.
      case AppearanceChanged changed when e == open().getFirst() -> handOn(changed.appearance());
      default -> {}
    }
  }

  /**
   * Gives the pointer's moves, the release and the keys that another window reports during a
   * press to the window the press began in, the pointer at the same point of the screen. SDL hands the pointer to a window
   * that opens under it, as one that follows the pointer during a drag does, and on X11 its moves
   * then come only while the pointer is over that window; capturing the pointer again brings
   * those outside it, and the release, back.
   *
   * @return whether the event went to the pressed window instead of {@code e}'s
   */
  private boolean route(Entry e, WindowEvent event) {
    Entry to = pressed;
    if (!to.isOpen()) {
      pressed = null;
      return false;
    }
    float x;
    float y;
    switch (event) {
      case PointerMove move -> {
        x = move.x();
        y = move.y();
      }
      case PointerUp up when up.button() == PointerButton.LEFT -> {
        x = up.x();
        y = up.y();
      }
      case PointerLeave leave -> {
        return true; // the pressed window keeps the pointer
      }
      // And the keys: a window that opened under the pointer took the keyboard focus, but Escape
      // gives up the drag that opened it.
      case KeyDown down -> {
        to.input = true;
        to.app.keyDown(down.key());
        return true;
      }
      case KeyUp up -> {
        to.input = true;
        to.app.keyUp(up.key());
        return true;
      }
      default -> {
        return false;
      }
    }
    if (!captured) {
      captured = true;
      Window.capturePointer(true); // for the window the pointer is over now
    }
    float from = e.window.unitsPerLogical();
    float into = to.window.unitsPerLogical();
    float sx = e.window.x() + x * from;
    float sy = e.window.y() + y * from;
    if (e.moved && Window.knowsPositions()) {
      // A window that follows the pointer reports it from where it has just moved to, which the
      // system confirms later (on X11 the window manager), so its position lags a step behind:
      // the system's pointer is where the pointer is. SDL also drops a move that leaves the
      // pointer where it was in the window, as each one does once the window caught up, so the
      // loop reads the pointer itself from now on (see poll).
      float[] at = Window.pointer();
      sx = at[0];
      sy = at[1];
      tracking = true;
    }
    float ax = (sx - to.window.x()) / into;
    float ay = (sy - to.window.y()) / into;
    to.input = true;
    if (event instanceof PointerUp) {
      released(to);
      to.app.pointerUp(ax, ay);
    } else {
      to.app.pointerMove(ax, ay);
    }
    return true;
  }

  /**
   * Whether the loop reads the system's pointer during a press: while a window that the app moves
   * along, as one that follows the pointer, is under it or is the pressed one, SDL reports no move
   * that leaves the pointer where it was in that window, which is every move once it caught up.
   */
  private boolean polls() {
    return pressed != null && pressed.isOpen() && Window.knowsPositions()
        && (tracking || pressed.moved);
  }

  /** Gives the pressed window the system's pointer when it moved; see {@link #polls}. */
  private void poll() {
    if (!polls()) {
      return;
    }
    float[] at = Window.pointer();
    if (polled != null && at[0] == polled[0] && at[1] == polled[1]) {
      return;
    }
    polled = at;
    Entry to = pressed;
    float into = to.window.unitsPerLogical();
    to.input = true;
    to.app.pointerMove((at[0] - to.window.x()) / into, (at[1] - to.window.y()) / into);
  }

  private void released(Entry in) {
    releasedIn = in;
    pressed = null;
    tracking = false;
    polled = null;
    if (captured) {
      captured = false;
      Window.capturePointer(false);
    }
  }

  private void handOn(SystemAppearance now) {
    Consumer<SystemAppearance> handler = appearance;
    List<Entry> open = open();
    if (handler != null && !open.isEmpty()) {
      open.getFirst().app.post(() -> handler.accept(now));
    }
  }

  /** Builds a frame and presents it if it is new, if {@code always}, or if one is pending. */
  private void draw(Entry e, boolean always) {
    resize(e);
    long now = System.nanoTime();
    App<?> app = e.app;
    if (app.frame() || always || e.presenter.pending()) {
      if (!e.isOpen()) {
        return; // a handler of the frame closed it
      }
      e.presenter.present(app.scene(), e.width, e.height, e.scale);
      e.presented = System.nanoTime();
      e.started = now;
    }
    if (!e.isOpen()) {
      return;
    }
    e.window.cursor(app.cursor());
    textInput(e);
  }

  private void textInput(Entry e) {
    App<?> app = e.app;
    App.InputArea area = app.inputArea();
    if (area == null) {
      e.window.stopTextInput();
    } else {
      e.window.textInputArea(
          area.x(), area.y(), area.width(), area.height(), area.cursor(), area.password());
    }
  }

  private void resize(Entry e) {
    float w = e.window.width();
    float h = e.window.height();
    float s = e.window.scale();
    if (w != e.width || h != e.height || s != e.scale) {
      e.width = w;
      e.height = h;
      e.scale = s;
      e.app.resize(w, h, s);
    }
  }

  private void refreshRate(Entry e) {
    float hertz = e.window.refreshRate();
    e.frameNanos = (long) (1e9 / (hertz >= 20 && hertz <= 1000 ? hertz : 60));
  }
}
