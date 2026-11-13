package com.rethinkj.ui.window;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.Key;
import com.rethinkj.ui.WindowPart;
import com.rethinkj.ui.window.WindowEvent.CloseRequested;
import com.rethinkj.ui.window.WindowEvent.KeyDown;
import com.rethinkj.ui.window.WindowEvent.KeyUp;
import com.rethinkj.ui.window.WindowEvent.PointerDown;
import com.rethinkj.ui.window.WindowEvent.PointerMove;
import com.rethinkj.ui.window.WindowEvent.PointerUp;
import com.rethinkj.ui.window.WindowEvent.Redraw;
import com.rethinkj.ui.window.WindowEvent.Resized;
import com.rethinkj.ui.window.WindowEvent.StateChanged;
import com.rethinkj.ui.window.WindowEvent.TextInput;
import com.rethinkj.ui.window.WindowEvent.Wheel;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Opens real windows for a moment. Skipped where SDL cannot be loaded, as in a build without the
 * natives profile, or where there is no display.
 */
class WindowTest {

  private static final String TITLE = "rethinkj window test";

  /** A title bar 30 high over a window 300 wide: drag area, then minimize, maximize and close. */
  private static final Window.Parts PARTS = (x, y) -> {
    if (y >= 30) {
      return WindowPart.CONTENT;
    }
    return x >= 254 ? WindowPart.CLOSE
        : x >= 208 ? WindowPart.MAXIMIZE
        : x >= 162 ? WindowPart.MINIMIZE
        : WindowPart.DRAG;
  };

  private final List<WindowEvent> events = new ArrayList<>();

  @BeforeEach
  void needsWindows() {
    Desktop.assumeWindows();
  }

  @Test
  void opensAndClosesAFramelessWindow() {
    Window window = Window.open(TITLE, 320, 200, Window.options().frameless());
    try {
      assertTrue(window.isOpen());
      assertTrue(window.isFrameless());
      assertEquals(320, window.width(), 0.5f);
      assertEquals(200, window.height(), 0.5f);
      assertTrue(window.scale() > 0, "scale " + window.scale());
      assertEquals(Math.round(320 * window.scale()), window.pixelWidth(), 1);
      assertEquals(Math.round(200 * window.scale()), window.pixelHeight(), 1);
      if (System.getProperty("os.name").startsWith("Windows")) {
        WindowHandle.Win32 handle = assertInstanceOf(WindowHandle.Win32.class, window.handle());
        assertNotEquals(MemorySegment.NULL, handle.hwnd());
        assertNotEquals(MemorySegment.NULL, handle.hinstance());
      }
    } finally {
      window.close();
    }
    assertFalse(window.isOpen());
    window.close();
    assertThrows(IllegalStateException.class, window::maximize);
    assertThrows(IllegalStateException.class, Window::pollEvents, "no window is open");
  }

  @Test
  void resizesInLogicalPixelsAndSaysSo() {
    try (Window window = Window.open(TITLE, 400, 300)) {
      assertFalse(window.isFrameless());
      assertEquals(400, window.width(), 0.5f);
      Desktop.settle();
      window.listener(events::add);
      window.resize(500, 350);
      assertTrue(Desktop.pollUntil(() -> window.width() == 500), "width " + window.width());
      assertEquals(350, window.height(), 0.5f);
      assertTrue(events.contains(new Resized(500, 350)), events.toString());
    }
  }

  @Test
  void inputArrivesInOrderAsOurOwnEvents() {
    try (Window window = Window.open(TITLE, 300, 200)) {
      Desktop.settle();
      window.listener(events::add);
      Desktop.key(window, true, 'a', 0x0001 | 0x0040, false);
      Desktop.key(window, true, 'a', 0x0001 | 0x0040, true);
      Desktop.key(window, false, 'a', 0x0001 | 0x0040, false);
      Desktop.text(window, "ü€");
      Desktop.motion(window, 40, 30);
      Desktop.button(window, true, Sdl.BUTTON_RIGHT, 40, 30, 2);
      Desktop.button(window, false, Sdl.BUTTON_RIGHT, 41, 31, 2);
      Desktop.wheel(window, 0, -1.5f, 41, 31);
      assertTrue(events.isEmpty(), "nothing is delivered before pollEvents: " + events);
      Window.pollEvents();

      int shiftControl = Key.SHIFT | Key.CONTROL;
      List<WindowEvent> expected = List.of(
          new KeyDown(new Key("A", shiftControl, false)),
          new KeyDown(new Key("A", shiftControl, true)),
          new KeyUp(new Key("A", shiftControl, false)),
          new TextInput("ü€"),
          new PointerMove(40, 30),
          new PointerDown(40, 30, PointerButton.RIGHT, 2),
          new PointerUp(41, 31, PointerButton.RIGHT),
          new Wheel(0, -1.5f, 41, 31));
      // The real pointer may cross the window meanwhile; only the sent events are compared.
      assertEquals(expected, events.stream().filter(expected::contains).toList());
    }
  }

  @Test
  void aCloseRequestIsAnEventAndTheWindowStaysOpen() {
    try (Window window = Window.open(TITLE, 300, 200)) {
      Desktop.settle();
      window.listener(events::add);
      Desktop.windowEvent(window, Sdl.EVENT_WINDOW_CLOSE_REQUESTED, 0, 0);
      Window.pollEvents();
      assertTrue(events.contains(new CloseRequested()), events.toString());
      assertTrue(window.isOpen());
    }
  }

