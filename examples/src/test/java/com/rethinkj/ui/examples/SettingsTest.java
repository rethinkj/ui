package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class SettingsTest {

  /** Runs the example's {@code main} on {@code settings.txt}; see {@link Snaps#check}. */
  @Test
  void theScriptShowsTheStoredFrames() throws IOException {
    Snaps.check("settings", Settings::main);
    // Every setting can be read from the tree.
    assertTrue(Snaps.line("settings", "settings-appearance", "role=option label=\"Appearance\"")
        .contains(" selected"));
    assertTrue(Snaps.line("settings", "settings-editor", "role=switch label=\"Word wrap\"")
        .contains(" checked"));
    assertTrue(Snaps.line("settings", "settings-editor", "role=button label=\"Advanced\"")
        .contains(" expanded"));
    assertTrue(Snaps.line("settings", "settings-editor", "role=spinbutton label=\"Tab size\"")
        .contains("\"5\""));
    assertTrue(Snaps.line("settings", "settings-connections", "role=button label=\"staging\"")
        .contains(" selected"));
    assertTrue(Snaps.line("settings", "settings-empty", "role=status")
        .contains("label=\"No connections match\""));
    assertTrue(Snaps.line("settings", "settings-dialog", "#port").contains(" invalid"));
    assertTrue(Snaps.line("settings", "settings-dialog", "label=\"Add\"").contains(" disabled"));
    assertTrue(Snaps.line("settings", "settings-added", "role=button label=\"backup\"")
        .contains(" selected"));
    assertTrue(Snaps.line("settings", "settings-restart", "role=slider")
        .contains("value=\"16\""));
    assertTrue(Snaps.line("settings", "settings-restart", "role=note")
        .contains("label=\"Restart to apply\""));
    assertTrue(Snaps.line("settings", "settings-light-appearance", "role=radio label=\"Light\"")
        .endsWith(" checked"));
  }
}
