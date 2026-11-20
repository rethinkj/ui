package com.rethinkj.ui.window;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Keys that arrive in one batch with the click or Tab that takes the focus into a field, as from
 * a fast typist or a tool that types: Windows makes their characters before the window has turned
 * its text input on for the field, SDL drops them, and they still reach the field. The keys are
 * real {@code WM_KEYDOWN} messages posted to the window, whose characters Windows makes with the
 * keyboard layout ({@code TranslateMessage}) while SDL handles the batch; the press is put into
 * SDL's queue before them. Posted keys come before the characters Windows makes of them, unlike
 * a keyboard's, whose character follows its key; which key's character a field takes is {@code
 * KeyTextTest}'s. Also characters without a key, which Windows sends to the window without SDL
 * knowing of them. Windows only; skipped where the window gets no keyboard focus.
 */
class TextInBatchTest {

  private static final String TITLE = "rethinkj text in a batch test";
  private static final int WM_KEYDOWN = 0x0100;
  private static final int WM_KEYUP = 0x0101;
  private static final int WM_CHAR = 0x0102;

  /** The field's string. */
  private static final class Form {
    String name = "";
  }

  @BeforeEach
  void needsWindowsAndText() {
    Desktop.assumeWindows();
    TextLibraries.assumeLoaded();
    assumeTrue(System.getProperty("os.name").startsWith("Windows"), "Skipped: Windows only");
  }

  private static App<Form> app() {
    App<Form> app = new App<>(
        new Form(),
        f -> col()
            .wFull()
            .hFull()
            .padding(20)
            .child(El.text(f.name)
                .editable(s -> f.name = s)
                .key("name")
                .w(160)
                .h(28)
                .padding(0, 8)));
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  private static final MethodHandle POST_MESSAGE;
  private static final MethodHandle MAP_VIRTUAL_KEY;

  static {
    MethodHandle post = null;
    MethodHandle map = null;
    if (System.getProperty("os.name").startsWith("Windows")) {
      SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());
      Linker linker = Linker.nativeLinker();
      post = linker.downcallHandle(
          user32.find("PostMessageW").orElseThrow(),
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG));
      map = linker.downcallHandle(
          user32.find("MapVirtualKeyW").orElseThrow(),
          FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
    }
    POST_MESSAGE = post;
    MAP_VIRTUAL_KEY = map;
  }

  private static void post(Window window, int message, long wParam, long lParam) {
    try {
      MemorySegment hwnd = ((WindowHandle.Win32) window.handle()).hwnd();
      int posted = (int) POST_MESSAGE.invokeExact(hwnd, message, wParam, lParam);
      assumeTrue(posted != 0, "Skipped: PostMessage failed");
    } catch (Throwable t) {
      throw new AssertionError(t);
    }
  }

  /** Posts a key's press and release to the window, as the keyboard would. */
  private static void key(Window window, int vk) {
    long scan;
    try {
      scan = (int) MAP_VIRTUAL_KEY.invokeExact(vk, 0);
    } catch (Throwable t) {
      throw new AssertionError(t);
    }
    post(window, WM_KEYDOWN, vk, 1L | scan << 16);
    post(window, WM_KEYUP, vk, 1L | scan << 16 | 0xC0000000L);
  }

  private static void abc(Window window) {
    key(window, 'A');
    key(window, 'B');
    key(window, 'C');
  }

  /** Opens the window, lets the app draw once and needs the keyboard focus. */
  private static Window open(App<Form> app) {
    Window window = Window.open(TITLE, 240, 100);
    Desktop.settle();
    if (!Desktop.pollUntil(window::isFocused)) {
      window.close();
      assumeTrue(false, "Skipped: the window has no keyboard focus here");
    }
    Desktop.runUntil(window, app, () -> true, "the first frame");
    return window;
  }

  @Test
  void keysTypedWithTheClickIntoAFieldReachIt() {
    App<Form> app = app();
    try (Window window = open(app)) {
      Desktop.click(window, 100, 34); // in the field: text input is off until the loop has it
      abc(window);
      Desktop.runUntil(
          window,
          app,
          () -> app.state().name.equals("abc"),
          "the keys after the click are in the field");
      assertEquals("abc", app.state().name);
    }
  }

