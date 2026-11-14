package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.TextField.codeArea;
import static com.rethinkj.ui.kit.TextField.textArea;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The kit's text areas: a query in a {@link TextField#codeArea} and notes in a {@link
 * TextField#textArea}, driven by {@code area.txt}, with stored images:
 * the caret on a middle line, a selection over three lines with its line feeds, both bars after
 * scrolling down and sideways, lines indented with Tab, and wrapped notes.
 */
class TextAreaTest {

  static final String QUERY = """
      SELECT o.id,
      \to.total,
      \tc.name
      FROM orders o
      JOIN customers c ON c.id = o.customer_id AND c.region = 'north west' AND c.active
      WHERE o.total > 100
        AND o.created_at >= DATE '2024-01-01'
      ORDER BY o.total DESC
      LIMIT 50;""";

  @TempDir
  Path out;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  static final class Notes {
    String query = QUERY;
    String notes = "Ship by Friday, from the north warehouse.";
  }

  static App<Notes> app(Theme t) {
    App<Notes> app = new App<>(
        new Notes(),
        s -> col()
            .wFull()
            .hFull()
            .padding(12)
            .gap(8)
            .fill(t.surface().window())
            .children(
                codeArea(t, s.query, "SQL", v -> s.query = v)
                    .key("query")
                    .lines(6)
                    .wFull(),
                textArea(t, s.notes, "Notes", v -> s.notes = v)
                    .key("notes")
                    .lines(2)
                    .wFull()));
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  @ParameterizedTest
  @ValueSource(strings = {"dark"})
  void looksAsStored(String theme) throws IOException {
    App<Notes> app = app(theme.equals("dark") ? Theme.DARK : Theme.LIGHT);
    String script = Files.readString(Path.of("src/test/resources/scripts/area.txt"));
    List<Path> files = Script.run("size 360 220 scale 2\n" + script, app, out);
    Golden.assertSnaps("area-" + theme + "-", files);
    assertTrue(
        app.state().query.startsWith("    SELECT o.id,\n    \to.total,\n\tc.name"),
        app.state().query);
    assertTrue(app.state().notes.endsWith("the gate closes at five."), app.state().notes);
    String caret = Files.readString(out.resolve("caret.tree.txt"));
    assertTrue(caret.contains(" focused caret 30 (3:8)"), caret);
    String selection = Files.readString(out.resolve("selection.tree.txt"));
    assertTrue(selection.contains(" focused caret 43 (4:13) anchor 20"), selection);
    String scrolled = Files.readString(out.resolve("scrolled.tree.txt"));
    assertTrue(scrolled.contains(" thumb-x ") && scrolled.contains(" thumb-y "), scrolled);
    assertEquals(2, Files.readString(out.resolve("wrapped.tree.txt")).split("lines ").length - 1);
  }
}
