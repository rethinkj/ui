package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.GlyphRun;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
    final Find.State find = new Find.State();
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
    String tree = run(app, """
        frame
        press text="Guide" clicks 1
        press text="Guide" clicks 2
        key C control
        frame""");
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

  /** An image of one color, of this size in pixels. */
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

  @Test
  void fragmentAndDefinitionsRenderCompactlyAndHandleLinks() throws IOException {
    MarkdownDocument summary = Markdown.parse("""
        Finds orders placed in a date range, newest first.

        Uses the `orders_placed_at` index; see [indexes](#indexes).""");
    MarkdownDocument fromDoc = Markdown.parse("Start of the range, inclusive, in UTC.");
    MarkdownDocument toDoc = Markdown.parse(
        "End of the range, exclusive; must lie after `from` and match [bounds](#bounds).");
    MarkdownDocument returnsDoc =
        Markdown.parse("Matching orders ordered by `placed_at` descending.");
    MarkdownDocument throwsDoc = Markdown.parse("When `to` is not after `from`.");
    List<String> clicked = new ArrayList<>();
    App<Object> app = new App<>(new Object(), state -> {
          Theme theme = Theme.CURRENT.get();
          Texts texts = Texts.CURRENT.get();
          Markdown.Options options = Markdown.options().onLink(clicked::add);
          El fragment = Markdown.fragment(summary, options).key("fragment");
          El definitions = Markdown.definitions(
                  List.of(
                      new Markdown.Section(
                          texts.parameters(),
                          List.of(
                              new Markdown.Definition("from", fromDoc),
                              new Markdown.Definition("to", toDoc))),
                      new Markdown.Section(
                          texts.returns(), List.of(new Markdown.Definition(null, returnsDoc))),
                      new Markdown.Section(
                          texts.thrown(),
                          List.of(new Markdown.Definition("IllegalArgumentException", throwsDoc)))),
                  options)
              .key("definitions");
          return col()
              .wFull()
              .hFull()
              .padding(theme.space().l())
              .fill(theme.surface().window())
              .child(Card.card(fragment, definitions).wFull());
        })
        .provide(Theme.CURRENT, state -> Theme.DARK);
    app.textRendering(TextRendering.LINUX);
    String image = "markdown-fragment-dark-2x";
    Script.run("size 480 250 scale 2\nframe\nframe\nsnap " + image, app, out);
    String tree = Files.readString(out.resolve(image + ".tree.txt"));
    Golden.assertMatches(image, Png.decode(Files.readAllBytes(out.resolve(image + ".png"))), tree);

    assertFalse(tree.contains("role=document"), tree);
    assertTrue(line(tree, "#fragment").contains(" 422x"), "fills the card's inner width: " + tree);
    assertTrue(line(tree, "#definitions").contains("role=list"), tree);
    assertTrue(line(tree, "label=\"Params:\"").contains("role=group"), tree);
    assertTrue(line(tree, "label=\"from\"").contains("role=listitem"), tree);
    assertTrue(line(tree, "label=\"IllegalArgumentException\"").contains("role=listitem"), tree);

    Script.run("""
        size 480 250 scale 2
        frame
        press role=link label="indexes"
        frame
        press role=link label="bounds"
        frame""", app, out);
    assertEquals(List.of("#indexes", "#bounds"), clicked);

    int commentColor = Theme.DARK.code().comment();
    Markdown.Options colored = Markdown.options().textColor(commentColor);
    El coloredFragment = Markdown.fragment(summary, colored);
    El coloredDefinitions = Markdown.definitions(
        List.of(new Markdown.Section("Params:", List.of(new Markdown.Definition("from", fromDoc)))),
        colored);
    assertEquals(commentColor, paragraph(coloredFragment).text().color());
    assertEquals(commentColor, paragraph(coloredDefinitions).text().color());
  }

  @Test
  void oneDocumentShowsTheSameParagraphWithAndWithoutItsImages() throws IOException {
    MarkdownDocument document = Markdown.parse("Before ![Inline](inline.png) and [after](#after).");
    Map<String, com.rethinkj.ui.Image> images = Map.of("inline.png", picture(60, 30));
    App<Object> app = new App<>(
        new Object(),
        state -> col()
            .wFull()
            .hFull()
            .child(Markdown.fragment(document, Markdown.options()))
            .child(Markdown.markdown(document, Markdown.options().images(images::get))
                .grow(1)));
    String tree = run(app, "frame");
    List<String> paragraphs = tree.lines()
        .map(String::strip)
        .filter(l -> l.contains("role=paragraph"))
        .toList();
    assertEquals(3, paragraphs.size(), tree);
    assertTrue(paragraphs.get(0).contains("role=paragraph \"Before Inline and after.\""), tree);
    assertTrue(paragraphs.get(1).contains("role=paragraph \"Before\""), tree);
    assertTrue(paragraphs.get(2).contains("role=paragraph \"and after.\""), tree);
    assertEquals(2, tree.lines().filter(l -> l.contains("role=link")).count(), tree);
  }

  @Test
  void aStreamingDocumentBuildsOnlyTheBlocksThatChanged() throws IOException {
    String step1 = """
        # Answer

        Intro paragraph with *style*.

        ```sql
        SELECT 1;
        SELECT 2;
        ```

        Start""";
    String step2 = step1 + " of the growing paragraph.";
    String step3 = step2 + "\n\n- item one\n- item two\n\nFinal note.";
    int[] sqlLines = new int[1];
    Highlighter sql = (line, state, out) -> {
      sqlLines[0]++;
      out.color(0, line.length(), Theme.DARK.code().keyword());
      return null;
    };
    Markdown.Options options =
        Markdown.options().languages(language -> language.equals("sql") ? sql : null);
    for (boolean fragment : new boolean[] {false, true}) {
      Function<MarkdownDocument, El> show = document -> fragment
          ? Markdown.fragment(document, options)
          : Markdown.markdown(document, options).grow(1);
      MarkdownDocument[] current = {Markdown.parse(step1)};
      App<Object> app = new App<>(
          new Object(),
          state -> col()
              .wFull()
              .hFull()
              .fill(Theme.DARK.surface().panel())
              .child(show.apply(current[0])));
      sqlLines[0] = 0;
      Markdown.piecesBuilt = 0;
      run(app, "frame");
      assertEquals(2, sqlLines[0]);
      assertEquals(3, Markdown.piecesBuilt, "heading and two paragraphs");

      current[0] = current[0].next(step2);
      Markdown.piecesBuilt = 0;
      run(app, "frame");
      assertEquals(1, Markdown.piecesBuilt, "the growing paragraph only");
      current[0] = current[0].next(step3);
      String streamed = run(app, "frame");
      assertEquals(2, sqlLines[0], "the code block is not highlighted again");

      App<Object> whole = new App<>(
          new Object(),
          state -> col()
              .wFull()
              .hFull()
              .fill(Theme.DARK.surface().panel())
              .child(show.apply(Markdown.parse(step3))));
      assertEquals(run(whole, "frame"), streamed);
    }
  }

  @Test
  void draggingAcrossBlocksSelectsAndCopiesAndFragmentsJoinInAScope() throws IOException {
    String source = """
        First paragraph with words.

        - list item one
        - list item two

        ```sql
        SELECT id FROM orders;
        ```

        Trailing paragraph.
        """;
    App<Doc> app = app(source);
    app.textRendering(TextRendering.LINUX);
    String image = "markdown-selection-dark-2x";
    Script.run("""
        size 480 280 scale 2
        frame
        down text="First paragraph with words." at left+32
        move role=code at left+80
        up role=code at left+80
        frame
        snap %s
        key C control
        frame""".formatted(image), app, out);
    String tree = Files.readString(out.resolve(image + ".tree.txt"));
    Golden.assertMatches(image, Png.decode(Files.readAllBytes(out.resolve(image + ".png"))), tree);

    assertEquals(
        "paragraph with words.\nlist item one\nlist item two\nSELECT id",
        app.clipboard().read(),
        tree);

    // Shift+press extends from the anchor into the trailing paragraph, and a press outside clears.
    Script.run("""
        press text="Trailing paragraph." at left+48 shift
        frame
        key C control
        frame""", app, out);
    String extended = app.clipboard().read();
    assertTrue(extended.contains("SELECT id FROM orders;\n"), extended);
    assertTrue(extended.endsWith("Trailing") || extended.contains("\nTrail"), extended);

    Script.run("""
        press 4,4
        frame
        clipboard "cleared"
        key C control
        frame""", app, out);
    assertEquals("cleared", app.clipboard().read());

    // Two fragments inside an enclosing selectionScope() join into one selection.
    MarkdownDocument first = Markdown.parse("Message one text.");
    MarkdownDocument second = Markdown.parse("Message two text.");
    MarkdownDocument third = Markdown.parse("Message three text.");
    App<Object> chat = new App<>(
            new Object(),
            state -> col()
                .wFull()
                .hFull()
                .padding(16)
                .gap(12)
                .selectionScope()
                .fill(Theme.DARK.surface().panel())
                .child(Markdown.fragment(first, Markdown.options()))
                .child(Markdown.fragment(second, Markdown.options()))
                .child(Markdown.fragment(third, Markdown.options())))
        .provide(Theme.CURRENT, state -> Theme.DARK);
    chat.textRendering(TextRendering.LINUX);
    Script.run("""
        size 400 160 scale 2
        frame
        down text="Message one text." at left+52
        move text="Message two text." at left+70
        up text="Message three text." at left+70
        frame
        key C control
        frame""", chat, out);
    String joined = chat.clipboard().read();
    assertTrue(joined.contains("one text.\nMessage two text.\nMessage"), joined); // up's place
  }

  @Test
  void selectionSpansOutOfViewRowsCopiesAllAndEdgeScrolls() throws IOException {
    StringBuilder source = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      source.append("Paragraph ").append(i).append(" with sample words.\n\n");
    }
    App<Doc> app = app(source.toString());
    app.textRendering(TextRendering.LINUX);

    // Click in Paragraph 2, scroll far down so row 2 is dropped, Shift+click Paragraph 120.
    Script.run("""
        size 420 200 scale 2
        frame
        press text="Paragraph 2 with sample words." at left+56
        frame
        scroll role=document row 120
        frame""", app, out);
    assertFalse(app.tree().dump().contains("Paragraph 2 "), "row 2 is no longer built");

    Script.run("""
        press text="Paragraph 120 with sample words." at left+70 shift
        frame
        key C control
        frame""", app, out);
    String copied = app.clipboard().read();
    List<String> lines = copied.lines().toList();
    assertEquals(119, lines.size(), "paragraphs 2 through 120 inclusive: " + lines.size());
    assertTrue(lines.getFirst().endsWith("with sample words."), lines.getFirst());
    assertEquals("Paragraph 3 with sample words.", lines.get(1));
    assertEquals("Paragraph 60 with sample words.", lines.get(58), "unbuilt middle row copied");
    assertEquals("Paragraph 119 with sample words.", lines.get(117));
    assertFalse(
        lines.getLast().endsWith("words."), "ends inside paragraph 120: " + lines.getLast());

    // Scrolling back up rebuilds early rows with their selection restored, and Ctrl+C still copies
    // the full range while the focused row (120) is out of view.
    Script.run("""
        scroll role=document row 2
        frame""", app, out);
    El p2 = findParagraph(app.tree(), "Paragraph 2 with sample words.");
    El p4 = findParagraph(app.tree(), "Paragraph 4 with sample words.");
    assertTrue(
        p2 != null && !p2.text().selection().isEmpty(), "rebuilt anchor row shows selection");
    assertTrue(
        p4 != null && !p4.text().selection().isEmpty(), "rebuilt middle row shows selection");
    app.clipboard().write("stale");
    Script.run("key C control\nframe", app, out);
    assertEquals(copied, app.clipboard().read(), "Ctrl+C copies all rows while focus waits");

    // A held drag survives wheel-scrolling until its start row is dropped.
    Script.run("""
        scroll role=document row 0
        frame
        down text="Paragraph 1 with sample words." at left+56
        move text="Paragraph 2 with sample words." at left+56
        frame
        wheel role=document 0 1600
        frame""", app, out);
    assertFalse(app.tree().dump().contains("Paragraph 1 "), "start row dropped while dragging");
    Script.run("""
        up 100,100
        frame
        key C control
        frame""", app, out);
    String wheelDragged = app.clipboard().read();
    List<String> wheelLines = wheelDragged.lines().toList();
    assertTrue(wheelLines.size() > 25, "dragged across many rows: " + wheelLines.size());
    assertTrue(wheelLines.getFirst().endsWith("with sample words."), wheelLines.getFirst());
    assertEquals("Paragraph 2 with sample words.", wheelLines.get(1));
    assertEquals("Paragraph 15 with sample words.", wheelLines.get(14));

    // Holding a drag past the bottom edge auto-scrolls while the pointer stays still.
    Script.run("""
        scroll role=document row 0
        frame
        down text="Paragraph 0 with sample words." at left+20
        move 100,230
        frame""", app, out);
    El docList = app.tree().children().getFirst();
    double scrollBefore = docList.scrollY();
    assertTrue(app.isMoving(), "edge scroll keeps frames coming");
    for (int i = 0; i < 6; i++) {
      app.advance(0.1);
      app.frame();
    }
    double scrollAfterDown = app.tree().children().getFirst().scrollY();
    assertTrue(
        scrollAfterDown > scrollBefore + 200,
        "scrolled down with still pointer: " + scrollBefore + " -> " + scrollAfterDown);
    Script.run("""
        up 100,230
        frame
        key C control
        frame""", app, out);
    assertFalse(app.isMoving(), "idle once drag released");
    List<String> autoLines = app.clipboard().read().lines().toList();
    assertTrue(
        autoLines.size() > 10, "edge scroll extended selection across rows: " + autoLines.size());
    assertEquals("Paragraph 1 with sample words.", autoLines.get(1));

    // Holding a drag past the top edge auto-scrolls upward and copies in tree order.
    Script.run("""
        down 100,120
        move 100,-20
        frame""", app, out);
    double scrollBeforeUp = app.tree().children().getFirst().scrollY();
    for (int i = 0; i < 4; i++) {
      app.advance(0.1);
      app.frame();
    }
    double scrollAfterUp = app.tree().children().getFirst().scrollY();
    assertTrue(
        scrollAfterUp < scrollBeforeUp - 100,
        "scrolled up with still pointer: " + scrollBeforeUp + " -> " + scrollAfterUp);
    Script.run("""
        up 100,-20
        frame
        clipboard "stale"
        key C control
        frame""", app, out);
    List<String> upLines = app.clipboard().read().lines().toList();
    assertEquals( // whole paragraphs in document order, the text of this copy
        Integer.parseInt(upLines.get(1).split(" ")[1]) + 1,
        Integer.parseInt(upLines.get(2).split(" ")[1]),
        String.join("\n", upLines));
  }

  private static App<Doc> findApp(String source) {
    Doc doc = new Doc(source);
    Theme t = Theme.DARK;
    App<Doc> app = new App<>(
        doc,
        d -> col()
            .wFull()
            .hFull()
            .fill(t.surface().panel())
            .child(Find.find(
                d.app,
                Markdown.markdown(
                        d.document,
                        Markdown.options()
                            .onLink(d.links::add)
                            .copy(d.app.clipboard())
                            .state(d.selection)
                            .images(d.images::get))
                    .grow(1),
                d.find)));
    doc.app = app;
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  @Test
  void findAcrossMarkdownBlocksCountsMarksStepsAndCopies() throws IOException {
    String source = """
        # Order Guide

        Review every order in the queue.

        - First order item
        - Second item

        ```sql
        SELECT id FROM orders;
        ```
        """;
    App<Doc> app = findApp(source);

    // Click inside a read-only paragraph and press Control+F: keys bubble to the scope.
    Script.run("""
        size 640 320 scale 2
        frame
        press text="Review every order in the queue." at left+20
        frame
        key F control
        frame
        type "order"
        frame
        snap first""", app, out);
    String first = Files.readString(out.resolve("first.tree.txt"));
    assertTrue(first.contains("#find role=search"), first);
    assertFalse(first.contains("#toggle-replace"), "scope find bar has no replace toggle");
    assertFalse(first.contains("#in-selection"), "scope find bar has no find-in-selection switch");
    assertTrue(countLine(first).contains("\"1 of 4\""), countLine(first));

    // Control+G does nothing in a scope (no go-to-line).
    Script.run("""
        key G control
        frame
        snap after-g""", app, out);
    assertFalse(Files.readString(out.resolve("after-g.tree.txt")).contains("#line-field"));

    // Enter steps to match 2 in the paragraph, marks it as underlined, and Escape + Ctrl+C copies.
    Script.run("""
        key Enter
        frame
        snap second""", app, out);
    String second = Files.readString(out.resolve("second.tree.txt"));
    assertTrue(countLine(second).contains("\"2 of 4\""), countLine(second));
    assertTrue(
        second.contains("\"Review every order in the queue.\"")
            && second.contains("underline 13-18"),
        second);

    // Shift+Enter steps back to match 1, and Shift+Enter again wraps to match 4 in the code block.
    Script.run("""
        key Enter shift
        frame
        key Enter shift
        frame
        snap fourth
        key Escape
        frame
        clipboard "stale"
        key C control
        frame""", app, out);
    String fourth = Files.readString(out.resolve("fourth.tree.txt"));
    assertTrue(countLine(fourth).contains("\"4 of 4\""), countLine(fourth));
    assertEquals("order", app.clipboard().read());

    // F3 while closed wraps to match 1 ("Order") and Ctrl+C copies it.
    Script.run("""
        key F3
        frame
        clipboard "stale"
        key C control
        frame""", app, out);
    assertEquals("Order", app.clipboard().read());
  }

  @Test
  void findStepsIntoOutOfViewVirtualRows() throws IOException {
    StringBuilder source = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      if (i == 2 || i == 150 || i == 195) {
        source.append("Paragraph ").append(i).append(" contains needle here.\n\n");
      } else {
        source.append("Paragraph ").append(i).append(" filler words.\n\n");
      }
    }
    App<Doc> app = findApp(source.toString());

    Script.run("""
        size 560 220 scale 2
        frame
        press text="Paragraph 0 filler words." at left+20
        frame
        key F control
        frame
        type "needle"
        frame
        snap m1""", app, out);
    String m1 = Files.readString(out.resolve("m1.tree.txt"));
    assertTrue(countLine(m1).contains("\"1 of 3\""), countLine(m1));
    assertFalse(m1.contains("Paragraph 150 contains needle here."), "row 150 is not built yet");

    // Enter jumps to match 2 in row 150, scrolling it into view and building it.
    Script.run("""
        key Enter
        frame
        snap m2""", app, out);
    String m2 = Files.readString(out.resolve("m2.tree.txt"));
    assertTrue(countLine(m2).contains("\"2 of 3\""), countLine(m2));
    assertTrue(m2.contains("Paragraph 150 contains needle here."), m2);
    El p150 = findParagraph(app.tree(), "Paragraph 150 contains needle here.");
    assertTrue(p150 != null, "row 150 is built");
    assertFalse(p150.text().selection().isEmpty(), "row 150 shows the match selection");
    assertTrue(m2.contains("underline 23-29"), m2);

    // Escape closes the bar while row 0 is scrolled out; Ctrl+C still copies the match in row 150.
    Script.run("""
        key Escape
        frame
        clipboard "stale"
        key C control
        frame""", app, out);
    assertEquals("needle", app.clipboard().read());

    // F3 steps to row 195, and F3 again wraps back to row 2.
    Script.run("""
        key F3
        frame
        snap m3
        key F3
        frame
        snap wrap""", app, out);
    assertTrue(Files.readString(out.resolve("m3.tree.txt"))
        .contains("Paragraph 195 contains needle here."));
    assertTrue(Files.readString(out.resolve("wrap.tree.txt"))
        .contains("Paragraph 2 contains needle here."));
  }

  private static String countLine(String tree) {
    return tree.lines().filter(l -> l.contains("#count ")).findFirst().orElse("");
  }

  private static El findParagraph(El el, String value) {
    if ("paragraph".equals(el.role())
        && el.text() != null
        && value.equals(el.text().string())) {
      return el;
    }
    for (El child : el.children()) {
      El found = findParagraph(child, value);
      if (found != null) {
        return found;
      }
    }
    return null;
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