  /**
   * A character without a key, as an input method, the emoji panel or SendInput's {@code
   * KEYEVENTF_UNICODE} sends it, that arrives alone while the loop waits with nothing to do, for an
   * element that takes no text, so that text input is off and SDL queues no event of it: the next
   * frame still shows it.
   */
  @Test
  void aCharacterWithoutAKeyWakesTheWaitingLoop() {
    try (Window window = Window.open(TITLE, 240, 100)) {
      Form form = new Form();
      App<Form> app = new App<>(
          form,
          f -> col()
              .wFull()
              .hFull()
              .child(el().key("target")
                  .size(160, 50)
                  .focusable()
                  .autofocus()
                  .onTypeAhead(s -> f.name = s)));
      AtomicBoolean sent = new AtomicBoolean();
      AtomicReference<WindowLoop> loop = new AtomicReference<>();
      loop.set(new WindowLoop(window, app, (scene, w, h, s) -> {
        if (sent.get() && form.name.equals("é")) {
          loop.get().stop();
        }
      }));
      MemorySegment hwnd = ((WindowHandle.Win32) window.handle()).hwnd();
      AtomicBoolean done = new AtomicBoolean();
      AtomicReference<String> posted = new AtomicReference<>("not yet");
      // The waits the loop announced, and whether the last was one for events alone.
      AtomicLong waits = new AtomicLong();
      AtomicBoolean idle = new AtomicBoolean();
      loop.get().waits((millis, holdBack) -> {
        idle.set(millis == -1);
        waits.incrementAndGet();
        if (millis == -1 && !sent.getAndSet(true)) {
          // From another thread once the loop waits in SDL for events alone: until then, events
          // of the window's opening that are still pending would deliver the character without a
          // wait, and a wait with a timeout would deliver it when it ends.
          Thread waiting = Thread.currentThread();
          Thread.ofPlatform().daemon().start(() -> {
            while (!inIdleWait(waiting, waits, idle)) {
              if (done.get()) {
                return;
              }
              Thread.onSpinWait();
            }
            try {
              int ok = (int) POST_MESSAGE.invokeExact(hwnd, WM_CHAR, (long) 'é', 1L);
              posted.set(ok != 0 ? "yes" : "PostMessage failed");
            } catch (Throwable t) {
              posted.set(t.toString());
            }
          });
        }
      });
      try {
        Desktop.run(
            loop.get(),
            () -> "a frame after the character; posted: " + posted.get() + ", typed: \"" + form.name
                + "\"");
      } finally {
        done.set(true);
      }
    }
  }

  /**
   * Whether the loop's thread waits in SDL for an event ({@link Sdl#waitEventTimeout}) in the wait
   * it announced last, and that one waits for events alone: no wait was announced since.
   */
  private static boolean inIdleWait(Thread thread, AtomicLong waits, AtomicBoolean idle) {
    long announced = waits.get();
    if (!idle.get()) {
      return false;
    }
    for (StackTraceElement frame : thread.getStackTrace()) {
      if (frame.getClassName().equals(Sdl.class.getName())
          && frame.getMethodName().equals("waitEventTimeout")) {
        return waits.get() == announced;
      }
    }
    return false;
  }

  @Test
  void charactersWithoutAKeyAfterTheClickReachTheField() {
    App<Form> app = app();
    try (Window window = open(app)) {
      Desktop.click(window, 100, 34);
      // As SendInput's KEYEVENTF_UNICODE sends them; the emoji in its two halves.
      post(window, WM_CHAR, 'é', 1);
      post(window, WM_CHAR, '\uD83D', 1);
      post(window, WM_CHAR, '\uDE00', 1);
      Desktop.runUntil(
          window, app, () -> app.state().name.equals("é😀"), "the characters are in the field");
    }
  }

  /**
   * AltGr: SDL reports a key held with it as held with Alt alone, hiding the left Control that
   * Windows adds, and Windows makes a character of it ({@code WM_CHAR}; Alt with a letter makes
   * {@code WM_SYSCHAR} instead). The window then says Control with Alt, so that an Alt shortcut,
   * as a menu bar's Alt+Q, does not take AltGr+Q, which is @ on a German keyboard.
   */
  @Test
  void aKeyHeldWithAltThatMakesACharacterWasAltGr() {
    try (Window window = Window.open(TITLE, 240, 100)) {
      Desktop.settle();
      List<WindowEvent> events = new ArrayList<>();
      window.listener(events::add);
      Desktop.key(window, true, 'q', Sdl.KMOD_ALT & 0x0200, false); // right Alt, as AltGr
      window.systemChar('@'); // what Windows makes of it, after SDL has had it
      Desktop.key(window, false, 'q', Sdl.KMOD_ALT & 0x0200, false);
      Desktop.key(window, true, 'q', Sdl.KMOD_ALT & 0x0100, false); // left Alt, a shortcut
      Desktop.key(window, false, 'q', Sdl.KMOD_ALT & 0x0100, false);
      // With text input on, SDL makes its text event of the character before the window sees it.
      window.textInputArea(10, 10, 100, 20, 10, false);
      Desktop.key(window, true, 'e', Sdl.KMOD_ALT & 0x0200, false);
      Desktop.text(window, "€");
      window.systemChar('€');
      Desktop.key(window, false, 'e', Sdl.KMOD_ALT & 0x0200, false);
      window.stopTextInput();
      Window.pollEvents();
      List<String> keys = events.stream()
          .filter(e -> e instanceof WindowEvent.KeyDown
              || e instanceof WindowEvent.KeyText
              || e instanceof WindowEvent.TextInput)
          .map(e -> e instanceof WindowEvent.KeyDown(Key k)
              ? k.name() + " " + k.modifiers()
              : e.toString())
          .toList();
      int altGr = Key.CONTROL | Key.ALT;
      assertEquals(
          List.of(
              "Q " + altGr, "KeyText[text=@]", "Q " + Key.ALT, "E " + altGr, "TextInput[text=€]"),
          keys);
    }
  }

  @Test
  void keysTypedWithTabIntoAFieldReachIt() {
    App<Form> app = app();
    try (Window window = open(app)) {
      key(window, 0x09); // Tab, from nothing to the field; its character is not text
      abc(window);
      Desktop.runUntil(
          window, app, () -> app.state().name.equals("abc"), "the keys after Tab are in the field");
    }
  }
}
