package com.rethinkj.ui.window;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import com.rethinkj.ui.window.WindowEvent.PointerDown;
import com.rethinkj.ui.window.WindowEvent.TextEditing;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
    App<Form> app = new App<>(new Form(), f -> col().wFull().hFull().padding(20).gap(10).children(
        El.text(f.name).editable(s -> f.name = s).key("name").w(160).h(28).padding(0, 8),
        el().key("button").size(40, 20).focusable().onPress(() -> {})));
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  /** Runs the loop until {@code done} after a frame was drawn, for at most three seconds. */
  private static void runUntil(Window window, App<?> app, BooleanSupplier done) {
    AtomicReference<WindowLoop> loop = new AtomicReference<>();
    loop.set(new WindowLoop(window, app, (scene, w, h, s) -> {
      if (done.getAsBoolean()) {
        loop.get().stop();
      }
    }));
    Thread timeout = Thread.ofPlatform().start(() -> {
      try {
        Thread.sleep(3000);
        loop.get().stop();
      } catch (InterruptedException e) {
        // the loop ended in time
      }
    });
    loop.get().run();
    timeout.interrupt();
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
      runUntil(window, app, () -> app.tree().dump().contains("composing \"ni\""));

      assertEquals("SELECT", app.state().name, "the composition is not in the string");
      assertTrue(app.tree().dump().contains("\"SELECT\" 20,20 160x28 focused caret 6 composing"
          + " \"ni\"\n"), app.tree().dump());

      Desktop.text(window, "日本"); // committed
      Desktop.editing(window, "", -1);
      runUntil(window, app, () -> app.state().name.equals("SELECT日本")
          && !app.tree().dump().contains("composing"));
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
      runUntil(window, app, () -> app.inputArea() != null);

      App.InputArea area = app.inputArea();
      assertEquals(20, area.x());
      assertEquals(20, area.y());
      assertEquals(160, area.width());
      assertEquals(28, area.height());
      assertTrue(area.cursor() > 8, "after the padding and the text: " + area.cursor());
      assertArrayEquals(
          new int[] {window.units(20), window.units(20), window.units(160), window.units(28),
              window.units(area.cursor())},
          Sdl.getTextInputArea(window.sdlWindow()));
    }
  }

  @Test
  void textInputIsOnOnlyWhileAFieldHasTheFocus() {
    try (Window window = Window.open(TITLE, 240, 120)) {
      Desktop.settle();
      assertFalse(Sdl.textInputActive(window.sdlWindow()), "off when the window opens");
      App<Form> app = app();
      runUntil(window, app, () -> true);
      assertFalse(Sdl.textInputActive(window.sdlWindow()), "off without focus");

      tab(window);
      runUntil(window, app, () -> app.focused() != null);
      assertEquals("name", app.focused().key());
      assertTrue(Sdl.textInputActive(window.sdlWindow()), "on for the field");

      tab(window);
      runUntil(window, app, () -> "button".equals(app.focused().key()));
      assertFalse(Sdl.textInputActive(window.sdlWindow()), "off again for the button");
    }
  }

  @Test
  void anIdleWindowWithAFocusedFieldDrawsOnlyWhenTheCaretBlinks() throws InterruptedException {
    try (Window window = Window.open(TITLE, 240, 120)) {
      Desktop.settle();
      App<Form> app = app();
      AtomicInteger presents = new AtomicInteger();
      WindowLoop loop = new WindowLoop(window, app, (scene, w, h, s) -> presents.incrementAndGet());
      int[] seen = new int[2];
      Thread stopper = Thread.ofPlatform().start(() -> {
        try {
          Thread.sleep(300);
          seen[0] = presents.get();
          Thread.sleep(1000);
          seen[1] = presents.get();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        loop.stop();
      });
      tab(window);
      loop.run();
      stopper.join();

      assertEquals("name", app.focused().key());
      int blinks = seen[1] - seen[0];
      assertTrue(blinks >= 1 && blinks <= 3, "a frame per blink, two a second: " + blinks);
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
      PointerDown down = (PointerDown) events.stream()
          .filter(PointerDown.class::isInstance).findFirst().orElseThrow();
      assertEquals(2, down.clicks());
      assertEquals(Window.modifiers(Sdl.getModState()), down.modifiers(),
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
    assumeTrue(Boolean.getBoolean("rethinkj.ui.test.clipboard"),
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
}
