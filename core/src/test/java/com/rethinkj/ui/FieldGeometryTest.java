package com.rethinkj.ui;

import static com.rethinkj.ui.Fields.field;
import static com.rethinkj.ui.Fields.focus;
import static com.rethinkj.ui.Fields.key;
import static com.rethinkj.ui.Fields.show;
import static com.rethinkj.ui.Fields.start;
import static com.rethinkj.ui.Fields.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.Fields.Model;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.GlyphRun;
import com.rethinkj.ui.text.TextLibraries;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Where the caret and the selection of a field are drawn, which position a press finds, and how
 * the text scrolls to keep the caret in view.
 */
class FieldGeometryTest {

  /** The left of the field's content box in the window, see {@link Fields#start}. */
  private static final float CONTENT = 14;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  /** The window x of a position of the field's string at scale 1, as it is drawn. */
  private static float x(App<?> app, int index) {
    Text text = field(app).text();
    return CONTENT + text.x(index) - text.scroll();
  }

  private static void press(App<?> app, float x, int clicks, int modifiers) {
    app.pointerDown(x, 22, clicks, modifiers);
    app.pointerUp(x, 22);
    app.frame();
  }

  // Positions in shaped text

  @Test
  void theCaretStandsWhereKerningPutsTheNextGlyph() {
    App<Model> app = start("AV");
    Text text = field(app).text();
    GlyphRun kerned = Font.sans().shape("AV", 14, 1);
    GlyphRun plain = Font.sans().shape("AV", 14, 1, "-kern");
    assertTrue(kerned.pen(1) < plain.pen(1), "Inter kerns A and V");
    assertEquals(kerned.pen(1), text.x(1));
    assertEquals(kerned.width(), text.x(2));

    focus(app);
    key(app, "ArrowLeft");
    assertEquals(kerned.pen(1), text(app).caret().x());
    key(app, "Home");
    key(app, "ArrowRight", Key.SHIFT);
    Text.Box selected = text(app).selection().getFirst();
    assertEquals(0, selected.x());
    assertEquals(kerned.pen(1), selected.width(), "the A up to where the V starts");
  }

  @Test
  void aCaretInsideALigatureSitsInProportionToTheGraphemesBeforeIt() {
    // Inter draws -> and <=> as one glyph each when contextual alternates are on.
    App<Model> app = start("a->b<=>", f -> f.fontFeatures("+calt"), 1);
    Text text = field(app).text();
    GlyphRun run = text.lines().getFirst().glyphs();
    assertEquals(4, run.count(), "a, ->, b and <=>");
    float arrow = run.pen(1);
    float after = run.pen(2);
    assertEquals(arrow, text.x(1));
    assertEquals((arrow + after) / 2, text.x(2), "between - and >");
    assertEquals(after, text.x(3));
    float start = run.pen(3);
    float end = run.width();
    assertEquals(start + (end - start) / 3, text.x(5), 1e-4);
    assertEquals(start + 2 * (end - start) / 3, text.x(6), 1e-4);

    focus(app);
    key(app, "Home");
    key(app, "ArrowRight");
    key(app, "ArrowRight", Key.SHIFT);
    Text.Box selected = text(app).selection().getFirst();
    assertEquals(arrow, selected.x());
    assertEquals((after - arrow) / 2, selected.width(), "half of the ligature");
  }

  @Test
  void jetBrainsMonoDrawsItsLigaturesWithOneGlyphPerCharacter() {
    App<Model> app = start("a->b", f -> f.font(Font.mono()).fontFeatures("+calt"), 1);
    Text text = field(app).text();
    GlyphRun run = text.lines().getFirst().glyphs();
    assertEquals(4, run.count());
    assertTrue(run.glyph(1) != Font.mono().shape("-", 14, 1).glyph(0), "a contextual form");
    for (int i = 0; i <= 4; i++) {
      assertEquals(run.pen(i), text.x(i), "position " + i);
    }
  }

  @Test
  void atScaleTwoTheCaretIsTwoPixelsWideOnWholePixels() {
    App<Model> app = start("hello", f -> f, 2);
    focus(app);
    Text text = field(app).text();
    assertEquals(2, text.caret().width());
    assertEquals(text.x(5), text.caret().x());
    assertEquals(
        text.lines().getFirst().baseline() - text.lineHeight() / 2,
        text.caret().y() + text.caret().height() / 2,
        text.lineHeight() / 2,
        "around the line");
  }

  // The pointer

  @Test
  void aPressPutsTheCaretAtTheNearestBoundary() {
    App<Model> app = start("hello world");
    press(app, (x(app, 2) * 3 + x(app, 3)) / 4, 1, 0);
    assertEquals("f", app.focused().key());
    assertEquals("he|llo world", show(app));
    press(app, (x(app, 2) + x(app, 3) * 3) / 4, 1, 0);
    assertEquals("hel|lo world", show(app));
    press(app, 100, 1, 0);
    assertEquals("hello world|", show(app), "right of the text: the end");
    press(app, 11, 1, 0);
    assertEquals("|hello world", show(app), "in the padding: the start");
  }

