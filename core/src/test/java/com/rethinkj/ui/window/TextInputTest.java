package com.rethinkj.ui.window;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import com.rethinkj.ui.window.WindowEvent.FocusChanged;
import com.rethinkj.ui.window.WindowEvent.PointerDown;
import com.rethinkj.ui.window.WindowEvent.TextEditing;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Text fields in real windows: what reaches them, where the window tells the input method the
 * caret is, and when text input is on. Skipped like {@link WindowTest}; events are synthetic.
 */
class TextInputTest {

  private static final String TITLE = "rethinkj text input test";

  /** The field's string, and a button beside it. */
  private static final class Form {
    String name = "";
  }

  @BeforeEach
  void needsWindowsAndText() {
    Desktop.assumeWindows();
    TextLibraries.assumeLoaded();
  }

  private static App<Form> app() {
    App<Form> app = new App<>(
        new Form(),
        f -> col()
            .wFull()
            .hFull()
            .padding(20)
            .gap(10)
            .children(
                El.text(f.name)
                    .editable(s -> f.name = s)
                    .key("name")
                    .w(160)
                    .h(28)
                    .padding(0, 8),
                el().key("button").size(40, 20).focusable().onPress(() -> {})));
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  private static void runUntil(Window window, App<?> app, BooleanSupplier done, String what) {
    Desktop.runUntil(window, app, done, what);
  }

  private static void tab(Window window) {
    Desktop.key(window, true, 0x09, 0, false);
    Desktop.key(window, false, 0x09, 0, false);
  }

  @Test
  void typedTextAndACompositionReachTheFocusedField() {
    try (Window window = Window.open(TITLE, 240, 120)) {
      Desktop.settle();
      App<Form> app = app();
      tab(window);
      Desktop.text(window, "SELECT");
      Desktop.editing(window, "ni", -1);
      runUntil(
          window,
          app,
          () -> app.tree().dump().contains("composing \"ni\""),
          "the field shows the composition");

      assertEquals("SELECT", app.state().name, "the composition is not in the string");
      assertTrue(
          app.tree()
              .dump()
              .contains("\"SELECT\" 20,20 160x28 focused caret 6 composing" + " \"ni\"\n"),
          app.tree().dump());

      Desktop.text(window, "日本"); // committed
      Desktop.editing(window, "", -1);
      runUntil(
          window,
          app,
          () -> app.state().name.equals("SELECT日本") && !app.tree().dump().contains("composing"),
          "the committed text is in the string");
      assertEquals("SELECT日本", app.state().name);
    }
  }

  @Test
  void theWindowTellsTheInputMethodWhereTheCaretIs() {
    try (Window window = Window.open(TITLE, 240, 120)) {
      Desktop.settle();
      App<Form> app = app();
      app.state().name = "abc";
      tab(window);
      runUntil(window, app, () -> app.inputArea() != null, "the field takes text");

      App.InputArea area = app.inputArea();
      assertEquals(20, area.x());
      assertEquals(20, area.y());
      assertEquals(160, area.width());
      assertEquals(28, area.height());
      assertTrue(area.cursor() > 8, "after the padding and the text: " + area.cursor());
      assertArrayEquals(
          new int[] {
            window.units(20),
            window.units(20),
            window.units(160),
            window.units(28),
            window.units(area.cursor())
          },
          Sdl.getTextInputArea(window.sdlWindow()));
    }
  }

  @Test
  void aMaskedFieldStartsTextInputAgainForAPassword() {
    try (Window window = Window.open(TITLE, 240, 120)) {
      Desktop.settle();
      String[] secret = {""};
      App<Form> app = new App<>(
          new Form(),
          f -> col()
              .wFull()
              .hFull()
              .padding(20)
              .gap(10)
              .children(
                  El.text(f.name).editable(s -> f.name = s).key("name").w(160).h(28),
                  El.text(secret[0])
                      .editable(s -> secret[0] = s)
                      .masked()
                      .key("secret")
                      .w(160)
                      .h(28)));
      app.textRendering(TextRendering.LINUX);
      tab(window);
      runUntil(window, app, () -> app.focused() != null, "Tab focuses the field");
      assertFalse(app.inputArea().password());
      tab(window);
      runUntil(window, app, () -> "secret".equals(app.focused().key()), "Tab focuses the password");
      assertTrue(app.inputArea().password());
      assertTrue(Sdl.textInputActive(window.sdlWindow()), "on again, for a password");

      Desktop.text(window, "hunter2");
      runUntil(window, app, () -> secret[0].equals("hunter2"), "the password is typed");
      assertEquals("hunter2", secret[0]);
      tab(window);
      runUntil(
          window, app, () -> "name".equals(app.focused().key()), "Tab goes around to the field");
      assertTrue(Sdl.textInputActive(window.sdlWindow()), "and again for text");
    }
  }

  @Test
  void textInputIsOnOnlyWhileAFieldHasTheFocus() {
    try (Window window = Window.open(TITLE, 240, 120)) {
      Desktop.settle();
      assertFalse(Sdl.textInputActive(window.sdlWindow()), "off when the window opens");
      App<Form> app = app();
      runUntil(window, app, () -> true, "a frame");
      assertFalse(Sdl.textInputActive(window.sdlWindow()), "off without focus");

      tab(window);
      runUntil(window, app, () -> app.focused() != null, "Tab focuses the field");
      assertEquals("name", app.focused().key());
      assertTrue(Sdl.textInputActive(window.sdlWindow()), "on for the field");

      tab(window);
      runUntil(window, app, () -> "button".equals(app.focused().key()), "Tab focuses the button");
      assertFalse(Sdl.textInputActive(window.sdlWindow()), "off again for the button");
    }
  }

  /**
   * An idle window with a focused field wakes once per turn of the caret, half a second after the
   * frame before, and draws one frame each time, the caret on and off by turns. The window may lose
   * and gain the system's focus meanwhile, when another window opens on the desktop, and the
   * desktop may ask for a redraw: then the test makes it active again and counts from there.
   */
  @Test
  void anIdleWindowWithAFocusedFieldDrawsOnlyWhenTheCaretBlinks() {
    try (Window window = Window.open(TITLE, 240, 120)) {
      Desktop.settle();
      App<Form> app = app();
      List<Boolean> carets = new ArrayList<>(); // per frame with the field focused: caret shown
      List<Long> waits = new ArrayList<>(); // the first wait after each of these frames
      long[] at = new long[2]; // when the first and the last of them were presented
      boolean[] sent = new boolean[2]; // Tab, and a wait after the last frame not yet recorded
      int[] restarts = new int[1];
      Desktop.Redraws redraws = new Desktop.Redraws();
      Runnable restart = () -> {
        restarts[0]++;
        carets.clear();
        waits.clear();
        sent[1] = false;
      };
      AtomicReference<WindowLoop> loop = new AtomicReference<>();
      loop.set(new WindowLoop(window, app, (scene, w, h, s) -> {
            if (redraws.asked()) {
              restart.run();
              return;
            }
            if (app.focused() == null) {
              return;
            }
            if (carets.isEmpty()) {
              at[0] = System.nanoTime();
            }
            carets.add(app.focused().text().caret() != null);
            sent[1] = true;
            if (carets.size() == 3) {
              at[1] = System.nanoTime();
              loop.get().stop();
            }
          })
          .waits((millis, holdBack) -> {
            if (sent[1]) {
              waits.add(millis);
              sent[1] = false;
            }
            if (millis == -1 && !sent[0]) { // the first frame is drawn: Tab to the field
              sent[0] = true;
              tab(window);
            }
          })
          .listener(event -> {
            redraws.event(event);
            if (event instanceof FocusChanged focus) { // not sent by the test: count from here
              restart.run();
              if (!focus.focused()) {
                Desktop.windowEvent(window, Sdl.EVENT_WINDOW_FOCUS_GAINED, 0, 0);
              }
            }
          }));
      Desktop.run(loop.get(), () -> "three frames of the caret, " + carets + " drawn");

      assertEquals("name", app.focused().key());
      for (int i = 1; i < carets.size(); i++) {
        assertTrue(carets.get(i) != carets.get(i - 1), "on and off by turns: " + carets);
      }
      for (long millis : waits.subList(0, 2)) {
        // Half a second less the time it took to build and present the frame.
        assertTrue(millis > 250 && millis <= 501, "it waits for the next turn: " + waits);
      }
      double seconds = (at[1] - at[0]) / 1e9;
      assertTrue(
          seconds >= 0.9,
          "two turns take a second, not " + seconds + " s; " + restarts[0]
              + " restarts after focus changes and redraws");
    }
  }

  /**
   * A window that is not active draws nothing while it is idle, also with a focused field, whose
   * caret it hides: the loop comes to wait for events alone. When the window is active again, its
   * next frame shows the caret. The desktop may make the window active meanwhile, which the test
   * takes back, and ask for redraws, whose frames may show no caret yet.
   */
  @Test
  void anInactiveWindowDrawsNoBlinksAndShowsTheCaretWhenActiveAgain() {
    try (Window window = Window.open(TITLE, 240, 120)) {
      Desktop.settle();
      App<Form> app = app();
      Desktop.Redraws redraws = new Desktop.Redraws();
      int[] step = new int[1]; // 0 before Tab, 1 inactive, 2 active again
      boolean[] active = {true}; // as the loop last heard
      int[] taken = new int[1]; // activations by the desktop that the test took back
      AtomicReference<WindowLoop> loop = new AtomicReference<>();
      loop.set(new WindowLoop(window, app, (scene, w, h, s) -> {
            boolean redraw = redraws.asked();
            if (step[0] == 2 && app.focused().text().caret() != null) {
              loop.get().stop();
            } else if (step[0] == 2 && !redraw) {
              throw new AssertionError("a frame after the activation shows no caret");
            }
          })
          .listener(event -> {
            redraws.event(event);
            if (event instanceof FocusChanged focus) {
              active[0] = focus.focused();
            }
          })
          .waits((millis, holdBack) -> {
            if (step[0] == 1 && active[0]) { // the desktop activated it: inactive again
              taken[0]++;
              Desktop.windowEvent(window, Sdl.EVENT_WINDOW_FOCUS_LOST, 0, 0);
            } else if (millis != -1) {
              return;
            } else if (step[0] == 0) { // the first frame is drawn
              step[0] = 1;
              tab(window);
              Desktop.windowEvent(window, Sdl.EVENT_WINDOW_FOCUS_LOST, 0, 0);
            } else if (step[0] == 1 && app.focused() != null) { // idle, inactive, with the focus
              assertEquals("name", app.focused().key(), "the field keeps the focus");
              assertNull(app.focused().text().caret(), "no caret");
              step[0] = 2;
              Desktop.windowEvent(window, Sdl.EVENT_WINDOW_FOCUS_GAINED, 0, 0);
            }
          }));
      Desktop.run(
          loop.get(),
          () -> (step[0] < 2
                  ? "the inactive window waits for events alone"
                  : "the active window shows the caret")
              + "; " + taken[0]
              + " activations by the desktop taken back");
    }
  }

  @Test
  void aPressReportsItsClicksAndTheModifiersHeld() {
    try (Window window = Window.open(TITLE, 240, 120)) {
      Desktop.settle();
      List<WindowEvent> events = new ArrayList<>();
      window.listener(events::add);
      Desktop.button(window, true, Sdl.BUTTON_LEFT, 40, 30, 2);
      Window.pollEvents();
      PointerDown down = (PointerDown)
          events.stream().filter(PointerDown.class::isInstance).findFirst().orElseThrow();
      assertEquals(2, down.clicks());
      assertEquals(
          Window.modifiers(Sdl.getModState()),
          down.modifiers(),
          "the modifiers SDL knows, from the real keyboard");
    }
  }

  @Test
  void theCursorOfACompositionIsCountedInCharacters() {
    assertEquals(-1, Window.compositionCursor("にほん", -1));
    assertEquals(1, Window.compositionCursor("にほん", 1));
    assertEquals(2, Window.compositionCursor("😀x", 1), "after the surrogate pair");
    assertEquals(3, Window.compositionCursor("😀x", 9), "at most its end");
    assertEquals(new TextEditing("x", -1), new TextEditing("x", -1));
  }

  @Test
  void theSystemClipboardCanBeRead() {
    try (Window window = Window.open(TITLE, 120, 80)) {
      String text = new SystemClipboard().read();
      assertTrue(text != null, "text or empty");
    }
  }

  /**
   * Writes to the system clipboard and puts back what was there. Only with {@code
   * -Drethinkj.ui.test.clipboard=true}: on X11 the text put back is lost when the test ends,
   * unless a clipboard manager keeps it.
   */
  @Test
  void theSystemClipboardTakesWhatIsCopied() {
    assumeTrue(
        Boolean.getBoolean("rethinkj.ui.test.clipboard"),
        "Skipped: writes to the system clipboard; -Drethinkj.ui.test.clipboard=true runs it");
    try (Window window = Window.open(TITLE, 120, 80)) {
      SystemClipboard clipboard = new SystemClipboard();
      String before = clipboard.read();
      try {
        clipboard.write("rethinkj ✓");
        assertEquals("rethinkj ✓", clipboard.read());
      } finally {
        clipboard.write(before);
      }
    }
  }

  /** A button that opens a dialog whose field takes the focus. */
  private static final class Asking {
    boolean open;
    String query = "";
  }

  private static App<Asking> asking() {
    App<Asking> app = new App<>(
        new Asking(),
        a -> col()
            .wFull()
            .hFull()
            .padding(20)
            .child(el().key("open")
                .size(40, 20)
                .focusable()
                .onPress(() -> a.open = true)
                .overlay(
                    a.open
                        ? col()
                            .centerInWindow()
                            .modal()
                            .padding(10)
                            .child(El.text(a.query)
                                .editable(s -> a.query = s)
                                .key("query")
                                .autofocus()
                                .w(120)
                                .h(28)
                                .padding(0, 8))
                        : null)));
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  /** A key and the text SDL makes of it while text input is on, as a keyboard's come. */
  private static void typed(Window window, int keycode, String text) {
    Desktop.key(window, true, keycode, 0, false);
    if (text != null) {
      Desktop.text(window, text);
    }
    Desktop.key(window, false, keycode, 0, false);
  }

  private static void abc(Window window) {
    typed(window, 'a', "a");
    typed(window, 'b', "b");
    typed(window, 'c', "c");
  }

  private static void assumeSdlMakesTheText() {
    assumeTrue(
        !System.getProperty("os.name").startsWith("Windows"),
        "Skipped: on Windows the window makes the text of such keys itself (TextInBatchTest)");
  }

  /**
   * On X11 and Wayland SDL makes text of a key only while text input is on as it handles the key,
   * and handles a whole batch of input before the loop sees the click that takes the focus into a
   * field: the click turns it on as SDL queues it, and the text of the keys after it goes where
   * the keys go.
   */
  @Test
  void aPressTurnsTextInputOnForTheKeysInItsBatch() {
    assumeSdlMakesTheText();
    try (Window window = Window.open(TITLE, 240, 120)) {
      Desktop.settle();
      List<WindowEvent> events = new ArrayList<>();
      window.listener(events::add);
      Desktop.click(window, 100, 34);
      assertTrue(Sdl.textInputActive(window.sdlWindow()), "on as SDL queues the press");
      typed(window, 'a', "a");
      Desktop.text(window, "日本"); // an input method's, of no key
      Window.pollEvents();
      List<WindowEvent> text = events.stream()
          .filter(e -> e instanceof WindowEvent.KeyText || e instanceof WindowEvent.TextInput)
          .toList();
      assertEquals(List.of(new WindowEvent.KeyText("a"), new WindowEvent.TextInput("日本")), text);
      window.stopTextInput();
      assertFalse(Sdl.textInputActive(window.sdlWindow()), "until the loop says");
    }
  }

  @Test
  void keysTypedWithTheClickIntoAFieldReachIt() {
    try (Window window = Window.open(TITLE, 240, 120)) {
      Desktop.settle();
      App<Form> app = app();
      runUntil(window, app, () -> true, "a frame");
      Desktop.click(window, 100, 34);
      abc(window);
      runUntil(window, app, () -> app.state().name.equals("abc"), "the keys reach the field");
      assertTrue(Sdl.textInputActive(window.sdlWindow()), "on for the field");
    }
  }

  @Test
  void keysTypedWithTabIntoAFieldReachIt() {
    try (Window window = Window.open(TITLE, 240, 120)) {
      Desktop.settle();
      App<Form> app = app();
      runUntil(window, app, () -> true, "a frame");
      typed(window, 0x09, null); // SDL makes no text of the key that turns text input on
      abc(window);
      runUntil(window, app, () -> app.state().name.equals("abc"), "the keys reach the field");
    }
  }

  /**
   * A key that presses a button and opens a dialog whose field takes the focus: the keys after
   * it type there, and the text of the key that pressed the button does not, nor that of a key
   * that went nowhere.
   */
  @Test
  void keysTypedWithTheKeyThatOpensADialogReachItsField() {
    assumeSdlMakesTheText();
    try (Window window = Window.open(TITLE, 240, 120)) {
      Desktop.settle();
      App<Asking> app = asking();
      runUntil(window, app, () -> true, "a frame");
      typed(window, 'x', null); // to nothing; turns text input on
      typed(window, 'y', "y"); // to nothing either
      typed(window, 0x09, null); // to the button
      typed(window, ' ', " "); // presses it; the dialog's field takes the focus
      typed(window, 'a', "a");
      typed(window, 'b', "b");
      runUntil(window, app, () -> app.state().query.equals("ab"), "the keys reach the field");
      assertEquals("ab", app.state().query);
      assertTrue(Sdl.textInputActive(window.sdlWindow()), "on for the field");
    }
  }

  @Test
  void keysTypedWithTheClickThatOpensADialogReachItsField() {
    try (Window window = Window.open(TITLE, 240, 120)) {
      Desktop.settle();
      App<Asking> app = asking();
      runUntil(window, app, () -> true, "a frame");
      Desktop.click(window, 40, 30);
      abc(window);
      runUntil(window, app, () -> app.state().query.equals("abc"), "the keys reach the field");
    }
  }

  @Test
  void aKeyThatTakesNoTextLeavesTextInputOffAfterItsFrame() {
    try (Window window = Window.open(TITLE, 240, 120)) {
      Desktop.settle();
      App<Form> app = app();
      runUntil(window, app, () -> true, "a frame");
      typed(window, 'x', null);
      Desktop.click(window, 200, 100); // on nothing
      runUntil(window, app, () -> true, "a frame");
      assertFalse(Sdl.textInputActive(window.sdlWindow()), "off: no field has the focus");
    }
  }
}
