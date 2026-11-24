package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * {@link MarkdownDocument#parse} against examples of the CommonMark specification as trees, and on
 * input that is not Markdown: random characters, deep nesting and long runs of each kind, which it
 * reads without throwing and in time that grows with the length; without native libraries.
 */
class MarkdownParserTest {

  private static final String FENCE = "`".repeat(32);

  @TestFactory
  Stream<DynamicTest> theExamplesGiveTheirTrees() throws IOException {
    String all;
    try (InputStream in = getClass().getResourceAsStream("/markdown/commonmark.txt")) {
      all = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    List<DynamicTest> tests = new ArrayList<>();
    String[] parts = all.split("\n" + FENCE + " example\n", -1);
    for (int i = 1; i < parts.length; i++) {
      String part = parts[i];
      int dot = part.indexOf("\n.\n");
      String markdown = part.substring(0, dot + 1).replace('→', '\t');
      String tree = part.substring(dot + 3, part.indexOf(FENCE));
      int n = i;
      tests.add(DynamicTest.dynamicTest(
          "example " + n + ": " + markdown.lines().findFirst().orElse(""),
          () -> assertEquals(tree, MarkdownDocument.parse(markdown).tree(), markdown)));
    }
    return tests.stream();
  }

  private static String tree(String markdown) {
    return MarkdownParser.read(markdown).tree();
  }

  @Test
  void headingsGetGitHubsAnchorsMadeUnique() {
    MarkdownDocument doc = MarkdownDocument.parse(
        "# Text fields\n## Text fields\n### `El.rows` and *more*!\n> # Quoted");
    assertEquals(
        List.of("text-fields", "text-fields-1", "elrows-and-more", "quoted"),
        doc.headings().stream().map(MarkdownDocument.Heading::id).toList());
    assertEquals(
        List.of("foo", "foo-1", "foo-1-1", "foo-2"),
        MarkdownDocument.parse("# Foo\n# Foo\n# Foo-1\n# Foo").headings().stream()
            .map(MarkdownDocument.Heading::id)
            .toList(),
        "an id that a heading has is not given again");
  }

  @Test
  void aLinkCannotHoldALinkAndAnAutolinkInsideOneWins() {
    assertEquals("""
        paragraph
          text "["
          link "https://safe.example"
            text "https://safe.example"
          text "](/other)"
        """, tree("[<https://safe.example>](/other)"));
    assertEquals(
        """
        paragraph
          link "/other"
            text "see www.a.b"
        """,
        tree("[see www.a.b](/other)"),
        "a bare address inside brackets is text, as GitHub has it");
  }

  @Test
  void referenceLabelsCompareByCaseFolding() {
    assertEquals("paragraph\n  link \"/url\"\n    text \"ẞ\"\n", tree("[ẞ]\n\n[SS]: /url\n"));
    assertEquals("paragraph\n  link \"/url\"\n    text \"ss\"\n", tree("[ss]\n\n[ẞ]: /url\n"));
  }

  @Test
  void referenceResolverResolvesUndefinedLinksOnly() {
    String source = """
        [Foo], [Foo][], [the foo][Foo], [bar], ![Foo], and [Defined].

        [Defined]: /explicit "t"
        """;
    MarkdownDocument doc = MarkdownDocument.parse(
        source, label -> label.equals("Foo") || label.equals("Defined") ? "ref:" + label : null);
    assertEquals("""
        paragraph
          link "ref:Foo"
            text "Foo"
          text ", "
          link "ref:Foo"
            text "Foo"
          text ", "
          link "ref:Foo"
            text "the foo"
          text ", [bar], ![Foo], and "
          link "/explicit" "t"
            text "Defined"
          text "."
        """, doc.tree());
    assertEquals(
        MarkdownDocument.parse(source).tree(),
        MarkdownDocument.parse(source, null).tree());
  }

  @Test
  void plainTextIsTheTextAsShown() {
    MarkdownDocument.Paragraph p = (MarkdownDocument.Paragraph)
        MarkdownDocument.parse("a *b* `c` [d](e)\n![f](g) h  \ni").blocks().getFirst();
    assertEquals("a b c d f h\ni", MarkdownDocument.plainText(p.content()));
  }

  @Test
  void aCodeBlocksLanguageIsTheFirstWordOfItsInfoString() {
    assertEquals("code \"sql\" \"SELECT 1;\\n\"\n", tree("```sql title=x\nSELECT 1;\n```"));
    assertEquals("code \"\" \"a\\n\"\n", tree("~~~\na\n~~~"));
  }

  @Test
  void carriageReturnsEndLinesAsLineFeedsDo() {
    assertEquals(tree("a\nb\n\nc"), tree("a\r\nb\r\rc"));
  }

  @Test
  void codeLinesMarksFencedAndIndentedCodeBlocksIncludingContainers() {
    String md = """
        Intro.

        > ```java
        > int a = 1;
        > ```
        > after quote fence

            indented 1

            indented 2

        prose

        - ```
          in list
          ```
        - plain
        """;
    BitSet expected = new BitSet();
    for (int line : new int[] {2, 3, 4, 7, 8, 9, 13, 14, 15}) {
      expected.set(line);
    }
    assertEquals(expected, MarkdownDocument.codeLines(md));
    assertEquals(expected, MarkdownDocument.codeLines(md.replace("\n", "\r\n")));
    assertEquals(new BitSet(), MarkdownDocument.codeLines(""));
    assertEquals(new BitSet(), MarkdownDocument.codeLines(null));
  }

  @Test
  void nestingDeeperThanTheLimitIsText() {
    int deep = 10_000;
    String quotes = tree(">".repeat(deep) + " a");
    assertEquals(
        MarkdownParser.MAX_DEPTH,
        quotes.lines().filter(l -> l.strip().equals("quote")).count());
    String lists = tree("- ".repeat(deep) + "a");
    assertTrue(lists.lines().filter(l -> l.strip().startsWith("list")).count()
        <= MarkdownParser.MAX_DEPTH);
    String emphasis = tree("*a ".repeat(deep) + "b" + " c*".repeat(deep));
    assertTrue(
        emphasis
                .lines()
                .mapToInt(l -> l.length() - l.stripLeading().length())
                .max()
                .orElse(0)
            <= 2 * (MarkdownParser.MAX_DEPTH + 2),
        "bounded depth");
    String links = tree("[".repeat(deep) + "a" + "](b)".repeat(deep));
    assertTrue(links.contains("link \"b\""), links.substring(0, 200));
  }

  /** Random text of Markdown's special characters, which never makes the reader throw. */
  @Test
  void randomTextNeverFails() {
    Random random = new Random(7);
    String alphabet = "*_~`[]()!<>&#;:-+=|\\\"' \t\n\r1.xaéw/h";
    for (int run = 0; run < 300; run++) {
      int length = random.nextInt(200);
      StringBuilder s = new StringBuilder();
      for (int i = 0; i < length; i++) {
        s.append(
            random.nextInt(20) == 0
                ? (char) random.nextInt(0x3000)
                : alphabet.charAt(random.nextInt(alphabet.length())));
      }
      String text = s.toString();
      MarkdownDocument doc = MarkdownParser.read(text); // throws where parse would hide it
      doc.tree();
    }
  }

  /**
   * 128 KB of each kind of input that makes a careless reader take quadratic time, which would
   * take seconds there, each read well within them.
   */
  @Test
  void inputThatMakesCarelessReadersQuadraticIsReadInTime() {
    List<String> kinds = List.of(
        "*a **b _c __d ~e ~~f `g [h ![i <j &k ",
        "[a](b 'c ",
        "[a](<b ",
        "``` ` `` ",
        "a_b*c ",
        "> - 1. ",
        "| a | b |\n|---|---|\n",
        "www.a.b/(((",
        "www.a.",
        "-|",
        "- ",
        "www.a.b/))) ",
        "http://a.b/))))",
        "&;&;&#;",
        "[foo]: /url\n[foo]\n",
        "[[[[]]]]",
        "**a* ",
        "\\* &amp; &#x41; ",
        "# a" + " ".repeat(100_000) + "b");
    for (String kind : kinds) {
      String text = kind.repeat(128 * 1024 / kind.length());
      assertTimeoutPreemptively(Duration.ofSeconds(2), () -> MarkdownParser.read(text), kind);
    }
  }

  @Test
  void longRunsOfDelimitersAllocateInProportionToTheirLength() {
    com.sun.management.ThreadMXBean bean =
        (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    org.junit.jupiter.api.Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    bean.setThreadAllocatedMemoryEnabled(true);
    long[] bytes = new long[2];
    for (int attempt = 0; attempt < 3; attempt++) {
      for (int i = 0; i < 2; i++) {
        String run = "*".repeat(16_384 << i);
        String source = run + "x" + run;
        MarkdownParser.read(source);
        long before = bean.getCurrentThreadAllocatedBytes();
        MarkdownParser.read(source);
        bytes[i] = bean.getCurrentThreadAllocatedBytes() - before;
      }
      if (bytes[1] < 3 * bytes[0]) {
        break;
      }
    }
    assertTrue(
        bytes[1] < 3 * bytes[0],
        "twice the run allocated " + bytes[0] + " and then " + bytes[1] + " bytes");
  }

  @Test
  void streamingWithNextMatchesFullParseAndReusesUnchangedBlocks() throws IOException {
    String all;
    try (InputStream in = getClass().getResourceAsStream("/markdown/commonmark.txt")) {
      all = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    String[] parts = all.split("\n" + FENCE + " example\n", -1);
    for (int i = 1; i < parts.length; i++) {
      String part = parts[i];
      int dot = part.indexOf("\n.\n");
      String markdown = part.substring(0, dot + 1).replace('→', '\t');
      String whole = MarkdownDocument.parse(markdown).tree();
      MarkdownDocument doc = MarkdownDocument.parse("");
      for (int end = 1; end <= markdown.length(); end++) {
        String prefix = markdown.substring(0, end);
        doc = doc.next(prefix);
        assertEquals(
            MarkdownDocument.parse(prefix).tree(),
            doc.tree(),
            "example " + i + " at " + end + ":\n" + prefix);
        assertEquals(
            whole,
            MarkdownDocument.parse(prefix).next(markdown).tree(),
            "example " + i + " from " + end + ":\n" + markdown);
      }
      // Also when next() receives a shorter or unrelated text, and CRLF split across chunks.
      String crlf = markdown.replace("\n", "\r\n");
      if (crlf.length() >= 2) {
        MarkdownDocument split = MarkdownDocument.parse(crlf.substring(0, crlf.length() / 2));
        split = split.next(crlf);
        assertEquals(MarkdownDocument.parse(crlf).tree(), split.tree());
        split = split.next("other");
        assertEquals(MarkdownDocument.parse("other").tree(), split.tree());
      }
    }

    // Reference resolver is preserved and later link definitions still take precedence.
    MarkdownDocument withRef = MarkdownDocument.parse(
        "See [Foo].\n\nStill typing", label -> label.equals("Foo") ? "ref:Foo" : null);
    withRef = withRef.next("See [Foo].\n\nStill typing more");
    assertEquals(
        MarkdownDocument.parse(
                "See [Foo].\n\nStill typing more", label -> label.equals("Foo") ? "ref:Foo" : null)
            .tree(),
        withRef.tree());
    withRef = withRef.next("See [Foo].\n\n[Foo]: /explicit\n");
    assertEquals(
        MarkdownDocument.parse(
                "See [Foo].\n\n[Foo]: /explicit\n", label -> label.equals("Foo") ? "ref:Foo" : null)
            .tree(),
        withRef.tree());

    // Lines read in the context of an open block: a table's header, a line that starts a
    // paragraph which then has no text.
    for (String[] grown : new String[][] {
      {"Intro\n2. A | B\n--- | ---\n", "x | y\n"}, {"    code\n\u2003\n", "    continuation\n"}
    }) {
      assertEquals(
          MarkdownDocument.parse(grown[0] + grown[1]).tree(),
          MarkdownDocument.parse(grown[0]).next(grown[0] + grown[1]).tree());
    }
    // A heading or a thematic break takes no more lines, and a list before a growing paragraph
    // reads the same again: kept while the next block grows.
    for (String closed :
        new String[] {"# Heading\n", "Heading\n===\n", "***\n", "1. one\n2. two\n\n"}) {
      MarkdownDocument before = MarkdownDocument.parse(closed);
      for (String text : new String[] {closed + "Text", closed + "Text grows"}) {
        MarkdownDocument after = before.next(text);
        assertSame(before.blocks().getFirst(), after.blocks().getFirst(), text);
        before = after;
      }
    }

    // A document made of blocks reads the text in full and keeps the blocks that read the same.
    MarkdownDocument made =
        new MarkdownDocument(MarkdownDocument.parse("# Answer").blocks());
    MarkdownDocument read = made.next("# Answer\n\nMore");
    assertEquals(MarkdownDocument.parse("# Answer\n\nMore").tree(), read.tree());
    assertSame(made.blocks().getFirst(), read.blocks().getFirst());

    // A growing answer: unchanged blocks before the growing one keep their object identity,
    // duplicate headings stay uniquely identified, and unfinished constructs parse sensibly.
    String part1 = """
        # Answer

        Here is a query:

        ```sql
        SELECT 1;
        ```

        - first
        - second

        | col | val |
        | --- | --- |
        | a   | 1   |

        # Answer

        More""";
    MarkdownDocument first = MarkdownDocument.parse(part1);
    String part2 = part1 + " text arrives here.\n\n```java\nint x = 1;";
    MarkdownDocument second = first.next(part2);
    assertEquals(MarkdownDocument.parse(part2).tree(), second.tree());
    for (int i = 0; i < 6; i++) {
      assertSame(first.blocks().get(i), second.blocks().get(i), "block " + i + " reused");
    }
    assertEquals(
        List.of("answer", "answer-1"),
        second.headings().stream().map(MarkdownDocument.Heading::id).toList());
    assertInstanceOf(MarkdownDocument.CodeBlock.class, second.blocks().getLast());

    String halfTable = part2 + "\n```\n\n| k | v |\n|---|---|\n| 1 |";
    MarkdownDocument third = second.next(halfTable);
    assertEquals(MarkdownDocument.parse(halfTable).tree(), third.tree());
    assertInstanceOf(MarkdownDocument.Table.class, third.blocks().getLast());

    String halfList = halfTable + " 2 |\n\n- one\n- tw";
    MarkdownDocument fourth = third.next(halfList);
    assertEquals(MarkdownDocument.parse(halfList).tree(), fourth.tree());
    assertInstanceOf(MarkdownDocument.ListBlock.class, fourth.blocks().getLast());
  }
}