  @Test
  void theHitTestAsksForThePartsAndKeepsAMarginForResizing() {
    try (Window window = Window.open(TITLE, 300, 200, Window.options().frameless())) {
      window.parts(PARTS);
      assertEquals(Sdl.HITTEST_DRAGGABLE, window.zone(100, 15));
      assertEquals(Sdl.HITTEST_NORMAL, window.zone(230, 15), "a button is content to SDL");
      assertEquals(Sdl.HITTEST_NORMAL, window.zone(150, 100));
      assertEquals(Sdl.HITTEST_RESIZE_LEFT, window.zone(1, 100));
      assertEquals(Sdl.HITTEST_RESIZE_TOP, window.zone(100, 2), "the margin is above the bar");
      assertEquals(Sdl.HITTEST_RESIZE_BOTTOMRIGHT, window.zone(299, 199));

      window.maximize();
      assertTrue(Desktop.pollUntil(window::isMaximized));
      assertEquals(Sdl.HITTEST_NORMAL, window.zone(1, 100), "a maximized window does not resize");
      assertEquals(Sdl.HITTEST_DRAGGABLE, window.zone(100, 2));
    }
    try (Window window = Window.open(TITLE, 300, 200, Window.options().frameless().fixedSize())) {
      window.parts(PARTS);
      assertEquals(Sdl.HITTEST_NORMAL, window.zone(1, 100));
      assertEquals(Sdl.HITTEST_DRAGGABLE, window.zone(100, 2));
    }
  }

  @Test
  void theButtonPartsActWhenClicked() {
    try (Window window = Window.open(TITLE, 300, 200, Window.options().frameless())) {
      Desktop.settle();
      window.parts(PARTS);
      window.listener(events::add);

      Desktop.click(window, 280, 15);
      Window.pollEvents();
      assertTrue(events.contains(new CloseRequested()), events.toString());
      assertTrue(window.isOpen());

      events.clear();
      Desktop.button(window, true, Sdl.BUTTON_LEFT, 280, 15, 1);
      Desktop.button(window, false, Sdl.BUTTON_LEFT, 180, 15, 1);
      Window.pollEvents();
      assertFalse(events.contains(new CloseRequested()), "a press that ends elsewhere does nothing");
      assertFalse(window.isMinimized());

      Desktop.click(window, 230, 15);
      assertTrue(Desktop.pollUntil(window::isMaximized), "maximized by its part");
      assertTrue(events.contains(new StateChanged(false, true)), events.toString());
      Desktop.click(window, 230, 15);
      assertTrue(Desktop.pollUntil(() -> !window.isMaximized()), "restored by the same part");

      Desktop.click(window, 180, 15);
      assertTrue(Desktop.pollUntil(window::isMinimized), "minimized by its part");
      window.restore();
      assertTrue(Desktop.pollUntil(() -> !window.isMinimized()));
    }
  }

  @Test
  void aFramelessWindowKeepsItsSizeWhenTheSystemMinimizesAndRestoresIt() {
    try (Window window = Window.open(TITLE, 300, 200, Window.options().frameless())) {
      assumeTrue(window.handle() instanceof WindowHandle.Win32, "Windows only");
      MemorySegment hwnd = ((WindowHandle.Win32) window.handle()).hwnd();
      // As the taskbar does it, from outside, not through SDL, which expects the sizes on the way.
      MethodHandle showWindow = Linker.nativeLinker().downcallHandle(
          SymbolLookup.libraryLookup("user32", Arena.global()).find("ShowWindow").orElseThrow(),
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
      Desktop.settle();
      window.listener(events::add);
      elsewhere(showWindow, hwnd, 6); // SW_MINIMIZE
      assertTrue(Desktop.pollUntil(window::isMinimized), "minimized");
      elsewhere(showWindow, hwnd, 9); // SW_RESTORE
      assertTrue(Desktop.pollUntil(() -> !window.isMinimized()), "restored");
      Desktop.settle();

      // Not the 160 by 28 pixels Windows gives a minimized window on the way.
      assertEquals(List.of(), events.stream().filter(e -> e instanceof Resized).toList());
      assertEquals(300, window.width(), 0.5f);
      assertEquals(200, window.height(), 0.5f);
    }
  }

  /** Calls ShowWindow on another thread, as the taskbar does from another process. */
  private static void elsewhere(MethodHandle showWindow, MemorySegment hwnd, int command) {
    Thread.ofPlatform().start(() -> {
      try {
        int ignored = (int) showWindow.invokeExact(hwnd, command);
      } catch (Throwable t) {
        throw new AssertionError(t);
      }
    });
  }

  @Test
  void whatTheListenerThrowsInsideTheSystemsLoopComesOutOfTheNextPoll() {
    try (Window window = Window.open(TITLE, 300, 200)) {
      Desktop.settle();
      IllegalStateException thrown = new IllegalStateException("from the listener");
      window.listener(event -> {
        if (event instanceof Redraw redraw && redraw.live()) {
          throw thrown;
        }
      });
      // What SDL sends from inside the system's move loop, where the listener is called from an
      // upcall: the exception cannot pass through native code.
      Desktop.windowEvent(window, Sdl.EVENT_WINDOW_EXPOSED, 1, 0);
      IllegalStateException caught = assertThrows(IllegalStateException.class, Window::pollEvents);
      assertSame(thrown, caught);
      Window.pollEvents();
    }
  }

  @Test
  void windowsBelongToTheThreadThatOpenedThem() throws InterruptedException {
    try (Window window = Window.open(TITLE, 300, 200)) {
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread other = Thread.ofPlatform().start(() -> {
        try {
          Window.pollEvents();
        } catch (Throwable t) {
          failure.set(t);
        }
      });
      other.join();
      assertInstanceOf(IllegalStateException.class, failure.get());
      assertTrue(window.isOpen());
    }
  }
}
