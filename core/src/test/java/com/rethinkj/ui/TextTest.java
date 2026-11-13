package com.rethinkj.ui;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.GlyphRun;
import com.rethinkj.ui.text.TextLibraries;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
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

  @TempDir Path out;

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
    assertEquals("text #save role=button label=\"Save all\" \"Save \\\"all\\\"\\nnow\"\n",
        El.text("Save \"all\"\nnow").key("save").role("button").label("Save all").dump());
    TextLibraries.assumeLoaded();
    El root = row().child(El.text("Save\nnow").font(Font.mono()));
    String tree = Headless.render(root, 200, 50, 1).tree();
    assertEquals("el 0,0 32x36\n  text \"Save\\nnow\" 0,0 32x36\n", tree);
  }

  @Test
  void aScriptPressesTheElementThatHoldsATextAndListsTheTexts() throws IOException {
    TextLibraries.assumeLoaded();
    List<String> pressed = new ArrayList<>();
    App<List<String>> app =
        new App<>(
            pressed,
            s ->
                row().gap(8).children(
                        el().padding(4).onPress(() -> s.add("save")).child(El.text("Save")),
                        el().padding(4).onPress(() -> s.add("both")).child(El.text("Two\nlines")),
                        El.text("Cancel")));
    Script.run(
        "size 300 60 scale 2\nframe\npress text=\"Save\"\npress text=\"Two\\nlines\"\nframe\n",
        app, out);
    assertEquals(List.of("save", "both"), pressed);
    assertEquals(2, app.tree().children().getFirst().children().getFirst().text().scale());

    Script.Failure failure =
        assertThrows(
            Script.Failure.class,
            () -> Script.run("size 300 60\nframe\npress text=\"Sav\"\n", app, out));
    assertTrue(
        failure.getMessage().contains(
            "    texts:  text=\"Cancel\" text=\"Save\" text=\"Two\\nlines\"\n"),
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
