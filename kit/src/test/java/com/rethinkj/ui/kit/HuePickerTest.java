package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HuePickerTest {

  @TempDir
  Path out;

  @Test
  void aPressOnTheOpenButtonInADialogClosesItsPopover() throws IOException {
    TextLibraries.assumeLoaded();
    Popover.State state = new Popover.State();
    App<Popover.State> app = new App<>(
        state,
        s -> El.col()
            .wFull()
            .hFull()
            .overlay(Dialog.dialog(
                "Connection",
                Form.form(
                    Form.Labels.ABOVE,
                    Form.field("Color", HuePicker.button(Theme.Hue.TEAL, "None", s, hue -> {}))),
                List.of(),
                () -> {})));
    String open = "size 480 320\nframe\npress role=button label=\"Color\"\nframe\n";
    Script.run(open, app, out);
    assertTrue(state.isOpen());
    Script.run("press role=button label=\"Color\"\nframe\n", app, out);
    assertFalse(state.isOpen());
  }

  @Test
  void thePopoverAtTheEndOfARowStaysInsideTheDialog() throws IOException {
    TextLibraries.assumeLoaded();
    Popover.State state = new Popover.State();
    App<Popover.State> app = new App<>(
        state,
        s -> El.col()
            .wFull()
            .hFull()
            .overlay(Dialog.dialog(
                "Connection",
                Form.form(
                        Form.Labels.ABOVE,
                        Form.row(
                            Form.field("Name", TextField.textField("", null, name -> {}))
                                .grow(),
                            Form.field("Color", HuePicker.button(null, "None", s, hue -> {}))))
                    .w(368),
                List.of(),
                () -> {})));
    Script.run("size 800 400\nframe\npress role=button label=\"Color\"\nframe\n", app, out);
    El dialog = app.tree().shownOverlays().getFirst();
    El popover = app.tree().shownOverlays().getLast();
    assertEquals(2, app.tree().shownOverlays().size(), "the popover is open");
    // The row of swatches is a little wider than this dialog: it sticks out no more than that.
    float wider = Math.max(popover.width() - dialog.width(), 0);
    float left = Math.max(dialog.x() - popover.x(), 0);
    float right = Math.max(popover.x() + popover.width() - dialog.x() - dialog.width(), 0);
    assertTrue(left + right <= wider + 1, "it sticks out " + left + " and " + right);
  }

  @Test
  void tabOutOfThePopoverClosesItSoEscapeAndEnterDoNotReachTheDialog() throws IOException {
    TextLibraries.assumeLoaded();
    final class Editor {
      final Popover.State popover = new Popover.State();
      int dismissed;

      El view() {
        El save = Button.button(Button.Variant.PRIMARY, "Save", () -> {});
        El panel = Form.form(
            Form.Labels.BESIDE,
            Form.row(
                Form.field("Name", TextField.textField("", null, name -> {})).grow(),
                Form.field("Color", HuePicker.button(null, "None", popover, hue -> {}))),
            Form.field("Host", TextField.textField("", null, host -> {})));
        return El.col()
            .wFull()
            .hFull()
            .overlay(Dialog.dialog("Editor", panel.w(368), List.of(save), save, () -> dismissed++));
      }
    }
    App<Editor> app = new App<>(new Editor(), Editor::view);
    String open =
        "size 640 400\nframe\npress role=button label=\"Color\"\nframe\nadvance 1\nframe\n";
    Script.run(open, app, out);
    assertTrue(app.state().popover.isOpen());
    Script.run("key Tab\nframe\n", app, out);
    assertFalse(app.state().popover.isOpen(), "Tab out of it closes it");
    Script.run("key Escape\nframe\nadvance 1\nframe\n", app, out);
    assertEquals(
        1, app.state().dismissed, "Escape goes to the dialog only once the popover is closed");
    Script.run(open, app, out);
    assertTrue(app.state().popover.isOpen());
    Script.run("key Tab shift\nframe\n", app, out);
    assertFalse(app.state().popover.isOpen(), "Shift+Tab out of it closes it");
  }

  @Test
  void buttonWritesThroughItsProp() throws IOException {
    TextLibraries.assumeLoaded();
    final class State {
      Theme.Hue hue = Theme.Hue.BLUE;
      final Popover.State popover = new Popover.State();
    }
    State state = new State();
    App<State> app = new App<>(
        state,
        s -> El.col()
            .wFull()
            .hFull()
            .child(Form.form(
                Form.Labels.ABOVE,
                Form.field(
                    "Color",
                    HuePicker.button(Prop.of(() -> s.hue, h -> s.hue = h), "None", s.popover)))));
    Script.run("""
        size 480 240
        frame
        press role=button label="Color"
        frame
        advance 1
        frame
        press role=radio label="Teal"
        frame
        """, app, out);
    assertEquals(Theme.Hue.TEAL, state.hue);
  }
}
