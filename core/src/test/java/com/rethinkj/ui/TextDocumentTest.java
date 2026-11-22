package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.Fields.key;
import static com.rethinkj.ui.Fields.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Multiple text fields sharing one {@link TextDocument}: shared undo/redo history, exact {@link
 * TextChange} caret and line updates, per-view coalescing, proposal resolution regardless of attach
 * order, and out-of-tree catch-up.
 */
class TextDocumentTest {

  static final class Model {
    final TextDocument doc = new TextDocument();
    final FieldState left = new FieldState(doc);
    final FieldState right = new FieldState(doc);
    String text;
    boolean showRight = true;
    boolean rightFirst;
    Function<String, String> filter = Function.identity();
    Runnable afterEdit;

    Model(String initial) {
      this.text = initial;
    }
  }

  private static El field(Model m, String key, FieldState state) {
    return El.text(m.text)
        .editable(value -> {
          m.text = m.filter.apply(value);
          if (m.afterEdit != null) {
            Runnable action = m.afterEdit;
            m.afterEdit = null;
            action.run();
          }
        })
        .multiline()
        .key(key)
        .font(Font.mono())
        .fontSize(14)
        .w(200)
        .lines(5)
        .padding(4)
        .scroll()
        .fieldState(state);
  }

  private static App<Model> start(Model model) {
    App<Model> app = new App<>(model, m -> {
      El left = field(m, "left", m.left);
      El right = m.showRight ? field(m, "right", m.right) : null;
      El container = row().wFull().hFull().padding(10).gap(8);
      if (m.rightFirst) {
        if (right != null) {
          container.child(right);
        }
        container.child(left);
      } else {
        container.child(left);
        if (right != null) {
          container.child(right);
        }
      }
      return col().wFull().hFull().child(container);
    });
    app.textRendering(TextRendering.LINUX);
    app.resize(460, 240, 1);
    app.frame();
    return app;
  }

  private static El find(App<Model> app, String key) {
    for (El child : app.tree().children().getFirst().children()) {
      if (key.equals(child.key())) {
        return child;
      }
    }
    return null;
  }

  @Test
  void editsMapCaretByRealChangeKeepLinesAndShareUndoAcrossBothAttachOrders() {
    TextLibraries.assumeLoaded();
    for (boolean rightFirst : new boolean[] {false, true}) {
      Model model = new Model("foo\nfoo\nbar");
      model.rightFirst = rightFirst;
      App<Model> app = start(model);
      // Place right's caret inside the second "foo" (between 'f' and 'o', offset 5).
      model.right.select(5, 5);
      // Select the first "foo\n" (0..4) in left.
      model.left.select(4, 0);
      app.frame();
      Lines rightLines = find(app, "right").editor().lineIndex();

      app.focus(find(app, "left"));
      app.frame();
      key(app, "Backspace"); // deletes first "foo\n" (TextChange(0, 4, 0))
      assertEquals("foo\nbar", model.text);
      assertEquals(0, find(app, "left").editor().caret());
      // TextChange.between would treat the second "foo\n" as deleted and land at 4; the real edit
      // moves offset 5 to 1.
      assertEquals(1, find(app, "right").editor().caret(), "rightFirst=" + rightFirst);
      assertSame(
          rightLines,
          find(app, "right").editor().lineIndex(),
          "other view updates lines via Lines.edit, not relining");

      // Undo in right undoes left's deletion: right gets the step's positions, left maps its caret.
      app.focus(find(app, "right"));
      app.frame();
      key(app, "Z", Key.CONTROL);
      assertEquals("foo\nfoo\nbar", model.text);
      assertEquals(4, find(app, "right").editor().caret());
      assertEquals(0, find(app, "right").editor().anchor());
      assertEquals(0, find(app, "left").editor().caret());

      // Redo in right reapplies the deletion.
      key(app, "Z", Key.CONTROL | Key.SHIFT);
      assertEquals("foo\nbar", model.text);
      assertEquals(1, find(app, "right").editor().caret());

      // An edit the app changes moves the other view by the real edit, then by the app's change.
      key(app, "Z", Key.CONTROL);
      model.right.select(5, 5);
      model.left.select(4, 0);
      app.focus(find(app, "left"));
      app.frame();
      model.filter = proposed -> proposed + "!";
      key(app, "Backspace");
      assertEquals("foo\nbar!", model.text);
      assertEquals(1, find(app, "right").editor().caret(), "rightFirst=" + rightFirst);
    }
  }

  @Test
  void typingRunsCoalescePerViewOnly() {
    TextLibraries.assumeLoaded();
    Model model = new Model("");
    App<Model> app = start(model);

    app.focus(find(app, "left"));
    app.frame();
    type(app, "a");
    type(app, "b");
    assertEquals("ab", model.text);

    model.right.select(2, 2);
    app.focus(find(app, "right"));
    app.frame();
    type(app, "c");
    type(app, "d");
    assertEquals("abcd", model.text);

    // One undo takes back right's coalesced run; the next takes back left's.
    key(app, "Z", Key.CONTROL);
    assertEquals("ab", model.text);
    key(app, "Z", Key.CONTROL);
    assertEquals("", model.text);
    key(app, "Y", Key.CONTROL);
    assertEquals("ab", model.text);
  }

