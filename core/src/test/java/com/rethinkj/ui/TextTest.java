package com.rethinkj.ui;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.GlyphRun;
import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Text elements in layout, the tree dump and scripts. Skipped without FreeType and HarfBuzz, but
 * for what needs no shaping.
 *
 * <p>JetBrains Mono makes the numbers easy: at 14 pixels every character advances 8.4, rounded to
 * 8 at scale 1, and a line is (1.02 + 0.3) * 14 = 18.48, rounded to 18.
 */
class TextTest {

  @TempDir
  Path out;

  @Test
  void aTextElementInARowTakesItsShapedWidthAndPushesItsSibling() {
    TextLibraries.assumeLoaded();
    El label = El.text("Hello");
    El box = el().size(10, 10);
    Headless.render(row().children(label, box), 200, 50, 1);
    float width = Font.sans().shape("Hello", 14, 1).width();
    assertEquals(width, label.width());
    assertEquals(17, label.height(), "Inter at 14: (1984 + 494) / 2048 * 14 = 16.94");
    assertEquals(width, box.x());
    Text.Line line = label.text().lines().getFirst();
    assertEquals(1, label.text().lines().size());
    assertEquals(width, line.width());
    assertEquals(14, line.baseline(), "Inter's ascent at 14: 13.56");
    assertEquals(5, line.glyphs().count());
  }

  @Test
  void aWrappingTextBreaksAtWordsWhereItIsTooWideAndGrowsTaller() {
    TextLibraries.assumeLoaded();
    El text = mono("one two six ten").wrapText();
    El sibling = el().size(24, 10).shrink(0);
    // 80 pixels: 56 for the text, seven characters, and the sibling.
    Headless.render(row().w(80).children(text, sibling), 200, 100, 1);
    assertEquals(List.of("one two ", "six ten"), lines(text));
    assertEquals(56, text.width());
    assertEquals(36, text.height());
    assertEquals(56, sibling.x());
    assertEquals(56, text.text().lines().getFirst().width(), "the space at the break takes none");
    assertEquals(List.of(14f, 32f), baselines(text), "1.02 * 14 = 14.28");

    // Narrower than two words: one word a line, and the box keeps the width of the widest.
    El narrow = mono("one two six ten").wrapText();
    Headless.render(row().w(16).child(narrow), 200, 100, 1);
    assertEquals(List.of("one ", "two ", "six ", "ten"), lines(narrow));
    assertEquals(24, narrow.width());
    assertEquals(72, narrow.height());

    // Without wrapText it stays on one line, and is wider than its row.
    El wide = mono("one two six ten");
    Headless.render(row().w(80).child(wide), 200, 100, 1);
    assertEquals(List.of("one two six ten"), lines(wide));
    assertEquals(120, wide.width());
    assertEquals(18, wide.height());
  }

  @Test
  void aLineFeedAlwaysBreaks() {
    TextLibraries.assumeLoaded();
    El text = mono("one\ntwo three\n");
    Headless.render(row().child(text), 200, 100, 1);
    assertEquals(List.of("one", "two three", ""), lines(text));
    assertEquals(72, text.width());
    assertEquals(54, text.height());

    El wrapped = mono("one\ntwo three").wrapText();
    Headless.render(row().w(56).child(wrapped), 200, 100, 1);
    assertEquals(List.of("one", "two ", "three"), lines(wrapped));
    assertEquals(54, wrapped.height());
  }

  @Test
  void noLineBreaksInsideARightToLeftRun() {
    TextLibraries.assumeLoaded();
    // The Hebrew with the spaces between its words is one run and one cluster, wider than 30.
    El text =
        El.text("ab אב גד הו cd").font(Examples.fallbackChain()).wrapText().w(30);
    Headless.render(row().child(text), 200, 100, 1);
    List<Text.Line> lines = text.text().lines();
    assertEquals(3, lines.size(), "ab, the Hebrew whole, cd");
    assertTrue(lines.get(1).width() > 30, "wider than its box: " + lines.get(1).width());

    El alone = El.text("אב גד הו").font(Examples.fallbackChain()).wrapText().w(30);
    Headless.render(row().child(alone), 200, 100, 1);
    assertEquals(1, alone.text().lines().size(), "no empty row after it");
  }

  static final String PATH = "Cannot read the connections in"
      + " /a/very/long/path/without/any/space/connections.json: it is not valid JSON.";

