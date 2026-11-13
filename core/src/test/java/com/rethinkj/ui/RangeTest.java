package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Elements over a range of a field's text ({@link El#range}), a field as high as its rows ({@link
 * El#fitLines}) and Tab passing a field by ({@link El#skipTab}): a read-only paragraph in
 * JetBrains Mono at 14, whose characters advance 8, 80 wide, so ten characters a row.
 */
class RangeTest {

  private static final String TEXT = "see the link here and more";
  private final List<String> pressed = new ArrayList<>();

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  private App<Object> app() {
    App<Object> app = new App<>(
        new Object(),
        s -> col()
            .padding(10)
            .children(
                el().key("before").focusable().size(10, 10),
                El.text(TEXT)
                    .editable(e -> {})
                    .readOnly()
                    .multiline()
                    .wrapText()
                    .fitLines()
                    .skipTab()
                    .key("p")
                    .font(Font.mono())
                    .fontSize(14)
                    .w(80)
                    .child(el().key("link")
                        .role("link")
                        .label("link here")
                        .range(8, 17)
                        .focusable()
                        .cursor(Cursor.POINTER)
                        .onPress(() -> pressed.add("link"))
                        .whenHovered(l -> l.fill(0x330000FF))),
                el().key("after").focusable().size(10, 10)));
    app.textRendering(TextRendering.LINUX);
    app.resize(200, 200, 1);
    app.frame();
    return app;
  }

  private static El find(El el, String key) {
    if (key.equals(el.key())) {
      return el;
    }
    for (El child : el.children()) {
      El found = find(child, key);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  @Test
  void theFieldIsAsHighAsItsRowsAndTheRangeLiesOverItsRows() {
    App<Object> app = app();
    El p = find(app.tree(), "p");
    El link = find(app.tree(), "link");
    // "see the " / "link here " / "and more": three rows of 18 pixels.
    assertEquals(3 * 18, p.height(), app.tree().dump());
    // "link here" wraps: "see the " ends the first row, so it lies on the second, from 0 to 72.
    assertEquals(
        List.of(new Text.Box(0, 0, 72, 18)), link.rangeBoxes(), app.tree().dump());
    assertEquals(0, link.x());
    assertEquals(18, link.y());
  }

  @Test
  void aRangeOverTwoRowsIsHitOnlyOnItsRows() {
    App<Object> app = new App<>(
        new Object(),
        s -> col()
            .padding(10)
            .children(El.text("abcdefgh ijklmnop")
                .editable(e -> {})
                .readOnly()
                .multiline()
                .wrapText()
                .fitLines()
                .key("p")
                .font(Font.mono())
                .fontSize(14)
                .w(80)
                .child(el().key("link").range(4, 13).onPress(() -> pressed.add("link")))));
    app.textRendering(TextRendering.LINUX);
    app.resize(200, 200, 1);
    app.frame();
    El link = find(app.tree(), "link");
    // "efgh " on the first row from 32 to its end at 72, "ijkl" on the second from 0.
    assertEquals(
        List.of(new Text.Box(32, 0, 40, 18), new Text.Box(0, 18, 32, 18)),
        link.rangeBoxes(),
        app.tree().dump());
    app.pointerDown(10 + 4, 10 + 9); // "a": the field's
    app.pointerUp(10 + 4, 10 + 9);
    app.pointerDown(10 + 60, 10 + 18 + 9); // after "ijkl", inside the bounding box
    app.pointerUp(10 + 60, 10 + 18 + 9);
    app.frame();
    assertEquals(List.of(), pressed);
    app.pointerDown(10 + 40, 10 + 9); // "f"
    app.pointerUp(10 + 40, 10 + 9);
    app.frame();
    assertEquals(List.of("link"), pressed);
  }

  @Test
  void tabReachesTheRangeAndPassesTheFieldByAndEnterPressesIt() {
    App<Object> app = app();
    app.keyDown(new Key("Tab"));
    app.frame();
    assertEquals("before", app.focused().key());
    app.keyDown(new Key("Tab"));
    app.frame();
    assertEquals("link", app.focused().key(), "the field is passed by");
    app.keyDown(new Key("Enter"));
    app.frame();
    assertEquals(List.of("link"), pressed);
    app.keyDown(new Key("Tab"));
    app.frame();
    assertEquals("after", app.focused().key());
    // A press still focuses the field, for selecting.
    app.pointerDown(10 + 4, 20 + 9);
    app.pointerUp(10 + 4, 20 + 9);
    app.frame();
    assertEquals("p", app.focused().key());
  }

  @Test
  void theRangeIsHoveredWithThePointerCursorAndItsLineIsInTheDump() {
    App<Object> app = app();
    app.pointerMove(10 + 20, 20 + 18 + 9);
    app.frame();
    assertEquals(Cursor.POINTER, app.cursor());
    String dump = app.tree().dump();
    assertTrue(dump.contains("el #link role=link label=\"link here\" 10,38 72x18 hovered"), dump);
  }
}
