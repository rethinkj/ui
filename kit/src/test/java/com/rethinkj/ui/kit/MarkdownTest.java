package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.GlyphRun;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link Markdown} driven by scripts and read from the tree: roles, levels and states of the
 * blocks, links pressed with the pointer and with Tab and Enter, copying a block, a code block and
 * the whole document, a long document of which only the blocks in view are built, and images.
 */
class MarkdownTest {

  @TempDir
  Path out;

  private static final String DOC = """
      # Guide

      Read the [spec](https://spec.commonmark.org) and the [notes](#notes), *now*.

      ```sql
      SELECT 1;
      ```

      - [x] done
      - [ ] open

      | a | b |
      |---|--:|
      | 1 | 2 |

      ## Notes

      > quoted

      1. one
      2. two
      """;

  /** The state of an app that shows one document and records the links pressed. */
  static final class Doc {
    final MarkdownDocument document;
    final Markdown.State selection = new Markdown.State();
    final List<String> links = new ArrayList<>();
    final Map<String, com.rethinkj.ui.Image> images = new HashMap<>();
    App<Doc> app;

    Doc(String source) {
      document = Markdown.parse(source);
    }
  }

  private static App<Doc> app(String source) {
    Doc doc = new Doc(source);
    Theme t = Theme.DARK;
    App<Doc> app = new App<>(
        doc,
        d -> col()
            .wFull()
            .hFull()
            .fill(t.surface().panel())
            .child(Markdown.markdown(
                    t,
                    d.document,
                    Markdown.options()
                        .onLink(d.links::add)
                        .copy(d.app.clipboard())
                        .state(d.selection)
                        .images(d.images::get))
                .grow(1)));
    doc.app = app;
    return app;
  }

  private String run(App<?> app, String steps) throws IOException {
    Script.run("size 640 480\n" + steps + "\nsnap s\n", app, out);
    return Files.readString(out.resolve("s.tree.txt"));
  }

  private static String line(String tree, String part) {
    return tree.lines()
        .filter(l -> l.contains(part))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no line with " + part + " in\n" + tree))
        .strip();
  }

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  @Test
  void theTreeSaysWhatEachBlockIs() throws IOException {
    String tree = run(app(DOC), "frame");
    assertTrue(line(tree, "#heading-guide").contains("role=heading \"Guide\""), tree);
    assertTrue(line(tree, "#heading-guide").contains(" level=1"), tree);
    assertTrue(line(tree, "#heading-notes").contains(" level=2"), tree);
    assertTrue(line(tree, "label=\"spec\"").startsWith("el #link-0 role=link label=\"spec\""));
    assertTrue(line(tree, "label=\"spec\"").endsWith("value=\"https://spec.commonmark.org\""));
    assertTrue(line(tree, "role=code").contains("label=\"sql\" \"SELECT 1;\""), tree);
    assertTrue(line(tree, "role=listitem").contains(" checked"), tree);
    assertTrue(line(tree, "role=checkbox").contains(" disabled checked"), "takes no input");
    assertTrue(tree.contains("role=table"), tree);
    assertTrue(line(tree, "role=columnheader").startsWith("el role=columnheader"), tree);
    assertTrue(line(tree, "role=blockquote").startsWith("el role=blockquote"), tree);
    assertTrue(line(tree, "role=document").contains(" rows 0-"), tree);
  }

  @Test
  void aLinkIsPressedWithThePointerAndWithTabAndEnter() throws IOException {
    App<Doc> app = app(DOC);
    run(app, "frame\npress role=link label=\"spec\"\nframe");
    assertEquals(List.of("https://spec.commonmark.org"), app.state().links);
    // Tab passes the paragraphs by and stops at each link.
    String tree = run(app, "key Tab\nframe");
    assertTrue(line(tree, "label=\"notes\"").contains(" focused"), tree);
    run(app, "key Enter\nframe");
    assertEquals(List.of("https://spec.commonmark.org", "#notes"), app.state().links);
    tree = run(app, "key Tab shift\nframe");
    assertTrue(line(tree, "label=\"spec\"").contains(" focused"), tree);
  }