  @Test
  void refusedAndChangedEditsResolveInEitherAttachOrder() {
    TextLibraries.assumeLoaded();
    for (boolean rightFirst : new boolean[] {false, true}) {
      Model model = new Model("hello\nworld");
      model.rightFirst = rightFirst;
      App<Model> app = start(model);

      // Establish one history step first.
      app.focus(find(app, "left"));
      app.frame();
      type(app, "!");
      assertEquals("!hello\nworld", model.text);

      // Place carets and refuse the next edit.
      model.left.select(1, 1);
      model.right.select(8, 8);
      app.invalidate();
      app.frame();
      Lines rightLines = find(app, "right").editor().lineIndex();
      model.filter = proposed -> model.text; // refuse
      type(app, "x");
      assertEquals("!hello\nworld", model.text);
      assertEquals(1, find(app, "left").editor().caret(), "left caret restored");
      assertEquals(8, find(app, "right").editor().caret(), "right untouched");
      assertSame(rightLines, find(app, "right").editor().lineIndex());

      // Prior history survived the refused edit.
      model.filter = Function.identity();
      app.focus(find(app, "right"));
      app.frame();
      key(app, "Z", Key.CONTROL);
      assertEquals("hello\nworld", model.text);

      // Now let the app transform an edit: both views show the transformed string and one undo
      // step reverts it.
      model.left.select(0, 0);
      model.right.select(6, 6);
      app.focus(find(app, "left"));
      app.invalidate();
      app.frame();
      model.filter = proposed -> proposed.replaceFirst("^a", "AA");
      type(app, "a");
      assertEquals("AAhello\nworld", model.text);
      assertEquals(2, find(app, "left").editor().caret());
      assertEquals(8, find(app, "right").editor().caret());

      model.filter = Function.identity();
      app.focus(find(app, "right"));
      app.frame();
      key(app, "Z", Key.CONTROL);
      assertEquals("hello\nworld", model.text);
    }
  }

  @Test
  void externalChangeClearsHistoryForAllViews() {
    TextLibraries.assumeLoaded();
    Model model = new Model("one");
    App<Model> app = start(model);

    app.focus(find(app, "left"));
    app.frame();
    type(app, "x");
    assertEquals("xone", model.text);

    model.text = "replaced";
    app.invalidate();
    app.frame();

    app.focus(find(app, "right"));
    app.frame();
    key(app, "Z", Key.CONTROL);
    assertEquals("replaced", model.text, "right has no history");

    app.focus(find(app, "left"));
    app.frame();
    key(app, "Z", Key.CONTROL);
    assertEquals("replaced", model.text, "left has no history");
  }

  @Test
  void returningViewCatchesUpFromChangeLogAndFallsBackPastLimit() {
    TextLibraries.assumeLoaded();
    Model model = new Model("dup\ndup\nend");
    App<Model> app = start(model);
    model.right.select(5, 5); // inside second "dup"
    app.invalidate();
    app.frame();
    Lines rightLines = find(app, "right").editor().lineIndex();

    // Hide right while left deletes the first "dup\n".
    model.showRight = false;
    model.left.select(4, 0);
    app.invalidate();
    app.frame();
    app.focus(find(app, "left"));
    app.frame();
    key(app, "Backspace");
    assertEquals("dup\nend", model.text);

    // Bring right back: mapped by the real change, lines preserved, undo still shared.
    model.showRight = true;
    app.invalidate();
    app.frame();
    assertEquals(1, find(app, "right").editor().caret());
    assertSame(rightLines, find(app, "right").editor().lineIndex());

    // Now hide right and exceed CHANGE_LIMIT so it falls back to TextChange.between.
    model.showRight = false;
    app.invalidate();
    app.frame();
    app.focus(find(app, "left"));
    app.frame();
    int count = TextDocument.CHANGE_LIMIT + 5;
    for (int i = 0; i < count; i++) {
      model.left.select(0, 0);
      app.invalidate();
      app.frame();
      type(app, "z");
    }
    model.showRight = true;
    app.invalidate();
    app.frame();
    assertEquals(1 + count, find(app, "right").editor().caret());
    app.focus(find(app, "right"));
    app.frame();
    key(app, "Z", Key.CONTROL);
    assertEquals("dup\nend".length() + count - 1, model.text.length());
  }

  @Test
  void twoEditsInOneFrameFromTwoViewsBuildOnEachOther() {
    TextLibraries.assumeLoaded();
    Model model = new Model("hello");
    App<Model> app = start(model);
    model.left.select(0, 0);
    model.right.select(5, 5);
    app.frame();

    // In left's onEdit handler (while left's edit waits for its answer), queue an edit on right.
    model.afterEdit = () -> model.right.replaceSelection("!");
    app.focus(find(app, "left"));
    app.frame();
    app.textInput(">");
    app.frame();
    assertEquals(">hello!", model.text);
    assertEquals(1, find(app, "left").editor().caret());
    assertEquals(7, find(app, "right").editor().caret());

    // Undo in left reverts right's edit first, then left's.
    key(app, "Z", Key.CONTROL);
    assertEquals(">hello", model.text);
    key(app, "Z", Key.CONTROL);
    assertEquals("hello", model.text);
  }
}
