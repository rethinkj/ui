package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DocumentsTest {

  /**
   * Runs the example's {@code main} on {@code documents.txt}; see {@link Snaps#check}. A window
   * torn off is snapped under its title, as {@code query-documents-torn.png}.
   */
  @Test
  void theScriptShowsTheStoredFramesOfEachWindow() throws IOException {
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
