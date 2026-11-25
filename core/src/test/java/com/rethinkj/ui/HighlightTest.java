package com.rethinkj.ui;

import static com.rethinkj.ui.Fields.field;
import static com.rethinkj.ui.Fields.focus;
import static com.rethinkj.ui.Fields.key;
import static com.rethinkj.ui.Fields.show;
import static com.rethinkj.ui.Fields.type;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.Fields.Model;
import com.rethinkj.ui.layout.Flex;
import com.rethinkj.ui.render.Scene;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.GlyphRun;
import com.rethinkj.ui.text.TextLibraries;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Colors and fonts of ranges ({@link El#highlight}): they reach the glyphs, change no position
 * unless a font changes, follow wraps, tabs and fallback fonts, and a multi-line field asks the
 * highlighter only about the lines it needs, keeping line states across edits. {@link Areas} makes
 * the field: five lines of 18 pixels of JetBrains Mono, whose characters advance 8 pixels.
 */
class HighlightTest {

  static final int RED = 0xFFCC0000;
  static final int GRAY = 0xFF808080;
  static final int TEXT = 0xFF000000;

  /** Digits red, and block comments gray, which carry on to the next line in the state. */
  static final Highlighter COMMENTS = (line, state, out) -> {
    boolean inside = state == Boolean.TRUE;
    int start = 0;
    for (int i = 0; i < line.length(); i++) {
      if (inside) {
        if (line.startsWith("*/", i)) {
          out.color(start, i + 2, GRAY);
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
      out.color(start, line.length(), GRAY);
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

  /** The color of each glyph of the rows laid out, row by row. */
  private static List<int[]> colors(App<?> app) {
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
  void colorsReachTheGlyphsAndMoveNothing() {
    App<Model> plain = area("ab 12\ncd", f -> f);
    App<Model> colored = area("ab 12\ncd", f -> f.highlight("digits", COMMENTS));
    Text p = field(plain).text();
    Text c = field(colored).text();
    assertArrayEquals(new int[] {TEXT, TEXT, TEXT, RED, RED}, colors(colored).get(0));
    assertNull(c.lines().get(1).colors(), "a line without ranges has the text's color");
    for (int i = 0; i <= 8; i++) {
      assertEquals(p.x(i), c.x(i), "the caret at " + i);
    }
    assertEquals(p.lines().get(0).glyphs().width(), c.lines().get(0).glyphs().width());
    key(colored, "End", Key.SHIFT);
    assertEquals("^ab 12|\ncd", show(colored));
    assertEquals(1, field(colored).text().selection().size());
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
    assertNotEquals(field(plain).text().x(6), t.x(6), "and not the regular font's");
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
    int[] first = colors(app).get(0);
    assertEquals(RED, first[15]);
    assertEquals(TEXT, first[14]);
    assertArrayEquals(new int[] {RED, RED, RED, RED}, colors(app).get(1));
    App<Model> tabbed = area("a\tb1", f -> f.highlight(1, COMMENTS));
    assertArrayEquals(new int[] {TEXT, TEXT, TEXT, RED}, colors(tabbed).get(0));
    assertEquals(4 * 8 + 8, field(tabbed).text().x(3), "the tab still reaches the next stop");
  }

  @Test
  void fallbackFontsKeepTheirColors() {
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
    assertNotSame(run.font(0), run.font(run.count() - 3), "the Hebrew comes from a fallback");
  }

  @Test
  void glyphsWithoutAColorTakeTheTextsNewColorWhenItMoves() {
    int white = 0xFFFFFFFF;
    int[] color = {TEXT};
    App<Model> app = area(
        "a 1",
        f -> f.highlight(1, COMMENTS).color(color[0]).motion(com.rethinkj.ui.motion.Spring.SNAP));
    app.post(() -> color[0] = white); // as a switch of the theme
    app.frame();
    app.advance(1);
    app.frame();
    assertArrayEquals(
        new int[] {white, white, RED},
        colors(app).get(0),
        "not the color of the first frame of the motion");
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
    for (int[] row : colors(app)) {
      for (int c : row) {
        assertEquals(GRAY, c, "everything in view is inside the comment now");
      }
    }
    int before = ls.highlighted;
    key(app, "Backspace");
    key(app, "Backspace");
    assertEquals("|line 0\nline 1", show(app).substring(0, 14));
    assertTrue(ls.highlighted - before <= 24, "again only the lines in view");
    assertEquals(RED, colors(app).get(0)[5], "the digit is red again");
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

  /** Marks the brackets outside strings in single quotes, which end with their line. */
  static final class Quotes implements Highlighter {
    int asked;

    @Override
    public Object line(String line, Object state, Spans out) {
      asked++;
      boolean quoted = false;
      for (int i = 0; i < line.length(); i++) {
        char c = line.charAt(i);
        quoted ^= c == '\'';
        if (!quoted && "()".indexOf(c) >= 0) {
          out.bracket(i);
        }
      }
      return null;
    }

    @Override
    public boolean marksBrackets() {
      return true;
    }
  }

  @Test
  void bracketsPairAsTheHighlighterMarksThemFromTheFieldsLines() {
    String text = numbered(2000) + "\nf(')')";
    int at = text.length() - 5; // the line's (
    Quotes quotes = new Quotes();
    FieldState state = new FieldState();
    state.select(at + 1, at + 1); // opened at the end
    int[][] pair = {null};
    App<Model> app = area(text, f -> {
      pair[0] = f.fieldState(state).highlight("q", quotes).matchingBracket(at + 1);
      return f;
    });
    assertTrue(quotes.asked <= 2001 + 12, "the lines before the end once, by layout");
    assertArrayEquals(new int[] {at, at + 4}, pair[0], "not the one in the string");
    key(app, "End", Key.CONTROL);
    int asked = quotes.asked;
    type(app, "x");
    assertArrayEquals(new int[] {at, at + 4}, pair[0]);
    assertTrue(quotes.asked - asked <= 12, "the field's lines, not the text's");
    El again = El.text(text + "x").editable(edit -> {}).multiline().fieldState(state);
    assertArrayEquals(
        new int[0], again.highlight("q", quotes).matchingBracket(at + 3), "in the string");
    assertArrayEquals(
        new int[] {at + 2, at},
        again.highlight("c", COMMENTS).matchingBracket(at + 3),
        "a highlighter that marks none: every bracket");
  }

  @Test
  void jumpingFarHighlightsWithinOneFramesBudgetAndCatchesUpWithoutInput() {
    // Line 0 opens a block comment that runs through all 80,000 lines after it, so the lines at
    // the end only turn gray once the state has actually propagated from line 0.
    String text = "/*\n" + numbered(80_000);
    int lastLine = 80_000;
    int[] asked = {0};
    Highlighter counting = (line, state, out) -> {
      asked[0]++;
      return COMMENTS.line(line, state, out);
    };
    App<Model> app = area(text, f -> f.highlight("c", counting));
    Lines ls = lines(app);
    assertFalse(ls.knows(lastLine, "c"), "far lines start unknown");
    int beforeJump = asked[0];

    app.keyDown(new Key("End", Key.CONTROL, false));
    app.keyUp(new Key("End", Key.CONTROL, false));
    boolean drawn = app.frame();
    assertEquals(app.time(), app.nextChange(), "asks for the next frame while catching up");
    int jumpCalls = asked[0] - beforeJump;
    assertTrue(drawn, "the jumped view is drawn in the same frame");
    assertEquals(lastLine, field(app).text().lastLineInView(), "the view reached line 80,000");
    assertTrue(
        jumpCalls > 0 && jumpCalls <= Lines.HIGHLIGHT_BUDGET,
        "at most one frame's budget: " + jumpCalls);
    assertFalse(ls.knows(lastLine, "c"), "states have not reached line 80,000 yet");
    for (Text.Line row : field(app).text().lines()) {
      assertNull(row.colors(), "unreached lines draw in the plain text color");
    }

    int frames = 0;
    boolean caughtUpDrawn = false;
    while (app.nextChange() == app.time()) {
      int prev = asked[0];
      caughtUpDrawn |= app.frame();
      int delta = asked[0] - prev;
      assertTrue(delta <= Lines.HIGHLIGHT_BUDGET, "frame " + frames + " exceeded budget: " + delta);
      frames++;
      assertTrue(frames < 100, "catching up finishes");
    }
    assertTrue(frames > 0, "took following frames without input");
    assertTrue(caughtUpDrawn, "the catch-up drew the colored lines");
    assertTrue(ls.knows(lastLine, "c"), "states reached the view");
    assertTrue(app.nextChange() > app.time(), "no longer asks for an immediate frame");
    for (int[] row : colors(app)) {
      for (int c : row) {
        assertEquals(GRAY, c, "every line in view is gray from line 0's block comment");
      }
    }
    assertEquals(asked[0], ls.highlighted);
  }

  @Test
  void bracketsStayCorrectWhileStatesAreMissingOrBudgetRunsOut() {
    int count = Lines.HIGHLIGHT_BUDGET * 2 + 100;
    String text = numbered(count) + "\nf(')')";
    int at = text.length() - 5; // the line's (
    Quotes quotes = new Quotes();
    FieldState state = new FieldState();
    state.select(at + 1, at + 1);
    int[][] pair = {null};
    App<Model> app = area(text, f -> {
      pair[0] = f.fieldState(state).highlight("q", quotes).matchingBracket(at + 1);
      return f;
    });
    Lines ls = lines(app);
    assertFalse(ls.knows(count, "q"), "the end line is still unknown after the first frame");
    assertArrayEquals(
        new int[] {at, at + 2}, pair[0], "plain matching while the line's state is unknown");
    while (app.nextChange() == app.time()) {
      app.frame();
    }
    assertTrue(ls.knows(count, "q"), "known once caught up");
    assertArrayEquals(
        new int[] {at, at + 4}, pair[0], "the view built again once caught up: not the string's");

    // When the bracket's own line is known, scanning forward across more lines than the budget
    // stops instead of treating unhighlighted lines as having no brackets.
    StringBuilder nested = new StringBuilder("(\n");
    for (int i = 0; i < Lines.HIGHLIGHT_BUDGET + 5; i++) {
      nested.append("a\n");
    }
    nested.append("(\n)\n)");
    String far = nested.toString();
    Lines.Budget budget = new Lines.Budget();
    Lines farLines = Lines.of(far);
    farLines.budget(budget);
    Quotes farQuotes = new Quotes();
    farLines.spans(0, far, farQuotes, "q"); // line 0 known (1 call)
    assertArrayEquals(
        new int[] {0},
        Brackets.matching(far, 1, farLines, farQuotes, "q"),
        "stops at the budget instead of pairing with the wrong closing bracket");
    assertEquals(Lines.HIGHLIGHT_BUDGET, farQuotes.asked, "never exceeds the budget");
    assertFalse(farLines.knows(Lines.HIGHLIGHT_BUDGET + 5, "q"), "unreached lines stay unknown");
    assertTrue(farLines.unreadKnown(), "a build goes on from there with the next budget");
    // When the budget is already 0 on a known line without cached spans, fall back to plain.
    Lines emptyBudgetLines = Lines.of("f(')')");
    emptyBudgetLines.budget(budget); // 0 remaining
    emptyBudgetLines.spans(
        0, "f(')')", farQuotes, "q"); // initializes key with known = 1, returns null
    assertArrayEquals(
        new int[] {1, 3},
        Brackets.matching("f(')')", 2, emptyBudgetLines, farQuotes, "q"),
        "falls back to plain matching when the first line's spans cannot be computed");
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
      out.underline(0, 3, RED).color(0, 3, RED); // the color is a highlighter's: ignored
    }
  };

  @Test
  void aDecoratorMarksLinesRangesAndUnderlines() {
    App<Model> app = area(
        "abcdef\nxy\nzzzz",
        f -> f.decorate((line, start, text, out) -> {
          MARKS.line(line, start, text, out);
          if (line == 1) {
            out.lineBackground(0x400000FF); // over the yellow, as the caret's line over a block
          }
        }));
    Text t = field(app).text();
    List<Text.Mark> marks = t.marks();
    assertEquals(
        List.of(
            new Text.Mark(new Text.Box(0, 18, 192, 18), YELLOW),
            new Text.Mark(new Text.Box(0, 18, 192, 18), 0x400000FF),
            new Text.Mark(new Text.Box(16, 0, 24, 18), BLUE),
            new Text.Mark(new Text.Box(0, 36 + under(t), 24, 1), RED)),
        marks,
        "the lines first, from the bottom up, then the range, then the underline");
    assertNull(t.lines().get(2).colors(), "a decorator's colors are ignored");
  }

  @Test
  void theDumpNamesTheUnderlinesJoinedOnlyAcrossALineFeed() {
    App<Model> app = area(
        "a b\ncd\nef",
        f -> f.decorate((line, start, text, out) -> {
          if (line == 0) {
            out.underline(0, 1, RED).underline(2, 3, RED);
          } else {
            out.underline(0, 2, RED);
          }
        }));
    assertTrue(app.tree().dump().contains(" underline 0-1,2-9"), app.tree().dump());
  }

  @Test
  void squigglesFollowWrapsAndJoinAcrossLineFeedsInTheDump() {
    Spans spans = new Spans(10).squiggle(1, 5, RED);
    assertEquals("squiggle 1-5 FFCC0000\n", spans.toString());
    App<Model> app = area(
        "aaaa bbbb cccc dddd eeee ffff\ncd\nef",
        f -> f.wrapText().decorate((line, start, text, out) -> {
          if (line == 0) {
            out.squiggle(15, 29, RED); // across the wrap onto row 1, touching line 1 across \n
          } else {
            out.squiggle(0, 2, RED);
          }
        }));
    List<Text.Mark> marks = field(app).text().marks();
    assertEquals(4, marks.size(), "two wrapped rows on line 0, plus lines 1 and 2: " + marks);
    for (Text.Mark mark : marks) {
      assertTrue(mark.squiggle(), "every mark is a squiggle: " + mark);
    }
    assertEquals(15 * 8, marks.get(0).box().x());
    assertEquals(0, marks.get(1).box().x(), "continues at the start of the wrapped row");
    assertTrue(app.tree().dump().contains(" squiggle 15-35"), app.tree().dump());
  }

  @Test
  void aDecoratorsSquiggleIsCutOutOfAHighlightersOne() {
    App<Model> app = area(
        "abcdef",
        f -> f.highlight(1, (line, state, out) -> {
              out.squiggle(0, 6, RED);
              return state;
            })
            .decorate((line, start, text, out) -> out.squiggle(2, 4, TEXT)));
    List<Text.Mark> marks = field(app).text().marks();
    assertEquals(3, marks.size(), marks.toString());
    float[] xs = {0, 4 * 8, 2 * 8};
    int[] colors = {RED, RED, TEXT};
    for (int i = 0; i < 3; i++) {
      assertEquals(xs[i], marks.get(i).box().x());
      assertEquals(16, marks.get(i).box().width());
      assertEquals(colors[i], marks.get(i).color());
    }
  }

  @Test
  void aSquiggleOnTheLastRowStaysInsideTheField() {
    App<Model> app = area(
        "ab\ncd",
        f -> f.lines(2).decorate((line, start, text, out) -> {
          if (line == 1) {
            out.squiggle(0, 2, RED);
          }
        }));
    Scene scene = app.scene();
    int squiggles = 0;
    for (int i = 0; i < scene.count(); i++) {
      if (scene.getInt(i, Scene.KIND) == Scene.SQUIGGLE) {
        squiggles++;
        int clip = scene.clipOf(i);
        float bottom =
            scene.get(i, Scene.Y) + scene.get(i, Scene.AMPLITUDE) + scene.get(i, Scene.STROKE) / 2;
        assertTrue(
            bottom <= scene.clipGet(clip, Scene.Y) + scene.clipGet(clip, Scene.HEIGHT),
            "its lower lobes are not clipped: " + bottom);
      }
    }
    assertEquals(1, squiggles);
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
  void aBackgroundWithRoomMovesFollowingTextAndRoundsCorners() {
    assertEquals(
        "background 1-5 FF0000FF\n", new Spans(10).background(1, 5, BLUE).toString());
    assertEquals(
        "background 1-5 FF0000FF room 4 radius 3\n",
        new Spans(10).background(1, 5, BLUE, 4, 3).toString());
    assertThrows(IllegalArgumentException.class, () -> new Spans(10).background(1, 5, BLUE, -1, 0));
    assertThrows(
        IllegalArgumentException.class, () -> new Spans(10).background(1, 5, BLUE, Float.NaN, 0));
    assertThrows(IllegalArgumentException.class, () -> new Spans(10).background(1, 5, BLUE, 0, -1));
    assertThrows(
        IllegalArgumentException.class, () -> new Spans(10).background(1, 5, BLUE, 0, Float.NaN));

    // "ab cdef gh": "cdef" at 3..7 with room 4 and radius 3, and a range element over it.
    App<Model> app = area(
        "ab cdef gh",
        f -> f.highlight(1, (line, state, out) -> {
              out.background(3, 7, BLUE, 4, 3);
              return null;
            })
            .child(El.el().key("link").range(3, 7)));
    Text t = field(app).text();
    assertEquals(24, t.x(3), "outer left edge of the room");
    assertEquals(36, t.x(4), "first character sits after the left room");
    assertEquals(64, t.x(7), "outer right edge moves by 2 * room");
    assertEquals(88, t.x(10), "following text moves by 2 * room");
    for (int i = 0; i <= 10; i++) {
      assertEquals(i, t.nearest(t.x(i), 4), "caret hit testing at " + i);
    }
    assertEquals(3, t.nearest(31, 4), "a press on the left half of c lands before it");
    assertEquals(3, t.nearest(26, 4), "a press in the room lands at the range's edge");
    assertEquals(List.of(new Text.Mark(new Text.Box(24, 0, 40, 18), BLUE, false, 3)), t.marks());
    Scene scene = app.scene();
    boolean roundedMark = false;
    for (int i = 0; i < scene.count(); i++) {
      if (scene.getInt(i, Scene.KIND) == Scene.RECT
          && scene.get(i, Scene.WIDTH) == 40
          && scene.get(i, Scene.HEIGHT) == 18
          && scene.get(i, Scene.RADIUS) == 3) {
        roundedMark = true;
      }
    }
    assertTrue(roundedMark, "the mark's rectangle carries the corner radius to the scene");
    El link = field(app).children().getFirst();
    assertEquals(List.of(new Text.Box(0, 0, 40, 18)), link.rangeBoxes(), "range box covers room");
    app.select(field(app), 3, 7);
    app.frame();
    assertEquals(
        List.of(new Text.Box(24, 0, 40, 18)),
        field(app).text().selection(),
        "selection covers room");

    // Wrapped range: room only at the real start and end, and wrapping counts the added width.
    App<Model> wrapped = area(
        "aaaa bbbb cccc dddd eeee ffff",
        f -> f.wrapText().highlight(1, (line, state, out) -> {
          out.background(15, 29, BLUE, 4, 3);
          return null;
        }));
    List<Text.Mark> wrappedMarks = field(wrapped).text().marks();
    assertEquals(2, wrappedMarks.size(), wrappedMarks.toString());
    assertEquals(
        new Text.Mark(new Text.Box(15 * 8, 0, 5 * 8 + 4, 18), BLUE, false, 3),
        wrappedMarks.get(0),
        "first row gets only the start room, and wraps before eeee");
    assertEquals(
        new Text.Mark(new Text.Box(0, 18, 9 * 8 + 4, 18), BLUE, false, 3),
        wrappedMarks.get(1),
        "second row gets only the end room");

    // Non-field text uses the same room and radius.
    El label = El.text("ab cd ef")
        .font(Font.mono())
        .fontSize(14)
        .highlight(1, (line, state, out) -> {
          out.background(3, 5, BLUE, 4, 3);
          return null;
        });
    Headless.render(El.row().child(label), 200, 50, 1);
    assertEquals(8 * 8 + 8, label.width(), "width includes 2 * room");
    assertEquals(
        List.of(new Text.Mark(new Text.Box(24, 0, 24, 18), BLUE, false, 3)),
        label.text().marks());
  }
}
