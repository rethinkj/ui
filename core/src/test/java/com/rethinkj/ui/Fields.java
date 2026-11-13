package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;

import com.rethinkj.ui.text.TextRendering;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/** An app with one text field, for the tests of fields, and what they need to drive it. */
final class Fields {

  private Fields() {}

  /** The app's state: the field's string, and how the app takes each edit. */
  static final class Model {
    String value;
    UnaryOperator<String> accept = s -> s; // what the app makes of an edit; null refuses it
    final List<String> edits = new ArrayList<>();
  }

  /**
   * A field 100 wide and 24 high at 10,10, its content from x 14, in Inter at 14 pixels, and a
   * focusable box below it; laid out at scale 1 with a first frame.
   */
  static App<Model> start(String value) {
    return start(value, UnaryOperator.identity(), 1);
  }

  static App<Model> start(String value, UnaryOperator<El> field, float scale) {
    Model model = new Model();
    model.value = value;
    App<Model> app = new App<>(
        model,
        m -> col()
            .wFull()
            .hFull()
            .padding(10)
            .gap(4)
            .children(
                field.apply(El.text(m.value)
                    .editable(edit -> {
                      m.edits.add(edit);
                      String accepted = m.accept.apply(edit);
                      if (accepted != null) {
                        m.value = accepted;
                      }
                    })
                    .key("f")
                    .w(100)
                    .h(24)
                    .padding(0, 4)),
                el().key("other").focusable().size(10, 10)));
    app.textRendering(TextRendering.LINUX);
    app.resize(200, 80, scale);
    app.frame();
    return app;
  }

  static El field(App<?> app) {
    return app.tree().children().getFirst();
  }

  /** Focuses the field as the app does, without selecting its text. */
  static void focus(App<?> app) {
    app.focus(field(app));
    app.frame();
  }

  static void key(App<?> app, String name) {
    key(app, name, 0);
  }

  static void key(App<?> app, String name, int modifiers) {
    app.keyDown(new Key(name, modifiers, false));
    app.keyUp(new Key(name, modifiers, false));
    app.frame();
  }

  static void type(App<?> app, String text) {
    app.textInput(text);
    app.frame();
  }

  /**
   * The field's string with the caret as {@code |} and the other end of a selection as {@code
   * ^}, as the editor has them.
   */
  static String show(App<?> app) {
    Editor editor = field(app).editor();
    StringBuilder out = new StringBuilder(editor.text());
    int caret = editor.caret();
    int anchor = editor.anchor();
    if (caret == anchor) {
      return out.insert(caret, '|').toString();
    }
    out.insert(Math.max(caret, anchor), caret > anchor ? '|' : '^');
    return out.insert(Math.min(caret, anchor), caret > anchor ? '^' : '|').toString();
  }
}
