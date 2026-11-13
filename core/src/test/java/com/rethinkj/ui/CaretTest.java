package com.rethinkj.ui;

import static com.rethinkj.ui.Fields.field;
import static com.rethinkj.ui.Fields.focus;
import static com.rethinkj.ui.Fields.key;
import static com.rethinkj.ui.Fields.show;
import static com.rethinkj.ui.Fields.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.Fields.Model;
import com.rethinkj.ui.text.TextLibraries;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What an editor of code needs of a field's caret: {@link El#onSelect} tells it, {@link App#edit}
 * and {@link App#select} change the string and the selection as one step of the history, and an
 * overlay {@link El#atCaret} lies at the caret. {@link Areas} makes the field: 200 by 98 at 10,10,
 * its content from 14,14, lines of 18 pixels of JetBrains Mono, whose characters advance 8.
 */
class CaretTest {

  private final List<String> told = new ArrayList<>();
  private boolean completing;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  private App<Model> area(String value, UnaryOperator<El> field) {
    App<Model> app = Areas.start(
        value, f -> field.apply(f.onSelect((caret, anchor) -> told.add(caret + ":" + anchor))), 1);
    told.clear();
    focus(app);
    return app;
  }

  @Test
  void theHandlerIsToldEveryChangeOnce() {
    App<Model> app = Areas.start(
        "ab\ncd", f -> f.onSelect((caret, anchor) -> told.add(caret + ":" + anchor)), 1);
    assertEquals(List.of("0:0"), told, "a new field tells where its caret starts");
    focus(app);
    key(app, "ArrowRight");
    key(app, "ArrowDown", Key.SHIFT);
    type(app, "x");
    app.frame();
    assertEquals(List.of("0:0", "1:1", "4:1", "2:2"), told);
    assertEquals("axd", app.state().value);
    app.pointerDown(14 + 8 * 3 + 2, 14 + 9);
    app.pointerUp(14 + 8 * 3 + 2, 14 + 9);
    app.frame();
    assertEquals("3:3", told.getLast(), "a press");
  }

  @Test
  void aRefusedEditTellsTheCaretItGoesBackTo() {
    App<Model> app = area("abc", f -> f);
    key(app, "End");
    app.state().accept = edit -> null;
    type(app, "x");
    assertEquals("abc|", show(app));
    assertEquals(List.of("3:3", "4:4", "3:3"), told, "the edit's caret, then back");
  }

  @Test
  void theHandlerIsToldOnlyPositionsBetweenGraphemes() {
    App<Model> app = area("\u0301", f -> f);
    key(app, "Home");
    told.clear();
    type(app, "e"); // joins the mark after it into one grapheme
    assertEquals("e\u0301", app.state().value);
    assertEquals("|e\u0301", show(app));
    assertEquals(List.of(), told, "never 1, between the letter and its mark");
  }

  @Test
  void anEditOfTheAppIsOneStepThroughTheHandler() {
    App<Model> app = area("SELECT na FROM t", f -> f);
    El field = field(app);
    app.edit(field, 7, 9, "name", 11);
    app.frame();
    assertEquals("SELECT name| FROM t", show(app));
    assertEquals(List.of("SELECT name FROM t"), app.state().edits, "as typed text is");
    assertEquals("11:11", told.getLast());
    key(app, "Z", Key.CONTROL);
    assertEquals("|SELECT na FROM t", show(app), "undo goes back to the caret before");
    key(app, "Y", Key.CONTROL);
    assertEquals("SELECT name| FROM t", show(app));
    app.state().accept = edit -> null;
    app.edit(field(app), 0, 6, "select", 6);
    app.frame();
    assertEquals("SELECT name| FROM t", show(app), "refused like typed text");
    app.state().accept = edit -> edit;
    app.edit(field(app), 0, 0, "a\r\nb", 99);
    app.frame();
    assertEquals(
        "a\nbSELECT name FROM t|",
        show(app),
        "line breaks as the field keeps them, the caret clamped to the end");
    assertThrows(IllegalArgumentException.class, () -> app.edit(El.el(), 0, 0, "", 0));
  }

  @Test
  void aHandlerEditsTheFieldOfTheTreeItRunsIn() {
    List<App<Model>> apps = new ArrayList<>();
    App<Model> app = Areas.start(
        "(a",
        f -> f.onKey(k -> {
          if (k.name().equals("F2")) { // closes the bracket, with the caret before it
            apps.getFirst().edit(f, 2, 2, ")", 2);
            return true;
          }
          return false;
        }),
        1);
    apps.add(app);
    focus(app);
    key(app, "End");
    key(app, "F2");
    assertEquals("(a|)", show(app));
    assertEquals("(a)", app.state().value);
  }

  @Test
  void undoAndRedoStepThroughTheFieldsHistoryAsItsKeysDo() {
    App<Model> app = area("ab", f -> f);
    focus(app);
    key(app, "End");
    app.edit(field(app), 2, 2, "c", 3);
    app.frame();
    assertEquals("abc", app.state().value);
    app.undo(field(app));
    app.frame();
    assertEquals("ab", app.state().value);
    app.undo(field(app)); // no step left
    app.redo(field(app));
    app.frame();
    assertEquals("abc|", show(app));
  }

  @Test
  void selectSelectsAndScrollsIntoView() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 30; i++) {
      text.append("line ").append(i).append('\n');
    }
    App<Model> app = area(text + "match", f -> f);
    app.select(field(app), text.length() + 5, text.length());
    app.frame();
    assertTrue(show(app).endsWith("^match|"), show(app));
    assertTrue(field(app).scrollY() > 0, "scrolled to the match");
    assertEquals((text.length() + 5) + ":" + text.length(), told.getLast());
  }

  @Test
  void anOverlayAtTheCaretLiesBelowItAndFollowsIt() {
    App<Model> app = area(
        "SELECT ",
        f -> f.overlay(
            completing
                ? El.el().key("list").size(60, 40).fill(0xFF000000).atCaret().keepFocus()
                : null));
    key(app, "End");
    completing = true;
    type(app, "n");
    El list = app.tree().shownOverlays().getFirst();
    // The caret after 8 characters at 14 + 64, its row from 14 to 32.
    assertEquals(14 + 64, list.x());
    assertEquals(14 + 18, list.y());
    assertEquals("SELECT n|", show(app), "the field kept the focus");
    key(app, "Enter");
    key(app, "Enter");
    key(app, "Enter");
    key(app, "Enter");
    list = app.tree().shownOverlays().getFirst();
    assertEquals(14, list.x(), "at the start of the fifth line");
    assertEquals(14 + 4 * 18 + 18, list.y(), "below it");
    key(app, "Enter");
    key(app, "Enter");
    key(app, "Enter");
    key(app, "Enter");
    key(app, "Enter");
    key(app, "Enter");
    key(app, "Enter");
    list = app.tree().shownOverlays().getFirst();
    // The field scrolled: the caret's row at the bottom of the content box, 14 + 72 to 14 + 90;
    // below it 116 to 156 fits in the window of 240.
    assertEquals(14 + 72 + 18, list.y(), "it followed the scroll");
  }
}