  @Test
  void aPieceWiderThanTheBoxBreaksInsideItAtEveryScale() {
    TextLibraries.assumeLoaded();
    for (float scale : new float[] {1, 1.5f, 2}) {
      El text = El.text(PATH).wrapText().w(228);
      Headless.render(El.col().child(text), 400, 300, scale, TextRendering.LINUX);
      List<String> lines = lines(text);
      assertBrokenWithin(text, 228, scale);
      assertEquals("Cannot read the connections in ", lines.getFirst(), "a word break first");
      assertTrue(
          lines.stream().anyMatch(l -> l.startsWith("/a/very/")),
          "the path starts a row of its own: " + lines);
      assertTrue(
          lines.stream()
              .anyMatch(l -> !l.isEmpty() && Character.isLetter(l.charAt(0)) && l.indexOf(' ') < 0),
          "broken inside the path: " + lines);
      assertTrue(text.width() <= 228, "the box keeps its width: " + text.width());
    }
  }

  @Test
  void aLongWordAfterShortWordsStartsARowOfItsOwnAndBreaksAsLateAsItFits() {
    TextLibraries.assumeLoaded();
    El text = mono("one two " + "x".repeat(20) + " end").wrapText().w(80);
    Headless.render(row().child(text), 200, 200, 1);
    assertEquals(List.of("one two ", "x".repeat(10), "x".repeat(10) + " ", "end"), lines(text));

    El alone = mono("x".repeat(25)).wrapText().w(80);
    Headless.render(row().child(alone), 200, 200, 1);
    assertEquals(List.of("x".repeat(10), "x".repeat(10), "x".repeat(5)), lines(alone));

    // What follows the end of a broken word shares its row while it fits.
    El tail = mono("x".repeat(13) + " ab cd").wrapText().w(80);
    Headless.render(row().child(tail), 200, 200, 1);
    assertEquals(List.of("x".repeat(10), "xxx ab cd"), lines(tail));
  }

  @Test
  void theMinContentWidthIsStillTheLongestPieceWhole() {
    TextLibraries.assumeLoaded();
    El text = mono("one " + "x".repeat(20) + " end").wrapText();
    El sibling = el().size(24, 10).shrink(0);
    Headless.render(row().w(80).children(text, sibling), 200, 200, 1);
    assertEquals(160, text.width(), "a row gives it room for the whole word");
    assertEquals(List.of("one ", "x".repeat(20) + " ", "end"), lines(text));
    Measure.Size min =
        text.text().measure(Float.NaN, Float.NaN, Measure.MIN_CONTENT, Float.POSITIVE_INFINITY);
    assertEquals(160, min.width());
    assertEquals(54, min.height());
  }

  @Test
  void aBreakInsideAWordKeepsGraphemesWhole() {
    TextLibraries.assumeLoaded();
    String marks = "e\u0301a\u0308".repeat(12); // e and a with combining marks
    String emoji = "\uD83D\uDC69\u200D\uD83D\uDCBB\uD83C\uDDE9\uD83C\uDDEA".repeat(8);
    String hangul = "\u1100\u1161\u11A8".repeat(10); // a syllable of three jamo
    for (String word : List.of(marks, emoji, hangul, "ab " + marks + emoji)) {
      for (float scale : new float[] {1, 1.5f, 2}) {
        El text = El.text(word).font(Examples.fallbackChain()).wrapText().w(40);
        Headless.render(El.col().child(text), 200, 400, scale, TextRendering.LINUX);
        assertTrue(text.text().lines().size() > 1, "broken: " + lines(text));
        assertBrokenWithin(text, 40, scale);
      }
    }
  }

  @Test
  void aRightToLeftWordLongerThanTheLineStaysWholeAsItIsOneCluster() {
    TextLibraries.assumeLoaded();
    String hebrew = "\u05D0\u05D1\u05D2\u05D3\u05D4\u05D5\u05D6\u05D7".repeat(4);
    El text = El.text("ab " + hebrew + " cd")
        .font(Examples.fallbackChain())
        .wrapText()
        .w(30);
    Headless.render(row().child(text), 200, 100, 1);
    assertEquals(List.of("ab ", hebrew + " ", "cd"), lines(text));
    assertTrue(text.text().lines().get(1).width() > 30);
  }

  @Test
  void aBoxNarrowerThanOneClusterPutsEachOnARowOfItsOwn() {
    TextLibraries.assumeLoaded();
    El text = mono("abcd").wrapText().w(4);
    Headless.render(row().child(text), 200, 100, 1);
    assertEquals(List.of("a", "b", "c", "d"), lines(text));
  }

