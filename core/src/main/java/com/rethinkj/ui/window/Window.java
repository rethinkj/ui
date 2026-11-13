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
import com.rethinkj.ui.render.Pixels;
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
 * the composition itself; the system places its candidate window below the area. Input for a
 * password asks the system for a hidden password's input, which SDL 3.4 passes on only on Wayland
 * (text-input-v3's purpose {@code password} with the hints {@code hidden_text} and {@code
 * sensitive_data}); on Windows and X11 the input method composes as in any field.
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

  /** How a window asks for the user's attention; see {@link #attention}. */
  public enum Attention {
    /** Stops asking. */
    NONE,
    /** Flashes the taskbar button once. */
    BRIEFLY,
    /**
     * Flashes the taskbar button until the user brings the window to the front; on Windows 11 the
     * button then stays highlighted.
     */
    UNTIL_FOCUSED
  }

  /** How a window opens; see {@link #options()}. */
  public static final class Options {
    private boolean frameless;
    private boolean resizable = true;
    private float minWidth;
    private float minHeight;
    private float resizeMargin = 6;
    private WindowIcon icon;
    private boolean positioned;
    private int x;
    private int y;
    private boolean underPointer;

    private Options() {}

    /**
     * For a window that opens under the pointer during a drag and follows it, as a tab torn off
     * into a window of its own: it shows without the system's opening animation (on Windows
     * {@code DWMWA_TRANSITIONS_FORCEDISABLED}, so that no empty frame zooms in before its first
     * frame), and without taking the keyboard focus from the window the drag began in ({@code
     * SDL_HINT_WINDOW_ACTIVATE_WHEN_SHOWN}), whose keys, Escape among them, reach the drag as they
     * did. A {@link WindowLoop} gives it the focus when the press ends, if it is still open.
     */
    public Options underPointer() {
      underPointer = true;
      return this;
    }

    /** Whether {@link #underPointer} was called. */
    public boolean isUnderPointer() {
      return underPointer;
    }

    /**
     * Where the content's top left corner opens on the screen, in the units of {@link
     * Window#moveTo}; where the system puts it unless set. Wayland ignores it: there the
     * compositor places every window.
     */
    public Options position(int x, int y) {
      positioned = true;
      this.x = x;
      this.y = y;
      return this;
    }

    /** Whether {@link #frameless} was called. */
    public boolean isFrameless() {
      return frameless;
    }

    /** Whether {@link #position} set where the window opens. */
    public boolean isPositioned() {
      return positioned;
    }

    /** The position {@link #position} set, else 0. */
    public int x() {
      return x;
    }

    /** See {@link #x()}. */
    public int y() {
      return y;
    }

    /**
     * The window's icon from the start, so that its taskbar button never shows another; see
     * {@link Window#icon}.
     */
    public Options icon(WindowIcon icon) {
      this.icon = Objects.requireNonNull(icon, "icon");
      return this;
    }

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
  /** What {@link #identity} set, for the next {@link #start}; guarded by {@link #LOCK}. */
  private static String appId;
  private static String appName;
  /** The appearance last delivered, or null before the first. */
  private static SystemAppearance appearance;

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
  private final boolean positioned;
  private final int x;
  private final int y;
  private final boolean underPointer;
  private String title;
  private WindowHandle handle;
  private Win32Frame win32;
  private Win32Shell shell;
  private WindowIcon icon;
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
  // The last event was a key pressed with Control and without Alt, whose character Windows types.
  private boolean afterControlKey;
  /** Where the last pointer move was, in logical pixels, while the pointer is in the window. */
  private boolean pointerIn;
  private float pointerX;
  private float pointerY;
  private Cursor cursor = Cursor.DEFAULT;
  /** The text input area last set, in window coordinates, while text input is on; else null. */
  private int[] textInput;
  private boolean passwordInput; // whether the text input on is for a password

  private Window(MemorySegment sdl, Options options) {
    this.sdl = sdl;
    this.id = Sdl.getWindowID(sdl);
    this.frameless = options.frameless;
    this.resizable = options.resizable;
    this.minWidth = options.minWidth;
    this.minHeight = options.minHeight;
    this.resizeMargin = options.resizeMargin;
    this.icon = options.icon;
    this.positioned = options.positioned;
    this.x = options.x;
    this.y = options.y;
    this.underPointer = options.underPointer;
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
    window.title = title;
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
    if (handle instanceof WindowHandle.Win32 win) {
      shell = Win32Shell.attach(this, win.hwnd());
    }
    if (icon != null) {
      applyIcon();
    }
    if (frameless) {
      if (!Sdl.setWindowHitTest(sdl, Upcalls.HIT_TEST, MemorySegment.ofAddress(id))) {
        throw new IllegalStateException("SDL_SetWindowHitTest failed: " + Sdl.getError());
      }
      if (handle instanceof WindowHandle.Win32 win) {
        win32 = Win32Frame.attach(this, win.hwnd());
      }
    }
    if (positioned) {
      Sdl.setWindowPosition(sdl, x, y);
    }
    if (underPointer) {
      if (shell != null) {
        shell.transitions(false); // no opening animation before the first frame
      }
      // SDL reads the hint as it shows the window: shown, not activated.
      String before = Sdl.getHint(Sdl.HINT_WINDOW_ACTIVATE_WHEN_SHOWN);
      Sdl.setHint(Sdl.HINT_WINDOW_ACTIVATE_WHEN_SHOWN, "0");
      try {
        Sdl.showWindow(sdl);
      } finally {
        Sdl.setHint(Sdl.HINT_WINDOW_ACTIVATE_WHEN_SHOWN, before == null ? "1" : before);
      }
    } else {
      Sdl.showWindow(sdl);
    }
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
      if (type == Sdl.EVENT_SYSTEM_THEME_CHANGED) {
        // SDL sends this only when light or dark changed; the accent may have come with it.
        deliverAppearance(systemAppearance());
        return true;
      }
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
    boolean controlKey = afterControlKey;
    afterControlKey = false;
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
      case Sdl.EVENT_MOUSE_WHEEL -> post(wheel(e.get(JAVA_FLOAT, Sdl.WHEEL_X),
          e.get(JAVA_FLOAT, Sdl.WHEEL_Y), logical(e.get(JAVA_FLOAT, Sdl.WHEEL_MOUSE_X)),
          logical(e.get(JAVA_FLOAT, Sdl.WHEEL_MOUSE_Y)),
          (Sdl.getModState() & Sdl.KMOD_SHIFT) != 0));
      case Sdl.EVENT_KEY_DOWN, Sdl.EVENT_KEY_UP -> {
        String key = Keys.name(e.get(JAVA_INT, Sdl.KEY_KEY));
        int modifiers = modifiers(e.get(JAVA_SHORT, Sdl.KEY_MOD));
        if (type == Sdl.EVENT_KEY_DOWN) {
          afterControlKey = handle instanceof WindowHandle.Win32
              && (modifiers & (Key.CONTROL | Key.ALT)) == Key.CONTROL;
          post(new KeyDown(new Key(key, modifiers, e.get(JAVA_BOOLEAN, Sdl.KEY_REPEAT))));
        } else {
          post(new KeyUp(new Key(key, modifiers, false)));
        }
      }
      case Sdl.EVENT_TEXT_INPUT -> {
        String text = Sdl.string(e.get(ADDRESS, Sdl.TEXT_TEXT));
        // Windows makes a space of Control+Space, which on Linux types nothing: a shortcut, such
        // as the one that opens completions, would type it too. Control with Alt is AltGr, whose
        // characters stay.
        if (text != null && !text.isEmpty() && !controlKey) {
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
          if (shell != null) {
            shell.scaleChanged(scale); // icons for the new scale
          }
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
   * The wheel's event. With Shift held, a wheel that turns only up and down scrolls sideways, as
   * in the system's own apps on Windows and in GTK's on Linux: towards the user is to the right.
   */
  static Wheel wheel(float dx, float dy, float x, float y, boolean shift) {
    return shift && dx == 0 && dy != 0 ? new Wheel(-dy, 0, x, y) : new Wheel(dx, dy, x, y);
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

  /**
   * Whether the system tells a client where its windows are and lets it move them: not on
   * Wayland, where the compositor alone places windows and {@link #x()} is always 0.
   */
  static boolean knowsPositions() {
    return !"wayland".equals(Sdl.getCurrentVideoDriver());
  }

  /**
   * Captures the pointer for the window it is over, or lets it go: while captured, that window
   * gets the pointer's moves and releases also outside it, as during a press.
   */
  static void capturePointer(boolean on) {
    checkThread();
    Sdl.captureMouse(on);
  }

  /**
   * Where the pointer is on the desktop now, in the units of {@link #moveTo}, from {@code
   * SDL_GetGlobalMouseState}: the system's pointer as it is when this is called, ahead of the
   * events that wait to be delivered. On Wayland, where a client knows no positions, it is the
   * pointer's place in the window under it, or the last one SDL knows. Needs an open window.
   *
   * @return x and y
   */
  public static float[] pointer() {
    checkThread();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment at = arena.allocate(8, 4);
      Sdl.getGlobalMouseState(at);
      return new float[] {at.get(JAVA_FLOAT, 0), at.get(JAVA_FLOAT, 4)};
    }
  }

  public void title(String title) {
    checkOpen();
    Sdl.setWindowTitle(sdl, Objects.requireNonNull(title, "title"));
    this.title = title;
  }

  /** The title the window opened with, or the last that {@link #title(String)} set. */
  public String title() {
    return title;
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

  /**
   * Opens the system's window menu (restore, move, size, minimize, maximize, close) at the left
   * edge, below the title bar, as Alt+Space does on a framed window: in a frameless window below
   * the rows at the top of the left edge that the view marks as its drag area ({@link
   * WindowPart#DRAG}). The menu runs a loop of its own, so it opens after the events queued now
   * are delivered. Where the platform has no such menu, nothing opens.
   */
  public void showSystemMenu() {
    checkOpen();
    float below = 0;
    if (frameless) {
      while (below < height && part(0, below) == WindowPart.DRAG) {
        below++;
      }
    }
    int y = units(below);
    deferred.add(() -> {
      if (open) {
        Sdl.showWindowSystemMenu(sdl, 0, y);
      }
    });
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
   * Sets the window's icon: its taskbar button, Alt+Tab, the title bar and system menu of a framed
   * window, and on Linux the dock. {@link Options#icon} sets it before the window shows. The
   * icon goes to {@code SDL_SetWindowIcon} as a surface of one size with larger alternate images:
   * on Windows the big icon's size, 32 pixels times the scale, and as SDL's Windows driver makes
   * one icon of the base image for both sizes, the window then also gets its small icon of 16
   * pixels times the scale ({@code WM_SETICON}), each from the image of its size, and new ones
   * when the scale changes. On Linux the base is 64 pixels with alternates of 128 and 256, which
   * Wayland's {@code xdg_toplevel_icon_v1} takes as scales 1, 2 and 4; X11's {@code
   * _NET_WM_ICON} gets the base only, and a dock may prefer the icon of the app's {@code
   * .desktop} file, which it finds by the app id ({@link #identity}).
   *
   * @return false where the system shows no icon of the window's own, such as a Wayland
   *     compositor without {@code xdg_toplevel_icon_v1}
   */
  public boolean icon(WindowIcon icon) {
    checkOpen();
    this.icon = Objects.requireNonNull(icon, "icon");
    boolean shown = applyIcon();
    rethrow();
    return shown;
  }

  private boolean applyIcon() {
    int base = shell != null ? Math.round(32 * scale) : 64;
    Pixels first = icon.image(base);
    MemorySegment surface = Sdl.createSurface(first);
    if (surface.equals(MemorySegment.NULL)) {
      throw new IllegalStateException("SDL_CreateSurface failed: " + Sdl.getError());
    }
    boolean shown;
    try {
      int largest = first.width();
      for (int size : shell != null ? new int[0] : new int[] {128, 256}) {
        Pixels image = icon.image(size);
        if (image.width() <= largest) {
          continue; // an icon of images that has none larger
        }
        largest = image.width();
        MemorySegment alternate = Sdl.createSurface(image);
        if (!alternate.equals(MemorySegment.NULL)) {
          Sdl.addSurfaceAlternateImage(surface, alternate); // SDL keeps a reference
          Sdl.destroySurface(alternate);
        }
      }
      shown = Sdl.setWindowIcon(sdl, surface);
    } finally {
      Sdl.destroySurface(surface);
    }
    if (shell != null) {
      shell.icon(icon, scale);
    }
    return shown;
  }

  /** The icon set last, or null if the window has none of its own. */
  public WindowIcon icon() {
    return icon;
  }

  /**
   * Asks for the user's attention without taking the focus: on Windows the taskbar button flashes
   * ({@code SDL_FlashWindow}, {@code FlashWindowEx}), on X11 the window manager is asked to mark
   * the window urgent, and on Wayland the compositor is asked to activate it through {@code
   * xdg_activation_v1}, which most show as a notice. A window that has the focus is not marked.
   *
   * @return false where the system cannot do that
   */
  public boolean attention(Attention attention) {
    checkOpen();
    return Sdl.flashWindow(sdl, switch (Objects.requireNonNull(attention, "attention")) {
      case NONE -> Sdl.FLASH_CANCEL;
      case BRIEFLY -> Sdl.FLASH_BRIEFLY;
      case UNTIL_FOCUSED -> Sdl.FLASH_UNTIL_FOCUSED;
    });
  }

  /**
   * Shows a small icon over the corner of the window's taskbar button, such as a count or a
   * status dot, drawn at 16 pixels times the scale; null removes it. On Windows this is the
   * button's overlay icon ({@code ITaskbarList3::SetOverlayIcon}); one set before the taskbar
   * made the button shows once it has, and it is set again when Explorer restarts. On Linux it
   * does nothing: SDL has no badge, and docks that show counts take them from the app's {@code
   * .desktop} entry over D-Bus.
   *
   * @param description what the badge says, for screen readers, such as "3 queries running";
   *     needed with an icon
   * @return false where the system shows no badge
   */
  public boolean badge(WindowIcon badge, String description) {
    checkOpen();
    if (badge != null) {
      Objects.requireNonNull(description, "description");
    }
    if (shell == null) {
      return false;
    }
    boolean shown = shell.badge(badge, description, scale);
    rethrow();
    return shown;
  }

  /**
   * Draws the system's frame of a framed window dark or light to match the app's theme where it
   * differs from the system's setting, which the frame follows until this is called: on Windows
   * the caption and border ({@code DWMWA_USE_IMMERSIVE_DARK_MODE}), set again after each change
   * of the setting, since SDL then sets the system's. A frameless window's thin border on Windows
   * 11 keeps following the system's setting (SDL's {@code WCA_USEDARKMODECOLORS}), and so does
   * the system menu of every window. On Linux it does nothing: the window manager draws the frame
   * from its own theme.
   *
   * @return false where the system refused it, and on Linux
   */
  public boolean frameDark(boolean dark) {
    checkOpen();
    return shell != null && shell.frameDark(dark);
  }

  /**
   * Sets who the app is, for windows opened after this: an id that the system groups the app's
   * windows by and finds its settings and icon with, and the app's name. It is the same for the
   * whole process; call it before the first window opens. Calling it again with the same id and
   * name does nothing, also while windows are open, so that the code that opens a later window may
   * set its options up the same way.
   *
   * <p>The id and the name go to {@code SDL_SetAppMetadata}, from which SDL takes the Wayland
   * app id and the X11 window class, which docks match with the app's {@code <id>.desktop} file.
   * On Windows the id is also the process's AppUserModelID ({@code
   * SetCurrentProcessExplicitAppUserModelID}): the taskbar then groups the app's windows under a
   * button of their own with the window's icon, instead of with every Java program under {@code
   * java.exe}. What a pinned button starts is a matter of packaging: the taskbar pins the
   * program that runs, {@code java.exe}, unless a Start menu shortcut with the same
   * AppUserModelID says how to start the app.
   *
   * @param id reverse DNS, such as {@code com.example.Notes}: at most 128 characters, without
   *     spaces, as Windows requires
   * @param name the app's name for people, or null
   * @throws IllegalStateException if a window is open and the id or the name differs from the one
   *     set
   */
  public static void identity(String id, String name) {
    Objects.requireNonNull(id, "id");
    if (id.isEmpty() || id.length() > 128 || id.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException(
          "An app id has 1 to 128 characters and no spaces, such as com.example.Notes: " + id);
    }
    synchronized (LOCK) {
      if (thread != null && !(id.equals(appId) && Objects.equals(name, appName))) {
        throw new IllegalStateException("The app's identity is set before the first window opens"
            + " and stays: " + appId + ", " + appName);
      }
      appId = id;
      appName = name;
    }
  }

  /**
   * How the system looks now: light or dark ({@code SDL_GetSystemTheme}), and on Windows the
   * accent colour (the {@code AccentColor} of {@code HKCU\Software\Microsoft\Windows\DWM}, which
   * is the colour chosen in Settings, else {@code DwmGetColorizationColor}) and high contrast
   * ({@code SPI_GETHIGHCONTRAST}). Each change comes to every window as a {@link
   * WindowEvent.AppearanceChanged}: light and dark through SDL's {@code
   * SDL_EVENT_SYSTEM_THEME_CHANGED}, and on Windows the accent and high contrast when the window
   * gets {@code WM_DWMCOLORIZATIONCOLORCHANGED}, {@code WM_SETTINGCHANGE} or {@code
   * WM_SYSCOLORCHANGE}. While a window is open, on its thread.
   */
  public static SystemAppearance systemAppearance() {
    checkThread();
    SystemAppearance.Scheme scheme = switch (Sdl.getSystemTheme()) {
      case Sdl.SYSTEM_THEME_LIGHT -> SystemAppearance.Scheme.LIGHT;
      case Sdl.SYSTEM_THEME_DARK -> SystemAppearance.Scheme.DARK;
      default -> SystemAppearance.Scheme.UNKNOWN;
    };
    boolean windows = "windows".equals(Sdl.getCurrentVideoDriver());
    return new SystemAppearance(scheme, windows ? Win32Shell.accent() : 0,
        windows && Win32Shell.highContrast());
  }

  /** A message said that the system's colours may have changed: tells the windows if they did. */
  static void appearanceMayHaveChanged() {
    SystemAppearance now = systemAppearance();
    if (!now.equals(appearance)) {
      deliverAppearance(now);
      wake();
    }
  }

  private static void deliverAppearance(SystemAppearance now) {
    appearance = now;
    for (Window window : windows.values()) {
      if (window.open) {
        window.post(new AppearanceChanged(now));
      }
    }
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
   *
   * @param password whether the text is a password that the field hides; text input starts again
   *     when this changes, since the system takes it only then
   */
  public void textInputArea(
      float x, float y, float width, float height, float cursor, boolean password) {
    checkOpen();
    int[] area = {units(x), units(y), units(width), units(height), units(cursor)};
    if (textInput != null && password != passwordInput) {
      stopTextInput();
    }
    if (textInput != null && Arrays.equals(area, textInput)) {
      return;
    }
    if (!Sdl.setTextInputArea(sdl, area[0], area[1], area[2], area[3], area[4])) {
      throw new IllegalStateException("SDL_SetTextInputArea failed: " + Sdl.getError());
    }
    if (textInput == null
        && !(password ? Sdl.startPasswordInput(sdl) : Sdl.startTextInput(sdl))) {
      throw new IllegalStateException("SDL_StartTextInput failed: " + Sdl.getError());
    }
    textInput = area;
    passwordInput = password;
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
      if (shell != null) {
        shell.detach();
      }
      Sdl.destroyWindow(sdl);
      if (shell != null) {
        shell.destroyIcons();
      }
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

  /** A position the system reported, such as the pointer's, in logical pixels; see below. */
  float logical(float units) {
    return position(units, density, scale);
  }

  /**
   * A position in window units as logical pixels. Where it is a whole physical pixel, as the
   * pointer's always is on Windows and X11, it is that pixel's centre, a 256th of a pixel towards
   * its top left: the scene snaps the edges of boxes to whole pixels, halves up, so a pixel is
   * drawn as part of a box exactly when that point lies in the box's layout rectangle, edges
   * included. A press then reaches the box that drew the pixel under it, also at 1.25 or 1.5, and
   * not the box that ends where the pixel begins.
   */
  static float position(float units, float density, float scale) {
    double pixels = (double) units * density;
    if (pixels == Math.rint(pixels)) {
      pixels += 0.5 - 1 / 256.0;
    }
    return (float) (pixels / scale);
  }

  int units(float logical) {
    return Math.round(logical * scale / density);
  }

  Win32Frame win32() {
    return win32;
  }

  Win32Shell shell() {
    return shell;
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
    String id;
    String name;
    synchronized (LOCK) {
      id = appId;
      name = appName;
    }
    if (id != null) {
      // Before SDL_Init and before any window: SDL reads it when it makes a window, and Windows
      // wants the AppUserModelID set before the process shows anything.
      Sdl.setAppMetadata(name, null, id);
      if (System.getProperty("os.name", "").startsWith("Windows")) {
        Win32.setCurrentProcessExplicitAppUserModelId(id);
      }
    }
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
    appearance = null;
    Win32Shell.releaseTaskbar(); // before SDL uninitialises COM
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
