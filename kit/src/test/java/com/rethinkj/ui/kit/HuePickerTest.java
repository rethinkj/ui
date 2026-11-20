package com.rethinkj.ui.kit;

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
}
