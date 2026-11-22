package com.rethinkj.ui.editor;

import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.ScrollState;
import com.rethinkj.ui.TextDocument;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.text.TextRendering;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Two {@link CodeEditor} views backed by one {@link TextDocument} and coupled scrolling. */
class SharedCodeEditorTest {

  static final class State {
    final TextDocument document = new TextDocument();
    final CodeEditor.State left = new CodeEditor.State(document);
    final CodeEditor.State right = new CodeEditor.State(document);
    final ScrollState leftScroll = new ScrollState();
    final ScrollState rightScroll = new ScrollState();
    String text;
    App<State> app;

    State(String text) {
      this.text = text;
      leftScroll.scrollWith(rightScroll, List.of());
    }
  }

  @Test
  void twoCodeEditorsShareDocumentUndoAndScroll() {
    TextLibraries.assumeLoaded();
    StringBuilder lines = new StringBuilder();
    for (int i = 1; i <= 30; i++) {
      lines.append("SELECT ").append(i).append(";\n");
    }
    State state = new State(lines.toString());
    App<State> app = new App<>(
        state,
        s -> Theme.CURRENT.with(
            Theme.LIGHT,
            () -> row()
                .wFull()
                .hFull()
                .gap(8)
                .child(CodeEditor.codeEditor(s.app, s.text, s.left, null, v -> s.text = v)
                    .key("left")
                    .w(220)
                    .lines(6)
                    .scrollState(s.leftScroll))
                .child(CodeEditor.codeEditor(s.app, s.text, s.right, null, v -> s.text = v)
                    .key("right")
                    .w(220)
                    .lines(6)
                    .scrollState(s.rightScroll))));
    state.app = app;
    app.textRendering(TextRendering.LINUX);
    app.resize(480, 200, 1);
    app.frame();

    state.right.select(10, 10);
    app.focus(app.tree().children().get(0));
    app.frame();
    app.textInput("-- ");
    app.frame();
    assertTrue(state.text.startsWith("-- SELECT 1;"), state.text);
    assertEquals(13, state.right.caret(), "right editor's caret shifted by left's edit");

    // Undo from the right editor reverts the left editor's typing.
    app.focus(app.tree().children().get(1));
    app.frame();
    app.keyDown(new Key("Z", Key.CONTROL, false));
    app.frame();
    assertTrue(state.text.startsWith("SELECT 1;"), state.text);

    // Scrolling the left editor scrolls the right editor with it.
    app.wheel(100, 50, 0, 120);
    app.frame();
    assertTrue(state.leftScroll.y() > 0);
    assertEquals(state.leftScroll.y(), state.rightScroll.y(), 0.5);
  }
}
