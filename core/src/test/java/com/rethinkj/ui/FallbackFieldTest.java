package com.rethinkj.ui;

import static com.rethinkj.ui.Fields.field;
import static com.rethinkj.ui.Fields.focus;
import static com.rethinkj.ui.Fields.key;
import static com.rethinkj.ui.Fields.show;
import static com.rethinkj.ui.Fields.start;
import static com.rethinkj.ui.Fields.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.Fields.Model;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.GlyphRun;
import com.rethinkj.ui.text.TextLibraries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A text field whose string needs fallback fonts: carets at every grapheme boundary across font
 * borders, typing, deleting, selecting and pressing there, and right-to-left text that is pasted.
 */
class FallbackFieldTest {

  private static final float CONTENT = 14; // see Fields#start

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  private static App<Model> start(String value) {
    return Fields.start(value, f -> f.font(Examples.fallbackChain()).w(160), 1);
  }

  private static Text text(App<?> app) {
    return field(app).text();
  }

  @Test
  void caretsStandAtEveryGraphemeBoundaryAcrossFonts() {
    // Inter, JetBrains Mono's box drawing, Inter, Thai with a tone mark over its second letter.
    App<Model> app = start("a├b ไม้");
    Text text = text(app);
    GlyphRun run = text.lines().getFirst().glyphs();
    assertSame(Font.mono(), run.font(1));
    assertSame(Examples.thai(), run.font(run.count() - 1));
    assertEquals(0, text.x(0));
    for (int i = 1; i <= 4; i++) {
      assertEquals(run.pen(i), text.x(i), "position " + i);
    }
    assertEquals(run.pen(5), text.x(5), "between ไ and ม");
    assertEquals(run.width(), text.x(7), "the end");

    focus(app);
    key(app, "End");
    key(app, "ArrowLeft");
    assertEquals("a├b ไ|ม้", show(app), "over the letter and its mark at once");
    assertEquals(text(app).x(5), text(app).caret().x());
    key(app, "Home");
    key(app, "ArrowRight");
    key(app, "ArrowRight", Key.SHIFT);
    Text.Box selected = text(app).selection().getFirst();
    assertEquals(run.pen(1), selected.x(), "from the end of Inter's a");
    assertEquals(run.pen(2) - run.pen(1), selected.width(), "over JetBrains Mono's ├");
  }

  @Test
  void typingAndDeletingAcrossFontBorders() {
    App<Model> app = start("");
    focus(app);
    type(app, "id ├ ไม้");
    assertEquals("id ├ ไม้|", show(app));
    key(app, "Backspace");
    assertEquals("id ├ ไ|", show(app), "a letter and its mark go together");
    key(app, "ArrowLeft");
    key(app, "ArrowLeft");
    key(app, "Backspace");
    assertEquals("id | ไ", show(app));
    type(app, "∈");
    assertEquals("id ∈| ไ", show(app));
    GlyphRun run = text(app).lines().getFirst().glyphs();
    assertSame(Font.mono(), run.font(3));
    assertEquals(run.pen(4), text(app).caret().x());
  }

  @Test
  void aPressFindsTheBoundaryNearestToItInAnyFont() {
    App<Model> app = start("a├├b");
    Text text = text(app);
    float left = text.x(2);
    float right = text.x(3);
    app.pointerDown(CONTENT + (left * 3 + right) / 4, 22);
    app.pointerUp(CONTENT + (left * 3 + right) / 4, 22);
    app.frame();
    assertEquals("a├|├b", show(app));
    app.pointerDown(CONTENT + (left + right * 3) / 4, 22);
    app.pointerUp(CONTENT + (left + right * 3) / 4, 22);
    app.frame();
    assertEquals("a├├|b", show(app));
  }

  /**
   * Hebrew pasted into a field: shaped right to left, as one cluster. The caret moves through it
   * from its left edge in proportion to its letters, never outside the run, and nothing fails.
   */
  @Test
  void pastedRightToLeftTextKeepsTheCaretInsideIt() {
    App<Model> app = start("ab ");
    focus(app);
    key(app, "End");
    app.clipboard().write("שלום");
    key(app, "V", Key.CONTROL);
    assertEquals("ab שלום|", show(app));
    Text text = text(app);
    float start = text.x(3);
    float end = text.x(7);
    assertTrue(start < end);
    float previous = end;
    for (int i = 6; i >= 3; i--) {
      key(app, "ArrowLeft");
      float x = text(app).caret().x();
      assertTrue(x >= start && x < previous, "position " + i + " at " + x);
      previous = x;
    }
    key(app, "Backspace");
    assertEquals("ab|שלום", show(app));
    key(app, "End");
    key(app, "Backspace");
    assertEquals("abשלו|", show(app));
    assertNotNull(text(app).caret());
  }
}
