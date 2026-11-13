package com.rethinkj.ui.window;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.WindowPart;
import com.rethinkj.ui.natives.Natives;
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
import com.rethinkj.ui.window.WindowEvent.StateChanged;
import com.rethinkj.ui.window.WindowEvent.TextEditing;
import com.rethinkj.ui.window.WindowEvent.TextInput;
import com.rethinkj.ui.window.WindowEvent.Wheel;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * A native window, opened through SDL 3. It delivers its input to a {@link WindowListener} as
 * {@link WindowEvent}s and gives the handles a GPU API needs to draw into it ({@link #handle}). It
 * draws nothing itself; {@link WindowLoop} connects it with an {@link App} and a renderer.
 *
 * <p>A window can leave out the system's frame and draw its own title bar: the view marks elements
 * with {@link El#windowPart}, the window asks {@link #parts} (usually {@link App#windowPart}) what
 * lies under the pointer, and the system then moves the window from a drag area, snaps it to the
 * screen's edges and resizes it from a margin along its edges. The minimize, maximize and close
 * parts act when clicked. A right click on a drag area opens the system's window menu there, and
 * the listener gets neither half of it, as an app gets nothing of a click on a system title bar. On Windows 11 such a window has rounded corners and the system's shadow,
 * and resting the pointer on the maximize part shows the snap layouts.
 *
 * <h2>Threads and events</h2>
 *
 * The first window opened makes its thread the one that uses windows, until the last window is
 * closed; every method that is not marked otherwise must be called on it. Events are not delivered
 * on their own: {@link #pollEvents} and {@link #waitEvents} deliver them to the listeners of all
 * open windows, in the order they happened. While the user moves or resizes a window on Windows,
 * the system runs a loop of its own and {@code waitEvents} does not return until the user lets go;
 * the listener is then called from inside that loop, with a {@link WindowEvent.Redraw} each time the
 * window should be drawn.
 *
 * <h2>Text input</h2>
 *
 * Text input is off until {@link #textInputArea} turns it on, which {@link WindowLoop} does while
 * the focused element takes text, and {@link #stopTextInput} turns it off again: while it is on,
 * keys also type text ({@link WindowEvent.TextInput}), input methods compose ({@link
 * WindowEvent.TextEditing}), and a system with an on-screen keyboard shows it. The window draws
 * the composition itself; the system places its candidate window below the area.
 *
 * <p>The native library is loaded by the first {@link #open}, not before. A listener that throws
 * while the system calls into Java, as it does during a move, cannot pass the exception through
 * native code; it is kept and thrown by the next {@code pollEvents} or {@code waitEvents}.
 */
public final class Window implements AutoCloseable {

  /** Says which part of a self-drawn window frame lies at a point in logical pixels. */
  @FunctionalInterface
  public interface Parts {
    /** @return the part, or {@link WindowPart#CONTENT} (or null) where the window is content */
    WindowPart at(float x, float y);
  }

  /** The progress shown on the window's taskbar button. */
  public enum Progress {
    NONE,
    /** Busy, without a known share of the work done. */
    INDETERMINATE,
    NORMAL,
    PAUSED,
    ERROR
  }

  /** How a window opens; see {@link #options()}. */
  public static final class Options {
    private boolean frameless;
    private boolean resizable = true;
    private float minWidth;
    private float minHeight;
    private float resizeMargin = 6;

    private Options() {}

    /**
     * Leaves out the system's frame and title bar, so that the view draws its own. See {@link
     * El#windowPart}.
     */
    public Options frameless() {
      frameless = true;
      return this;
    }

    /** Keeps the user from resizing the window. */
    public Options fixedSize() {
      resizable = false;
      return this;
    }

    /** The smallest size the user can resize the window to, in logical pixels. */
    public Options minimumSize(float width, float height) {
      minWidth = width;
      minHeight = height;
      return this;
    }

    /**
     * How far from its edges, in logical pixels, a frameless window can be grabbed to resize it;
     * 6 unless set. The margin lies inside the window, over its content, and narrows to {@link
     * Window#CONTROL_MARGIN} where something that takes presses lies under it ({@link
     * WindowPart#CONTROL}).
     */
    public Options resizeMargin(float pixels) {
      resizeMargin = pixels;
      return this;
    }
  }

  /**
   * How far from its edges, in logical pixels, a frameless window can be grabbed to resize it
   * where an element that takes presses lies under its resize margin ({@link
   * WindowPart#CONTROL}), such as a scrollbar along its right edge, or a button of its title bar.
   */
  public static final float CONTROL_MARGIN = 2;

  /** Options for a resizable window with the system's frame; their methods change that. */
  public static Options options() {
    return new Options();
  }

  // ---- What all windows share. SDL has one event queue per process, and SDL_Init makes the
  // calling thread the one that may use windows.

  /** Guards {@link #thread} and {@link #wakeType} against {@link #wake} from other threads. */
  private static final Object LOCK = new Object();

  private static volatile Thread thread;
  private static int wakeType;
  private static final Map<Integer, Window> windows = new HashMap<>();
  /** Translated events in the order they happened, for {@link #dispatch}. */
  private static final ArrayDeque<Posted> pending = new ArrayDeque<>();
  /** What has to wait until no event is being delivered, such as the system menu. */
  private static final ArrayDeque<Runnable> deferred = new ArrayDeque<>();
  private static boolean dispatching;
  /** What an upcall caught, for {@link #rethrow}. */
  private static Throwable failure;
  private static MemorySegment buffer;
  private static MemorySegment ints;
  /** SDL's cursors by {@link Cursor#ordinal}, made on first use; SDL_Quit frees them. */
  private static final MemorySegment[] cursors = new MemorySegment[Cursor.values().length];
  /** The cursor SDL shows, one for all windows. */
  private static Cursor cursorShown = Cursor.DEFAULT;

  private record Posted(Window window, WindowEvent event) {}

  /** Upcall stubs, made on first use and kept for the life of the process. */
  private static final class Upcalls {
    static final MemorySegment HIT_TEST =
        upcall(MethodHandles.lookup(), Window.class, "hitTest", Sdl.HIT_TEST);
    static final MemorySegment WATCH =
        upcall(MethodHandles.lookup(), Window.class, "watch", Sdl.EVENT_FILTER);
  }

  // ---- One window

  private final MemorySegment sdl;
  private final int id;
  private final boolean frameless;
  private final boolean resizable;
  private final float minWidth;
  private final float minHeight;
  private final float resizeMargin;
  private WindowHandle handle;
  private Win32Frame win32;
  private WindowListener listener;
  private Parts parts;
  private boolean open = true;
  private float width;
  private float height;
  private float scale = 1;
  private float density = 1;
  private int pixelWidth;
  private int pixelHeight;
  private boolean minimized;
  private boolean maximized;
  /** The window button a left press began on, or null. */
  private WindowPart pressed;
  /** Whether a secondary press began on a drag area, where it belongs to the system menu. */
  private boolean menuPress;
  /** Where the last pointer move was, in logical pixels, while the pointer is in the window. */
  private boolean pointerIn;
  private float pointerX;
  private float pointerY;
  private Cursor cursor = Cursor.DEFAULT;
  /** The text input area last set, in window coordinates, while text input is on; else null. */
  private int[] textInput;

  private Window(MemorySegment sdl, Options options) {
    this.sdl = sdl;
    this.id = Sdl.getWindowID(sdl);
    this.frameless = options.frameless;
    this.resizable = options.resizable;
    this.minWidth = options.minWidth;
    this.minHeight = options.minHeight;
    this.resizeMargin = options.resizeMargin;
  }

  /** Opens a resizable window with the system's frame. */
  public static Window open(String title, float width, float height) {
    return open(title, width, height, options());
  }

  /**
   * Opens a window and shows it.
   *
   * @param width the width of the content in logical pixels
   * @param height the height of the content in logical pixels
   * @throws UnsatisfiedLinkError if SDL cannot be loaded
   * @throws IllegalStateException if this system cannot open windows here, for example because
   *     there is no display, or if another thread uses windows
   */
  public static Window open(String title, float width, float height, Options options) {
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(options, "options");
    if (!(width > 0 && height > 0 && width < 1e6f && height < 1e6f)) {
      throw new IllegalArgumentException("Size " + width + "x" + height + " is not a window size");
    }
    start();
    // Window coordinates are pixels on Windows and X11 and points elsewhere; the content scale is
    // what one logical pixel is in them. Until the window exists, guess the primary display's.
    float content = Sdl.getDisplayContentScale(Sdl.getPrimaryDisplay());
    if (!(content > 0)) {
      content = 1;
    }
    long flags = Sdl.WINDOW_HIDDEN | Sdl.WINDOW_HIGH_PIXEL_DENSITY
        | (options.frameless ? Sdl.WINDOW_BORDERLESS : 0)
        | (options.resizable ? Sdl.WINDOW_RESIZABLE : 0);
    MemorySegment sdl = Sdl.createWindow(
        title, Math.round(width * content), Math.round(height * content), flags);
    if (sdl.equals(MemorySegment.NULL)) {
      String error = Sdl.getError();
      stopIfUnused();
      throw new IllegalStateException("SDL_CreateWindow failed: " + error);
    }
    Window window = new Window(sdl, options);
    windows.put(window.id, window);
    try {
      window.setUp(width, height, content);
    } catch (RuntimeException | Error e) {
      try {
        window.close();
      } catch (RuntimeException | Error suppressed) {
        e.addSuppressed(suppressed);
      }
      throw e;
    }
    return window;
  }

  private void setUp(float logicalWidth, float logicalHeight, float guessedScale) {
    measure();
    if (Math.abs(unitsPerLogical() - guessedScale) > 1e-3f) {
      Sdl.setWindowSize(sdl, units(logicalWidth), units(logicalHeight));
      measure();
    }
    applyMinimumSize();
    handle = readHandle();
    if (frameless) {
      if (!Sdl.setWindowHitTest(sdl, Upcalls.HIT_TEST, MemorySegment.ofAddress(id))) {
        throw new IllegalStateException("SDL_SetWindowHitTest failed: " + Sdl.getError());
      }
      if (handle instanceof WindowHandle.Win32 win) {
        win32 = Win32Frame.attach(this, win.hwnd());
      }
    }
    Sdl.showWindow(sdl);
    long flags = Sdl.getWindowFlags(sdl);
    minimized = (flags & Sdl.WINDOW_MINIMIZED) != 0;
    maximized = (flags & Sdl.WINDOW_MAXIMIZED) != 0;
    rethrow();
  }

  // ---- Events

  /**
   * Sets who receives this window's events, replacing the one before.
   *
   * @param listener the listener, or null for none
   */
  public void listener(WindowListener listener) {
    checkThread();
    this.listener = listener;
  }

  /**
   * Sets what the window asks for the parts of its frame, usually {@code app::windowPart}. Only a
   * frameless window moves from its drag areas; the minimize, maximize and close parts act in any
   * window.
   *
   * @param parts the query, or null if the window has no parts
   */
  public void parts(Parts parts) {
    checkThread();
    this.parts = parts;
  }

  /** Delivers the events that have happened, without waiting. */
  public static void pollEvents() {
    pump(0);
  }

  /** Waits until something happens, then delivers the events, all windows' in their order. */
  public static void waitEvents() {
    pump(-1);
  }

  /**
   * Like {@link #waitEvents()}, but waits no longer than {@code timeoutMillis}.
   *
   * @param timeoutMillis 0 to not wait at all
   */
  public static void waitEvents(long timeoutMillis) {
    pump((int) Math.clamp(timeoutMillis, 0, Integer.MAX_VALUE));
  }

  /**
   * Ends a {@link #waitEvents} that is waiting, at once. This may be called on any thread; it does
   * nothing when no window is open.
   */
  public static void wake() {
    synchronized (LOCK) {
      if (thread == null) {
        return;
      }
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment event = arena.allocate(Sdl.EVENT_SIZE, 8);
        event.set(JAVA_INT, Sdl.EVENT_TYPE, wakeType);
        Sdl.pushEvent(event);
      }
    }
  }

  private static void pump(int timeoutMillis) {
    checkThread();
    rethrow();
    if (timeoutMillis != 0 && pending.isEmpty() && deferred.isEmpty()) {
      Sdl.waitEventTimeout(buffer, timeoutMillis);
    }
    // The event watch has translated every event as SDL queued it; SDL's queue only wakes us.
    drain();
    dispatch();
    while (failure == null && !deferred.isEmpty()) {
      deferred.poll().run();
      drain();
      dispatch();
    }
    rethrow();
  }

  private static void drain() {
    while (Sdl.pollEvent(buffer)) {
      // Nothing to do: see pump.
    }
  }

  /** Delivers the pending events, unless that is under way already further up the stack. */
  private static void dispatch() {
    if (dispatching) {
      return;
    }
    dispatching = true;
    try {
      for (Posted posted; (posted = pending.poll()) != null; ) {
        if (posted.window.open) {
          posted.window.deliver(posted.event);
        }
      }
    } finally {
      dispatching = false;
    }
  }

  /** Queues an event of this window for {@link #dispatch}. */
  void post(WindowEvent event) {
    // A redraw shows the newest state, so it replaces one of this window that is still pending,
    // and is live only if both are. On X11 every step of a resize brings one, and drawing each
    // would leave the loop ever further behind the window's size.
    if (event instanceof Redraw redraw) {
      for (Iterator<Posted> i = pending.iterator(); i.hasNext(); ) {
        Posted posted = i.next();
        if (posted.window == this && posted.event instanceof Redraw before) {
          i.remove();
          event = new Redraw(redraw.live() && before.live());
          break;
        }
      }
    }
    pending.add(new Posted(this, event));
  }

  private void deliver(WindowEvent event) {
    WindowPart click = null;
    switch (event) {
      case PointerDown down when down.button() == PointerButton.LEFT ->
          pressed = button(part(down.x(), down.y()));
      case PointerUp up when up.button() == PointerButton.LEFT -> {
        WindowPart part = button(part(up.x(), up.y()));
        click = part != null && part == pressed ? part : null;
        pressed = null;
      }
      // A secondary press on a drag area is the system menu's, as on a system title bar: the
      // listener gets neither half, and the menu opens if it ends there too.
      case PointerDown down when down.button() == PointerButton.RIGHT && frameless
          && part(down.x(), down.y()) == WindowPart.DRAG -> {
        menuPress = true;
        return;
      }
      case PointerUp up when up.button() == PointerButton.RIGHT && menuPress -> {
        menuPress = false;
        if (part(up.x(), up.y()) == WindowPart.DRAG) {
          // The menu runs a loop of its own; deliver the events before it first.
          int x = units(up.x());
          int y = units(up.y());
          deferred.add(() -> {
            if (open) {
              Sdl.showWindowSystemMenu(sdl, x, y);
            }
          });
        }
        return;
      }
      default -> {}
    }
    WindowListener current = listener;
    if (current != null) {
      current.event(event);
    }
    if (click != null && open) {
      switch (click) {
        case MINIMIZE -> minimize();
        case MAXIMIZE -> {
          if (isMaximized()) {
            restore();
          } else {
            maximize();
          }
        }
        default -> deliver(new CloseRequested());
      }
    }
  }

  private static WindowPart button(WindowPart part) {
    return switch (part) {
      case MINIMIZE, MAXIMIZE, CLOSE -> part;
      default -> null;
    };
  }

  /** SDL_EventFilter: SDL calls this for every event as it queues it. */
  private static boolean watch(MemorySegment userdata, MemorySegment event) {
    try {
      // Events pushed on other threads, such as wake(), are only there to end a wait.
      if (Thread.currentThread() != thread || failure != null) {
        return true;
      }
      MemorySegment e = event.reinterpret(Sdl.EVENT_SIZE);
      int type = e.get(JAVA_INT, Sdl.EVENT_TYPE);
      boolean ofAWindow = (type >= Sdl.EVENT_WINDOW_SHOWN && type <= Sdl.EVENT_WINDOW_LAST)
          || (type >= Sdl.EVENT_KEY_DOWN && type <= Sdl.EVENT_TEXT_INPUT)
          || (type >= Sdl.EVENT_MOUSE_MOTION && type <= Sdl.EVENT_MOUSE_WHEEL);
      if (!ofAWindow) {
        return true;
      }
      Window window = windows.get(e.get(JAVA_INT, Sdl.EVENT_WINDOW_ID));
      if (window == null) {
        return true;
      }
      window.translate(type, e);
      // SDL sends this from inside the system's move and resize loop, where nothing else can
      // deliver events: deliver them now, so that the listener can draw.
      if (type == Sdl.EVENT_WINDOW_EXPOSED && e.get(JAVA_INT, Sdl.WINDOW_DATA1) != 0) {
        dispatch();
      }
    } catch (Throwable t) {
      fail(t);
    }
    return true;
  }

  private void translate(int type, MemorySegment e) {
    switch (type) {
      case Sdl.EVENT_MOUSE_MOTION -> {
        if (win32 != null) {
          win32.pointerInClient();
        }
        pointerIn = true;
        pointerX = logical(e.get(JAVA_FLOAT, Sdl.MOTION_X));
        pointerY = logical(e.get(JAVA_FLOAT, Sdl.MOTION_Y));
        post(new PointerMove(pointerX, pointerY));
      }
      case Sdl.EVENT_MOUSE_BUTTON_DOWN, Sdl.EVENT_MOUSE_BUTTON_UP -> {
        PointerButton button = switch (e.get(JAVA_BYTE, Sdl.BUTTON_BUTTON)) {
          case Sdl.BUTTON_LEFT -> PointerButton.LEFT;
          case Sdl.BUTTON_MIDDLE -> PointerButton.MIDDLE;
          case Sdl.BUTTON_RIGHT -> PointerButton.RIGHT;
          case Sdl.BUTTON_X1 -> PointerButton.BACK;
          case Sdl.BUTTON_X2 -> PointerButton.FORWARD;
          default -> null;
        };
        if (button == null) {
          return;
        }
        float x = logical(e.get(JAVA_FLOAT, Sdl.BUTTON_X));
        float y = logical(e.get(JAVA_FLOAT, Sdl.BUTTON_Y));
        if (type == Sdl.EVENT_MOUSE_BUTTON_DOWN) {
          // The modifiers as SDL knows them now, while it queues the press, which is in order.
          int clicks = Math.max(1, e.get(JAVA_BYTE, Sdl.BUTTON_CLICKS) & 0xFF);
          post(new PointerDown(x, y, button, clicks, modifiers(Sdl.getModState())));
        } else {
          post(new PointerUp(x, y, button));
        }
      }
      // As the system reports it, so that natural scrolling is already applied.
      case Sdl.EVENT_MOUSE_WHEEL ->
          post(new Wheel(e.get(JAVA_FLOAT, Sdl.WHEEL_X), e.get(JAVA_FLOAT, Sdl.WHEEL_Y),
              logical(e.get(JAVA_FLOAT, Sdl.WHEEL_MOUSE_X)),
              logical(e.get(JAVA_FLOAT, Sdl.WHEEL_MOUSE_Y))));
      case Sdl.EVENT_KEY_DOWN, Sdl.EVENT_KEY_UP -> {
        String key = Keys.name(e.get(JAVA_INT, Sdl.KEY_KEY));
        int modifiers = modifiers(e.get(JAVA_SHORT, Sdl.KEY_MOD));
        if (type == Sdl.EVENT_KEY_DOWN) {
          post(new KeyDown(new Key(key, modifiers, e.get(JAVA_BOOLEAN, Sdl.KEY_REPEAT))));
        } else {
          post(new KeyUp(new Key(key, modifiers, false)));
        }
      }
      case Sdl.EVENT_TEXT_INPUT -> {
        String text = Sdl.string(e.get(ADDRESS, Sdl.TEXT_TEXT));
        if (text != null && !text.isEmpty()) {
          post(new TextInput(text));
        }
      }
      case Sdl.EVENT_TEXT_EDITING -> {
        String text = Sdl.string(e.get(ADDRESS, Sdl.EDITING_TEXT));
        text = text == null ? "" : text;
        int start = e.get(JAVA_INT, Sdl.EDITING_START);
        post(new TextEditing(text, compositionCursor(text, start)));
      }
      case Sdl.EVENT_WINDOW_RESIZED, Sdl.EVENT_WINDOW_PIXEL_SIZE_CHANGED,
          Sdl.EVENT_WINDOW_DISPLAY_SCALE_CHANGED -> {
        float oldWidth = width;
        float oldHeight = height;
        float oldScale = scale;
        measure();
        if (scale != oldScale) {
          applyMinimumSize();
          post(new ScaleChanged(scale));
        }
        if (width != oldWidth || height != oldHeight) {
          post(new Resized(width, height));
        }
      }
      case Sdl.EVENT_WINDOW_MINIMIZED, Sdl.EVENT_WINDOW_MAXIMIZED, Sdl.EVENT_WINDOW_RESTORED -> {
        long flags = Sdl.getWindowFlags(sdl);
        boolean nowMinimized = (flags & Sdl.WINDOW_MINIMIZED) != 0;
        boolean nowMaximized = (flags & Sdl.WINDOW_MAXIMIZED) != 0;
        if (nowMinimized != minimized || nowMaximized != maximized) {
          minimized = nowMinimized;
          maximized = nowMaximized;
          post(new StateChanged(minimized, maximized));
        }
      }
      case Sdl.EVENT_WINDOW_EXPOSED -> post(new Redraw(e.get(JAVA_INT, Sdl.WINDOW_DATA1) != 0));
      case Sdl.EVENT_WINDOW_MOUSE_LEAVE -> {
        pointerIn = false;
        if (win32 == null || !win32.pointerOnMaximize()) {
          post(new PointerLeave());
        }
      }
      case Sdl.EVENT_WINDOW_FOCUS_GAINED -> post(new FocusChanged(true));
      case Sdl.EVENT_WINDOW_FOCUS_LOST -> post(new FocusChanged(false));
      case Sdl.EVENT_WINDOW_CLOSE_REQUESTED -> post(new CloseRequested());
      default -> {}
    }
  }

  /**
   * The cursor of a composition, which SDL counts in characters, as a UTF-16 index into it; -1,
   * for the end, where SDL does not say.
   */
  static int compositionCursor(String text, int characters) {
    if (characters < 0) {
      return -1;
    }
    int count = text.codePointCount(0, text.length());
    return characters >= count ? text.length() : text.offsetByCodePoints(0, characters);
  }

  static int modifiers(short sdlModifiers) {
    int mod = sdlModifiers & 0xFFFF;
    return ((mod & Sdl.KMOD_SHIFT) != 0 ? Key.SHIFT : 0)
        | ((mod & Sdl.KMOD_CTRL) != 0 ? Key.CONTROL : 0)
        | ((mod & Sdl.KMOD_ALT) != 0 ? Key.ALT : 0)
        | ((mod & Sdl.KMOD_GUI) != 0 ? Key.META : 0);
  }

  // ---- The frame

  /** SDL_HitTest: SDL asks this whenever the system asks what lies under the pointer. */
  private static int hitTest(MemorySegment win, MemorySegment area, MemorySegment data) {
    try {
      if (Thread.currentThread() != thread || failure != null) {
        return Sdl.HITTEST_NORMAL;
      }
      Window window = windows.get((int) data.address());
      if (window == null || !window.open) {
        return Sdl.HITTEST_NORMAL;
      }
      MemorySegment point = area.reinterpret(8);
      return window.zone(window.logical(point.get(JAVA_INT, 0)),
          window.logical(point.get(JAVA_INT, 4)));
    } catch (Throwable t) {
      fail(t);
      return Sdl.HITTEST_NORMAL;
    }
  }

  /** What a point in logical pixels is to the system: an SDL_HitTestResult. */
  int zone(float x, float y) {
    boolean resizes = resizable
        && (Sdl.getWindowFlags(sdl) & (Sdl.WINDOW_MAXIMIZED | Sdl.WINDOW_FULLSCREEN)) == 0;
    return zone(x, y, width, height, resizes ? resizeMargin : 0, part(x, y));
  }

  /**
   * What a point is to the system in a window of this size, with this resize margin (0 for none)
   * and this part of the frame under the point. Where the part takes presses itself, a control
   * or one of the window's buttons, the margin is no wider than {@link #CONTROL_MARGIN}; the
   * corners keep their reach along the edges.
   */
  static int zone(float x, float y, float width, float height, float margin, WindowPart part) {
    boolean control = switch (part) {
      case CONTROL, MINIMIZE, MAXIMIZE, CLOSE -> true;
      case CONTENT, DRAG -> false;
    };
    int edge = edge(x, y, width, height, control ? Math.min(margin, CONTROL_MARGIN) : margin,
        2 * margin + 2);
    if (edge != Sdl.HITTEST_NORMAL) {
      return edge;
    }
    return part == WindowPart.DRAG ? Sdl.HITTEST_DRAGGABLE : Sdl.HITTEST_NORMAL;
  }

  /**
   * The resize edge or corner of a window of this size at a point, or {@link Sdl#HITTEST_NORMAL}.
   * Corners reach further along the edges than the margin is wide, so they are easier to grab.
   */
  static int edge(float x, float y, float width, float height, float margin) {
    return edge(x, y, width, height, margin, 2 * margin + 2);
  }

  /** As {@link #edge(float, float, float, float, float)}, with the corners' reach given. */
  private static int edge(
      float x, float y, float width, float height, float margin, float corner) {
    if (!(margin > 0)) {
      return Sdl.HITTEST_NORMAL;
    }
    boolean left = x < margin;
    boolean right = x >= width - margin;
    boolean top = y < margin;
    boolean bottom = y >= height - margin;
    boolean nearLeft = x < corner;
    boolean nearRight = x >= width - corner;
    boolean nearTop = y < corner;
    boolean nearBottom = y >= height - corner;
    if ((top && nearLeft) || (left && nearTop)) {
      return Sdl.HITTEST_RESIZE_TOPLEFT;
    }
    if ((top && nearRight) || (right && nearTop)) {
      return Sdl.HITTEST_RESIZE_TOPRIGHT;
    }
    if ((bottom && nearLeft) || (left && nearBottom)) {
      return Sdl.HITTEST_RESIZE_BOTTOMLEFT;
    }
    if ((bottom && nearRight) || (right && nearBottom)) {
      return Sdl.HITTEST_RESIZE_BOTTOMRIGHT;
    }
    if (top) {
      return Sdl.HITTEST_RESIZE_TOP;
    }
    if (bottom) {
      return Sdl.HITTEST_RESIZE_BOTTOM;
    }
    if (left) {
      return Sdl.HITTEST_RESIZE_LEFT;
    }
    if (right) {
      return Sdl.HITTEST_RESIZE_RIGHT;
    }
    return Sdl.HITTEST_NORMAL;
  }

  /** The part at a point in logical pixels, never null. */
  WindowPart part(float x, float y) {
    Parts query = parts;
    WindowPart part = query == null ? null : query.at(x, y);
    return part == null ? WindowPart.CONTENT : part;
  }

  // ---- Size and state

  /** The width of the content in logical pixels. */
  public float width() {
    return width;
  }

  /** The height of the content in logical pixels. */
  public float height() {
    return height;
  }

  /**
   * Physical pixels per logical pixel: the scale the user chose for the display the window is on,
   * such as 1.5 for 150 %.
   */
  public float scale() {
    return scale;
  }

  /** The width of the content in physical pixels, the size of a GPU surface for it. */
  public int pixelWidth() {
    return pixelWidth;
  }

  /** The height of the content in physical pixels. */
  public int pixelHeight() {
    return pixelHeight;
  }

  /** The refresh rate of the window's display in hertz, or 0 if it is not known. */
  public float refreshRate() {
    checkOpen();
    return Sdl.getCurrentRefreshRate(Sdl.getDisplayForWindow(sdl));
  }

  /** Resizes the content to this size in logical pixels. */
  public void resize(float width, float height) {
    checkOpen();
    Sdl.setWindowSize(sdl, units(width), units(height));
    rethrow();
  }

  /** Moves the window's top left corner to this position on the desktop, in screen coordinates. */
  public void moveTo(int x, int y) {
    checkOpen();
    Sdl.setWindowPosition(sdl, x, y);
    rethrow();
  }

  /** The position of the content's top left corner on the desktop, in screen coordinates. */
  public int x() {
    checkOpen();
    Sdl.getWindowPosition(sdl, ints);
    return ints.get(JAVA_INT, 0);
  }

  /** See {@link #x()}. */
  public int y() {
    checkOpen();
    Sdl.getWindowPosition(sdl, ints);
    return ints.get(JAVA_INT, 4);
  }

  public void title(String title) {
    checkOpen();
    Sdl.setWindowTitle(sdl, Objects.requireNonNull(title, "title"));
  }

  public boolean isOpen() {
    return open;
  }

  public boolean isFrameless() {
    return frameless;
  }

  public boolean isMaximized() {
    checkOpen();
    return (Sdl.getWindowFlags(sdl) & Sdl.WINDOW_MAXIMIZED) != 0;
  }

  public boolean isMinimized() {
    checkOpen();
    return (Sdl.getWindowFlags(sdl) & Sdl.WINDOW_MINIMIZED) != 0;
  }

  public boolean isFocused() {
    checkOpen();
    return (Sdl.getWindowFlags(sdl) & Sdl.WINDOW_INPUT_FOCUS) != 0;
  }

  public void minimize() {
    checkOpen();
    Sdl.minimizeWindow(sdl);
    rethrow();
  }

  public void maximize() {
    checkOpen();
    Sdl.maximizeWindow(sdl);
    rethrow();
  }

  /** Restores a minimized or maximized window to its normal size and position. */
  public void restore() {
    checkOpen();
    Sdl.restoreWindow(sdl);
    rethrow();
  }

  /** Brings the window to the front and gives it the keyboard focus, if the system lets it. */
  public void raise() {
    checkOpen();
    Sdl.raiseWindow(sdl);
    rethrow();
  }

  /**
   * Shows progress on the window's taskbar button.
   *
   * @param value the share of the work done, from 0 to 1, for {@link Progress#NORMAL}, {@link
   *     Progress#PAUSED} and {@link Progress#ERROR}
   * @return false where the system shows no progress
   */
  public boolean progress(Progress state, float value) {
    checkOpen();
    boolean shown = Sdl.setWindowProgressState(sdl, state.ordinal());
    if (shown && state != Progress.NONE && state != Progress.INDETERMINATE) {
      shown = Sdl.setWindowProgressValue(sdl, Math.clamp(value, 0f, 1f));
    }
    return shown;
  }

  /**
   * Shows the system's cursor of this shape while the pointer is over the window's content, as
   * {@link App#cursor} asks; {@link WindowLoop} calls this after each frame. SDL has one cursor
   * for all its windows, so the window under the pointer should set it. Over the resize margin of
   * a frameless window the system's resize cursors keep precedence: the shape waits until a call
   * after the pointer has left the margin.
   */
  public void cursor(Cursor cursor) {
    checkOpen();
    this.cursor = Objects.requireNonNull(cursor, "cursor");
    // X11 and Wayland show the hit test's resize cursor themselves, and ours would replace it.
    if (cursor == cursorShown
        || frameless && pointerIn && zone(pointerX, pointerY) >= Sdl.HITTEST_RESIZE_TOPLEFT) {
      return;
    }
    MemorySegment shape = cursors[cursor.ordinal()];
    if (shape == null) {
      shape = Sdl.createSystemCursor(switch (cursor) {
        case DEFAULT -> Sdl.SYSTEM_CURSOR_DEFAULT;
        case POINTER -> Sdl.SYSTEM_CURSOR_POINTER;
        case TEXT -> Sdl.SYSTEM_CURSOR_TEXT;
        case NOT_ALLOWED -> Sdl.SYSTEM_CURSOR_NOT_ALLOWED;
        case EW_RESIZE -> Sdl.SYSTEM_CURSOR_EW_RESIZE;
        case NS_RESIZE -> Sdl.SYSTEM_CURSOR_NS_RESIZE;
        case NWSE_RESIZE -> Sdl.SYSTEM_CURSOR_NWSE_RESIZE;
        case NESW_RESIZE -> Sdl.SYSTEM_CURSOR_NESW_RESIZE;
      });
      if (shape.equals(MemorySegment.NULL)) {
        return; // a video driver without cursors: the pointer keeps its shape
      }
      cursors[cursor.ordinal()] = shape;
    }
    if (!Sdl.setCursor(shape)) {
      throw new IllegalStateException("SDL_SetCursor failed: " + Sdl.getError());
    }
    cursorShown = cursor;
  }

  /**
   * Turns text input on, if it is off, and tells the system where the text is, in logical pixels:
   * the box of the element that takes it, and the caret's distance from its left edge. The system
   * places an input method's candidate window there, below the box. {@link WindowLoop} calls this
   * after each frame while the focused element takes text, as {@link App#inputArea} says.
   */
  public void textInputArea(float x, float y, float width, float height, float cursor) {
    checkOpen();
    int[] area = {units(x), units(y), units(width), units(height), units(cursor)};
    if (textInput != null && Arrays.equals(area, textInput)) {
      return;
    }
    if (!Sdl.setTextInputArea(sdl, area[0], area[1], area[2], area[3], area[4])) {
      throw new IllegalStateException("SDL_SetTextInputArea failed: " + Sdl.getError());
    }
    if (textInput == null && !Sdl.startTextInput(sdl)) {
      throw new IllegalStateException("SDL_StartTextInput failed: " + Sdl.getError());
    }
    textInput = area;
  }

  /**
   * Turns text input off: keys type nothing, an input method stops composing and an on-screen
   * keyboard goes away. Calling this while it is off does nothing.
   */
  public void stopTextInput() {
    checkOpen();
    if (textInput != null) {
      textInput = null;
      Sdl.stopTextInput(sdl);
    }
  }

  /** The cursor this window asked for last; see {@link #cursor(Cursor)}. */
  public Cursor cursor() {
    return cursor;
  }

  /** The cursor SDL shows now, for all windows. */
  static Cursor cursorShown() {
    return cursorShown;
  }

  /**
   * The system's handles of this window, for a GPU surface.
   *
   * @throws UnsupportedOperationException if SDL runs a video driver other than Windows, X11 or
   *     Wayland
   */
  public WindowHandle handle() {
    checkOpen();
    if (handle == null) {
      throw new UnsupportedOperationException(
          "SDL's video driver " + Sdl.getCurrentVideoDriver() + " gives no handle for a surface");
    }
    return handle;
  }

  /** Closes the window. Closing a closed window does nothing. */
  @Override
  public void close() {
    if (!open) {
      return;
    }
    checkThread();
    open = false;
    try {
      if (win32 != null) {
        win32.detach();
      }
      Sdl.destroyWindow(sdl);
    } finally {
      windows.remove(id);
      pending.removeIf(posted -> posted.window == this);
      stopIfUnused();
    }
  }

  // ---- Inside the package

  MemorySegment sdlWindow() {
    return sdl;
  }

  int id() {
    return id;
  }

  /** Window coordinates per logical pixel. */
  float unitsPerLogical() {
    return scale / density;
  }

  float logical(float units) {
    return units * density / scale;
  }

  int units(float logical) {
    return Math.round(logical * scale / density);
  }

  Win32Frame win32() {
    return win32;
  }

  /** The open window with this SDL window ID, or null. Only on the windows' thread. */
  static Window byId(int id) {
    Window window = windows.get(id);
    return window != null && window.open ? window : null;
  }

  /** Keeps what an upcall caught, to throw it when control is back in Java. */
  static void fail(Throwable t) {
    if (failure == null) {
      failure = t;
    } else if (failure != t) {
      failure.addSuppressed(t);
    }
  }

  static boolean failed() {
    return failure != null;
  }

  /** Initialises SDL's video on this thread, or checks that this is the thread it runs on. */
  static void start() {
    Thread current = Thread.currentThread();
    Thread owner = thread;
    if (owner != null) {
      if (owner != current) {
        throw wrongThread(owner, current);
      }
      return;
    }
    // Throws an UnsatisfiedLinkError that says what is missing, every time, before Sdl loads.
    Natives.lookup("sdl3");
    // The JVM handles SIGINT and SIGTERM itself.
    Sdl.setHint(Sdl.HINT_NO_SIGNAL_HANDLERS, "1");
    // The app draws the composition of an input method, the system its candidates.
    Sdl.setHint(Sdl.HINT_IME_IMPLEMENTED_UI, "composition");
    // SDL counts a second press within 32 pixels of the first as a double press, as for fingers
    // on a touch screen: two quick presses on neighbouring rows would be one. An environment
    // variable of the same name still wins.
    Sdl.setHint(Sdl.HINT_MOUSE_DOUBLE_CLICK_RADIUS, String.valueOf(doubleClickRadius()));
    if (!Sdl.init(Sdl.INIT_VIDEO)) {
      throw new IllegalStateException("Cannot open windows here: SDL_Init failed: "
          + Sdl.getError());
    }
    if (buffer == null) {
      buffer = Arena.global().allocate(Sdl.EVENT_SIZE, 8);
      ints = Arena.global().allocate(8, 4);
    }
    if (!Sdl.addEventWatch(Upcalls.WATCH, MemorySegment.NULL)) {
      String error = Sdl.getError();
      Sdl.quit();
      throw new IllegalStateException("SDL_AddEventWatch failed: " + error);
    }
    int registered = Sdl.registerEvents(1);
    synchronized (LOCK) {
      // SDL_EVENT_USER if none is left; the type does not matter, only that it wakes the wait.
      wakeType = registered != 0 ? registered : 0x8000;
      thread = current;
    }
  }

  /**
   * How far, in window coordinates, a second press may lie from the first along each axis to make
   * a double press: on Windows half the system's rectangle for it ({@code SM_CXDOUBLECLK}, 4 by
   * default, centred on the first press), elsewhere 5, GTK's default distance.
   */
  static int doubleClickRadius() {
    if (!System.getProperty("os.name", "").startsWith("Windows")) {
      return 5;
    }
    int box = Math.max(Win32.getSystemMetrics(Win32.SM_CXDOUBLECLK),
        Win32.getSystemMetrics(Win32.SM_CYDOUBLECLK));
    return box > 0 ? Math.max(1, box / 2) : 2;
  }

  /** Quits SDL when no window is open, so that another thread may open the next one. */
  static void stopIfUnused() {
    if (!windows.isEmpty() || thread == null) {
      return;
    }
    Sdl.removeEventWatch(Upcalls.WATCH, MemorySegment.NULL);
    pending.clear();
    deferred.clear();
    Arrays.fill(cursors, null); // SDL_Quit frees them, and the next SDL starts with its default
    cursorShown = Cursor.DEFAULT;
    synchronized (LOCK) {
      Sdl.quit();
      thread = null;
    }
  }

  /** Throws what an upcall caught. */
  private static void rethrow() {
    Throwable t = failure;
    if (t == null) {
      return;
    }
    failure = null;
    if (t instanceof RuntimeException e) {
      throw e;
    }
    if (t instanceof Error e) {
      throw e;
    }
    throw new IllegalStateException(t);
  }

  private static void checkThread() {
    Thread owner = thread;
    Thread current = Thread.currentThread();
    if (owner == null) {
      throw new IllegalStateException("No window is open");
    }
    if (owner != current) {
      throw wrongThread(owner, current);
    }
  }

  private void checkOpen() {
    checkThread();
    if (!open) {
      throw new IllegalStateException("The window is closed");
    }
  }

  private static IllegalStateException wrongThread(Thread owner, Thread current) {
    return new IllegalStateException("Windows are used on the thread that opened the first of them,"
        + " " + owner.getName() + ", until all are closed; this is " + current.getName());
  }

  private void measure() {
    Sdl.getWindowSizeInPixels(sdl, ints);
    pixelWidth = ints.get(JAVA_INT, 0);
    pixelHeight = ints.get(JAVA_INT, 4);
    float s = Sdl.getWindowDisplayScale(sdl);
    scale = s > 0 ? s : 1;
    float d = Sdl.getWindowPixelDensity(sdl);
    density = d > 0 ? d : 1;
    width = pixelWidth / scale;
    height = pixelHeight / scale;
  }

  private void applyMinimumSize() {
    if (minWidth > 0 || minHeight > 0) {
      Sdl.setWindowMinimumSize(sdl, units(minWidth), units(minHeight));
    }
  }

  private WindowHandle readHandle() {
    int properties = Sdl.getWindowProperties(sdl);
    String driver = Sdl.getCurrentVideoDriver();
    return switch (driver == null ? "" : driver) {
      case "windows" -> new WindowHandle.Win32(
          Sdl.getPointerProperty(properties, Sdl.PROP_WINDOW_WIN32_HWND_POINTER),
          Sdl.getPointerProperty(properties, Sdl.PROP_WINDOW_WIN32_INSTANCE_POINTER));
      case "x11" -> new WindowHandle.Xlib(
          Sdl.getPointerProperty(properties, Sdl.PROP_WINDOW_X11_DISPLAY_POINTER),
          Sdl.getNumberProperty(properties, Sdl.PROP_WINDOW_X11_WINDOW_NUMBER));
      case "wayland" -> new WindowHandle.Wayland(
          Sdl.getPointerProperty(properties, Sdl.PROP_WINDOW_WAYLAND_DISPLAY_POINTER),
          Sdl.getPointerProperty(properties, Sdl.PROP_WINDOW_WAYLAND_SURFACE_POINTER));
      default -> null;
    };
  }

  /**
   * An upcall stub for a static method, kept for the life of the process. The method must catch
   * everything: an exception that leaves an upcall ends the JVM.
   */
  static MemorySegment upcall(
      MethodHandles.Lookup lookup, Class<?> owner, String name, FunctionDescriptor descriptor) {
    MethodHandle target;
    try {
      target = lookup.findStatic(owner, name, descriptor.toMethodType());
    } catch (ReflectiveOperationException e) {
      throw new LinkageError("No upcall target " + owner.getName() + "." + name, e);
    }
    return Linker.nativeLinker().upcallStub(target, descriptor, Arena.global());
  }
}