  @Test
  void highlightsTabsAndFallbackFontsGoOnAcrossABreakInsideAWord() {
    TextLibraries.assumeLoaded();
    int red = 0xFFFF0000;
    // A mono word of 16 characters, the last 8 red: the second row is red, the first is not.
    El text = mono("a".repeat(8) + "b".repeat(8))
        .color(0xFF000000)
        .highlight(1, (line, state, out) -> {
          out.color(8, 16, red);
          return null;
        })
        .wrapText()
        .w(64);
    Headless.render(row().child(text), 200, 100, 1);
    assertEquals(List.of("aaaaaaaa", "bbbbbbbb"), lines(text));
    for (int c : text.text().lines().get(1).colors()) {
      assertEquals(red, c);
    }
    for (int c : text.text().lines().get(0).colors()) {
      assertEquals(0xFF000000, c);
    }
    // A range in another font breaks by its own glyphs.
    El fonts = mono("a".repeat(8) + "b".repeat(8))
        .highlight(2, (line, state, out) -> {
          out.font(4, 12, Font.sans());
          return null;
        })
        .wrapText()
        .w(48);
    Headless.render(row().child(fonts), 200, 100, 1);
    assertBrokenWithin(fonts, 48, 1);
    assertTrue(lines(fonts).size() >= 3, lines(fonts).toString());
  }

  /**
   * Every line of {@code el} no wider than its box, unless it is a single cluster, every break at
   * a grapheme boundary, and the lines together the string.
   */
  private static void assertBrokenWithin(El el, float box, float scale) {
    String string = el.text().string();
    List<String> lines = lines(el);
    assertEquals(string, String.join("", lines), "the lines are the string");
    java.text.BreakIterator chars = java.text.BreakIterator.getCharacterInstance();
    chars.setText(string);
    int at = 0;
    for (int i = 0; i < lines.size(); i++) {
      assertTrue(chars.isBoundary(at), "a row starts between graphemes: " + at + " " + lines);
      Text.Line line = el.text().lines().get(i);
      boolean oneCluster = line.glyphs().count() > 0
          && line.glyphs().cluster(line.glyphs().count() - 1) == line.glyphs().cluster(0);
      assertTrue(
          line.width() <= box * scale + 0.1 || oneCluster,
          "line " + i + " at scale " + scale + " is " + line.width() + " wide: " + lines);
      at += lines.get(i).length();
    }
  }

  @Test
  void textIsShapedAtThePhysicalSizeAndMeasuredInLogicalPixels() {
    TextLibraries.assumeLoaded();
    // At scale 2 a character is 16.8 physical pixels, 16.75 on the quarter grid.
    El text = mono("abc");
    El sibling = el().size(10, 10);
    Headless.render(row().children(text, sibling), 100, 50, 2);
    Text.Line line = text.text().lines().getFirst();
    assertEquals(2, text.text().scale());
    assertEquals(50.25f, line.width());
    assertEquals(16.75f, line.glyphs().x(1));
    assertEquals(28, line.glyphs().pixelSize());
    assertEquals(29, line.baseline(), "1.02 * 28 = 28.56");
    assertEquals(25, text.width(), "25.125, and layout rounds edges to whole pixels");
    assertEquals(25, sibling.x());
    assertEquals(19, text.height(), "37 physical pixels a line, 18.5, rounded by layout");

    // At 1.5 a character is 12.6, 12.5 on the grid; at 1.25 it is 10.5, 11 on whole pixels.
    El finer = mono("abc");
    Headless.render(row().child(finer), 100, 50, 1.5f);
    assertEquals(37.5f, finer.text().lines().getFirst().width());
    El coarser = mono("abc");
    Headless.render(row().child(coarser), 100, 50, 1.25f);
    assertEquals(33, coarser.text().lines().getFirst().width());
  }

  @Test
  void theDumpShowsTheStringOfATextElement() {
    // Before layout: no shaping, so no native library.
    assertEquals(
        "text #save role=button label=\"Save all\" \"Save \\\"all\\\"\\nnow\"\n",
        El.text("Save \"all\"\nnow")
            .key("save")
            .role("button")
            .label("Save all")
            .dump());
    TextLibraries.assumeLoaded();
    El root = row().child(El.text("Save\nnow").font(Font.mono()));
    String tree = Headless.render(root, 200, 50, 1).tree();
    assertEquals("el 0,0 32x36\n  text \"Save\\nnow\" 0,0 32x36\n", tree);
  }

  /**
   * At scale 1.5, 10 logical pixels are 15 physical ones and 11 are 16.5: text is drawn there
   * from a bitmap moved by half a pixel, an icon at 17, the same bitmap as at 15 two pixels on, so
   * that its strokes stay on the pixels.
   */
  @Test
  void iconsAreDrawnOnWholePixelsWhereTextIsDrawnOnQuarters() {
    TextLibraries.assumeLoaded();
    assertFalse(movedByTwoPixels(Font.sans(), "+"));
    assertTrue(movedByTwoPixels(Font.icons(), "\uF108")); // ic_fluent_add_16_regular
  }