  @Test
  void aBlockCopiesItsSelectionAndControlACopiesTheWholeDocument() throws IOException {
    App<Doc> app = app(DOC);
    // A double press selects a word of the paragraph, Control+C copies it.
    String tree = run(
        app,
        "frame\npress text=\"Guide\" clicks 1\npress text=\"Guide\" clicks 2"
            + "\nkey C control\nframe");
    assertEquals("Guide", app.clipboard().read(), tree);
    // Control+A selects the document, which shows in every block; Control+C copies all of it.
    run(app, "key A control\nframe\nkey C control\nframe");
    assertTrue(app.state().selection.allSelected());
    String all = app.clipboard().read();
    assertTrue(all.startsWith("Guide\n\nRead the spec and the notes, now.\n\nSELECT 1;\n"), all);
    assertTrue(all.contains("- [x] done\n- [ ] open\n"), all);
    assertTrue(all.contains("a\tb\n1\t2\n"), all);
    assertTrue(all.endsWith("1. one\n2. two"), all);
    // A press anywhere ends it.
    run(app, "press text=\"Guide\"\nframe");
    assertTrue(!app.state().selection.allSelected());
  }

  @Test
  void aCodeBlockCopiesItsCodeWithTheButtonUnderThePointer() throws IOException {
    App<Doc> app = app(DOC);
    String tree = run(app, "frame\nmove role=code\nframe");
    assertTrue(line(tree, "role=code").endsWith(" hovered"), tree);
    assertTrue(line(tree, "label=\"Copy code\"").startsWith("el #copy role=button"), tree);
    // The button springs in with the theme's feedback, so the clock moves past it first.
    run(app, "frame\nmove role=code\nframe\nadvance 1\nframe\npress label=\"Copy code\"\nframe");
    assertEquals("SELECT 1;", app.clipboard().read());
    // Hovered it is a ghost button's overlay, and it confirms with a check for a moment.
    tree = run(app, "move label=\"Copy code\"\nframe\nadvance 0.25\nframe");
    assertTrue(line(tree, "#copy").endsWith(" hovered"), tree);
    assertTrue(tree.contains("text label=\"checkmark\""), tree);
    assertTrue(tree.contains("role=tooltip label=\"Copied\""), tree);
    El copy = CellEditorTest.find(app, "copy");
    assertEquals(Theme.DARK.surface().hover(), copy.fill(), "the hover overlay");
    tree = run(app, "advance 2\nframe\nadvance 1\nframe");
    assertFalse(tree.contains("label=\"checkmark\""), "the copy icon back");
    assertFalse(tree.contains("label=\"Copied\""), tree);
  }

  @Test
  void aLongDocumentBuildsTheBlocksInViewOnly() throws IOException {
    StringBuilder source = new StringBuilder();
    for (int i = 0; i < 2000; i++) {
      source
          .append("## Part ")
          .append(i)
          .append("\n\nParagraph ")
          .append(i)
          .append(" with a [link](#part-")
          .append(i)
          .append(").\n\n");
    }
    App<Doc> app = app(source.toString());
    String tree = run(app, "frame");
    String list = line(tree, "role=document");
    assertTrue(list.contains("/4000"), list);
    assertTrue(
        tree.lines().count() < 200, "a few blocks, not 4000: " + tree.lines().count());
    // To the row of a heading far down, as an app does for a link to it.
    El document = app.tree().children().getFirst();
    app.scrollToRow(document, Markdown.rowOf(app.state().document, "part-1500"));
    tree = run(app, "frame");
    assertTrue(tree.contains("#heading-part-1500"), tree);
  }

