package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsTest {

  @Test
  void theAboutPageNamesTheRealPlatformInAWindow() {
    String platform = Settings.platform();
    assertTrue(platform.startsWith("Java " + Runtime.version().feature() + " · "), platform);
    assertTrue(platform.contains(System.getProperty("os.name")), platform);
    assertFalse(platform.contains("amd64"), "the architecture as platforms name it: " + platform);
  }

  /** Runs the example's {@code main} on {@code settings.txt}; see {@link Snaps#check}. */
  @Test
  void theScriptShowsTheStoredFrames() throws IOException {
    Snaps.check("settings", Settings::main);
    // Every setting can be read from the tree.
    assertTrue(Snaps.line("settings", "settings-2x-appearance", "role=option label=\"Appearance\"")
        .contains(" selected"));
    assertTrue(Snaps.line("settings", "settings-editor", "role=switch label=\"Word wrap\"")
        .contains(" checked"));
    assertTrue(Snaps.line("settings", "settings-editor", "role=button label=\"Advanced\"")
        .contains(" expanded"));
    assertTrue(Snaps.line("settings", "settings-editor", "role=spinbutton label=\"Tab size\"")
        .contains("\"5\""));
    assertTrue(Snaps.line("settings", "settings-dialog", "#port").contains(" invalid"));
    assertTrue(Snaps.line("settings", "settings-dialog", "label=\"Add\"").contains(" disabled"));
    assertTrue(Snaps.line("settings", "settings-restart", "role=slider").contains("value=\"16\""));
    assertTrue(Snaps.line("settings", "settings-restart", "role=note")
        .contains("label=\"Restart to apply\""));
    assertTrue(Snaps.line("settings", "settings-light-2x-appearance", "role=radio label=\"Light\"")
        .endsWith(" checked"));
  }

  @Test
  void enterInAFieldOfTheNewConnectionAddsItOnceTheFormIsValid(@TempDir Path out)
      throws IOException {
    Snaps.assumeText();
    App<Settings> app = new App<>(new Settings(), Settings::view);
    Script.run("""
        size 880 600
        frame
        press role=option label="Connections"
        frame
        advance 1
        frame
        press role=button label="New connection…"
        frame
        advance 1
        frame
        type "backup"
        key Enter
        frame
        """, app, out);
    assertTrue(app.state().adding, "the host is missing: Enter adds nothing");
    Script.run("""
        size 880 600
        key Tab
        type "backup.example.com"
        key Enter
        frame
        """, app, out);
    assertFalse(app.state().adding, "the dialog closed");
    assertEquals("backup", app.state().connections.getLast().name);
    assertEquals("backup", app.state().selected);
  }

  @Test
  void aZeroPortCannotBeAddedAndANewDialogForgetsItsInvalidDraft(@TempDir Path out)
      throws IOException {
    Snaps.assumeText();
    App<Settings> app = new App<>(new Settings(), Settings::view);
    Script.run("""
        size 880 600
        frame
        press role=option label="Connections"
        frame
        advance 1
        frame
        press role=button label="New connection…"
        frame
        advance 1
        frame
        type "backup"
        key Tab
        type "backup.example.com"
        key Tab
        key A control
        type "0"
        key Enter
        frame
        """, app, out);
    assertTrue(app.state().adding, "port zero must not enable Add");
    assertEquals("1 to 65535", app.state().portDraft.error());
    Script.run("""
        size 880 600
        press role=button label="Cancel"
        frame
        advance 1
        frame
        press role=button label="New connection…"
        frame
        advance 1
        frame
        """, app, out);
    assertEquals("5432", app.state().portDraft.text());
    assertEquals(null, app.state().portDraft.error(), "a new form starts with a valid default");
  }
}
