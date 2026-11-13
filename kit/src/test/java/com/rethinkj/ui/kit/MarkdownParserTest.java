package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * {@link MarkdownDocument#parse} against examples of the CommonMark specification as trees, and on
 * input that is not Markdown: random characters, deep nesting and a megabyte of each kind, which it
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
    for (int run = 0; run < 3000; run++) {
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
   * A megabyte of each kind of input that makes a careless reader take quadratic time, read in
   * time that grows linearly: twice the text takes about twice as long, never four times.
   */
  @Test
  void aMegabyteTakesLinearTime() {
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
        "\\* &amp; &#x41; ");
    for (String kind : kinds) {
      String small = kind.repeat(512 * 1024 / kind.length());
      String large = kind.repeat(1024 * 1024 / kind.length());
      MarkdownParser.read(small); // warm up
      // A garbage collection on a busy machine can make one measurement several times longer;
      // a reader that takes quadratic time is slow in every one.
      long smallTime = 0;
      long largeTime = 0;
      for (int attempt = 0; attempt < 3; attempt++) {
        smallTime = time(small);
        largeTime = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> time(large));
        if (largeTime < 4 * smallTime + 50_000_000L) {
          break;
        }
      }
      System.out.printf(
          "Markdown of 1 MB of \"%s\": %d ms (512 KB: %d ms)%n",
          kind.replace("\n", "\\n"), largeTime / 1_000_000, smallTime / 1_000_000);
      assertTrue(
          largeTime < 4 * smallTime + 50_000_000L,
          "1 MB of \"" + kind + "\" took " + largeTime / 1e6 + " ms, 512 KB " + smallTime / 1e6);
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

  private static long time(String text) {
    long best = Long.MAX_VALUE;
    for (int i = 0; i < 3; i++) {
      long start = System.nanoTime();
      MarkdownParser.read(text);
      best = Math.min(best, System.nanoTime() - start);
    }
    return best;
  }
}
