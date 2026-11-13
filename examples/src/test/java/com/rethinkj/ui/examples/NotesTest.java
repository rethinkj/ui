package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NotesTest {

  /**
   * Runs the example's {@code main} on {@code notes.txt}; see {@link Snaps#check}. The note's
   * window is snapped under its title, as {@code groceries-notes-torn.png}.
   */
  @Test
  void theScriptShowsTheStoredFramesOfEachWindow() throws IOException {
    Snaps.check("notes", Notes::main);
    assertTrue(Files.exists(Path.of("target/notes/groceries-notes-torn.png")));
    assertTrue(Snaps.line("notes", "notes-edited", "#text-Groceries")
        .contains("Coffee, bread, milk"), "the row shows the edit of the note's window");
    assertTrue(Snaps.line("notes", "notes-reorder-marker", "#marker").startsWith("el #marker"));
  }
}
