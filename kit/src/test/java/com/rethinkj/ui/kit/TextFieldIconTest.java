package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.TextField.textField;
import static com.rethinkj.ui.kit.TextField.withIcon;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link TextField#withIcon}: pressing the leading icon reaches the field beneath it. */
class TextFieldIconTest {

  @TempDir
  Path out;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  static final class Filter {
    String query;

    Filter(String query) {
      this.query = query;
    }
  }

  private static App<Filter> app(String query) {
    App<Filter> app = new App<>(
        new Filter(query),
        s -> col()
            .padding(16)
            .child(
                withIcon(textField(s.query, "Filter", v -> s.query = v).key("q"), Symbol.SEARCH)));
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  private void run(App<?> app, String steps) throws IOException {
    Script.run("size 300 80\nframe\n" + steps + "\nframe", app, out);
  }

  private static String line(App<?> app, String part) {
    return app.tree()
        .dump()
        .lines()
        .filter(l -> l.contains(part))
        .findFirst()
        .orElseThrow()
        .strip();
  }

  @Test
  void pressingTheIconFocusesTheFieldAtTheStartOfItsText() throws IOException {
    App<Filter> app = app("orders");

    // The field is at 16,16 200x24 and the 16x16 icon sits centered in its 24x24 left square
    // (20,20 16x16), so 28,28 is the center of the icon.
    run(app, "move 28,28");
    assertTrue(
        line(app, "label=\"search\"").contains("20,20 16x16"), line(app, "label=\"search\""));
    assertTrue(line(app, "#q").endsWith("\"orders\" 16,16 200x24 hovered"), line(app, "#q"));
    assertEquals(Cursor.TEXT, app.cursor(), "hovering the icon shows the field's text cursor");

    run(app, "press 28,28");
    assertEquals("q", app.focused().key(), "pressing the icon focuses the field");
    assertTrue(
        line(app, "#q").endsWith("\"orders\" 16,16 200x24 hovered focused caret 0"),
        line(app, "#q"));
  }

  @Test
  void aPressOnTheIconOfAScrolledFieldLandsAtTheStartOfTheTextInView() throws IOException {
    // 41 is the left edge of the field's content box, after its border and the icon's square.
    assertEquals(caretAfterPressAt(41), caretAfterPressAt(28));
  }

  /** The caret of a field scrolled to the end of a long query after a press at this x. */
  private String caretAfterPressAt(int x) throws IOException {
    App<Filter> app = app("orders placed by customers in the last thirty days");
    run(app, "press #q\nkey End\nframe\npress " + x + ",28");
    String line = line(app, "#q");
    return line.substring(line.lastIndexOf(" caret ") + " caret ".length());
  }
}
