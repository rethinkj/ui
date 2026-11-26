package com.rethinkj.ui.java;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.FieldState;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Spans;
import com.rethinkj.ui.editor.Comments;
import com.rethinkj.ui.editor.DocComment;
import com.rethinkj.ui.kit.MarkdownDocument;
import com.rethinkj.ui.kit.Theme;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Java}: lexical highlighting, bracket marking, {@link Comments#scan} ranges
 * for ordinary and doc comments, and parsing of both Javadoc and {@code ///} Markdown doc comments.
 */
class JavaTest {

  private static final Theme.Code C = new Theme.Code(1, 2, 3, 4, 5, 0, 0, 0, 0, 0, 0, 0, 6);
  private static final Java JAVA = new Java(C);

  /** Tokens of each line as "text:kind" using color kinds 1..6 from {@link #C}. */
  private static List<String> tokens(String... lines) {
    List<String> out = new ArrayList<>();
    Object state = null;
    for (String line : lines) {
      Spans spans = new Spans(line.length());
      state = JAVA.line(line, state, spans);
      StringBuilder tokens = new StringBuilder();
      for (String range : spans.toString().split("\n")) {
        if (range.isEmpty()) {
          continue;
        }
        String[] parts = range.split(" ");
        String[] at = parts[1].split("-");
        tokens
            .append(tokens.isEmpty() ? "" : " ")
            .append(line, Integer.parseInt(at[0]), Integer.parseInt(at[1]))
            .append(':')
            .append(parts[2]);
      }
      out.add(tokens.toString());
    }
    return out;
  }

  @Test
  void highlightsKeywordsAnnotationsNumbersStringsTextBlocksAndComments() {
    assertEquals(
        List.of("public:1 sealed:1 interface:1 permits:1 {:5 }:5"),
        tokens("public sealed interface Shape permits Circle {}"));
    assertEquals(
        List.of("non-sealed:1 class:1 @Override:6 @interface:1 @java.lang.Deprecated:6"),
        tokens("non-sealed class Circle @Override @interface @java.lang.Deprecated"));
    assertEquals(
        List.of(
            "0xFF_EC:3 ,:5 0x1.0p-3:3 ,:5 0b1010_01L:3 ,:5 1_000L:3 ,:5 1.5f:3 ,:5 .5:3 ,:5 2e-3d:3"),
        tokens("0xFF_EC, 0x1.0p-3, 0b1010_01L, 1_000L, 1.5f, .5, 2e-3d"));
    assertEquals(
        List.of("var:1 =:5 \"a\\\"/*x*/\":2 ,:5 '\\'':2 ,:5 '/':2 ;:5 // end:4"),
        tokens("var s = \"a\\\"/*x*/\", '\\'', '/'; // end"));
    assertEquals(
        List.of(
            "=:5 \"\"\":2",
            "  // not a comment /* or block */ \\\"\"\" still:2",
            "  \\\\\"\"\":2 +:5 \"ok\":2 ;:5"),
        tokens(
            "String t = \"\"\"",
            "  // not a comment /* or block */ \\\"\"\" still",
            "  \\\\\"\"\" + \"ok\";"));
    assertEquals( // """ with more on its line, as while typing, opens no text block
        List.of("=:5 \"\":2 \";:2", "// c:4"), tokens("String q = \"\"\";", "// c"));
    assertEquals(
        List.of("/* a:4", "b */:4 return:1 true:1 ;:5"), tokens("/* a", "b */ return true;"));
    assertEquals("//", JAVA.lineComment());
    assertTrue(JAVA.marksBrackets());
  }

  @Test
  void highlighterAndCommentScannerAgreeAcrossEdits() {
    String[] sources = {
      "String s = \"\"\"\n  /* a */\n  \\\"\"\"\n\"\"\";",
      "/* a\n * b\n */ int x = 1;",
      "String a = \"x\"; // c\nchar b = '\\'';\n/// doc\nint x = 1;"
    };
    for (String source : sources) {
      for (int at = 0; at <= source.length(); at++) {
        for (String insert : new String[] {"\"\"\"", "\"", "'", "/*", "*/", "//", "///", "\\"}) {
          assertCommentsMatchHighlight(source.substring(0, at) + insert + source.substring(at));
        }
      }
    }
  }

  private static void assertCommentsMatchHighlight(String source) {
    int[] highlighted = new int[source.length()];
    Object state = null;
    int start = 0;
    for (String line : source.split("\n", -1)) {
      Spans spans = new Spans(line.length());
      state = JAVA.line(line, state, spans);
      System.arraycopy(colors(spans, line.length()), 0, highlighted, start, line.length());
      start += line.length() + 1;
    }
    Comments scanned = Comments.scan(source, JAVA, null);
    assertTrue(scanned.complete());
    boolean[] inComment = new boolean[source.length()];
    for (Comments.Comment comment : scanned.comments()) {
      Arrays.fill(inComment, comment.start(), comment.end(), true);
    }
    for (int i = 0; i < source.length(); i++) {
      if (source.charAt(i) == '\n') {
        continue;
      }
      assertEquals(inComment[i], highlighted[i] == C.comment(), "at " + i + " in:\n" + source);
    }
  }

  private static int[] colors(Spans spans, int length) {
    int[] out = new int[length];
    for (String line : spans.toString().split("\n")) {
      if (line.isEmpty()) {
        continue;
      }
      String[] parts = line.split(" ");
      String[] range = parts[1].split("-");
      Arrays.fill(
          out,
          Integer.parseInt(range[0]),
          Integer.parseInt(range[1]),
          Integer.parseInt(parts[2], 16));
    }
    return out;
  }

  @Test
  void bracketsMatchOutsideStringsCharsTextBlocksAndComments() {
    TextLibraries.assumeLoaded();
    String text = "f(\"}\", ')', \"\"\"\n}\n\"\"\", /* ) */ 1)";
    assertArrayEquals(new int[] {1, text.length() - 1}, matching(JAVA, text, 2));
    assertArrayEquals(new int[0], matching(JAVA, text, 3), "bracket inside string");
    assertArrayEquals(new int[0], matching(JAVA, text, 8), "bracket inside char");
  }

  private static int[] matching(Highlighter highlighter, String text, int caret) {
    FieldState state = new FieldState();
    Supplier<El> field = () -> El.text(text)
        .editable(value -> {})
        .multiline()
        .fieldState(state)
        .highlight(highlighter, highlighter);
    App<Object> app = new App<>(new Object(), model -> field.get());
    app.resize(400, 200, 1);
    app.frame();
    return field.get().matchingBracket(caret);
  }

  @Test
  void scansOrdinaryAndDocCommentsIgnoringStringsCharsAndTextBlocks() {
    String source = """
        // heading
        String s = "/* nope */ // nope /// nope";
        char c = '/';
        String block = \"\"\"
          /// not a doc comment
          /** also not a doc comment */
          \"\"\";
        /**/ int empty;
        /* multi
           line */ int a;
        /**
         * Javadoc body.
         */
          /// First markdown line
          /// Second markdown line

        /// Separate run
        int x = 1; /// trailing after code""";
    Comments scanned = Comments.scan(source, JAVA, null);
    assertTrue(scanned.complete());
    List<Comments.Comment> list = scanned.comments();
    assertEquals(7, list.size(), list.toString());

    assertComment(source, list.get(0), "// heading", false);
    assertComment(source, list.get(1), "/**/", false);
    assertComment(source, list.get(2), "/* multi\n   line */", false);
    assertComment(source, list.get(3), "/**\n * Javadoc body.\n */", true);
    assertComment(source, list.get(4), "/// First markdown line\n  /// Second markdown line", true);
    assertComment(source, list.get(5), "/// Separate run", true);
    assertComment(source, list.get(6), "/// trailing after code", false);
  }

  @Test
  void unclosedCommentReachesTheEnd() {
    String source = "int a;\n/** draft\n\n";
    List<Comments.Comment> list = Comments.scan(source, JAVA, null).comments();
    assertEquals(List.of(new Comments.Comment(7, source.length(), true)), list);
  }

  private static void assertComment(
      String text, Comments.Comment comment, String expected, boolean doc) {
    assertEquals(expected, text.substring(comment.start(), comment.end()));
    assertEquals(doc, comment.doc());
  }

  @Test
  void parsesTraditionalJavadocTagsInlineTagsAndHtmlSubset() {
    String javadoc = """
        /**
         * Computes <b>sum</b> and <i>product</i> for {@code a < b} with {@literal x & y > 0}.
         * <p>See {@link Foo#bar(int, String)} and {@link Foo#bar(int) the bar method},
         * or {@linkplain Baz} and {@linkplain Baz#go() go now}, plus {@inheritDoc}.
         * <br>Entities: &lt; &gt; &amp; &quot; &#64; &#x2A; and <code>List&lt;T&gt;</code>.
         * <p>External: <a href="https://example.com/?a=1&amp;b=2"><strong>docs</strong></a>.
         * <p>Canonical: <code>new File({@link #path})</code>, {@link Map<String, Integer>}.
         * <ul>
         *   <li>first item</li>
         *   <li><em>second</em> item</li>
         * </ul>
         * <ol>
         *   <li>step one</li>
         *   <li>step two</li>
         * </ol>
         * <pre>{@code
         * @Override
         * public int run() { return 1; }
         * }</pre>
         * <pre>    +-- root
         *    |   leaf
         *    +-- end</pre>
         *
         * @param <T> the element type
         * @param count number of {@code items}
         * @param empty
         * @return the computed value
         * @throws IllegalArgumentException when {@code count < 0}
         * @exception java.io.IOException on I/O failure
         * @see Foo#bar(int, String)
         * @see Foo#bar(int) custom label
         * @see <a href="https://example.com">Spec</a>
         * @see "The Java Language Specification"
         * @deprecated use {@link #compute()} instead
         * @since 1.0
         */""";

    DocComment doc = JAVA.docComment(javadoc);
    assertNotNull(doc);
    assertNotNull(doc.body());
    String bodyTree = doc.body().tree();
    assertTrue(bodyTree.contains("strong\n    text \"sum\""), bodyTree);
    assertTrue(bodyTree.contains("emphasis\n    text \"product\""), bodyTree);
    assertTrue(bodyTree.contains("code \"a < b\""), bodyTree);
    assertTrue(bodyTree.contains("text \" with x & y > 0.\""), bodyTree);
    assertTrue(
        bodyTree.contains("link \"Foo#bar(int, String)\"\n    code \"Foo#bar(int, String)\""),
        bodyTree);
    assertTrue(bodyTree.contains("link \"Foo#bar(int)\"\n    text \"the bar method\""), bodyTree);
    assertTrue(bodyTree.contains("link \"Baz\"\n    text \"Baz\""), bodyTree);
    assertTrue(bodyTree.contains("link \"Baz#go()\"\n    text \"go now\""), bodyTree);
    assertTrue(bodyTree.contains("{@inheritDoc}"), bodyTree);
    assertTrue(bodyTree.contains("hardbreak"), bodyTree);
    assertTrue(bodyTree.contains("Entities: < > & \\\" @ * and "), bodyTree);
    assertTrue(bodyTree.contains("code \"List<T>\""), bodyTree);
    assertTrue(
        bodyTree.contains("link \"https://example.com/?a=1&b=2\"\n    strong\n      text \"docs\""),
        bodyTree);
    assertTrue(bodyTree.contains("list bullet tight"), bodyTree);
    assertTrue(bodyTree.contains("list ordered start=1 tight"), bodyTree);
    assertTrue(
        bodyTree.contains("code \"\" \"@Override\\npublic int run() { return 1; }\\n\""), bodyTree);
    assertTrue(
        bodyTree.contains("code \"\" \"+-- root\\n|   leaf\\n+-- end\\n\""), bodyTree); // aligned
    assertTrue(bodyTree.contains("link \"Map<String, Integer>\""), bodyTree);
    assertTrue(
        bodyTree.contains("code \"new File(\"\n  link \"#path\"\n    code \"#path\"\n  code \")\""),
        bodyTree);
    assertTrue(bodyTree.contains("text \"@since 1.0\""), bodyTree);

    assertEquals(3, doc.parameters().size());
    assertEquals("<T>", doc.parameters().get(0).name());
    assertNull(doc.parameters().get(0).type());
    assertEquals("the element type", plainText(doc.parameters().get(0).description()));
    assertEquals("count", doc.parameters().get(1).name());
    assertTrue(doc.parameters().get(1).description().tree().contains("code \"items\""));
    assertEquals("empty", doc.parameters().get(2).name());
    assertNull(doc.parameters().get(2).description());

    assertNotNull(doc.returns());
    assertEquals("the computed value", plainText(doc.returns()));

    assertEquals(2, doc.exceptions().size());
    assertEquals("IllegalArgumentException", doc.exceptions().get(0).name());
    assertNull(doc.exceptions().get(0).type());
    assertTrue(doc.exceptions().get(0).description().tree().contains("code \"count < 0\""));
    assertEquals("java.io.IOException", doc.exceptions().get(1).name());
    assertEquals("on I/O failure", plainText(doc.exceptions().get(1).description()));

    assertEquals(4, doc.see().size());
    assertTrue(doc.see()
        .get(0)
        .tree()
        .contains("link \"Foo#bar(int, String)\"\n    code \"Foo#bar(int, String)\""));
    assertTrue(
        doc.see().get(1).tree().contains("link \"Foo#bar(int)\"\n    text \"custom label\""));
    assertTrue(doc.see().get(2).tree().contains("link \"https://example.com\"\n    text \"Spec\""));
    assertEquals("\"The Java Language Specification\"", plainText(doc.see().get(3)));

    assertNotNull(doc.deprecated());
    assertTrue(doc.deprecated().tree().contains("link \"#compute()\"\n    code \"#compute()\""));
  }

  @Test
  void nestedBracesInInlineCodeAndBareDeprecated() {
    DocComment doc = JAVA.docComment("""
        /**
         * Lambda {@code () -> { return Map.of("k", "`v`"); }}.
         * @deprecated
         */""");
    assertNotNull(doc.body());
    assertTrue(
        doc.body().tree().contains("code \"() -> { return Map.of(\\\"k\\\", \\\"`v`\\\"); }\""),
        doc.body().tree());
    assertNotNull(doc.deprecated());
    assertTrue(doc.deprecated().blocks().isEmpty());
  }

  @Test
  void parsesTripleSlashMarkdownDocCommentsAndKeepsFencedAtLinesInCode() {
    String md = """
          /// Computes a **weighted** sum.
          ///
          /// ```java
          /// @Override
          /// @param notATag
          /// public void run() {}
          /// ```
          ///
          /// - item one
          /// - item two
          /// - ```
          ///   @param inList
          ///   ```
          ///
          /// @param <T> the *element* type
          /// @param x input value with `code`
          /// @return
          ///     the result
          ///     ```yaml
          ///     a: 1
          ///
          ///     b: 2
          ///     ```
          ///
          ///     Result r = run();
          ///
          /// > ```
          /// > @param inQuote
          /// > ```
          /// @throws IllegalStateException
          ///     when closed
          /// @see Foo#bar(int)
          /// @see [Docs](https://example.com)
          /// @deprecated ```
          ///     use(next());
          ///     ```
          ///
          ///     old();
          /// @since 23""";

    DocComment doc = JAVA.docComment(md);
    assertNotNull(doc);
    assertNotNull(doc.body());
    String bodyTree = doc.body().tree();
    assertTrue(bodyTree.contains("strong\n    text \"weighted\""), bodyTree);
    assertTrue(
        bodyTree.contains("code \"java\" \"@Override\\n@param notATag\\npublic void run() {}\\n\""),
        bodyTree);
    assertTrue(bodyTree.contains("list bullet tight"), bodyTree);
    assertTrue(bodyTree.contains("code \"\" \"@param inList\\n\""), bodyTree);
    assertTrue(bodyTree.contains("text \"@since 23\""), bodyTree);

    assertEquals(2, doc.parameters().size());
    assertEquals("<T>", doc.parameters().get(0).name());
    assertTrue(
        doc.parameters().get(0).description().tree().contains("emphasis\n    text \"element\""));
    assertEquals("x", doc.parameters().get(1).name());
    assertTrue(doc.parameters().get(1).description().tree().contains("code \"code\""));

    assertNotNull(doc.returns());
    assertEquals("the result", plainText(doc.returns()));
    assertTrue(doc.returns().tree().contains("code \"yaml\" \"a: 1\\n\\nb: 2\\n\""));
    assertTrue(doc.returns().tree().contains("code \"\" \"Result r = run();\\n\""));
    assertTrue(doc.returns().tree().contains("code \"\" \"@param inQuote\\n\""));

    assertEquals(1, doc.exceptions().size());
    assertEquals("IllegalStateException", doc.exceptions().get(0).name());
    assertEquals("when closed", plainText(doc.exceptions().get(0).description()));

    assertEquals(2, doc.see().size());
    assertTrue(
        doc.see().get(0).tree().contains("link \"Foo#bar(int)\"\n    code \"Foo#bar(int)\""));
    assertTrue(doc.see().get(1).tree().contains("link \"https://example.com\"\n    text \"Docs\""));

    assertNotNull(doc.deprecated());
    assertTrue(doc.deprecated().tree().contains("code \"\" \"use(next());\\n\""));
    assertTrue(doc.deprecated().tree().contains("code \"\" \"old();\\n\""));
  }

  @Test
  void tripleSlashMarkdownDocCommentsHandleInlineTagsAndReferenceLinks() {
    String javadoc = """
        /**
         * Uses {@code a*b*c} and {@literal x*y*z & 0}, see {@link String}, {@link Foo#bar(int, String) the bar method}, {@linkplain Baz}, {@linkplain Baz#go() go now}, and {@inheritDoc}.
         *
         * @param count number of {@code *items*} for {@link List}
         * @return the {@literal *result*} or {@linkplain String text}
         * @throws IllegalArgumentException when {@code a*b < 0}
         * @see Foo#bar(int) custom {@code label}
         * @deprecated use {@link #compute()} instead
         */""";
    String markdown = """
        /// Uses {@code a*b*c} and {@literal x*y*z & 0}, see {@link String}, {@link Foo#bar(int, String) the bar method}, {@linkplain Baz}, {@linkplain Baz#go() go now}, and {@inheritDoc}.
        ///
        /// @param count number of {@code *items*} for {@link List}
        /// @return the {@literal *result*} or {@linkplain String text}
        /// @throws IllegalArgumentException when {@code a*b < 0}
        /// @see Foo#bar(int) custom {@code label}
        /// @deprecated use {@link #compute()} instead""";

    DocComment expected = JAVA.docComment(javadoc);
    DocComment actual = JAVA.docComment(markdown);
    assertEquals(expected.body().tree(), actual.body().tree());
    assertEquals(expected.parameters().size(), actual.parameters().size());
    assertEquals(
        expected.parameters().get(0).description().tree(),
        actual.parameters().get(0).description().tree());
    assertEquals(expected.returns().tree(), actual.returns().tree());
    assertEquals(
        expected.exceptions().get(0).description().tree(),
        actual.exceptions().get(0).description().tree());
    assertEquals(expected.see().get(0).tree(), actual.see().get(0).tree());
    assertEquals(expected.deprecated().tree(), actual.deprecated().tree());
  }

  @Test
  void tripleSlashMarkdownDocCommentsKeepTagsInCodeAndLinkReferences() {
    DocComment codeAndRefs = JAVA.docComment("""
        /// Keep `{@code x}` and `{@link Foo}` in code spans.
        ///
        /// ```java
        /// // {@code a*b*c} and {@link String}
        /// ```
        ///
        ///     {@code indented}
        ///
        /// Links: [String], [String][], [List#add(Object)], [#field], [java.util.Map.Entry], [a list][List], [see {@link String}](https://example.com), and [Custom].
        ///
        /// Not links: [not a ref], [0], [(optional)], ![String].
        ///
        /// > Quote with {@code q*1*} and [String].
        ///
        /// > [!NOTE]
        /// > Alert with [String].
        ///
        /// | Tag | Ref |
        /// | --- | --- |
        /// | {@code t*1*} | [List] |
        ///
        /// [Custom]: https://example.com/custom
        /// @see [List#add(Object)]
        /// @see [a list][List]""");

    String tree = codeAndRefs.body().tree();
    assertTrue(tree.contains("code \"{@code x}\""), tree);
    assertTrue(tree.contains("code \"{@link Foo}\""), tree);
    assertTrue(tree.contains("code \"java\" \"// {@code a*b*c} and {@link String}\\n\""), tree);
    assertTrue(tree.contains("code \"\" \"{@code indented}\\n\""), tree);
    assertTrue(tree.contains("link \"String\"\n    code \"String\""), tree);
    assertTrue(tree.contains("link \"List#add(Object)\"\n    code \"List#add(Object)\""), tree);
    assertTrue(tree.contains("link \"#field\"\n    code \"#field\""), tree);
    assertTrue(
        tree.contains("link \"java.util.Map.Entry\"\n    code \"java.util.Map.Entry\""), tree);
    assertTrue(tree.contains("link \"List\"\n    text \"a list\""), tree);
    assertTrue(
        tree.contains("link \"https://example.com\"\n    text \"see \"\n    code \"String\""),
        tree);
    assertTrue(tree.contains("link \"https://example.com/custom\"\n    text \"Custom\""), tree);
    assertTrue(
        tree.contains("text \"Not links: [not a ref], [0], [(optional)], ![String].\""), tree);
    assertTrue(
        tree.contains("quote\n  paragraph\n    text \"Quote with \"\n    code \"q*1*\""), tree);
    assertTrue(
        tree.contains(
            "quote alert=note\n  paragraph\n    text \"Alert with \"\n    link \"String\""),
        tree);
    assertTrue(tree.contains("cell\n      code \"t*1*\""), tree);
    assertTrue(tree.contains("cell\n      link \"List\"\n        code \"List\""), tree);

    assertEquals(2, codeAndRefs.see().size());
    assertTrue(
        codeAndRefs
            .see()
            .get(0)
            .tree()
            .contains("link \"List#add(Object)\"\n    code \"List#add(Object)\""),
        codeAndRefs.see().get(0).tree());
    assertTrue(
        codeAndRefs.see().get(1).tree().contains("link \"List\"\n    text \"a list\""),
        codeAndRefs.see().get(1).tree());

    assertEquals(
        """
        paragraph
          text "Write "
          code "{@code"
          text " and close it with "
          code "}"
          text "."
        """, JAVA.docComment("/// Write `{@code` and close it with `}`.").body().tree());
    assertEquals(
        "hello and \uFFFD0\uFFFD",
        plainText(JAVA.docComment("/// {@code hello} and &#xD800;0&#xDC00;").body()));
    assertTrue(
        JAVA.docComment("/// \uD83D\uDC00 {@code a*b*c}").body().tree().contains("code \"a*b*c\""));
    assertTrue(JAVA.docComment("/// [java.base/]").body().tree().contains("link \"java.base/\""));
    assertTrue(JAVA.docComment("/// [reset()]").body().tree().contains("link \"reset()\""));
  }

  @Test
  void tripleSlashMarkdownDocCommentsMatchParserCodeBlocks() {
    DocComment indentedFence = JAVA.docComment("""
        /// Intro.
        ///
        ///     ```
        ///     @param inIndented
        ///
        /// @param a after indented""");
    assertTrue(
        indentedFence.body().tree().contains("code \"\" \"```\\n@param inIndented\\n\""),
        indentedFence.body().tree());
    assertEquals(1, indentedFence.parameters().size());
    assertEquals("a", indentedFence.parameters().get(0).name());
    assertEquals("after indented", plainText(indentedFence.parameters().get(0).description()));

    DocComment quotedCloserInFence = JAVA.docComment("""
        /// ```markdown
        /// > ```
        /// @param inFence
        /// ```
        /// @param b after fence""");
    assertTrue(
        quotedCloserInFence
            .body()
            .tree()
            .contains("code \"markdown\" \"> ```\\n@param inFence\\n\""),
        quotedCloserInFence.body().tree());
    assertEquals(1, quotedCloserInFence.parameters().size());
    assertEquals("b", quotedCloserInFence.parameters().get(0).name());
    assertEquals(
        "after fence", plainText(quotedCloserInFence.parameters().get(0).description()));

    DocComment paramOpensFence = JAVA.docComment("""
        /// @param x ```java
        /// @Override
        /// @return inParamFence
        /// ```
        /// @return real return""");
    assertEquals(1, paramOpensFence.parameters().size());
    assertEquals("x", paramOpensFence.parameters().get(0).name());
    assertTrue(
        paramOpensFence
            .parameters()
            .get(0)
            .description()
            .tree()
            .contains("code \"java\" \"@Override\\n@return inParamFence\\n\""),
        paramOpensFence.parameters().get(0).description().tree());
    assertEquals("real return", plainText(paramOpensFence.returns()));
    assertEquals(
        1, JAVA.docComment("/// @since ```\n/// @param x value").parameters().size());

    DocComment indentedInline = JAVA.docComment("""
        /// Intro
        ///
        ///     {@code open
        ///
        /// Outside }""");
    assertEquals("""
        paragraph
          text "Intro"
        code "" "{@code open\\n"
        paragraph
          text "Outside }"
        """, indentedInline.body().tree());
  }

  @Test
  void tripleSlashMarkdownBlockTagContinuationKeepsMixedIndentAndListFencesConsistent() {
    DocComment listFence = JAVA.docComment("""
        /// @return - ```java
        ///   @Override
        ///       indented();
        ///   ```
        /// @param x value""");
    assertNull(listFence.body());
    assertEquals(1, listFence.parameters().size());
    assertEquals("x", listFence.parameters().get(0).name());
    assertEquals("""
        list bullet tight
          item
            code "java" "@Override\\n    indented();\\n"
        """, listFence.returns().tree());

    DocComment listFenceIndentedFirst = JAVA.docComment("""
        /// @return - ```java
        ///       indented();
        ///   ```
        /// @param x value""");
    assertEquals(1, listFenceIndentedFirst.parameters().size());
    assertEquals("""
        list bullet tight
          item
            code "java" "    indented();\\n"
        """, listFenceIndentedFirst.returns().tree());

    DocComment fenceToClosure = JAVA.docComment("""
        /// @return ```java
        ///     return result;
        /// }
        /// ```
        /// @param x value""");
    assertEquals(1, fenceToClosure.parameters().size());
    assertEquals("""
        code "java" "    return result;\\n}\\n"
        """, fenceToClosure.returns().tree());

    DocComment indentedMarker = JAVA.docComment("""
        /// @return ```md
        /// text
        ///     ```
        /// @param inExample
        /// ```
        /// @param x value""");
    assertEquals(1, indentedMarker.parameters().size());
    assertEquals("x", indentedMarker.parameters().get(0).name());

    DocComment fenceAfterProse = JAVA.docComment("""
        /// @return text
        ///   more
        /// ```md
        ///     ```
        /// @param inExample
        /// ```
        /// @param x value""");
    assertEquals(1, fenceAfterProse.parameters().size());
    assertEquals("x", fenceAfterProse.parameters().get(0).name());

    DocComment mixedIndent = JAVA.docComment("""
        /// @return
        ///     ```
        ///    @param x value
        /// tail""");
    assertNull(mixedIndent.body());
    assertTrue(mixedIndent.parameters().isEmpty());
    assertNotNull(mixedIndent.returns());
    assertEquals("""
        code "" "@param x value\\ntail\\n"
        """, mixedIndent.returns().tree());

    DocComment sinceMixedIndent = JAVA.docComment("""
        /// @since ```beta
        ///     ```
        ///    @param x value
        /// tail""");
    assertTrue(sinceMixedIndent.parameters().isEmpty());
    assertNotNull(sinceMixedIndent.body());
    assertEquals("""
        paragraph
          text "@since ```beta"
        code "" "@param x value\\ntail\\n"
        """, sinceMixedIndent.body().tree());
  }

  @Test
  void untrustedPathologicalInputParsesSafelyWithoutOverflow() {
    String evil = "/** "
        + "<b>".repeat(2_000)
        + "<ul><li>".repeat(2_000)
        + "{@code ".repeat(2_000)
        + "<code>".repeat(2_000)
        + "<pre>".repeat(500)
        + "<a href=\"x".repeat(500)
        + "{@link a ".repeat(20_000) // links in labels: nested deeper than the stack goes
        + "}".repeat(20_000)
        + "*/";
    DocComment doc = JAVA.docComment(evil);
    assertNotNull(doc);
  }

  private static String plainText(MarkdownDocument doc) {
    assertNotNull(doc);
    StringBuilder sb = new StringBuilder();
    for (MarkdownDocument.Block b : doc.blocks()) {
      if (b instanceof MarkdownDocument.Paragraph p) {
        if (!sb.isEmpty()) {
          sb.append('\n');
        }
        sb.append(MarkdownDocument.plainText(p.content()));
      }
    }
    return sb.toString();
  }
}
