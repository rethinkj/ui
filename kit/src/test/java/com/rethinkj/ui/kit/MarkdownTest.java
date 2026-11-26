package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Clipboard;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.RowHeights;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.ScrollState;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Text;
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
import java.util.stream.Collectors;
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

      Read the [spec](https://spec.commonmark.org "CommonMark") and the [notes](#notes), *now*.

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
    int parentEscapes;
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
            .onKey(k -> {
              if (k.modifiers() == 0 && k.name().equals("Escape")) {
                d.parentEscapes++;
                return true;
              }
              return false;
            })
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
  void aTaskCheckboxCallsOnTaskOnPointerPressAndSpace() throws IOException {
    class State {
      String source = """
          - [x] Done
          - [ ] Open
            - [ ] Nested
          """;
      MarkdownDocument document = Markdown.parse(source);
      final List<Markdown.Task> received = new ArrayList<>();

      void toggle(Markdown.Task task) {
        received.add(task);
        source = task.applyTo(source);
        document = document.next(source);
      }
    }
    State state = new State();
    Theme t = Theme.DARK;
    App<State> app = new App<>(
        state,
        s -> col()
            .wFull()
            .hFull()
            .fill(t.surface().panel())
            .child(Markdown.markdown(s.document, Markdown.options().onTask(s::toggle))
                .grow(1)));

    String tree = run(app, "frame\npress role=checkbox label=\"Open\"\nframe");
    assertEquals(1, state.received.size());
    assertEquals(state.source.indexOf("[x] Open"), state.received.getFirst().offset());
    assertTrue(state.received.getFirst().checked());
    assertTrue(state.source.contains("- [x] Open\n"), state.source);
    assertTrue(line(tree, "role=checkbox label=\"Open\"").contains(" checked"), tree);
    assertFalse(line(tree, "role=checkbox label=\"Open\"").contains(" disabled"), tree);

    // Tab reaches the task checkbox, and Space toggles it.
    tree = run(app, "key Tab\nkey Tab\nframe");
    assertTrue(line(tree, "role=checkbox label=\"Open\"").contains(" focused"), tree);
    tree = run(app, "key Space\nframe");
    assertEquals(2, state.received.size());
    assertEquals(state.source.indexOf("[ ] Open"), state.received.get(1).offset());
    assertFalse(state.received.get(1).checked());
    assertTrue(state.source.contains("- [ ] Open\n"), state.source);
    assertTrue(line(tree, "role=checkbox label=\"Open\"").contains(" checked=false"), tree);
  }

  @Test
  void tableColumnsUseAvailableWidthWrapBeforeScrollingAndCacheMeasurements() throws IOException {
    String source = """
        | Key | Description |
        | --- | --- |
        | alpha | A long explanation that stretches well past half the prose measure on a wide page. |
        | beta | Short note. |
        """;
    MarkdownDocument[] current = {Markdown.parse(source)};
    Theme t = Theme.DARK;
    App<Object> app = new App<>(
        new Object(),
        state -> col()
            .wFull()
            .hFull()
            .fill(t.surface().panel())
            .child(Markdown.markdown(current[0], Markdown.options()).grow(1)));
    app.textRendering(TextRendering.LINUX);

    // Wide: long column exceeds measure()/2, nothing wraps, table fits within its scroll area.
    Markdown.tablesMeasured = 0;
    Script.run("size 800 400 scale 2\nframe", app, out);
    assertEquals(1, Markdown.tablesMeasured);
    El[] wide = tableAndArea(app.tree(), null);
    assertNotNull(wide);
    assertTrue(wide[1].width() <= wide[0].width(), "fits at wide width");
    assertEquals(wide[1].height(), wide[0].height(), "no room below for the scrollbar");
    List<El> wideRows = tableRows(wide[1]);
    assertEquals(3, wideRows.size());
    float wideCol1 = wideRows.get(1).children().get(1).width();
    assertTrue(
        wideCol1 > t.prose().measure() / 2, "long column wider than measure()/2: " + wideCol1);
    float singleLine = wideRows.get(1).children().get(0).children().getFirst().height();
    assertEquals(
        singleLine,
        wideRows.get(1).children().get(1).children().getFirst().height(),
        "long sentence does not wrap at wide width");

    // Narrow: columns stay aligned across every row, table fits the area, long column wraps.
    Script.run("size 360 400 scale 2\nframe", app, out);
    assertEquals(1, Markdown.tablesMeasured, "reuses cached widths at same scale");
    El[] narrow = tableAndArea(app.tree(), null);
    assertNotNull(narrow);
    assertTrue(narrow[1].width() <= narrow[0].width(), "fits at narrow width");
    List<El> narrowRows = tableRows(narrow[1]);
    for (int col = 0; col < 2; col++) {
      float expectedX = narrowRows.getFirst().children().get(col).x();
      float expectedW = narrowRows.getFirst().children().get(col).width();
      for (El row : narrowRows) {
        El cell = row.children().get(col);
        assertEquals(expectedX, cell.x(), "column " + col + " x matches across rows");
        assertEquals(expectedW, cell.width(), "column " + col + " width matches across rows");
      }
    }
    assertTrue(
        narrowRows.get(1).children().get(1).children().getFirst().height() > singleLine,
        "long column wraps at narrow width");

    // Narrower than the sum of the longest words: table overflows its area and scrolls sideways.
    Script.run("size 140 400 scale 2\nframe", app, out);
    assertEquals(1, Markdown.tablesMeasured);
    El[] tiny = tableAndArea(app.tree(), null);
    assertNotNull(tiny);
    assertTrue(
        tiny[1].width() > tiny[0].width(),
        "table wider than area when longest words cannot fit: "
            + tiny[1].width()
            + " vs "
            + tiny[0].width());
    assertTrue(
        tiny[0].height() > tiny[1].height(), "room below for the scrollbar while it scrolls");

    // Streaming appends after the table keep its measured widths.
    current[0] = current[0].next(source + "\nTrailing paragraph.\n");
    app.invalidate();
    Script.run("size 360 400 scale 2\nframe", app, out);
    assertEquals(1, Markdown.tablesMeasured, "next() carries cached table widths over");

    // At a fractional scale, where the overflow rounds, the room shows exactly with the bar.
    Script.run("size 199 400 scale 1.75\nframe", app, out);
    El[] edge = tableAndArea(app.tree(), null);
    assertNotNull(edge);
    assertEquals(edge[0].horizontalBar() != null, edge[0].height() > edge[1].height());
  }

  private static El[] tableAndArea(El el, El parent) {
    if ("table".equals(el.role())) {
      return new El[] {parent, el};
    }
    for (El child : el.children()) {
      El[] found = tableAndArea(child, el);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static List<El> tableRows(El table) {
    return table.children().stream().filter(e -> "row".equals(e.role())).toList();
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
    // A link with a title shows it as a tooltip; one without shows none.
    tree = run(app, "move role=link label=\"spec\"\nframe\nadvance 1\nframe");
    assertTrue(line(tree, "role=tooltip").contains("label=\"CommonMark\""), tree);
    tree = run(app, "key Tab\nmove role=link label=\"notes\"\nframe\nadvance 1\nframe");
    assertFalse(tree.contains("role=tooltip"), tree);
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
    assertTrue(
        all.startsWith("Guide\n\nRead the spec and the notes, now.\n\nSELECT 1;\n\n- [x] done"),
        all);
    assertTrue(all.contains("- [x] done\n- [ ] open\n"), all);
    assertTrue(all.contains("a\tb\n1\t2\n"), all);
    assertTrue(all.endsWith("1. one\n2. two"), all);
    // A press anywhere ends it.
    run(app, "press text=\"Guide\"\nframe");
    assertFalse(app.state().selection.allSelected());
    // Escape ends it too, without reaching the parent; with nothing selected it goes on.
    run(app, "key A control\nframe");
    assertTrue(app.state().selection.allSelected());
    run(app, "key Escape\nframe");
    assertFalse(app.state().selection.allSelected());
    assertEquals(0, app.state().parentEscapes);
    run(app, "key Escape\nframe");
    assertEquals(1, app.state().parentEscapes);
  }

  @Test
  void aContextMenuActsOnTextLinksAndCodeBlocks() throws IOException {
    App<Doc> app = app(DOC);
    // On plain text without a selection: Copy is disabled, Select all selects the whole document.
    String tree = run(app, "frame\npress #heading-guide right\nframe\nadvance 1\nframe");
    assertEquals("Copy-, Select all, Copy link", menuItems(tree), tree);
    run(app, "press label=\"Select all\"\nframe\nadvance 1\nframe");
    assertTrue(app.state().selection.allSelected());
    tree = run(app, "press #heading-guide right\nframe\nadvance 1\nframe");
    assertEquals("Copy, Select all, Copy link", menuItems(tree), tree);
    run(app, "press label=\"Copy\"\nframe\nadvance 1\nframe");
    assertEquals(Markdown.text(app.state().document), app.clipboard().read());

    // Selection across blocks: Copy is enabled and copies the cross-block selection.
    run(app, """
        press #heading-guide at left+2
        press text="Read the spec and the notes, now." at left+30 shift
        frame""");
    assertFalse(app.state().selection.allSelected());
    tree = run(
        app,
        "press text=\"Read the spec and the notes, now.\" at left+5 right\nframe\nadvance 1\nframe");
    assertEquals("Copy, Select all", menuItems(tree), tree);
    run(app, "press role=menuitem label=\"Copy\"\nframe\nadvance 1\nframe");
    String cross = app.clipboard().read();
    assertTrue(cross.startsWith("Guide\nRead"), cross);

    // On a link inside the active selection (right click): Copy stays enabled, plus link actions.
    tree = run(app, "press role=link label=\"spec\" right\nframe\nadvance 1\nframe");
    assertEquals("Copy, Select all, Open link, Copy link address", menuItems(tree), tree);
    run(app, "press role=menuitem label=\"Copy link address\"\nframe\nadvance 1\nframe");
    assertEquals("https://spec.commonmark.org", app.clipboard().read());

    // Select all from the menu ends the selection across blocks, as Control+A does.
    run(app, "press role=link label=\"spec\" right\nframe\nadvance 1\nframe");
    run(app, "press role=menuitem label=\"Select all\"\nframe\nkey Escape\nframe");
    assertFalse(app.state().selection.allSelected());
    app.clipboard().write("kept");
    run(app, "key C control\nframe");
    assertEquals("kept", app.clipboard().read(), "no selection is left to copy");

    // On a focused link via Menu key: Open link hands the destination to onLink.
    tree = run(
        app, "focus role=link label=\"notes\"\nframe\nkey ContextMenu\nframe\nadvance 1\nframe");
    assertEquals("Copy-, Select all, Open link, Copy link address", menuItems(tree), tree);
    run(app, "press role=menuitem label=\"Open link\"\nframe\nadvance 1\nframe");
    assertEquals(List.of("#notes"), app.state().links);

    // On a code block: Copy code copies the block's code.
    tree = run(app, "press role=code right\nframe\nadvance 1\nframe");
    assertEquals("Copy-, Select all, Copy code", menuItems(tree), tree);
    run(app, "press role=menuitem label=\"Copy code\"\nframe\nadvance 1\nframe");
    assertEquals("SELECT 1;", app.clipboard().read());

    // In a fragment: items whose options are omitted do not show, and Select all selects one block.
    MarkdownDocument snippet =
        Markdown.parse("Visit [docs](https://example.com).\n\n```sql\nSELECT 2;\n```");
    App<Object> bare = new App<>(
        new Object(),
        ignored -> col().wFull().hFull().child(Markdown.fragment(snippet, Markdown.options())));
    tree = run(bare, "frame\npress role=link label=\"docs\" right\nframe\nadvance 1\nframe");
    assertEquals("Copy-, Select all", menuItems(tree), "no Open link or Copy link address");
    run(bare, "key Escape\nframe\nadvance 1\nframe");
    tree = run(bare, "press role=code right\nframe\nadvance 1\nframe");
    assertEquals("Copy-, Select all", menuItems(tree), "no Copy code without clipboard");
    run(bare, "press role=menuitem label=\"Select all\"\nframe\nadvance 1\nframe");
    tree = run(bare, "press role=code right\nframe\nadvance 1\nframe");
    assertEquals("Copy, Select all", menuItems(tree), tree);
    run(bare, "press role=menuitem label=\"Copy\"\nframe\nadvance 1\nframe");
    assertEquals("SELECT 2;", bare.clipboard().read());

    List<String> fragmentLinks = new ArrayList<>();
    Clipboard fragmentClipboard = Clipboard.inMemory();
    App<Object> full = new App<>(
        new Object(),
        ignored -> col()
            .wFull()
            .hFull()
            .child(Markdown.fragment(
                snippet, Markdown.options().onLink(fragmentLinks::add).copy(fragmentClipboard))));
    tree = run(full, "frame\npress role=link label=\"docs\" right\nframe\nadvance 1\nframe");
    assertEquals("Copy-, Select all, Open link, Copy link address", menuItems(tree), tree);
    run(full, "press role=menuitem label=\"Open link\"\nframe\nadvance 1\nframe");
    assertEquals(List.of("https://example.com"), fragmentLinks);
    tree = run(full, "press role=code right\nframe\nadvance 1\nframe");
    assertEquals("Copy-, Select all, Copy code", menuItems(tree), tree);
    run(full, "press role=menuitem label=\"Copy code\"\nframe\nadvance 1\nframe");
    assertEquals("SELECT 2;", fragmentClipboard.read());
  }

  @Test
  void aCodeBlocksMenuOpensAnywhereOnItsPanel() throws IOException {
    App<Doc> app = app(DOC);
    String tree = run(app, "frame\npress text=\"sql\" right\nframe\nadvance 1\nframe");
    assertEquals("Copy-, Select all, Copy code", menuItems(tree), "on language badge");
    tree = run(app, "key Escape\nframe\npress role=group at top+8 right\nframe\nadvance 1\nframe");
    assertEquals("Copy-, Select all, Copy code", menuItems(tree), "on empty header");
    tree = run(app, "key Escape\nframe\npress #wrap right\nframe\nadvance 1\nframe");
    assertEquals("Copy-, Select all, Copy code", menuItems(tree), "on wrap button");
    assertFalse(app.state().selection.wraps(0), "a secondary press does not toggle wrap");
    app.clipboard().write("kept");
    tree = run(app, "key Escape\nframe\npress #copy right\nframe\nadvance 1\nframe");
    assertEquals("Copy-, Select all, Copy code", menuItems(tree), "on copy button");
    assertEquals("kept", app.clipboard().read(), "a secondary press does not press copy");
    tree = run(app, "key Escape\nframe\npress role=group at left+0 right\nframe\nadvance 1\nframe");
    assertEquals("Copy-, Select all, Copy code", menuItems(tree), "on panel outside the text");
    assertTrue(
        bounds(tree, "role=group")[0] < bounds(tree, "role=code")[0],
        "left edge of the panel is outside the code field");
    tree =
        run(app, "key Escape\nframe\nfocus #wrap\nframe\nkey ContextMenu\nframe\nadvance 1\nframe");
    assertEquals("Copy-, Select all, Copy code", menuItems(tree), "Menu key from header button");
    run(app, "press role=menuitem label=\"Copy code\"\nframe\nadvance 1\nframe");
    assertEquals("SELECT 1;", app.clipboard().read());
  }

  private static String menuItems(String tree) {
    return tree.lines()
        .filter(l -> l.contains("role=menuitem"))
        .map(l -> {
          int from = l.indexOf("label=\"") + "label=\"".length();
          String label = l.substring(from, l.indexOf('"', from));
          return l.endsWith(" disabled") ? label + "-" : label;
        })
        .collect(Collectors.joining(", "));
  }

  @Test
  void aHeadingShowsItsLinkAnchorOnHoverAndCopiesItsLinkFromTheMenu() throws IOException {
    App<Doc> app = app(DOC);
    String tree = run(app, "frame");
    assertTrue(line(tree, "label=\"Guide\"").contains("role=link"), tree);
    assertTrue(line(tree, "label=\"Guide\"").endsWith("value=\"#guide\""), tree);
    assertEquals(0f, anchorGlyph(app).opacity());
    // Tab reaches the hidden anchor and shows it.
    run(app, "key Tab\nframe\nadvance 1\nframe");
    assertEquals(1f, anchorGlyph(app).opacity());
    run(app, "key Tab shift\nframe\nadvance 1\nframe");

    // Hovering the heading reveals its # anchor, and pressing it hands #id to onLink.
    run(app, "move #heading-guide\nframe\nadvance 1\nframe");
    assertEquals(1f, anchorGlyph(app).opacity());
    run(app, "press role=link label=\"Guide\"\nframe");
    assertEquals(List.of("#guide"), app.state().links);

    // Context menu on a heading offers Copy link, and after link items on a link inside a heading.
    tree = run(app, "press #heading-guide right\nframe\nadvance 1\nframe");
    assertEquals("Copy-, Select all, Copy link", menuItems(tree), tree);
    run(app, "press role=menuitem label=\"Copy link\"\nframe\nadvance 1\nframe");
    assertEquals("#guide", app.clipboard().read());

    App<Doc> linked = app("# See [spec](https://example.com)");
    tree = run(linked, "frame\npress role=link label=\"spec\" right\nframe\nadvance 1\nframe");
    assertEquals(
        "Copy-, Select all, Open link, Copy link address, Copy link", menuItems(tree), tree);

    // Without onLink, neither the anchor nor Copy link is shown.
    MarkdownDocument parsed = Markdown.parse(DOC);
    Clipboard clipboard = Clipboard.inMemory();
    App<Object> plain = new App<>(
        new Object(),
        ignored -> col()
            .wFull()
            .hFull()
            .child(Markdown.markdown(parsed, Markdown.options().copy(clipboard)).grow(1)));
    tree = run(plain, "frame");
    assertFalse(tree.contains("label=\"Guide\""), tree);
    tree = run(plain, "press #heading-guide right\nframe\nadvance 1\nframe");
    assertEquals("Copy-, Select all", menuItems(tree), tree);
  }

  private static El anchorGlyph(App<Doc> app) {
    return labeled(app.tree(), "link", "Guide").children().getFirst();
  }

  private static El labeled(El el, String role, String label) {
    if (role.equals(el.role()) && label.equals(el.label())) {
      return el;
    }
    for (El child : el.children()) {
      El found = labeled(child, role, label);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  @Test
  void textLeavesOneEmptyLineAfterACodeBlock() {
    assertEquals("int a;\n\nafter", Markdown.text(Markdown.parse("```java\nint a;\n```\n\nafter")));
    assertEquals(
        "- int a;\n\n  after",
        Markdown.text(Markdown.parse("- ```java\n  int a;\n  ```\n\n  after")));
    assertEquals(
        "int a;\n\nafter", Markdown.text(Markdown.parse("> ```java\n> int a;\n> ```\n>\n> after")));
    assertEquals(
        "int a;\n\nafter",
        Markdown.text(new MarkdownDocument(List.of(
            new MarkdownDocument.CodeBlock("java", "int a;"),
            new MarkdownDocument.Paragraph(List.of(new MarkdownDocument.Text("after")))))));
  }

  @Test
  void aCodeBlockHeaderShowsLanguageAndCopiesCode() throws IOException {
    App<Doc> app = app(DOC);
    String tree = run(app, "frame");
    assertTrue(line(tree, "label=\"sql\"").contains("label=\"sql\""), tree);
    assertTrue(line(tree, "#wrap").startsWith("el #wrap role=button label=\"Wrap lines\""), tree);
    assertTrue(line(tree, "label=\"Copy code\"").startsWith("el #copy role=button"), tree);
    float[] copyBounds = bounds(tree, "#copy ");
    float[] codeBounds = bounds(tree, "role=code");
    assertTrue(copyBounds[1] + copyBounds[3] <= codeBounds[1], tree);
    run(app, "press label=\"Copy code\"\nframe");
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
  void aCodeBlockWrapToggleWrapsLinesAndRestoresSidewaysScrolling() throws IOException {
    String longLine = "SELECT order_id, customer_id, status, total_cents, created_at, updated_at"
        + " FROM orders WHERE status = 'pending' AND total_cents > 10000 ORDER BY created_at DESC;";
    String source = "```sql\n" + longLine + "\n```\n\nFollowing paragraph.";
    App<Doc> app = app(source);
    app.textRendering(TextRendering.LINUX);

    String initial = run(app, "size 420 320\nframe");
    assertTrue(line(initial, "#wrap").contains("checked=false"), initial);
    assertFalse(app.state().selection.wraps(0));
    El unwrappedCode = role(app.tree(), "code");
    assertEquals(1, unwrappedCode.text().lines().size(), initial);
    assertNotNull(unwrappedCode.horizontalBar(), "scrolls sideways while unwrapped");
    float unwrappedHeight = unwrappedCode.height();
    float[] paraBefore = bounds(initial, "role=paragraph");

    // Toggle wrap on: the line wraps across rows, horizontal scrolling disappears, and the row
    // grows.
    String wrapped = run(app, "size 420 320\npress #wrap\nframe");
    assertTrue(line(wrapped, "#wrap").contains(" checked"), wrapped);
    assertFalse(line(wrapped, "#wrap").contains("checked=false"), wrapped);
    assertTrue(app.state().selection.wraps(0));
    El wrappedCode = role(app.tree(), "code");
    assertTrue(wrappedCode.text().lines().size() > 1, "wrapped into multiple lines");
    assertEquals(null, wrappedCode.horizontalBar(), "no sideways scrollbar while wrapped");
    assertTrue(wrappedCode.height() > unwrappedHeight, "height grew when wrapped");
    float[] boxWrapped = bounds(wrapped, "role=group");
    float[] paraWrapped = bounds(wrapped, "role=paragraph");
    assertTrue(paraWrapped[1] >= boxWrapped[1] + boxWrapped[3], "no overlap below:\n" + wrapped);
    assertTrue(paraWrapped[1] > paraBefore[1], "following paragraph shifted down");

    // Toggle wrap off: restores single line, sideways scrolling, and original height.
    String restored = run(app, "size 420 320\npress #wrap\nframe");
    assertTrue(line(restored, "#wrap").contains("checked=false"), restored);
    assertFalse(app.state().selection.wraps(0));
    El restoredCode = role(app.tree(), "code");
    assertEquals(1, restoredCode.text().lines().size(), restored);
    assertNotNull(restoredCode.horizontalBar(), "sideways scrolling restored");
    assertEquals(unwrappedHeight, restoredCode.height(), 0.5f);
    assertEquals(paraBefore[1], bounds(restored, "role=paragraph")[1], 0.5f);

    // A fragment with a State also shows the wrap toggle; without a State it does not.
    MarkdownDocument doc = Markdown.parse(source);
    Markdown.State fragState = new Markdown.State();
    App<Object> frag = new App<>(
            new Object(),
            ignored -> col()
                .wFull()
                .hFull()
                .child(Markdown.fragment(doc, Markdown.options().state(fragState)))
                .child(Markdown.fragment(doc, Markdown.options())))
        .provide(Theme.CURRENT, ignored -> Theme.DARK);
    String fragTree = run(frag, "size 420 320\nframe\npress #wrap\nframe");
    assertEquals(1, fragTree.lines().filter(l -> l.contains("#wrap")).count(), fragTree);
    assertTrue(fragState.wraps(0));
  }

  @Test
  void aLongLanguageShortensSoThatTheButtonsStayInTheBlock() throws IOException {
    MarkdownDocument document =
        Markdown.parse("```src/components/settings/SettingsPanel.tsx\nx\n```");
    Clipboard clipboard = Clipboard.inMemory();
    App<Object> app = new App<>(
            new Object(),
            ignored -> col()
                .wFull()
                .child(Markdown.fragment(
                    document, Markdown.options().copy(clipboard).state(new Markdown.State()))))
        .provide(Theme.CURRENT, ignored -> Theme.DARK);
    String tree = run(app, "size 200 200\nframe");
    float[] block = bounds(tree, "role=group");
    float[] copy = bounds(tree, "#copy ");
    assertTrue(copy[0] + copy[2] <= block[0] + block[2], tree);
    assertTrue(bounds(tree, "#wrap ")[0] >= block[0], tree);
  }

  @Test
  void aWrappedCodeBlockThatStreamsKeepsItsHeightAndTheScrollOffset() throws IOException {
    String line = "SELECT " + "column_name, ".repeat(16) + "\n";
    // On its own, and in the second item of a list.
    for (String source : new String[] {
      "Intro.\n\n```sql\n" + line.repeat(30),
      "Intro.\n\n- one\n- two\n\n  ```sql\n" + ("  " + line).repeat(30)
    }) {
      MarkdownDocument[] document = {Markdown.parse(source)};
      Markdown.State state = new Markdown.State();
      state.wrap(0, true);
      App<Object> app = new App<>(
              new Object(),
              ignored -> col()
                  .wFull()
                  .hFull()
                  .child(Markdown.markdown(document[0], Markdown.options().state(state))
                      .grow(1)))
          .provide(Theme.CURRENT, ignored -> Theme.DARK);
      run(app, "size 420 320\nframe\nwheel #markdown 0 3000\nframe\nframe");
      double scrolled = CellEditorTest.find(app, "markdown").scrollY();
      assertTrue(scrolled > 1000, "scrolled into the wrapped block: " + scrolled);
      document[0] = document[0].next(source + "SELECT");
      run(app, "size 420 320\nframe");
      assertEquals(scrolled, CellEditorTest.find(app, "markdown").scrollY(), 1, source);
    }
  }

  @Test
  void anOffscreenRowWithCodeThatGetsShorterDropsItsOldHeight() throws IOException {
    String tail = "Paragraph.\n\n".repeat(40);
    String code = "```sql\n" + "SELECT 1;\n".repeat(40) + "```\n\n" + tail;
    String shortCode = "```sql\nSELECT 1;\n```\n\n" + tail;
    String linked = "- See [ref] " + "and more words ".repeat(30)
        + "\n\n  ```sql\n  SELECT 1;\n  ```\n\n" + tail;
    String image = "- ![chart](plot.png)\n\n  ```sql\n  SELECT 1;\n  ```\n\n" + tail;
    String merged = "> Notes " + "and more words ".repeat(30)
        + "\n>\n> ```sql\n> SELECT 1;\n> ```\n>\n> ```sql\n> SELECT 2;\n> ```\n\n" + tail;
    Map<String, com.rethinkj.ui.Image> images = Map.of("plot.png", picture(100, 160));
    // The code block shortened, its lines joined or its blank lines in a list removed, a
    // definition appended that turns "[ref]" into a link, an image turned into a link, and two
    // fenced code blocks merged into one; in a view and in a virtual fragment.
    String[][] replacements = {
      {"view", code, shortCode},
      {
        "fragment",
        "- Item\n\n  ```sql\n  SELECT 1;\n" + "\n".repeat(39) + "  ```\n\n" + tail,
        "- Item\n\n  ```sql\n  SELECT 1;\n  ```\n\n" + tail
      },
      {"view", code, code.replace("SELECT 1;\n".repeat(40), "SELECT 1; ".repeat(40) + "\n")},
      {"view", linked, linked + "[ref]: https://example.com\n"},
      {
        "view",
        merged,
        merged.replace(
            "> ```\n>\n> ```sql\n> SELECT 2;",
            ">\n> SELECT 2;\n> SELECT 3;\n> SELECT 4;\n> SELECT 5;")
      },
      {"view", image, image.replace("![chart]", "[chart]")},
      {"fragment", code, shortCode}
    };
    for (String[] replacement : replacements) {
      boolean fragment = replacement[0].equals("fragment");
      MarkdownDocument[] document = {Markdown.parse(replacement[1])};
      App<Object> app = new App<>(
              new Object(),
              ignored -> fragment
                  ? col()
                      .key("outer")
                      .wFull()
                      .hFull()
                      .scroll()
                      .child(Markdown.fragment(
                              document[0], Markdown.options().virtual().images(images::get))
                          .key("markdown"))
                  : col()
                      .wFull()
                      .hFull()
                      .child(Markdown.markdown(document[0], Markdown.options().images(images::get))
                          .grow(1)))
          .provide(Theme.CURRENT, ignored -> Theme.DARK);
      run(
          app,
          "size 420 320\nframe\nwheel #" + (fragment ? "outer" : "markdown")
              + " 0 10000\nframe\nframe");
      El before = CellEditorTest.find(app, "markdown");
      assertTrue(before.firstRow() > 0, "the row is out of view");
      // At the bottom, the scroll offset is the height of the content less the view's.
      double height = fragment ? before.height() : before.scrollY();
      document[0] = document[0].next(replacement[2]);
      run(app, "size 420 320\nframe");
      El after = CellEditorTest.find(app, "markdown");
      assertTrue(after.firstRow() > 0, "the row stays out of view");
      double shorter = fragment ? after.height() : after.scrollY();
      assertTrue(shorter < height - 20, replacement[2] + ": " + height + " -> " + shorter);
    }
  }

  @Test
  void aCodeBlockInAChatMessageConfirmsItsCopyWithoutAState() throws IOException {
    Button.Copied copied = new Button.Copied();
    Clipboard clipboard = Clipboard.inMemory();
    MarkdownDocument reply = Markdown.parse("```sql\nSELECT 42;\n```");
    App<Object> app = new App<>(
            new Object(),
            ignored -> col()
                .wFull()
                .hFull()
                .child(Chat.agent(reply, Chat.options().copy(clipboard).copied(copied))))
        .provide(Theme.CURRENT, ignored -> Theme.DARK);
    app.textRendering(TextRendering.LINUX);
    String tree = run(app, """
        frame
        press label="Copy code"
        frame
        advance 0.25
        frame""");
    assertEquals("SELECT 42;", clipboard.read());
    assertFalse(tree.contains("#wrap"), "no wrap toggle without a State:\n" + tree);
    assertTrue(tree.contains("text label=\"checkmark\""), tree);
    assertTrue(tree.contains("role=tooltip label=\"Copied\""), tree);
    float[] copy = bounds(tree, "#copy ");
    float[] code = bounds(tree, "role=code");
    assertTrue(copy[1] + copy[3] <= code[1], "copy button sits in the header above code:\n" + tree);
    tree = run(app, "advance 2\nframe\nadvance 1\nframe");
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
  void proseFontsApplyToEveryMarkdownElementAndStruckLinkUsesLinkColor() {
    Font customBody = Font.sansMedium();
    Font customHeading = Font.monoBold();
    Font customEmphasis = Font.mono();
    Font customStrong = Font.sansBold();
    Font customStrongEmphasis = Font.sans();
    Font customHeadingStrong = Font.sansItalic();
    Font customTableHeader = customHeading;
    Font customMono = Font.sansBoldItalic();
    Font customMonoStrong = Font.sansSemibold();
    float customMonoSize = 15;
    Theme.Prose base = Theme.DARK.prose();
    List<Theme.TextStyle> customHeadings = base.headings().stream()
        .map(style -> new Theme.TextStyle(() -> customHeading, style.size()))
        .toList();
    Theme theme = Theme.DARK.withProse(new Theme.Prose(
        new Theme.TextStyle(() -> customBody, base.body().size()),
        customHeadings,
        () -> customEmphasis,
        () -> customStrong,
        () -> customStrongEmphasis,
        () -> customHeadingStrong,
        () -> customTableHeader,
        new Theme.TextStyle(() -> customMono, customMonoSize),
        () -> customMonoStrong,
        base.link(),
        base.code(),
        base.quote(),
        base.measure()));
    MarkdownDocument doc = Markdown.parse("""
        # Head **hb** `hc`

        Plain *em* **st** ***se*** `p` and **`s`** and *`e`* and ~~[gone](https://example.com)~~ and ~~plain~~.

        ```
        code block
        ```

        | hdr `th` |
        | --- |
        | `td` |
        """);
    List<Markdown.Section> sections = List.of(new Markdown.Section(
        "Parameters", List.of(new Markdown.Definition("arg", Markdown.parse("A value.")))));
    App<Object> app = new App<>(
            new Object(),
            state -> col()
                .wFull()
                .hFull()
                .children(
                    Markdown.markdown(doc, Markdown.options()).grow(1),
                    Markdown.definitions(sections, Markdown.options())))
        .provide(Theme.CURRENT, state -> theme);
    app.resize(640, 480, 1);
    app.frame();

    El heading = role(app.tree(), "heading");
    GlyphRun headingGlyphs = heading.text().lines().getFirst().glyphs();
    String headingText = heading.text().string();
    assertSame(customHeading, headingGlyphs.font(0));
    assertSame(customHeadingStrong, headingGlyphs.font(headingText.indexOf("hb")));
    assertSame(customMonoStrong, headingGlyphs.font(headingText.indexOf("hc")));

    El p = paragraph(app.tree());
    GlyphRun glyphs = p.text().lines().getFirst().glyphs();
    String line = p.text().string();
    assertSame(customBody, glyphs.font(0));
    assertSame(customEmphasis, glyphs.font(line.indexOf("em")));
    assertSame(customStrong, glyphs.font(line.indexOf("st")));
    assertSame(customStrongEmphasis, glyphs.font(line.indexOf("se")));
    assertSame(customMono, glyphs.font(line.indexOf(" p ") + 1));
    assertSame(customMonoStrong, glyphs.font(line.indexOf(" s ") + 1));
    assertSame(customMono, glyphs.font(line.indexOf(" e ") + 1));

    El codeBlock = role(app.tree(), "code");
    assertSame(customMono, codeBlock.text().font());
    assertEquals(customMonoSize, codeBlock.text().size());

    El headerCell = role(app.tree(), "columnheader").children().getFirst();
    GlyphRun headerGlyphs = headerCell.text().lines().getFirst().glyphs();
    assertSame(customTableHeader, headerGlyphs.font(0));
    assertSame(customMonoStrong, headerGlyphs.font("hdr th".indexOf("th")));
    El bodyCell = role(app.tree(), "cell").children().getFirst();
    assertSame(customMono, bodyCell.text().lines().getFirst().glyphs().font(0));

    El term = role(app.tree(), "listitem").children().getFirst().children().getFirst();
    assertSame(customMono, term.text().font());
    assertEquals(customMonoSize, term.text().size());

    List<Text.Mark> linkMarks =
        p.text().marks().stream().filter(m -> m.color() == theme.prose().link()).toList();
    List<Text.Mark> plainMarks = p.text().marks().stream()
        .filter(m -> m.color() == theme.text().primary())
        .toList();
    assertEquals(1, plainMarks.size(), "one strike over plain text");
    assertEquals(2, linkMarks.size(), "underline and strike over struck-out link");
    float strikeY = plainMarks.getFirst().box().y();
    assertTrue(
        linkMarks.stream().anyMatch(m -> m.box().y() == strikeY),
        "link strike is drawn in the link color");
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

  /**
   * A virtual fragment in a row of a virtual list builds only the blocks in view, and the text of
   * a selection scope around the list reaches the others.
   */
  @Test
  void aVirtualFragmentBuildsTheBlocksInViewAndReachesTheRest() throws IOException {
    StringBuilder source = new StringBuilder();
    for (int i = 0; i < 300; i++) {
      source.append("Paragraph ").append(i).append(" of the answer.\n\n");
    }
    MarkdownDocument document = Markdown.parse(source.toString());
    RowHeights heights = new RowHeights(i -> 100);
    App<Object> app = new App<>(
        new Object(),
        state -> col()
            .wFull()
            .hFull()
            .child(col()
                .key("scope")
                .selectionScope()
                .grow(1)
                .minSize(0, 0)
                .rows(
                    1,
                    heights,
                    i -> Markdown.fragment(document, Markdown.options().virtual()))));
    String tree = run(app, "frame");
    long built = tree.lines().filter(l -> l.contains("role=paragraph")).count();
    assertTrue(built > 0 && built < 60, "built " + built + " of 300 blocks");
    String all = app.scopeText(CellEditorTest.find(app.tree(), "scope"));
    assertTrue(all.startsWith("Paragraph 0 of the answer.\nParagraph 1 "), all);
    assertTrue(all.endsWith("Paragraph 299 of the answer."), all);
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
  void imagesInLinksAndEmphasisShowAsImagesAndThoseSideBySideWrap() throws IOException {
    App<Doc> app = app("""
        [![CI](ci.png)](https://ci.example.com)&nbsp;*![Docs](docs.png)* **![License](license.png)**

        Before [![Build](build.png)](https://build.example.com) and after.

        *Read ![Guide](guide.png) first* and [![Status](status.png) build](https://status.example.com)

        [![Banner](banner.png)](https://banner.example.com)
        """);
    for (String badge : List.of("ci.png", "docs.png", "license.png")) {
      app.state().images.put(badge, picture(80, 20));
    }
    app.state().images.put("build.png", picture(60, 20));
    app.state().images.put("guide.png", picture(40, 20));
    app.state().images.put("status.png", picture(40, 20));
    app.state().images.put("banner.png", picture(2000, 500));
    String tree = run(app, "frame");
    // Linked, emphasized and strong images sit side by side in one row.
    float[] ci = bounds(tree, "image role=img label=\"CI\"");
    float[] docs = bounds(tree, "image role=img label=\"Docs\"");
    float[] license = bounds(tree, "image role=img label=\"License\"");
    assertTrue(
        ci[1] == docs[1] && docs[1] == license[1] && ci[0] < docs[0] && docs[0] < license[0], tree);
    // Among text, a linked image breaks the paragraph; inside emphasis or a link too, and the
    // link's text stays a link to the same destination.
    assertTrue(tree.contains("role=paragraph \"Before\""), tree);
    assertTrue(tree.contains("role=paragraph \"and after.\""), tree);
    assertTrue(tree.contains("role=paragraph \"Read\""), tree);
    assertTrue(line(tree, "image role=img label=\"Guide\"").contains(" 40x20"), tree);
    assertTrue(
        line(tree, "role=link label=\"Status\"").contains("value=\"https://status.example.com\""),
        tree);
    assertTrue(
        line(tree, "role=link label=\"build\"").contains("value=\"https://status.example.com\""),
        tree);
    // A large linked image is as wide as the page, and its link as large as it.
    assertTrue(line(tree, "role=link label=\"Banner\"").contains(" 592x148"), tree);

    // A press, and Tab then Enter, hand the destination to onLink.
    run(app, "press role=link label=\"CI\"\nframe");
    String focused = run(app, "key Tab\nframe");
    assertTrue(line(focused, "role=link label=\"Build\"").contains(" focused"), focused);
    run(app, "key Enter\nframe");
    assertEquals(List.of("https://ci.example.com", "https://build.example.com"), app.state().links);

    // Narrower, the row of three badges wraps the third onto a line of its own.
    Script.run("size 220 480 scale 2\nframe\nsnap narrow\n", app, out);
    String narrow = Files.readString(out.resolve("narrow.tree.txt"));
    ci = bounds(narrow, "image role=img label=\"CI\"");
    docs = bounds(narrow, "image role=img label=\"Docs\"");
    license = bounds(narrow, "image role=img label=\"License\"");
    assertEquals(ci[1], docs[1], 0.5f, narrow);
    assertTrue(license[1] > ci[1] + ci[3], narrow);
  }

  @Test
  void headingsAndCellsOfImagesShowThemAndMixedOnesTheirAltText() throws IOException {
    String source = """
        # [![Logo](logo.png "Project logo")](https://logo.example.com)

        ## Status ![CI](ci.png)

        | ![Col](col.png) | Notes |
        | :---: | --- |
        | [![Badge](badge.png)](https://badge.example.com) | See ![Tip](tip.png) |
        """;
    App<Doc> app = app(source);
    for (String image : List.of("logo.png", "ci.png", "col.png", "badge.png", "tip.png")) {
      app.state().images.put(image, picture(50, 20));
    }
    String tree = run(app, "frame");
    // The heading keeps its role, level and anchor, and shows its linked image.
    assertTrue(line(tree, "#heading-logo").contains("role=heading"), tree);
    assertTrue(line(tree, "#heading-logo").contains(" level=1"), tree);
    assertTrue(
        line(tree, "role=link label=\"Logo\"").contains("value=\"https://logo.example.com\""),
        tree);
    assertTrue(line(tree, "#heading-status-ci").contains("role=heading \"Status CI\""), tree);
    assertTrue(line(tree, "image role=img label=\"Col\"").contains(" 50x20"), tree);
    assertTrue(
        line(tree, "role=link label=\"Badge\"").contains("value=\"https://badge.example.com\""),
        tree);
    assertTrue(tree.contains("\"See Tip\""), tree);
    assertFalse(tree.contains("label=\"Tip\""), tree);
    // Narrow, the image in a cell scales down with its column and stays inside its cell.
    Script.run("size 150 480 scale 2\nframe\nsnap cells\n", app, out);
    String cells = Files.readString(out.resolve("cells.tree.txt"));
    float[] badge = bounds(cells, "image role=img label=\"Badge\"");
    float[] cell = bounds(cells, "role=cell");
    assertTrue(badge[0] >= cell[0] && badge[0] + badge[2] <= cell[0] + cell[2], cells);
    Script.run("size 800 600 scale 2\nframe", app, out);

    // A linked image's title is its tooltip.
    tree = run(
        app,
        "move role=link label=\"Logo\" value=\"https://logo.example.com\"\nframe\nadvance 1\nframe");
    assertTrue(line(tree, "role=tooltip").contains("label=\"Project logo\""), tree);

    // Without Options#images, the images stay their alt text and a linked one a text link.
    MarkdownDocument parsed = Markdown.parse(source);
    App<Object> noImages = new App<>(
        new Object(),
        state -> col()
            .wFull()
            .hFull()
            .child(Markdown.markdown(parsed, Markdown.options()).grow(1)));
    String plain = run(noImages, "frame");
    assertFalse(plain.contains("role=img"), plain);
    assertTrue(line(plain, "#heading-logo").contains("role=heading \"Logo\""), plain);
  }

  @Test
  void streamingNextKeepsPiecesAroundNestedImages() throws IOException {
    String step1 = """
        # ![Logo](logo.png)

        Before [![CI](ci.png)](https://ci.example.com) and after.

        | ![Col](col.png) | Text |
        | --- | --- |
        | ![Cell](cell.png) | Value |

        Start""";
    String step2 = step1 + " of the growing paragraph.";
    Map<String, com.rethinkj.ui.Image> images = Map.of(
        "logo.png", picture(60, 24),
        "ci.png", picture(80, 20),
        "col.png", picture(50, 20),
        "cell.png", picture(50, 20));
    Markdown.Options options = Markdown.options().images(images::get);
    MarkdownDocument[] current = {Markdown.parse(step1)};
    App<Object> app = new App<>(
        new Object(),
        state -> col()
            .wFull()
            .hFull()
            .fill(Theme.DARK.surface().panel())
            .child(Markdown.markdown(current[0], options).grow(1)));
    Markdown.piecesBuilt = 0;
    run(app, "frame");
    assertEquals(5, Markdown.piecesBuilt, "two runs around linked image, two text cells, start");

    current[0] = current[0].next(step2);
    Markdown.piecesBuilt = 0;
    String tree = run(app, "frame");
    assertEquals(1, Markdown.piecesBuilt, "only the growing paragraph");
    assertTrue(tree.contains("role=paragraph \"Before\""), tree);
    assertTrue(tree.contains("role=paragraph \"and after.\""), tree);
    assertTrue(tree.contains("role=paragraph \"Start of the growing paragraph.\""), tree);
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
  void twoViewsWithDifferentTextColorsBuildPiecesOnce() throws IOException {
    String step1 = "# Title\n\nA ~~struck~~ paragraph.";
    String step2 = step1 + "\n\nSecond paragraph.";
    MarkdownDocument[] current = {Markdown.parse(step1)};
    Markdown.Options primary = Markdown.options();
    Markdown.Options muted = Markdown.options().textColor(Theme.DARK.code().comment());
    App<Object> app = new App<>(
        new Object(),
        state -> col()
            .wFull()
            .hFull()
            .fill(Theme.DARK.surface().panel())
            .child(Markdown.fragment(current[0], primary))
            .child(Markdown.fragment(current[0], muted)));
    Markdown.piecesBuilt = 0;
    run(app, "frame");
    assertEquals(4, Markdown.piecesBuilt, "two blocks built once per text color");

    Markdown.piecesBuilt = 0;
    app.invalidate();
    run(app, "frame");
    assertEquals(0, Markdown.piecesBuilt, "rebuild reuses pieces for both colors");

    current[0] = current[0].next(step2);
    Markdown.piecesBuilt = 0;
    app.invalidate();
    run(app, "frame");
    assertEquals(2, Markdown.piecesBuilt, "takeOver keeps both colors; only the new block builds");
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

  /** {x, y, width, height} of the first line of a tree dump that contains {@code part}. */
  private static float[] bounds(String tree, String part) {
    String line = tree.lines().filter(l -> l.contains(part)).findFirst().orElseThrow();
    java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            " (-?[\\d.]+),(-?[\\d.]+) ([\\d.]+)x([\\d.]+)")
        .matcher(line.substring(line.indexOf(part) + part.length()));
    assertTrue(m.find(), line);
    return new float[] {
      Float.parseFloat(m.group(1)),
      Float.parseFloat(m.group(2)),
      Float.parseFloat(m.group(3)),
      Float.parseFloat(m.group(4))
    };
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
    assertNotNull(p150, "row 150 is built");
    assertFalse(p150.text().selection().isEmpty(), "row 150 shows the match selection");
    assertTrue(m2.contains("underline 23-29"), m2);

    // Enter to row 195, then Shift+Enter back up to row 150: it comes into view below the bar.
    Script.run("""
        key Enter
        frame
        key Enter shift
        frame
        snap back""", app, out);
    String back = Files.readString(out.resolve("back.tree.txt"));
    float[] bar = bounds(back, "#find role=search");
    float[] match = bounds(back, "\"Paragraph 150 contains needle here.\"");
    assertTrue(match[1] >= bar[1] + bar[3], back);

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

  @Test
  void findScrollsFirstLineOfScopeListBelowBar() throws IOException {
    Find.State find = new Find.State();
    List<String> rows = new ArrayList<>();
    rows.add("Leading words on the first row ending with needle");
    for (int i = 1; i < 30; i++) {
      rows.add(i == 20 ? "Later row with needle here" : "Filler row " + i);
    }
    App<?>[] ref = new App<?>[1];
    App<Object> app = new App<>(new Object(), state -> {
          Theme t = Theme.CURRENT.get();
          El list = col()
              .key("list")
              .wFull()
              .grow(1)
              .minSize(0, 0)
              .padding(t.space().m())
              .scrollbars(t.scrollbars())
              .rows(
                  rows.size(),
                  24,
                  i -> col()
                      .child(El.text(rows.get(i))
                          .editable(null)
                          .readOnly()
                          .font(t.prose().body().font().get())
                          .fontSize(t.prose().body().size())));
          El scope = col().wFull().grow(1).minSize(0, 0).selectionScope().child(list);
          return col()
              .wFull()
              .hFull()
              .fill(t.surface().panel())
              .child(Find.find(ref[0], scope, find));
        })
        .provide(Theme.CURRENT, state -> Theme.DARK);
    ref[0] = app;
    app.textRendering(TextRendering.LINUX);

    // Narrow window so the right-aligned bar sits directly over the right end of row 0.
    Script.run("""
        size 440 200 scale 2
        frame
        press text="Leading words on the first row ending with needle" at left+20
        frame
        key F control
        frame
        type "needle"
        frame
        snap first""", app, out);
    String first = Files.readString(out.resolve("first.tree.txt"));
    float[] bar = bounds(first, "#find role=search");
    float[] match = bounds(first, "\"Leading words on the first row ending with needle\"");
    assertTrue(match[1] >= bar[1] + bar[3], first);

    // Closing the bar removes coveredTop, clamping the list back to 0.
    Script.run("""
        key Escape
        frame""", app, out);
    assertEquals(0, CellEditorTest.find(app, "list").scrollY());
  }

  @Test
  void findInShortScrolledScopeKeepsMatchBelowBar() throws IOException {
    StringBuilder source = new StringBuilder();
    for (int i = 0; i < 12; i++) {
      if (i == 4 || i == 5) {
        source.append("Paragraph ").append(i).append(" contains needle here.\n\n");
      } else {
        source.append("Paragraph ").append(i).append(" filler words.\n\n");
      }
    }
    Doc doc = new Doc(source.toString());
    Theme t = Theme.DARK;
    App<Doc> app = new App<>(
        doc,
        d -> col()
            .key("outer")
            .wFull()
            .hFull()
            .scroll()
            .fill(t.surface().panel())
            .child(El.el().h(120).shrink(0))
            .child(Find.find(
                d.app,
                Markdown.markdown(d.document, Markdown.options().state(d.selection))
                    .h(140)
                    .shrink(0),
                d.find))
            .child(El.el().h(300).shrink(0)));
    doc.app = app;
    app.textRendering(TextRendering.LINUX);

    // Scroll the outer area so the 140 px scope sits 80 px above the window top (y = -80..60),
    // and scroll the scope's list down so Paragraph 4 starts in the visible band near the top.
    Script.run("""
        size 560 220 scale 2
        frame
        wheel #markdown 0 60
        frame
        wheel #outer 0 200
        frame
        press text="Paragraph 4 contains needle here." at left+20
        frame
        key F control
        frame
        type "needle"
        frame
        key Enter
        frame
        key Enter shift
        frame
        snap back""", app, out);
    String back = Files.readString(out.resolve("back.tree.txt"));
    float[] bar = bounds(back, "#find role=search");
    float[] match = bounds(back, "\"Paragraph 4 contains needle here.\"");
    assertTrue(match[1] >= bar[1] + bar[3], back);
  }

  @Test
  void findInTinyScrolledScopeKeepsMatchBelowBarAsTheOuterAreaMovesIt() throws IOException {
    Find.State find = new Find.State();
    App<?>[] ref = new App<?>[1];
    App<Object> app = new App<>(new Object(), state -> {
          Theme t = Theme.CURRENT.get();
          El list = col()
              .key("list")
              .wFull()
              .h(54)
              .shrink(0)
              .scrollbars(t.scrollbars())
              .selectionScope()
              .rows(
                  30,
                  24,
                  i -> col()
                      .child(El.text(
                              i == 0
                                  ? "another needle"
                                  : i == 1 ? "A row on the right ends with needle" : "Filler " + i)
                          .key("row" + i)
                          .editable(null)
                          .readOnly()
                          .font(t.prose().body().font().get())
                          .fontSize(t.prose().body().size())));
          return col()
              .key("outer")
              .wFull()
              .hFull()
              .scroll()
              .child(El.el().h(120).shrink(0))
              .child(Find.find(ref[0], list, find))
              .child(El.el().h(300).shrink(0));
        })
        .provide(Theme.CURRENT, state -> Theme.DARK);
    ref[0] = app;
    app.textRendering(TextRendering.LINUX);

    // The 54 px scope sits 24 px above the window; stepping to row 1 moves the outer area, and
    // with it the bar, which then has to clear the match where it ends up.
    Script.run("""
        size 300 220 scale 2
        frame
        press #row0 at left+5
        frame
        key F control
        frame
        type "needle"
        frame
        wheel #outer 0 144
        frame
        key Enter
        frame
        snap step""", app, out);
    String step = Files.readString(out.resolve("step.tree.txt"));
    float[] bar = bounds(step, "#find role=search");
    float[] match = bounds(step, "\"A row on the right ends with needle\"");
    assertTrue(match[1] >= bar[1] + bar[3], step);
  }

  @Test
  void findScrollsFirstLineOfScopeListInsideOuterVirtualRowBelowBar() throws IOException {
    Find.State find = new Find.State();
    List<String> innerRows = new ArrayList<>();
    innerRows.add("Leading words on the first row ending with needle");
    for (int i = 1; i < 30; i++) {
      innerRows.add(i == 20 ? "Later row with needle here" : "Filler row " + i);
    }
    App<?>[] ref = new App<?>[1];
    App<Object> app = new App<>(new Object(), state -> {
          Theme t = Theme.CURRENT.get();
          El outer = col()
              .key("outer")
              .wFull()
              .hFull()
              .scrollbars(t.scrollbars())
              .rows(5, 180, outerRow -> {
                if (outerRow != 0) {
                  return col().h(180).child(El.text("Outer row " + outerRow));
                }
                El inner = col()
                    .key("inner")
                    .wFull()
                    .grow(1)
                    .minSize(0, 0)
                    .padding(t.space().m())
                    .scrollbars(t.scrollbars())
                    .rows(
                        innerRows.size(),
                        24,
                        i -> col()
                            .child(El.text(innerRows.get(i))
                                .editable(null)
                                .readOnly()
                                .font(t.prose().body().font().get())
                                .fontSize(t.prose().body().size())));
                El scope = col().wFull().grow(1).minSize(0, 0).selectionScope().child(inner);
                return col().wFull().h(180).child(Find.find(ref[0], scope, find));
              });
          return col().wFull().hFull().fill(t.surface().panel()).child(outer);
        })
        .provide(Theme.CURRENT, state -> Theme.DARK);
    ref[0] = app;
    app.textRendering(TextRendering.LINUX);

    // Narrow window so the right-aligned bar sits directly over the right end of inner row 0.
    Script.run("""
        size 440 200 scale 2
        frame
        press text="Leading words on the first row ending with needle" at left+20
        frame
        key F control
        frame
        type "needle"
        frame
        snap first""", app, out);
    String first = Files.readString(out.resolve("first.tree.txt"));
    float[] bar = bounds(first, "#find role=search");
    float[] match = bounds(first, "\"Leading words on the first row ending with needle\"");
    assertTrue(match[1] >= bar[1] + bar[3], first);

    // Closing the bar removes coveredTop, clamping the inner list back to 0.
    Script.run("""
        key Escape
        frame""", app, out);
    assertEquals(0, CellEditorTest.find(app, "inner").scrollY());
  }

  @Test
  void findInShortMultilineFieldInScrolledScopeKeepsMatchBelowBar() throws IOException {
    Find.State find = new Find.State();
    StringBuilder lines = new StringBuilder();
    lines.append("another needle\n");
    for (int i = 1; i < 15; i++) {
      lines.append("Filler line ").append(i).append('\n');
    }
    lines.append("A line on the right ends with needle\n");
    for (int i = 16; i < 25; i++) {
      lines.append("Filler line ").append(i).append('\n');
    }
    String text = lines.toString();
    App<?>[] ref = new App<?>[1];
    App<Object> app = new App<>(new Object(), state -> {
          Theme t = Theme.CURRENT.get();
          // 56 px scope with a 56 px read-only multi-line field (46 px content inside 4 px
          // padding and 1 px border): enough room below the bar for a line once the field scrolls.
          El field = El.text(text)
              .key("field")
              .editable(null)
              .readOnly()
              .multiline()
              .wFull()
              .grow(1)
              .minSize(0, 0)
              .padding(4, 8, 4, 8)
              .border(1, t.border().control())
              .scrollbars(t.scrollbars())
              .font(t.prose().body().font().get())
              .fontSize(t.prose().body().size());
          El scope = col().key("scope").wFull().h(56).shrink(0).selectionScope().child(field);
          return col()
              .key("outer")
              .wFull()
              .hFull()
              .scroll()
              .child(El.el().h(120).shrink(0))
              .child(Find.find(ref[0], scope, find))
              .child(El.el().h(300).shrink(0));
        })
        .provide(Theme.CURRENT, state -> Theme.DARK);
    ref[0] = app;
    app.textRendering(TextRendering.LINUX);

    // Scope sits 24 px above the window; stepping to the match on line 15 scrolls both the field
    // and the outer area so the match lands below the bar.
    Script.run("""
        size 300 220 scale 2
        frame
        press #field at left+10,top+10
        frame
        key F control
        frame
        type "needle"
        frame
        wheel #outer 0 144
        frame
        key Enter
        frame
        snap step""", app, out);
    String step = Files.readString(out.resolve("step.tree.txt"));
    float[] bar = bounds(step, "#find role=search");
    float[] fieldBox = bounds(step, "#field ");
    El field = CellEditorTest.find(app, "field");
    assertFalse(field.text().selection().isEmpty(), step);
    Text.Box sel = field.text().selection().getFirst();
    float matchY = fieldBox[1]
        + field.border(Side.TOP)
        + field.padding(Side.TOP)
        + sel.y() / field.text().scale();
    assertTrue(
        matchY >= bar[1] + bar[3], "matchY=" + matchY + " bar=" + (bar[1] + bar[3]) + "\n" + step);

    // Stepping back up to the first line keeps that match below the bar too.
    Script.run("""
        wheel #outer 0 144
        frame
        key Enter shift
        frame
        snap back""", app, out);
    String back = Files.readString(out.resolve("back.tree.txt"));
    bar = bounds(back, "#find role=search");
    fieldBox = bounds(back, "#field ");
    field = CellEditorTest.find(app, "field");
    assertFalse(field.text().selection().isEmpty(), back);
    sel = field.text().selection().getFirst();
    matchY = fieldBox[1]
        + field.border(Side.TOP)
        + field.padding(Side.TOP)
        + sel.y() / field.text().scale();
    assertTrue(
        matchY >= bar[1] + bar[3], "matchY=" + matchY + " bar=" + (bar[1] + bar[3]) + "\n" + back);
  }

  @Test
  void findScrollsSidewaysIntoOutOfViewSpansColumns() throws IOException {
    int cols = 10;
    float colWidth = 100;
    float rowHeight = 24;
    Find.State find = new Find.State();
    App<?>[] ref = new App<?>[1];
    App<Object> app = new App<>(new Object(), state -> {
          Theme t = Theme.CURRENT.get();
          El area = El.row()
              .key("area")
              .scrollBoth()
              .selectionScope()
              .w(360)
              .h(160)
              .child(col()
                  .w(cols * colWidth)
                  .h(160)
                  .child(col()
                      .key("list")
                      .grow(1)
                      .minSize(0, 0)
                      .rows(30, rowHeight, (i, from, to) -> {
                        El r = El.row().key("row-" + i);
                        int first = cols;
                        int last = -1;
                        for (int c = 0; c < cols; c++) {
                          float left = c * colWidth;
                          if (left + colWidth > from && left < to) {
                            first = Math.min(first, c);
                            last = c;
                          }
                        }
                        if (first < cols) {
                          r.padding(0, 0, 0, first * colWidth);
                          for (int c = first; c <= last; c++) {
                            String label = (i == 1 && c == 0) || (i == 20 && c == 8)
                                ? "R" + i + "C" + c + " needle"
                                : "R" + i + "C" + c + " filler";
                            r.child(El.text(label)
                                .editable(null)
                                .readOnly()
                                .multiline()
                                .fitLines()
                                .key("cell-" + i + "-" + c)
                                .font(t.prose().body().font().get())
                                .fontSize(t.prose().body().size())
                                .w(colWidth)
                                .h(rowHeight));
                          }
                        }
                        return r;
                      })));
          return col()
              .wFull()
              .hFull()
              .fill(t.surface().panel())
              .child(Find.find(ref[0], area, find));
        })
        .provide(Theme.CURRENT, state -> Theme.DARK);
    ref[0] = app;
    app.textRendering(TextRendering.LINUX);

    // Match 1 is in view at row 1 col 0; match 2 is out of view vertically and sideways at row 20
    // col 8. Both must be counted during the view build before layout, even on the first open.
    Script.run("""
        size 480 200 scale 2
        frame
        press #cell-1-0 at left+5
        frame
        key F control
        frame
        type "needle"
        frame
        snap first""", app, out);
    String first = Files.readString(out.resolve("first.tree.txt"));
    assertTrue(countLine(first).contains("\"1 of 2\""), first);
    assertEquals(null, CellEditorTest.find(app, "cell-20-8"), "row 20 col 8 is not built yet");

    // Enter scrolls both vertically and sideways, builds row 20 col 8 and shows its selection.
    Script.run("""
        key Enter
        frame
        snap second""", app, out);
    String second = Files.readString(out.resolve("second.tree.txt"));
    assertTrue(countLine(second).contains("\"2 of 2\""), second);
    El area = CellEditorTest.find(app, "area");
    assertTrue(area.scrollX() > 0, "scrolled sideways: scrollX=" + area.scrollX());
    El target = CellEditorTest.find(app, "cell-20-8");
    assertNotNull(target, "row 20 col 8 is built");
    assertFalse(target.text().selection().isEmpty(), "cell-20-8 shows its selection");
    assertTrue(second.contains("underline 6-12"), second);
    float[] areaBounds = bounds(second, "#area");
    float[] cellBounds = bounds(second, "\"R20C8 needle\"");
    assertTrue(
        cellBounds[0] >= areaBounds[0]
            && cellBounds[0] + cellBounds[2] <= areaBounds[0] + areaBounds[2],
        second);

    // Escape + Ctrl+C copies the match, and F3 wraps back left to the now-unbuilt row 1 col 0.
    Script.run("""
        key Escape
        frame
        clipboard "stale"
        key C control
        frame""", app, out);
    assertEquals("needle", app.clipboard().read());
    assertEquals(null, CellEditorTest.find(app, "cell-1-0"), "row 1 col 0 left the built span");
    Script.run("""
        key F3
        frame
        clipboard "stale"
        key C control
        frame""", app, out);
    El back = CellEditorTest.find(app, "cell-1-0");
    assertNotNull(back, "row 1 col 0 was scrolled back into view and built");
    assertFalse(back.text().selection().isEmpty(), "cell-1-0 shows its selection");
    assertEquals("needle", app.clipboard().read());
    assertEquals(0, CellEditorTest.find(app, "area").scrollX(), 0.5);
  }

  @Test
  void findInScopeDoesNotSkipMatchAfterSelectedFieldEmpties() throws IOException {
    List<String> fields = new ArrayList<>(List.of("needle", "needle", "needle"));
    Find.State find = new Find.State();
    App<?>[] ref = new App<?>[1];
    App<Object> app = new App<>(new Object(), state -> {
          Theme t = Theme.CURRENT.get();
          El scope = col().key("scope").wFull().grow(1).minSize(0, 0).gap(4).selectionScope();
          for (int i = 0; i < fields.size(); i++) {
            scope.child(El.text(fields.get(i))
                .key("f" + i)
                .editable(null)
                .readOnly()
                .font(t.prose().body().font().get())
                .fontSize(t.prose().body().size()));
          }
          return col()
              .wFull()
              .hFull()
              .fill(t.surface().panel())
              .child(Find.find(ref[0], scope, find));
        })
        .provide(Theme.CURRENT, state -> Theme.DARK);
    ref[0] = app;
    app.textRendering(TextRendering.LINUX);

    // Select the first of three identical fields, then press into the query field to clear the
    // scope's selection.
    Script.run("""
        size 480 200 scale 2
        frame
        press #f0 at left+5
        frame
        key F control
        frame
        type "needle"
        frame
        advance 1
        frame
        press #find-field at left+20
        frame""", app, out);
    String first = app.tree().dump();
    assertTrue(countLine(first).contains("\"1 of 3\""), first);
    assertTrue(CellEditorTest.find(app, "f0").text().selection().isEmpty());

    // Empty the first field: the kept position collapses before the second field (which now starts
    // at 0), so Enter selects the second field (1 of 2) instead of skipping to the third.
    fields.set(0, "");
    app.invalidate();
    Script.run("frame", app, out);
    String emptied = app.tree().dump();
    assertTrue(countLine(emptied).contains("\"2 results\""), emptied);

    Script.run("key Enter\nframe", app, out);
    String next = app.tree().dump();
    assertTrue(countLine(next).contains("\"1 of 2\""), next);
    assertFalse(CellEditorTest.find(app, "f1").text().selection().isEmpty(), next);
    assertTrue(CellEditorTest.find(app, "f2").text().selection().isEmpty(), next);
  }

  @Test
  void aTableAlignsWrappedAndSingleLineCellsInCenterAndRightColumns() throws IOException {
    String source = """
        | Left | Center | Right |
        | :--- | :---: | ---: |
        | a | short | 42 |
        | note | a longer centered cell with a [guide](#guide) that wraps across lines | a longer right aligned cell with enough words to wrap across multiple lines |
        """;
    App<Doc> app = app(source);
    app.textRendering(TextRendering.LINUX);
    Script.run("size 800 320 scale 2\nframe\npress role=link label=\"guide\"\nframe", app, out);
    assertEquals(
        List.of("#guide"), app.state().links, "link in centered wrapped cell is clickable");

    List<El> cells = new ArrayList<>();
    collectTableCells(app.tree(), cells);
    assertEquals(9, cells.size(), "3 header + 6 body cells");
    // Row 1 (short single-line cells): Left x == 0, Center x > 0, Right x > 0 near right edge.
    Text shortLeft = cells.get(3).text();
    Text shortCenter = cells.get(4).text();
    Text shortRight = cells.get(5).text();
    assertEquals(0, shortLeft.lines().getFirst().x());
    assertTrue(shortCenter.lines().getFirst().x() > 0, "single-line centered cell");
    assertTrue(shortRight.lines().getFirst().x() > 0, "single-line right cell");
    assertTrue(
        Math.abs(shortRight.lines().getFirst().x()
                + shortRight.lines().getFirst().width()
                - shortRight.scale() * cells.get(5).width())
            <= shortRight.scale() + 1, // the room a field keeps for its caret
        "right-aligned cell ends at the column's right edge");

    // Row 2 (wrapped cells): every wrapped row in Center and Right is aligned.
    Text wrappedCenter = cells.get(7).text();
    Text wrappedRight = cells.get(8).text();
    assertTrue(wrappedCenter.lines().size() >= 2, "center cell wrapped");
    assertTrue(wrappedRight.lines().size() >= 2, "right cell wrapped");
    for (Text.Line line : wrappedCenter.lines()) {
      assertTrue(line.x() > 0, "wrapped center row is indented: " + line);
    }
    for (Text.Line line : wrappedRight.lines()) {
      assertTrue(line.x() > 0, "wrapped right row is indented: " + line);
    }
  }

  @Test
  void scrollOptionJumpsToHeadingsAndRebuildsViewWhenCurrentHeadingMoves() {
    String filler = "Paragraph text to give the section enough height to scroll past the top.\n\n";
    String source = "Preamble before any heading.\n\n"
        + "# First\n\n"
        + filler.repeat(6)
        + "## Second\n\n"
        + filler.repeat(6)
        + "#### Detail\n\n"
        + filler.repeat(6)
        + "## Third\n\n"
        + filler.repeat(12);
    MarkdownDocument document = Markdown.parse(source);
    List<MarkdownDocument.Heading> outline =
        document.headings().stream().filter(h -> h.level() <= 3).toList();
    ScrollState scroll = new ScrollState();
    int[] shown = {-2};
    App<Object> app = new App<>(new Object(), state -> {
          shown[0] = Markdown.current(document, outline, scroll);
          return col()
              .wFull()
              .hFull()
              .child(
                  Markdown.markdown(document, Markdown.options().scroll(scroll)).grow(1));
        })
        .provide(Theme.CURRENT, state -> Theme.DARK);
    app.textRendering(TextRendering.LINUX);
    app.resize(640, 240, 1);
    app.frame();

    assertEquals(-1, Markdown.current(document, outline, scroll), "preamble is before First");
    assertEquals(-1, shown[0]);

    // Jumping to a heading via ScrollState puts its row at the top and rebuilds the view with it.
    app.scrollToRow(scroll, Markdown.rowOf(document, "second"));
    app.frame();
    assertEquals(Markdown.rowOf(document, "second"), scroll.topRow());
    assertEquals(1, Markdown.current(document, outline, scroll));
    assertEquals(1, shown[0], "view rebuilt with the heading jumped to");

    // A heading omitted from the outline leaves the previous outline heading active.
    app.scrollToRow(scroll, Markdown.rowOf(document, "detail"));
    app.frame();
    assertEquals(1, Markdown.current(document, outline, scroll));
    assertEquals(1, shown[0]);

    // User wheel scrolling past Third rebuilds the view so the active heading moves to Third.
    app.wheel(320, 120, 0, 400);
    app.frame();
    assertEquals(2, Markdown.current(document, outline, scroll));
    assertEquals(2, shown[0], "view rebuilt when wheel moved past Third");

    // Scrolling back to the top returns to -1.
    app.wheel(320, 120, 0, -5000);
    app.frame();
    assertEquals(0, scroll.topRow());
    assertEquals(-1, Markdown.current(document, outline, scroll));
    assertEquals(-1, shown[0]);

    // Invalid row throws; an unattached ScrollState is a no-op.
    assertThrows(IllegalArgumentException.class, () -> app.scrollToRow(scroll, -1));
    app.scrollToRow(new ScrollState(), 0);
    app.frame();
  }

  @Test
  void twoOutlineArrowKeysInOneFrameMoveTwoHeadings() {
    // Second is near the end, past where the list reaches before its rows are measured.
    String gap = "Short paragraph.\n\n";
    String source = "# First\n\n"
        + gap.repeat(20)
        + "## Second\n\n"
        + gap.repeat(2)
        + "## Third\n\n"
        + "A wider paragraph with enough words. ".repeat(6);
    MarkdownDocument document = Markdown.parse(source);
    List<MarkdownDocument.Heading> headings = document.headings();
    List<NavList.Entry> entries = headings.stream()
        .map(h -> (NavList.Entry) NavList.item(MarkdownDocument.plainText(h.content())))
        .toList();
    class State {
      final ScrollState scroll = new ScrollState();
      App<State> app;
    }
    State state = new State();
    App<State> app = new App<>(
            state,
            s -> El.row()
                .wFull()
                .hFull()
                .child(NavList.outline(
                        entries,
                        Markdown.current(document, headings, s.scroll),
                        i -> s.app.scrollToRow(
                            s.scroll, Markdown.rowOf(document, headings.get(i).id())))
                    .key("outline")
                    .w(130))
                .child(Markdown.markdown(document, Markdown.options().scroll(s.scroll))
                    .grow(1)))
        .provide(Theme.CURRENT, ignored -> Theme.DARK);
    state.app = app;
    app.textRendering(TextRendering.LINUX);
    app.resize(280, 300, 1);
    app.frame();

    assertEquals(0, Markdown.current(document, headings, state.scroll));
    app.focus(CellEditorTest.find(app, "outline"));
    app.frame();

    // Two ArrowDown keys in the same frame step from First through Second to Third.
    app.keyDown(new Key("ArrowDown"));
    app.keyDown(new Key("ArrowDown"));
    app.frame();
    assertEquals(Markdown.rowOf(document, "third"), state.scroll.topRow());
    assertEquals(2, Markdown.current(document, headings, state.scroll));
    assertTrue(
        line(app.tree().dump(), "label=\"Third\"").contains(" selected"),
        app.tree().dump());
  }

  /** A jump with one document's state does not move another document shown in the same place. */
  @Test
  void scrollToRowLeavesAnotherDocumentInTheSamePlace() {
    MarkdownDocument document = Markdown.parse(
        "# Top\n\n" + "Paragraph text that is long enough to need a line.\n\n".repeat(30));
    ScrollState[] scrolls = {new ScrollState(), new ScrollState()};
    int[] shown = {0};
    App<Object> app = new App<>(
            new Object(),
            state -> Markdown.markdown(document, Markdown.options().scroll(scrolls[shown[0]]))
                .key("doc")
                .hFull())
        .provide(Theme.CURRENT, state -> Theme.DARK);
    app.textRendering(TextRendering.LINUX);
    app.resize(640, 240, 1);
    app.frame();

    app.post(
        () -> { // a handler that jumps and then shows the other document
          app.scrollToRow(scrolls[0], 10);
          shown[0] = 1;
        });
    app.frame();
    app.frame();
    assertEquals(0, scrolls[1].y());

    app.post(() -> shown[0] = 0);
    app.frame();
    app.post(() -> app.scrollToRow(scrolls[0], 10));
    app.frame();
    assertEquals(10, scrolls[0].topRow());
    app.post(() -> shown[0] = 1); // while the jump's row would still be followed
    app.frame();
    app.frame();
    assertEquals(0, scrolls[1].y());
  }

  private static void collectTableCells(El el, List<El> into) {
    if (("columnheader".equals(el.role()) || "cell".equals(el.role()))
        && !el.children().isEmpty()
        && el.children().getFirst().text() != null) {
      into.add(el.children().getFirst());
      return;
    }
    for (El child : el.children()) {
      collectTableCells(child, into);
    }
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
    return role(el, "paragraph");
  }

  @Test
  void codeBlocksHookReplacesMatchingBlocksAndKeepsOthers() throws IOException {
    MarkdownDocument doc = Markdown.parse("""
        ```diff
        -old
        +new
        ```

        ```java
        int x = 1;
        ```

        > ```diff
        > -quotedOld
        > +quotedNew
        > ```

        - item

          ```diff
          -listOld
          +listNew
          ```
        """);
    Function<MarkdownDocument.CodeBlock, El> hook = block -> block.language().equals("diff")
        ? El.text("diff:" + block.code().strip())
            .role("region")
            .label("custom-diff")
            .editable(text -> {})
            .readOnly()
            .w(120)
        : null;
    Clipboard clipboard = Clipboard.inMemory();
    Markdown.State state = new Markdown.State();

    List<Function<Object, El>> views = List.of(
        ignored -> col()
            .wFull()
            .hFull()
            .child(Markdown.markdown(
                    doc, Markdown.options().copy(clipboard).state(state).codeBlocks(hook))
                .grow(1)),
        ignored -> col()
            .wFull()
            .hFull()
            .child(Markdown.fragment(
                doc, Markdown.options().copy(clipboard).state(state).codeBlocks(hook))),
        ignored -> col()
            .wFull()
            .hFull()
            .child(Markdown.fragment(
                    doc,
                    Markdown.options().virtual().copy(clipboard).state(state).codeBlocks(hook))
                .grow(1)));

    for (Function<Object, El> view : views) {
      App<Object> app = new App<>(new Object(), view).provide(Theme.CURRENT, ignored -> Theme.DARK);
      String tree = run(app, "frame");
      List<El> codes = new ArrayList<>();
      collectRole(app.tree(), "code", codes);
      assertEquals(1, codes.size(), "only the java block uses the default code block:\n" + tree);
      assertEquals("java", codes.getFirst().label(), tree);
      assertEquals("int x = 1;", codes.getFirst().text().string(), tree);
      assertEquals(1, tree.lines().filter(l -> l.contains("#copy ")).count(), tree);
      assertEquals(1, tree.lines().filter(l -> l.contains("#wrap ")).count(), tree);

      List<El> custom = new ArrayList<>();
      collectRole(app.tree(), "region", custom);
      assertEquals(
          List.of("diff:-old\n+new", "diff:-quotedOld\n+quotedNew", "diff:-listOld\n+listNew"),
          custom.stream().map(e -> e.text().string()).toList(),
          tree);
      // The element keeps the width it was given, not the document's.
      assertEquals(
          3,
          tree.lines()
              .filter(l -> l.contains("role=region") && l.contains(" 120x"))
              .count(),
          tree);
    }

    // Focus moving into a field of a replacing element ends the whole-document selection.
    App<Object> app =
        new App<>(new Object(), views.getFirst()).provide(Theme.CURRENT, ignored -> Theme.DARK);
    run(app, "frame");
    state.selectAll(true);
    run(app, "press 30,30\nframe"); // on the first replaced block
    assertFalse(state.allSelected());

    String plain = Markdown.text(doc);
    assertTrue(plain.contains("-old\n+new"), plain);
    assertTrue(plain.contains("int x = 1;"), plain);
  }

  private static void collectRole(El el, String role, List<El> into) {
    if (role.equals(el.role())) {
      into.add(el);
    }
    for (El child : el.children()) {
      collectRole(child, role, into);
    }
  }

  private static El role(El el, String role) {
    if (role.equals(el.role())) {
      return el;
    }
    for (El child : el.children()) {
      El found = role(child, role);
      if (found != null) {
        return found;
      }
    }
    return null;
  }
}