  /** An image of one colour, of this size in pixels. */
  private static com.rethinkj.ui.Image picture(int width, int height) {
    byte[] rgba = new byte[width * height * 4];
    for (int i = 0; i < rgba.length; i += 4) {
      rgba[i] = (byte) 0x3B;
      rgba[i + 1] = (byte) 0x82;
      rgba[i + 2] = (byte) 0xF6;
      rgba[i + 3] = (byte) 0xFF;
    }
    return com.rethinkj.ui.Image.of(new Pixels(width, height, rgba));
  }

  private static final String IMAGES = """
      ![The frame](frame.png "The frame of the workbench")

      ![An icon](icon.png)

      Before ![Inline](inline.png) and after.

      ![Missing](missing.png)
      """;

  @Test
  void anImageIsAtMostAsWideAsThePageAndBreaksTheTextItStandsIn() throws IOException {
    App<Doc> app = app(IMAGES);
    app.state().images.put("frame.png", picture(2000, 500));
    app.state().images.put("icon.png", picture(40, 20));
    app.state().images.put("inline.png", picture(60, 30));
    String tree = run(app, "frame");
    // 640 wide less the document's padding of 24 on each side: 592, and a quarter as high.
    assertTrue(
        line(tree, "label=\"The frame\"")
            .matches("image role=img label=\"The frame\"" + " 24,24 592x148"),
        tree);
    assertTrue(line(tree, "label=\"An icon\"").endsWith(" 40x20"), "as large as its pixels");
    // The paragraph broke at its image: the text before it, the image in its column, the text.
    List<String> lines = tree.lines().map(String::strip).toList();
    int inline = lines.indexOf(line(tree, "label=\"Inline\""));
    assertTrue(lines.get(inline - 2).contains("role=paragraph \"Before\""), tree);
    assertTrue(lines.get(inline + 1).contains("role=paragraph \"and after.\""), tree);
    // An image the app does not have: its placeholder, with its description.
    assertTrue(line(tree, "label=\"Missing\"").startsWith("el role=img label=\"Missing\""), tree);
    // The title is the image's tooltip.
    tree = run(app, "move label=\"The frame\"\nframe\nadvance 1\nframe");
    assertTrue(line(tree, "role=tooltip").contains("label=\"The frame of the workbench\""), tree);
  }

  @Test
  void anImageThatArrivesAboveTheViewLeavesWhatIsInViewWhereItIs() throws IOException {
    StringBuilder source = new StringBuilder("![The frame](frame.png)\n\n");
    for (int i = 0; i < 60; i++) {
      source.append("Paragraph ").append(i).append(".\n\n");
    }
    App<Doc> app = app(source.toString());
    // Down so far that the image's row is above the view, but still built.
    String tree = run(app, "frame\nwheel role=document 0 100\nframe");
    String before = line(tree, "\"Paragraph 8.\"");
    assertTrue(line(tree, "role=document").contains(" rows 0-"), tree);
    app.post(() -> app.state().images.put("frame.png", picture(1000, 500)));
    tree = run(app, "await\nframe");
    assertTrue(line(tree, "label=\"The frame\"").contains(" 592x296"), tree);
    assertEquals(before, line(tree, "\"Paragraph 8.\""), "the paragraph stayed in place");
  }

  @Test
  void strongInsideEmphasisIsBoldItalicAndTheRestItalic() {
    App<Doc> app = app("***both*** and *a **b** c*");
    app.resize(640, 240, 1);
    app.frame();
    GlyphRun glyphs = paragraph(app.tree()).text().lines().getFirst().glyphs();
    String text = "both and a b c";
    assertSame(Font.sansBoldItalic(), glyphs.font(text.indexOf("both")));
    assertSame(Font.sansItalic(), glyphs.font(text.indexOf("a b")));
    assertSame(Font.sansBoldItalic(), glyphs.font(text.indexOf("b c")));
    assertSame(Font.sansItalic(), glyphs.font(text.indexOf(" c") + 1));
  }

  private static El paragraph(El el) {
    if ("paragraph".equals(el.role())) {
      return el;
    }
    for (El child : el.children()) {
      El found = paragraph(child);
      if (found != null) {
        return found;
      }
    }
    return null;
  }
}
