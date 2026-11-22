package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The searches of the find bar ({@link Find}) on another thread, in a text area. */
class FindThreadsTest {

  @TempDir
  Path out;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  static final class Doc {
    String text;
    final Find.State find = new Find.State();
    App<Doc> app;

    Doc(String text) {
      this.text = text;
    }
  }

  private static App<Doc> app(Doc doc) {
    App<Doc> app = new App<>(
        doc,
        s -> col()
            .wFull()
            .hFull()
            .padding(12)
            .child(Find.find(
                s.app,
                TextField.textArea(s.text, "Notes", v -> s.text = v)
                    .wrapText(false)
                    .key("notes")
                    .lines(6)
                    .wFull(),
                s.find)));
    doc.app = app;
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  /** Runs the steps and returns the text area's line of the tree after them. */
  private String run(App<Doc> app, String script) throws IOException {
    Script.run("size 640 240\n" + script + "\nframe\nadvance 0.25\nframe\nsnap s", app, out);
    return Files.readString(out.resolve("s.tree.txt"))
        .lines()
        .filter(l -> l.contains("#notes "))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void aJumpOnAnotherThreadSelectsNothingOnceTheTextChanged() throws Exception {
    Doc doc = new Doc(("x".repeat(60) + "\n").repeat(500_000) + "needle");
    App<Doc> app = app(doc);
    run(
        app,
        "frame\nfocus #notes\nkey F control\nframe\nadvance 0.25\nframe\n"
            + "focus #find-field\ntype \"needle\"");
    assertNotNull(doc.find.jump, "the jump takes longer than a frame");
    String before = run(app, "focus #notes\ntype \"NEW\"");
    app.await(Duration.ofSeconds(10));
    app.frame();
    assertEquals(before, run(app, ""), "the jump's offsets were those of the text before");
  }

  @Test
  void aCountOnAnotherThreadForAnOlderTextIsReplaced() throws Exception {
    Doc doc = new Doc("x \n".repeat(100_000));
    App<Doc> app = app(doc);
    run(
        app,
        "frame\nfocus #notes\nkey F control\nframe\nadvance 0.25\nframe\n"
            + "focus #find-field\ntype \"needle\"");
    assertNotNull(doc.find.work, "counting on another thread");
    doc.text = "needle\n" + doc.text;
    app.invalidate();
    app.frame();
    app.await(Duration.ofSeconds(10));
    app.frame();
    assertEquals(doc.text, doc.find.searched);
    assertEquals(1, doc.find.matches.count());
    assertTrue(doc.find.matches.complete());
  }
}
