package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link Markdown} driven by scripts and read from the tree: roles, levels and states of the
 * blocks, links pressed with the pointer and with Tab and Enter, copying a block, a code block and
 * the whole document, and a long document of which only the blocks in view are built.
 */
class MarkdownTest {

  @TempDir Path out;

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
    App<Doc> app;

    Doc(String source) {
      document = Markdown.parse(source);
    }
  }

  private static App<Doc> app(String source) {
    Doc doc = new Doc(source);
    Theme t = Theme.DARK;
    App<Doc> app = new App<>(doc, d -> col().wFull().hFull().fill(t.surface().panel()).child(
        Markdown.markdown(t, d.document, Markdown.options().onLink(d.links::add)
            .copy(d.app.clipboard()).state(d.selection)).grow(1)));
    doc.app = app;
    return app;
  }

  private String run(App<?> app, String steps) throws IOException {
    Script.run("size 640 480\n" + steps + "\nsnap s\n", app, out);
    return Files.readString(out.resolve("s.tree.txt"));
  }

  private static String line(String tree, String part) {
    return tree.lines().filter(l -> l.contains(part)).findFirst().orElseThrow(() ->
        new AssertionError("no line with " + part + " in\n" + tree)).strip();
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
    String tree = run(app, "frame\npress text=\"Guide\" clicks 1\npress text=\"Guide\" clicks 2"
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
  }

  @Test
  void aLongDocumentBuildsTheBlocksInViewOnly() throws IOException {
    StringBuilder source = new StringBuilder();
    for (int i = 0; i < 2000; i++) {
      source.append("## Part ").append(i).append("\n\nParagraph ").append(i)
          .append(" with a [link](#part-").append(i).append(").\n\n");
    }
    App<Doc> app = app(source.toString());
    String tree = run(app, "frame");
    String list = line(tree, "role=document");
    assertTrue(list.contains("/4000"), list);
    assertTrue(tree.lines().count() < 200, "a few blocks, not 4000: " + tree.lines().count());
    // To the row of a heading far down, as an app does for a link to it.
    El document = app.tree().children().getFirst();
    app.scrollToRow(document, Markdown.rowOf(app.state().document, "part-1500"));
    tree = run(app, "frame");
    assertTrue(tree.contains("#heading-part-1500"), tree);
  }
}
