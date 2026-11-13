package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Script;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The workbench's Rename dialog ({@link Bench}): modal over a scrim, the focus in its field, Tab
 * around inside it, Escape and a press on the scrim dismiss it, and the focus goes back to the
 * button that opened it; stored images in both themes at scales 1 and 2.
 */
class DialogTest {

  @TempDir Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  /** Runs these steps of a script in the workbench's window of 360 by 240. */
  private void run(App<?> app, String steps) throws IOException {
    Script.run("size 360 240\n" + steps, app, out);
  }

  @ParameterizedTest(name = "{0} at {1}x")
  @CsvSource({"dark, 1", "dark, 2", "light, 1", "light, 2"})
  void opensWithTheFocusInsideKeepsItThereAndGivesItBack(String theme, int scale)
      throws IOException {
    App<Bench> app = Bench.assertScript("dialog", theme, scale, out);
    String open = Bench.tree(out, "dialog", theme, scale, "open");
    assertTrue(open.contains("\noverlay center modal scrim on "), open);
    assertTrue(open.contains(" role=dialog label=\"Rename table\" "), open);
    assertTrue(open.contains(" \"orders\" ") && open.contains(" focused caret "), open);
    String cycled = Bench.tree(out, "dialog", theme, scale, "cycled");
    assertTrue(cycled.contains("\"orders_2026\""), cycled);
    assertTrue(cycled.contains(" focused caret 11 anchor 0\n"), "Tab came around: " + cycled);
    String closed = Bench.tree(out, "dialog", theme, scale, "closed");
    assertFalse(closed.contains("overlay"), closed);
    assertTrue(closed.contains("label=\"Rename…\" ") && closed.contains(" focused\n"), closed);
    assertEquals("rename", app.focused().key());
    assertEquals("orders", app.state().name, "dismissed: nothing renamed");
  }

  @Test
  void theScrimDismissesAndRenameRenames() throws IOException {
    Bench bench = new Bench(Theme.DARK);
    App<Bench> app = bench.app();
    run(app, "frame\npress #rename\nframe\npress 10,230\nframe\n");
    assertFalse(bench.renaming, "a press on the scrim dismisses it");
    assertEquals("Ready", bench.status, "and presses nothing under it");
    run(app, "press #rename\nframe\nadvance 0.25\nframe\nkey A control\ntype \"order_lines\"\n"
        + "press #ok\nframe\n");
    assertFalse(bench.renaming);
    assertEquals("order_lines", bench.name);
  }

  /** A dialog with a field, a text area, Cancel and OK, OK the default while the name is set. */
  private static final class Ask {
    String name = "orders";
    String note = "";
    final List<String> log = new ArrayList<>();

    El view() {
      Theme t = Theme.DARK;
      El ok = Button.button(t, Button.Variant.PRIMARY, "OK",
          name.isBlank() ? null : () -> log.add("ok")).key("ok");
      return El.col().wFull().hFull().overlay(Dialog.dialog(t, "Ask",
          El.col().children(
              TextField.textField(t, name, "Name", v -> name = v).key("name"),
              TextField.textArea(t, note, "Note", v -> note = v).key("note").h(60)),
          List.of(Button.button(t, Button.Variant.SECONDARY, "Cancel", () -> log.add("cancel"))
              .key("cancel"), ok),
          ok, () -> log.add("dismiss")));
    }
  }

  @Test
  void enterInAFieldPressesTheDefaultButtonAndOnAButtonThatButton() throws IOException {
    Ask ask = new Ask();
    App<Ask> app = new App<>(ask, Ask::view);
    run(app, "frame\nadvance 0.25\nframe\nkey Enter\nframe\n");
    assertEquals("name", app.focused().key(), "the dialog opened with the focus in its field");
    assertEquals(List.of("ok"), ask.log, "Enter in the field pressed OK");

    run(app, "focus #cancel\nkey Enter\nframe\n");
    assertEquals(List.of("ok", "cancel"), ask.log, "Enter on Cancel pressed Cancel");

    run(app, "focus #note\nkey Enter\nframe\n");
    assertEquals("\n", ask.note, "the text area keeps Enter for a line break");
    assertEquals(List.of("ok", "cancel"), ask.log);

    run(app, "focus #name\nframe\n");
    app.keyDown(new Key("Enter", 0, true));
    app.frame();
    assertEquals(List.of("ok", "cancel"), ask.log, "a repeat presses nothing");

    run(app, "key A control\nkey Backspace\nframe\nkey Enter\nframe\n");
    assertEquals("", ask.name);
    assertEquals(List.of("ok", "cancel"), ask.log, "a disabled default is not pressed");
  }

  @Test
  void theDefaultButtonIsOneOfTheButtons() {
    Theme t = Theme.DARK;
    El ok = Button.button(t, Button.Variant.PRIMARY, "OK", () -> {});
    El other = Button.button(t, Button.Variant.PRIMARY, "OK", () -> {});
    assertThrows(IllegalArgumentException.class,
        () -> Dialog.dialog(t, "Ask", El.col(), List.of(ok), other, () -> {}));
  }
}
