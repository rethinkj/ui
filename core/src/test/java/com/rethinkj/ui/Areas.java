package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;

import com.rethinkj.ui.Fields.Model;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextRendering;
import java.util.function.UnaryOperator;

/**
 * An app with one multi-line field, for the tests of text areas; {@link Fields} drives it, as its
 * field is the first child of the root too.
 */
final class Areas {

  private Areas() {}

  /**
   * A field 200 wide and five lines high at 10,10, with a padding of 4, in JetBrains Mono at 14
   * pixels, so that every character advances as far, and a focusable box below it; in a window of
   * 320 by 240, laid out at {@code scale} with a first frame.
   */
  static App<Model> start(String value, UnaryOperator<El> field, float scale) {
    return start(value, field, scale, UnaryOperator.identity());
  }

  /** {@link #start(String, UnaryOperator, float)} with a root that {@code root} goes on with. */
  static App<Model> start(
      String value, UnaryOperator<El> field, float scale, UnaryOperator<El> root) {
    Model model = new Model();
    model.value = value;
    App<Model> app = new App<>(model, m -> root.apply(col().wFull().hFull().padding(10).gap(4)).children(
        field.apply(El.text(m.value)
            .editable(edit -> {
              m.edits.add(edit);
              String accepted = m.accept.apply(edit);
              if (accepted != null) {
                m.value = accepted;
              }
            })
            .multiline()
            .key("f")
            .font(Font.mono())
            .fontSize(14)
            .w(200)
            .lines(5)
            .padding(4)),
        el().key("other").focusable().size(10, 10)));
    app.textRendering(TextRendering.LINUX);
    app.resize(320, 240, scale);
    app.frame();
    return app;
  }

  static App<Model> start(String value) {
    return start(value, UnaryOperator.identity(), 1);
  }

  /** The line of the field in the dump. */
  static String line(App<?> app) {
    for (String line : app.tree().dump().split("\n")) {
      if (line.contains("#f ")) {
        return line.strip();
      }
    }
    throw new AssertionError(app.tree().dump());
  }
}