  @Test
  void dragSelectsAndDoubleAndTriplePressesSelectWordsAndAll() {
    App<Model> app = start("hello big world");
    app.pointerDown(x(app, 2), 22);
    app.pointerMove(x(app, 7) + 1, 22);
    app.frame();
    assertEquals("he^llo b|ig world", show(app));
    app.pointerMove(x(app, 1), 22);
    app.pointerUp(x(app, 1), 22);
    app.frame();
    assertEquals("h|e^llo big world", show(app));
    app.pointerMove(x(app, 10), 22);
    app.frame();
    assertEquals("h|e^llo big world", show(app), "the button is up");

    press(app, x(app, 7) + 1, 1, 0);
    press(app, x(app, 7) + 1, 2, 0);
    assertEquals("hello ^big| world", show(app));
    press(app, x(app, 7) + 1, 3, 0);
    assertEquals("^hello big world|", show(app));

    // A double press, then a drag: by whole words.
    app.pointerDown(x(app, 7), 22, 2, 0);
    app.pointerMove(x(app, 13), 22);
    app.frame();
    assertEquals("hello ^big world|", show(app));
    app.pointerMove(x(app, 1), 22);
    app.pointerUp(x(app, 1), 22);
    app.frame();
    assertEquals("|hello big^ world", show(app));
  }

  @Test
  void shiftWithAPressExtendsTheSelection() {
    App<Model> app = start("hello world");
    press(app, x(app, 2), 1, 0);
    press(app, x(app, 9), 1, Key.SHIFT);
    assertEquals("he^llo wor|ld", show(app));
    press(app, x(app, 1), 1, Key.SHIFT);
    assertEquals("h|e^llo world", show(app));
  }

  @Test
  void aDragBeyondTheEdgeScrollsTheText() {
    App<Model> app = start("SELECT name FROM customers WHERE id = 1");
    Text text = field(app).text();
    assertTrue(text.lines().getFirst().width() > 150, "wider than the field");
    int edge = text.nearest(text.contentWidth(), 0); // what shows at the right edge
    app.pointerDown(x(app, 2), 22);
    app.pointerMove(150, 22); // 40 beyond the right edge
    app.frame();
    Text shown = field(app).text();
    assertTrue(shown.scroll() > 0, "scrolled");
    int caret = field(app).editor().caret();
    assertTrue(caret > edge, "the caret went beyond what showed: " + caret + " > " + edge);
    assertEquals(
        shown.x(caret),
        shown.scroll() + shown.contentWidth() - Editor.MARGIN - 1,
        1,
        "the caret at the edge");
    assertCaretInView(shown);
    app.pointerMove(-100, 22); // far to the left, beyond the window, as a captured pointer goes
    app.pointerUp(-100, 22);
    app.frame();
    assertEquals(0, field(app).text().scroll());
    assertEquals(0, field(app).editor().caret());
  }

  // Scrolling

  @Test
  void theTextScrollsToKeepTheCaretInViewAndBackAtTheStart() {
    App<Model> app = start("");
    focus(app);
    type(app, "SELECT name FROM customers");
    Text text = field(app).text();
    float width = text.contentWidth();
    assertTrue(text.scroll() > 0);
    assertCaretInView(text);
    assertEquals(
        Editor.MARGIN,
        width - (text.caret().x() + text.caret().width()),
        0.5,
        "the margin after the end");

    for (int i = 0; i < 26; i++) {
      key(app, "ArrowLeft");
      assertCaretInView(field(app).text());
      if (field(app).text().scroll() > 0) {
        assertTrue(field(app).text().caret().x() >= Editor.MARGIN - 0.5, "margin before");
      }
    }
    assertEquals(0, field(app).text().scroll(), "at the start");
    key(app, "End");
    assertEquals(text.scroll(), field(app).text().scroll(), "the same view as before");

    key(app, "A", Key.CONTROL);
    type(app, "short");
    assertEquals(0, field(app).text().scroll(), "nothing to scroll once it fits");

    type(app, " but then longer than the field is wide");
    key(app, "Tab");
    assertEquals(0, field(app).text().scroll(), "without the focus it shows its start");
  }

  private static void assertCaretInView(Text text) {
    Text.Box caret = text.caret() != null ? text.caret() : null;
    if (caret == null) {
      return; // blinked off
    }
    assertTrue(
        caret.x() >= 0 && caret.x() + caret.width() <= text.contentWidth(),
        "caret at " + caret.x() + " in " + text.contentWidth());
  }

  // Order of what is drawn

  @Test
  void theSceneDrawsTheSelectionBehindTheTextAndTheCaretInFront() {
    App<Model> app = start("hello");
    focus(app);
    key(app, "ArrowLeft", Key.SHIFT);
    var scene = app.scene();
    List<Integer> kinds = new java.util.ArrayList<>();
    for (int i = 0; i < scene.count(); i++) {
      kinds.add(scene.getInt(i, com.rethinkj.ui.render.Scene.KIND));
    }
    int firstGlyph = kinds.indexOf(com.rethinkj.ui.render.Scene.GLYPH);
    int lastGlyph = kinds.lastIndexOf(com.rethinkj.ui.render.Scene.GLYPH);
    assertEquals(com.rethinkj.ui.render.Scene.RECT, kinds.get(firstGlyph - 1), "the selection");
    assertEquals(com.rethinkj.ui.render.Scene.RECT, kinds.get(lastGlyph + 1), "the caret");
    assertEquals(1, scene.get(lastGlyph + 1, com.rethinkj.ui.render.Scene.WIDTH));
    assertEquals(lastGlyph + 3, kinds.size(), "then only the focus ring");
  }

  private static Text text(App<?> app) {
    return field(app).text();
  }
}
