package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import org.junit.jupiter.api.Test;

/** A single-line field with {@link El#ellipsis}: shortened without the focus, whole with it. */
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
}
