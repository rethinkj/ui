package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.text.TextLibraries;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What another application drops on the window ({@link El#onFileDrop}): through {@link App}'s
 * input, as a window hands it on, and through a script's {@code drop} step.
 */
class FileDropTest {

  /** A window whose top half takes drops, with a box inside it that takes its own. */
  static final class Shelf {
    final List<String> log = new ArrayList<>();
    String field = "SELECT ";
    boolean withField;
  }

  private static App<Shelf> shelf() {
    App<Shelf> app = new App<>(new Shelf(), s -> {
      El top = el().key("top").size(200, 50).onFileDrop(d -> s.log.add("top " + describe(d)));
      top.child(el().key("inner").size(40, 40).margin(5, 0, 0, 5)
          .onFileDrop(d -> s.log.add("inner " + describe(d))));
      El root = col().wFull().hFull().child(top);
      if (s.withField) {
        root.child(El.text(s.field).key("field").editable(v -> s.field = v).size(200, 30));
      }
      return root;
    });
    app.resize(200, 100, 1);
    app.frame();
    return app;
  }

  private static String describe(Drop d) {
    return d.phase() + (d.payload() == null ? "" : " " + d.payload())
        + (Double.isNaN(d.x()) ? "" : " " + (int) d.x() + "," + (int) d.y());
  }

  @Test
  void theInnermostHandlerUnderTheDragIsOverLeftAndGetsTheFiles() {
    App<Shelf> app = shelf();
    app.fileDragOver(100, 20); // over the top half
    app.fileDragOver(20, 20); // into the inner box
    app.fileDragOver(20, 80); // below both
    app.fileDragOver(150, 30);
    app.fileDrop(150, 30, List.of(Path.of("ä b.sql"), Path.of("c.csv")), null);
    app.frame();
    assertEquals(List.of("top OVER 100,20", "top LEAVE", "inner OVER 15,15", "inner LEAVE",
        "top OVER 150,30", "top DROP [ä b.sql, c.csv] 150,30"), app.state().log);
  }

  @Test
  void aDragThatLeavesTellsTheHandlerItWasOverAndTextGoesToAHandler() {
    App<Shelf> app = shelf();
    app.fileDragOver(20, 20);
    app.fileDragLeave();
    app.fileDrop(100, 20, List.of(), "a line\nanother");
    app.frame();
    assertEquals(List.of("inner OVER 15,15", "inner LEAVE", "top DROP a line\nanother 100,20"),
        app.state().log);
  }

  @Test
  void textDroppedOnAFieldGoesInAtThePointAsAPaste() {
    TextLibraries.assumeLoaded();
    App<Shelf> app = shelf();
    app.state().withField = true;
    app.invalidate();
    app.frame();
    app.fileDragOver(100, 20);
    app.fileDragOver(190, 65); // over the field, beyond its text
    app.fileDrop(190, 65, List.of(), "name\nFROM t");
    app.frame();
    assertEquals("SELECT name FROM t", app.state().field, "at the end, its line break a space");
    assertEquals(List.of("top OVER 100,20", "top LEAVE"), app.state().log);
    assertEquals("field", app.focused().key(), "the field has the focus");
  }

  @Test
  void aScriptDropsFilesAndTextAndDragsOverAndAway(@TempDir Path out) throws IOException {
    App<Shelf> app = shelf();
    Script.run("""
        size 200 100
        frame
        drop over #inner
        frame
        drop leave
        drop #top "one.sql" "Ördner mit Leerzeichen/two.md"
        frame
        drop 100,25 text "dropped"
        frame
        """, app, out);
    assertEquals(List.of("inner OVER 20,20", "inner LEAVE", "top OVER 100,25",
        "top DROP [one.sql, " + Path.of("Ördner mit Leerzeichen", "two.md") + "] 100,25",
        "top OVER 100,25",
        "top DROP dropped 100,25"), app.state().log);
    Script.Failure failure = assertThrows(Script.Failure.class,
        () -> Script.run("drop #top", app, out));
    assertTrue(failure.getMessage().contains("Write `drop <target> \"<path>\"`"),
        failure.getMessage());
  }
}
