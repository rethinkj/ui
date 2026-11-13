package com.rethinkj.ui;

import static com.rethinkj.ui.Fields.field;
import static com.rethinkj.ui.Fields.focus;
import static com.rethinkj.ui.Fields.key;
import static com.rethinkj.ui.Fields.show;
import static com.rethinkj.ui.Fields.type;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.Fields.Model;
import com.rethinkj.ui.layout.Flex;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.GlyphRun;
import com.rethinkj.ui.text.TextLibraries;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Colours and fonts of ranges ({@link El#highlight}): they reach the glyphs, change no position
 * unless a font changes, follow wraps, tabs and fallback fonts, and a multi-line field asks the
 * highlighter only about the lines it needs, keeping line states across edits. {@link Areas} makes
 * the field: five lines of 18 pixels of JetBrains Mono, whose characters advance 8 pixels.
 */
class HighlightTest {

  static final int RED = 0xFFCC0000;
  static final int GREY = 0xFF808080;
  static final int TEXT = 0xFF000000;

  /** Digits red, and block comments grey, which carry on to the next line in the state. */
  static final Highlighter COMMENTS = (line, state, out) -> {
    boolean inside = state == Boolean.TRUE;
    int start = 0;
    for (int i = 0; i < line.length(); i++) {
      if (inside) {
        if (line.startsWith("*/", i)) {
          out.color(start, i + 2, GREY);
          inside = false;
          i++;
        }
      } else if (line.startsWith("/*", i)) {
        inside = true;
        start = i;
        i++;
      } else if (Character.isDigit(line.charAt(i))) {
        out.color(i, i + 1, RED);
      }
    }
    if (inside) {
      out.color(start, line.length(), GREY);
    }
    return inside ? Boolean.TRUE : null;
  };

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  private static App<Model> area(String value, UnaryOperator<El> field) {
    App<Model> app = Areas.start(value, field, 1);
    focus(app);
    return app;
  }

  private static Lines lines(App<?> app) {
    return field(app).editor().lineIndex();
  }

  /** The colour of each glyph of the rows laid out, row by row. */
  private static List<int[]> colours(App<?> app) {
    List<int[]> rows = new ArrayList<>();
    for (Text.Line line : field(app).text().lines()) {
      int[] c = line.colors();
      if (c == null) {
        c = new int[line.glyphs().count()];
        java.util.Arrays.fill(c, TEXT);
      }
      rows.add(c);
    }
    return rows;
  }

  @Test
  void coloursReachTheGlyphsAndMoveNothing() {
    App<Model> plain = area("ab 12\ncd", f -> f);
    App<Model> coloured = area("ab 12\ncd", f -> f.highlight("digits", COMMENTS));
    Text p = field(plain).text();
    Text c = field(coloured).text();
    assertArrayEquals(new int[] {TEXT, TEXT, TEXT, RED, RED}, colours(coloured).get(0));
    assertNull(c.lines().get(1).colors(), "a line without ranges has the text's colour");
    for (int i = 0; i <= 8; i++) {
      assertEquals(p.x(i), c.x(i), "the caret at " + i);
    }
    assertEquals(p.lines().get(0).glyphs().width(), c.lines().get(0).glyphs().width());
    key(coloured, "End", Key.SHIFT);
    assertEquals("^ab 12|\ncd", show(coloured));
    assertEquals(1, field(coloured).text().selection().size());
  }

  @Test
  void aBoldRangeIsShapedOnItsOwnAndCaretsFollowIt() {
    Highlighter bold = (line, state, out) -> {
      out.font(0, 6, Font.monoBold()).color(0, 6, RED);
      return null;
    };
    App<Model> app = Areas.start("SELECT x", f -> f.font(Font.sans()).highlight(1, bold), 1);
    App<Model> plain = Areas.start("SELECT x", f -> f.font(Font.sans()), 1);
    Text t = field(app).text();
    GlyphRun boldWord = Font.monoBold().shape("SELECT", 14, 1);
    assertEquals(boldWord.width(), t.x(6), "the bold word in the bold font's advances");
    assertTrue(t.x(6) != field(plain).text().x(6), "and not the regular font's");
    float space = Font.sans().shape(" ", 14, 1).width();
    assertEquals(boldWord.width() + space, t.x(7), 0.01, "the rest follows it");
    for (int i = 0; i <= 8; i++) {
      assertEquals(i, t.nearest(t.x(i), 4), "a press at the caret of " + i + " finds it");
    }
    assertEquals(Font.monoBold(), t.lines().getFirst().glyphs().font(0));
  }

  @Test
  void rangesFollowWrapsAndTabs() {
    // 29 characters of 8 pixels in a content box of 192: wraps before "ffff".
    App<Model> app = area(
        "aaaa bbbb cccc dddd eeee ffff",
        f -> f.wrapText().highlight(1, (line, state, out) -> {
          out.color(15, 29, RED); // "dddd eeee ffff", across the wrap
          return null;
        }));
    List<Text.Line> rows = field(app).text().lines();
    assertEquals(2, rows.size());
    int[] first = colours(app).get(0);
    assertEquals(RED, first[15]);
    assertEquals(TEXT, first[14]);
    assertArrayEquals(new int[] {RED, RED, RED, RED}, colours(app).get(1));
    App<Model> tabbed = area("a\tb1", f -> f.highlight(1, COMMENTS));
    assertArrayEquals(new int[] {TEXT, TEXT, TEXT, RED}, colours(tabbed).get(0));
    assertEquals(4 * 8 + 8, field(tabbed).text().x(3), "the tab still reaches the next stop");
  }

  @Test
  void fallbackFontsKeepTheirColours() {
    App<Model> app = Areas.start(
        "ab שלום 1",
        f -> f.font(Examples.fallbackChain()).highlight(1, (line, state, out) -> {
          out.color(3, 9, RED); // the Hebrew word and the digit
          return null;
        }),
        1);
    Text.Line line = field(app).text().lines().getFirst();
    GlyphRun run = line.glyphs();
    for (int g = 0; g < run.count(); g++) {
      assertEquals(run.cluster(g) >= 3 ? RED : TEXT, line.colors()[g], "glyph " + g);
    }
    assertTrue(run.font(run.count() - 3) != run.font(0), "the Hebrew comes from a fallback");
  }

  @Test
  void glyphsWithoutAColourTakeTheTextsNewColourWhenItMoves() {
    int white = 0xFFFFFFFF;
    int[] colour = {TEXT};
    App<Model> app = area(
        "a 1",
        f -> f.highlight(1, COMMENTS).color(colour[0]).motion(com.rethinkj.ui.motion.Spring.SNAP));
    app.post(() -> colour[0] = white); // as a switch of the theme
    app.frame();
    app.advance(1);
    app.frame();
    assertArrayEquals(
        new int[] {white, white, RED},
        colours(app).get(0),
        "not the colour of the first frame of the motion");
  }

  @Test
  void otherTextIsOneLineForTheHighlighter() {
    El label = El.text("x 1\n2 y").highlight(1, COMMENTS).color(TEXT);
    El root = El.col().child(label);
    El.scaleText(root, 1);
    Flex.layout(root, 200, 100);
    List<Text.Line> lines = label.text().lines();
    assertArrayEquals(new int[] {TEXT, TEXT, RED}, lines.get(0).colors());
    assertArrayEquals(new int[] {RED, TEXT, TEXT}, lines.get(1).colors());
  }

  // Line states

  private static String numbered(int count) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < count; i++) {
      text.append("line ").append(i).append(i + 1 < count ? "\n" : "");
    }
    return text.toString();
  }

  @Test
  void anEditAsksAgainOnlyFromTheEditToTheView() {
    App<Model> app = area(numbered(2000), f -> f.highlight("c", COMMENTS));
    Lines ls = lines(app);
    int first = ls.highlighted;
    assertTrue(first > 0 && first <= 12, "the lines in view and three below: " + first);
    type(app, "/*");
    int afterOpen = ls.highlighted - first;
    assertTrue(afterOpen <= 12, "the lines from the edit to the view, not 2000: " + afterOpen);
    for (int[] row : colours(app)) {
      for (int c : row) {
        assertEquals(GREY, c, "everything in view is inside the comment now");
      }
    }
    int before = ls.highlighted;
    key(app, "Backspace");
    key(app, "Backspace");
    assertEquals("|line 0\nline 1", show(app).substring(0, 14));
    assertTrue(ls.highlighted - before <= 24, "again only the lines in view");
    assertEquals(RED, colours(app).get(0)[5], "the digit is red again");
  }

  @Test
  void theEndOfALongTextCostsOnePassOnce() {
    App<Model> app = area(numbered(2000), f -> f.highlight("c", COMMENTS));
    Lines ls = lines(app);
    int start = ls.highlighted;
    key(app, "End", Key.CONTROL);
    int pass = ls.highlighted - start;
    assertTrue(pass >= 1990 && pass <= 2010, "every line before the end once: " + pass);
    key(app, "Home", Key.CONTROL);
    int back = ls.highlighted;
    key(app, "End", Key.CONTROL);
    assertEquals(back, ls.highlighted, "the states and the lines at both ends are known");
  }

  @Test
  void anotherKeyAsksAgainAndTheSameKeyDoesNot() {
    Fields.Model model = new Fields.Model();
    model.value = numbered(50);
    Object[] key = {"a"};
    App<Model> app = Areas.start(model.value, f -> f.highlight(key[0], COMMENTS), 1);
    focus(app);
    Lines ls = lines(app);
    int first = ls.highlighted;
    app.focus(null);
    app.frame(); // a rebuild with the same key
    assertEquals(first, ls.highlighted);
    key[0] = "b";
    app.focus(field(app));
    app.frame();
    assertEquals(2 * first, ls.highlighted, "another key: the lines in view again");
  }

  // Backgrounds and underlines

  private static final int YELLOW = 0xFFFFEE00;
  private static final int BLUE = 0xFF0000FF;

  /** Line 1's background, a background on 2 to 5 of line 0 and a line under 0 to 3 of line 2. */
  private static final Decorator MARKS = (line, start, text, out) -> {
    if (line == 1) {
      out.lineBackground(YELLOW);
    } else if (line == 0) {
      out.background(2, 5, BLUE);
    } else if (line == 2) {
      out.underline(0, 3, RED).color(0, 3, RED); // the colour is a highlighter's: ignored
    }
  };

  @Test
  void aDecoratorMarksLinesRangesAndUnderlines() {
    App<Model> app = area("abcdef\nxy\nzzzz", f -> f.decorate(MARKS));
    Text t = field(app).text();
    List<Text.Mark> marks = t.marks();
    assertEquals(
        List.of(
            new Text.Mark(new Text.Box(0, 18, 192, 18), YELLOW),
            new Text.Mark(new Text.Box(16, 0, 24, 18), BLUE),
            new Text.Mark(new Text.Box(0, 36 + under(t), 24, 1), RED)),
        marks,
        "the line first, then the range, then the underline");
    assertNull(t.lines().get(2).colors(), "a decorator's colours are ignored");
  }

  private static float under(Text t) {
    return Math.round((t.font().ascent() + t.font().lineGap() / 2) * 14)
        + Math.round(t.font().descent() * 14 / 2);
  }

  @Test
  void marksCoverEachRowOfAWrappedLineAndScrollWithTheText() {
    App<Model> app = area(
        "aaaa bbbb cccc dddd eeee ffff\nx\ny\nz\nw\nv",
        f -> f.wrapText().decorate((line, start, text, out) -> {
          if (line == 0) {
            out.lineBackground(YELLOW).background(15, 29, BLUE);
          }
        }));
    List<Text.Mark> marks = field(app).text().marks();
    assertEquals(4, marks.size(), "a line background and a range per row: " + marks);
    assertEquals(new Text.Box(0, 18, 192, 18), marks.get(1).box());
    assertEquals(
        new Text.Box(15 * 8, 0, 10 * 8, 18),
        marks.get(2).box(),
        "to the end of row 0, with the space it wraps at");
    assertEquals(new Text.Box(0, 18, 4 * 8, 18), marks.get(3).box(), "ffff on row 1");
    app.wheel(50, 30, 0, 18);
    app.frame();
    assertEquals(
        new Text.Box(0, -18, 192, 18),
        field(app).text().marks().getFirst().box(),
        "a row up with the text");
  }

  @Test
  void marksLieUnderTheSelection() {
    App<Model> app = area("abcdef\nxy\nz", f -> f.decorate(MARKS).selectionColor(0x80FF0000));
    key(app, "ArrowDown", Key.SHIFT);
    key(app, "ArrowDown", Key.SHIFT);
    // Line 1 at y 18 of the content box, which starts at 14,14, selected to the edge: its
    // background, and the selection half red over it.
    int pixel = app.render().pixels().argb(14 + 100, 14 + 18 + 9);
    assertEquals(
        0xFFFFAE00, pixel, "half red over yellow, in linear light: " + Integer.toHexString(pixel));
  }

  @Test
  void aHighlightersBackgroundsAreKeptWithItsColours() {
    App<Model> app = area(
        "ab\ncd",
        f -> f.highlight(1, (line, state, out) -> {
          out.background(0, 1, BLUE).lineBackground(YELLOW);
          return null;
        }));
    assertEquals(4, field(app).text().marks().size());
  }
}
