package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import org.junit.jupiter.api.Test;

/**
 * A single-line field with {@link El#ellipsis}: shortened without the focus, whole with it; one
 * that wraps grows with its lines up to {@link El#maxLines}.
 */
class FieldEllipsisTest {

  @Test
  void aFieldEndsInAnEllipsisOnlyWithoutTheFocus() {
    TextLibraries.assumeLoaded();
    App<Object> app = new App<>(
        new Object(),
        s -> col()
            .children(
                El.text("shop.public.orders_by_customer_and_month")
                    .editable(v -> {})
                    .readOnly()
                    .ellipsis()
                    .key("value")
                    .w(80)
                    .h(20),
                El.el().key("other").focusable().size(10, 10)));
    app.textRendering(TextRendering.LINUX);
    app.resize(200, 100, 1);
    app.frame();
    El field = app.tree().children().getFirst();
    assertTrue(field.text().shortened(), "the start and an ellipsis");
    app.focus(field);
    app.frame();
    field = app.tree().children().getFirst();
    assertFalse(field.text().shortened(), "with the focus the whole string, to select and scroll");
    app.focus(app.tree().children().get(1));
    app.frame();
    assertTrue(app.tree().children().getFirst().text().shortened(), "and shortened again");
  }

  @Test
  void aWrappedFieldGrowsUpToItsMostLinesAndSelectsAcrossThem() {
    TextLibraries.assumeLoaded();
    String[] value = {"one two"};
    App<Object> app = new App<>(
        new Object(),
        s -> col()
            .align(Style.Align.START)
            .child(El.text(value[0])
                .editable(v -> {})
                .readOnly()
                .wrapText()
                .maxLines(3)
                .ellipsis()
                .w(80)));
    app.textRendering(TextRendering.LINUX);
    app.resize(200, 200, 1);
    app.frame();
    El field = app.tree().children().getFirst();
    float line = field.text().lineHeight();
    assertEquals(line, field.height(), "a short value is one line high");

    value[0] = "aaaa bbbb cccc dddd eeee ffff gggg hhhh iiii jjjj";
    app.invalidate();
    app.frame();
    field = app.tree().children().getFirst();
    assertEquals(3 * line, field.height(), "three lines high, however long");
    assertEquals(3, field.text().lines().size());
    assertTrue(field.text().shortened(), "the third line ends in an ellipsis");

    // A drag from the first line's start into the second selects across the wrap.
    app.pointerDown(field.x() + 1, field.y() + line / 2);
    app.pointerMove(field.x() + 1, field.y() + line * 1.5f);
    app.pointerUp(field.x() + 1, field.y() + line * 1.5f);
    app.frame();
    Fields.key(app, "C", Key.CONTROL);
    El selected = app.tree().children().getFirst();
    assertEquals(
        value[0].substring(0, selected.text().lineStart(1)), app.clipboard().read());
  }

  @Test
  void aCutFieldScrollsToTheCaretAtItsEnd() {
    TextLibraries.assumeLoaded();
    App<Object> app = new App<>(
        new Object(),
        s -> col()
            .align(Style.Align.START)
            .child(El.text("aaaa bbbb cccc dddd eeee ffff gggg hhhh iiii jjjj")
                .editable(v -> {})
                .readOnly()
                .wrapText()
                .maxLines(3)
                .ellipsis()
                .w(80)));
    app.textRendering(TextRendering.LINUX);
    app.resize(200, 200, 1);
    app.frame();
    app.focus(app.tree().children().getFirst());
    app.frame();
    Fields.key(app, "End", Key.CONTROL);
    El field = app.tree().children().getFirst();
    assertFalse(field.text().shortened(), "with the focus the last line runs on");
    assertTrue(field.text().scroll() > 0, "and scrolls until the caret at its end shows");
  }

  @Test
  void textCutWithoutAnEllipsisEndsWithItsLastLine() {
    TextLibraries.assumeLoaded();
    App<Object> app = new App<>(
        new Object(),
        s -> col()
            .align(Style.Align.START)
            .child(El.text("word ".repeat(30)).wrapText().maxLines(3).w(80)));
    app.textRendering(TextRendering.LINUX);
    app.resize(200, 200, 1);
    app.frame();
    El text = app.tree().children().getFirst();
    assertEquals(3, text.text().lines().size());
    assertTrue(
        text.text().lines().getLast().glyphs().width() <= 80,
        "the last line's glyphs stay inside the box");
  }
}