  private static boolean movedByTwoPixels(Font font, String string) {
    Pixels at10 = drawnAt(font, string, 10);
    Pixels at11 = drawnAt(font, string, 11);
    int width = at10.width();
    for (int y = 0; y < at10.height(); y++) {
      int row = y * width * 4;
      if (!Arrays.equals(
          at11.rgba(), row + 8, row + width * 4, at10.rgba(), row, row + width * 4 - 8)) {
        return false;
      }
    }
    return true;
  }

  private static Pixels drawnAt(Font font, String string, float x) {
    // The fraction in the text's own padding: its box at 0 is on whole pixels at every position.
    El view = row()
        .wFull()
        .hFull()
        .fill(0xFFFFFFFF)
        .padding(2, 0, 0, 0)
        .child(El.text(string).font(font).fontSize(16).padding(0, 0, 0, x));
    return Headless.render(view, 40, 24, 1.5f, TextRendering.LINUX).pixels();
  }

  @Test
  void aCharacterOfAPrivateUseAreaIsDumpedAsItsCodePointAndSelectedSo() throws IOException {
    // Such as an icon of Font.icons(), which other fonts and editors show as nothing or a box.
    String icons = "\uE0EB" + Character.toString(0xF0283) + "\uFFFDa";
    assertEquals("text \"\\u{E0EB}\\u{F0283}\uFFFDa\"\n", El.text(icons).dump());
    TextLibraries.assumeLoaded();
    List<String> pressed = new ArrayList<>();
    App<List<String>> app = new App<>(
        pressed,
        s -> row().child(el().padding(4).onPress(() -> s.add("icons")).child(El.text(icons))));
    Script.run("size 100 40\nframe\npress text=\"\\u{e0eb}\\u{F0283}\uFFFDa\"\nframe\n", app, out);
    assertEquals(List.of("icons"), pressed);
  }

  @Test
  void aScriptPressesTheElementThatHoldsATextAndListsTheTexts() throws IOException {
    TextLibraries.assumeLoaded();
    List<String> pressed = new ArrayList<>();
    App<List<String>> app = new App<>(
        pressed,
        s -> row()
            .gap(8)
            .children(
                el().padding(4).onPress(() -> s.add("save")).child(El.text("Save")),
                el().padding(4).onPress(() -> s.add("both")).child(El.text("Two\nlines")),
                El.text("Cancel")));
    Script.run(
        "size 300 60 scale 2\nframe\npress text=\"Save\"\npress text=\"Two\\nlines\"\nframe\n",
        app,
        out);
    assertEquals(List.of("save", "both"), pressed);
    assertEquals(
        2, app.tree().children().getFirst().children().getFirst().text().scale());

    Script.Failure failure = assertThrows(
        Script.Failure.class,
        () -> Script.run("size 300 60\nframe\npress text=\"Sav\"\n", app, out));
    assertTrue(
        failure
            .getMessage()
            .contains("    texts:  text=\"Cancel\" text=\"Save\" text=\"Two\\nlines\"\n"),
        failure.getMessage());
  }

  @Test
  void textCallsNeedATextElement() {
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> el().fontSize(12));
    assertEquals(
        "fontSize is for text elements, which El.text(\"...\") makes; this element has no text.",
        e.getMessage());
    assertThrows(IllegalArgumentException.class, () -> El.text("a").fontSize(0));
    assertThrows(IllegalArgumentException.class, () -> El.text(null));
  }

  private static El mono(String string) {
    return El.text(string).font(Font.mono());
  }

  /** The characters of each line, which start where the line's first glyph does. */
  private static List<String> lines(El el) {
    String[] paragraphs = el.text().string().split("\n", -1);
    List<Text.Line> lines = el.text().lines();
    List<String> strings = new ArrayList<>();
    int paragraph = 0;
    for (int i = 0; i < lines.size(); i++) {
      GlyphRun glyphs = lines.get(i).glyphs();
      String text = paragraphs[paragraph];
      int start = glyphs.count() == 0 ? text.length() : glyphs.cluster(0);
      GlyphRun next = i + 1 < lines.size() ? lines.get(i + 1).glyphs() : null;
      boolean sameParagraph = next != null && next.count() > 0 && next.cluster(0) > start;
      int end = sameParagraph ? next.cluster(0) : text.length();
      strings.add(text.substring(Math.min(start, end), end));
      if (!sameParagraph) {
        paragraph++;
      }
    }
    return strings;
  }

  private static List<Float> baselines(El el) {
    return el.text().lines().stream().map(Text.Line::baseline).toList();
  }
}
