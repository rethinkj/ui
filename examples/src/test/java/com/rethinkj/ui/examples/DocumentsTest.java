package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Dialogs;
import com.rethinkj.ui.FileDialog;
import com.rethinkj.ui.MessageBox;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentsTest {

  /**
   * Runs the example's {@code main} on {@code documents.txt}; see {@link Snaps#check}. A window
   * torn off is snapped under its title, as {@code query-documents-torn.png}.
   */
  @Test
  void theScriptShowsTheStoredFramesOfEachWindow() throws IOException {
    Files.deleteIfExists(Path.of("target/orders copy.sql"));
    Snaps.check("documents", Documents::main);
    assertTrue(Snaps.line("documents", "documents-moving", "#tab-0").contains("grabbed"),
        "the tab being moved");
    assertTrue(Snaps.line("documents", "documents-moving", "#marker").startsWith("el #marker"));
    assertTrue(Snaps.line("documents", "documents-docked", "#tab-1").contains("\"Query\""),
        "docked between Notes and Todo");
    assertTrue(Files.exists(Path.of("target/documents/query-documents-torn.png")));
    assertFalse(Files.exists(Path.of("target/documents/query-documents-docked.png")),
        "its window closed");
    assertTrue(Snaps.line("documents", "ideas-documents-light-docked", "#tab-1")
        .contains("\"Notes\""), "in the window of Ideas, after it");

    assertTrue(Snaps.line("documents", "documents-opened", "#tab-5").contains("\"orders.sql\""),
        "a file the dialog chose, read into a new tab");
    assertTrue(Snaps.line("documents", "documents-opened", "#toast")
        .contains("label=\"Cannot open missing.md: it is not there\""));
    assertEquals(Files.readString(Path.of("src/test/resources/documents/orders.sql")),
        Files.readString(Path.of("target/orders copy.sql")), "Save As wrote the active document");
    assertTrue(Snaps.line("documents", "documents-saved", "#tab-5")
        .contains("\"orders copy.sql\""), "named after its file");
    assertTrue(Snaps.line("documents", "documents-drop-over", "#drop-hint")
        .contains("role=status"), "a drag from a file manager over the window");
    assertTrue(Snaps.line("documents", "documents-dropped", "#tab-6")
        .contains("\"notes ü.md\""), "the dropped file in a new tab");
  }

  /** A file too large, one the dialog could not show, and text dropped beside the editor. */
  @Test
  void whatCannotBeOpenedIsSaidInAToast(@TempDir Path folder) throws IOException {
    Snaps.assumeText();
    Path big = folder.resolve("big.txt");
    Files.write(big, new byte[(int) Documents.LIMIT + 1]);
    Documents s = new Documents();
    s.app = new App<>(s, d -> d.view(d.groups.main(), d.app));
    Script.run("""
        size 640 360
        frame
        drop #tabs "%s"
        frame
        await
        frame
        snap big
        dialog error "No portal"
        key O control
        frame
        await
        frame
        snap failed
        drop 300,16 text "dropped text"
        frame
        snap text
        """.formatted(big.toString().replace("\\", "\\\\")), s.app, folder.resolve("out"));
    assertEquals("Cannot open big.txt: it is larger than 1024 KB", lineOf(folder, "big"));
    assertEquals("No file dialog: No portal", lineOf(folder, "failed"));
    assertEquals("Dropped text", s.groups.main().activeTab().title);
    assertEquals("dropped text", s.groups.main().activeTab().text);
  }

  /** Save As starts with the filter of the document's kind, which the dialog shows first. */
  @Test
  void saveAsOffersTheDocumentsOwnKindFirst() {
    Documents s = new Documents();
    s.app = new App<>(s, d -> d.view(d.groups.main(), d.app));
    List<FileDialog> asked = new ArrayList<>();
    s.app.dialogs(new Dialogs() {
      @Override
      public void file(FileDialog dialog, Consumer<FileDialog.Choice> chosen,
          Consumer<String> failed) {
        asked.add(dialog);
      }

      @Override
      public void message(MessageBox box, IntConsumer pressed, Consumer<String> failed) {}
    });
    Documents.Doc query = new Documents.Doc("Query", "SELECT 1;");
    Documents.Doc notes = new Documents.Doc("Notes", "Coffee");
    s.saveAs(query);
    s.saveAs(notes);
    assertEquals("Query.sql", asked.get(0).fileName());
    assertEquals("SQL", asked.get(0).filters().getFirst().name());
    assertEquals("Notes.txt", asked.get(1).fileName());
    assertEquals("Text", asked.get(1).filters().getFirst().name());
  }

  private static String lineOf(Path folder, String snap) throws IOException {
    String line = Files.readAllLines(folder.resolve("out").resolve(snap + ".tree.txt")).stream()
        .filter(l -> l.contains("#toast")).findFirst().orElseThrow();
    return line.substring(line.indexOf("label=\"") + 7, line.indexOf("\" "));
  }

  /** A document's text has a find bar, in the first window and in one torn off. */
  @Test
  void controlFFindsInADocument() throws IOException {
    Snaps.assumeText();
    Documents s = new Documents();
    s.app = new App<>(s, d -> d.view(d.groups.main(), d.app));
    s.app.textRendering(TextRendering.LINUX);
    Path out = Path.of("target", "documents-find");
    Script.run("""
        size 640 360
        frame
        press #text
        key F control
        type "the"
        frame
        advance 0.25
        frame
        snap find""", s.app, out);
    String tree = Files.readString(out.resolve("find.tree.txt"));
    assertTrue(tree.contains("role=search"), tree);
    assertTrue(tree.contains("text #count \"1 of 1\""), tree);
  }
}
