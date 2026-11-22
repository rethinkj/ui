package com.rethinkj.ui;

import static com.rethinkj.ui.Fields.field;
import static com.rethinkj.ui.Fields.focus;
import static com.rethinkj.ui.Fields.key;
import static com.rethinkj.ui.Fields.show;
import static com.rethinkj.ui.Fields.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Line folding in a multi-line field ({@link El#folds}): caret navigation, selection and copy,
 * unfolding on edit/find/undo/click, gutter and wrapping, and skipping shaping of hidden lines.
 */
class FoldTest {

  private static final int SHIFT = Key.SHIFT;
  private static final int CONTROL = Key.CONTROL;

  private static final class State {
    String value;
    final List<El.Fold> folds = new ArrayList<>();
    final List<El.Fold> unfolded = new ArrayList<>();
    FieldState fieldState;
    Function<El.Fold, El> mark;
    boolean autoUnfold = true;

    State(String value, El.Fold... folds) {
      this.value = value;
      this.folds.addAll(List.of(folds));
    }

    void unfold(El.Fold fold) {
      unfolded.add(fold);
      if (autoUnfold) {
        folds.remove(fold);
      }
    }
  }

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  private static App<State> start(State state, UnaryOperator<El> extra) {
    App<State> app = new App<>(
        state,
        s -> El.col()
            .wFull()
            .hFull()
            .padding(10)
            .child(extra.apply(El.text(s.value)
                .editable(v -> s.value = v)
                .multiline()
                .key("f")
                .font(Font.mono())
                .fontSize(14)
                .w(200)
                .lines(5)
                .padding(4)
                .fieldState(s.fieldState)
                .folds(s.folds, s::unfold, s.mark))));
    app.textRendering(TextRendering.LINUX);
    app.resize(320, 240, 1);
    app.frame();
    focus(app);
    return app;
  }

  @Test
  void arrowKeysSkipHiddenLinesAndCopyKeepsThem() {
    State state = new State("line0\nline1 {\n  hiddenA\n  hiddenB\n}\nline5", new El.Fold(1, 4));
    App<State> app = start(state, UnaryOperator.identity());

    // Down from line 0 -> line 1 -> line 5 (skipping lines 2..4), Up back to line 1.
    key(app, "ArrowDown");
    assertEquals("line0\n|line1 {\n  hiddenA\n  hiddenB\n}\nline5", show(app));
    key(app, "ArrowDown");
    assertEquals("line0\nline1 {\n  hiddenA\n  hiddenB\n}\n|line5", show(app));
    key(app, "ArrowUp");
    assertEquals("line0\n|line1 {\n  hiddenA\n  hiddenB\n}\nline5", show(app));

    // Right at end of fold's first line -> start of line after fold; Left back again.
    key(app, "End");
    assertEquals("line0\nline1 {|\n  hiddenA\n  hiddenB\n}\nline5", show(app));
    key(app, "ArrowRight");
    assertEquals("line0\nline1 {\n  hiddenA\n  hiddenB\n}\n|line5", show(app));
    key(app, "ArrowLeft");
    assertEquals("line0\nline1 {|\n  hiddenA\n  hiddenB\n}\nline5", show(app));

    // Shift+Right selects over the fold; copy keeps the hidden lines without unfolding.
    key(app, "ArrowRight", SHIFT);
    key(app, "C", CONTROL);
    assertEquals("\n  hiddenA\n  hiddenB\n}\n", app.clipboard().read());
    assertEquals(List.of(), state.unfolded, "selecting and copying does not unfold");

    // Ctrl+ArrowRight / Ctrl+ArrowLeft skip hidden lines.
    key(app, "ArrowLeft"); // collapses to end of line 1
    key(app, "ArrowRight", CONTROL);
    assertEquals("line0\nline1 {\n  hiddenA\n  hiddenB\n}\n|line5", show(app));
    key(app, "ArrowLeft", CONTROL);
    assertEquals("line0\nline1 {|\n  hiddenA\n  hiddenB\n}\nline5", show(app));

    // Fold reaching the end of the file: Down, Right, PageDown, Ctrl+End stay on visible line.
    State tail = new State("a\nb\nc\nd", new El.Fold(1, 3));
    App<State> tailApp = start(tail, UnaryOperator.identity());
    key(tailApp, "End", CONTROL);
    assertEquals("a\nb|\nc\nd", show(tailApp));
    key(tailApp, "ArrowRight");
    assertEquals("a\nb|\nc\nd", show(tailApp));
    key(tailApp, "ArrowDown");
    assertEquals("a\nb|\nc\nd", show(tailApp));
    key(tailApp, "PageDown");
    assertEquals("a\nb|\nc\nd", show(tailApp));
  }

  @Test
  void editsFindFieldStateAndUndoUnfoldTouchedOrHiddenFolds() {
    El.Fold fold = new El.Fold(1, 3);
    // 1. Typing on the visible header line does not unfold; Delete at its end touches line 2 and
    // unfolds.
    State state = new State("a\nheader\nhid1\nhid2\nafter", fold);
    App<State> app = start(state, UnaryOperator.identity());
    key(app, "ArrowDown");
    key(app, "End");
    type(app, "!");
    assertEquals(List.of(), state.unfolded, "editing visible header line keeps fold");
    key(app, "Delete");
    assertEquals(List.of(fold), state.unfolded, "Delete into hidden line unfolds");
    assertEquals("a\nheader!hid1\nhid2\nafter", state.value);

    // 2. Backspace at start of line after fold touches last hidden line and unfolds.
    State back = new State("a\nheader\nhid1\nhid2\nafter", fold);
    App<State> backApp = start(back, UnaryOperator.identity());
    key(backApp, "End", CONTROL);
    key(backApp, "Home");
    key(backApp, "Backspace");
    assertEquals(List.of(fold), back.unfolded);

    // 3. App.select (find) onto a hidden line unfolds nested folds and reveals it.
    El.Fold outer = new El.Fold(0, 4);
    El.Fold inner = new El.Fold(1, 3);
    State find = new State("L0\nL1\nFIND\nL3\nL4\nL5", outer, inner);
    App<State> findApp = start(find, UnaryOperator.identity());
    int match = find.value.indexOf("FIND");
    findApp.select(field(findApp), match + 4, match);
    findApp.frame();
    assertEquals(List.of(outer, inner), find.unfolded, "nested folds hiding match both unfold");
    assertTrue(find.folds.isEmpty());

    // 4. FieldState.select onto a hidden line unfolds on the frame that attaches it.
    State fs = new State("L0\nL1\nTARGET\nL3", new El.Fold(0, 2));
    fs.fieldState = new FieldState();
    fs.fieldState.select(fs.value.indexOf("TARGET"), fs.value.indexOf("TARGET"));
    start(fs, UnaryOperator.identity());
    assertEquals(List.of(new El.Fold(0, 2)), fs.unfolded);

    // 5. Undo into a folded range unfolds it.
    State undo = new State("L0\nL1\nEDIT\nL3\nL4");
    App<State> undoApp = start(undo, UnaryOperator.identity());
    key(undoApp, "ArrowDown");
    key(undoApp, "ArrowDown");
    type(undoApp, "X");
    assertEquals("L0\nL1\nXEDIT\nL3\nL4", undo.value);
    key(undoApp, "Home", CONTROL);
    El.Fold undoFold = new El.Fold(1, 3);
    undo.folds.add(undoFold);
    undoApp.invalidate();
    undoApp.frame();
    key(undoApp, "Z", CONTROL);
    assertEquals(List.of(undoFold), undo.unfolded, "undo into hidden line unfolds");
    assertEquals("L0\nL1\nEDIT\nL3\nL4", undo.value);

    // 6. Enter at the end of the header, with folds the app does not move: the caret's new line
    // is hidden, so the field asks to unfold.
    State enter = new State("a\nheader\nhid1\nhid2\nafter", fold);
    App<State> enterApp = start(enter, UnaryOperator.identity());
    key(enterApp, "ArrowDown");
    key(enterApp, "End");
    key(enterApp, "Enter");
    assertEquals(List.of(fold), enter.unfolded, "a caret the edit left on a hidden line unfolds");
  }

  @Test
  void hiddenLinesAreNotShapedAndGutterWrappingAndCustomMarkWork() {
    Context<String> tag = new Context<>("default");
    StringBuilder text = new StringBuilder("first line that is long enough to wrap around\n");
    for (int i = 1; i <= 50; i++) {
      text.append("hidden line ").append(i).append("\n");
    }
    text.append("after fold");
    El.Fold fold = new El.Fold(0, 50);
    State state = new State(text.toString(), fold);
    AtomicInteger marked = new AtomicInteger();
    state.mark = f -> {
      marked.incrementAndGet();
      return El.text("[" + tag.get() + ":" + (f.last() - f.first()) + "]").fontSize(12);
    };
    List<Integer> gutterLines = new ArrayList<>();
    App<State> app = new App<>(
        state,
        s -> tag.with(
            "ctx",
            () -> El.col()
                .wFull()
                .hFull()
                .padding(10)
                .child(El.text(s.value)
                    .editable(v -> s.value = v)
                    .multiline()
                    .wrapText()
                    .key("f")
                    .font(Font.mono())
                    .fontSize(14)
                    .w(200)
                    .lines(5)
                    .padding(4)
                    .gutter(line -> {
                      gutterLines.add(line.index());
                      return El.text(String.valueOf(line.index() + 1))
                          .font(Font.mono())
                          .fontSize(14)
                          .padding(0, 4, 0, 0);
                    })
                    .decorate((line, start, lineText, out) -> out.lineBackground(0x203B82F6))
                    .folds(s.folds, s::unfold, s.mark))));
    app.textRendering(TextRendering.LINUX);
    app.resize(320, 240, 1);
    app.frame();

    Lines lines = field(app).editor().lineIndex();
    assertEquals(2, lines.shaped, "only the 2 visible lines are shaped, not the 50 hidden ones");
    assertEquals(1, marked.get(), "mark built once for the fold in view");
    assertTrue(
        gutterLines.contains(0) && gutterLines.contains(51),
        "gutter uses real line indices: " + gutterLines);
    assertFalse(gutterLines.contains(1), "hidden lines get no gutter cell: " + gutterLines);

    String dump = app.tree().dump();
    assertTrue(dump.contains("#fold-0 \"[ctx:50]\""), "context captured and default key: " + dump);

    // Click the fold mark to unfold.
    El markEl = field(app).children().stream()
        .filter(c -> "fold-0".equals(c.key()))
        .findFirst()
        .orElseThrow();
    float clickX = 10 + markEl.x() + markEl.width() / 2;
    float clickY = 10 + markEl.y() + markEl.height() / 2;
    app.pointerDown(clickX, clickY);
    app.pointerUp(clickX, clickY);
    app.frame();
    assertEquals(List.of(fold), state.unfolded, "clicking fold mark asks to unfold");
    assertTrue(state.folds.isEmpty());
    assertFalse(app.tree().dump().contains("#fold-0"));
  }
}
