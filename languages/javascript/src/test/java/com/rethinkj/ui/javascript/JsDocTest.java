package com.rethinkj.ui.javascript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.editor.DocComment;
import com.rethinkj.ui.kit.MarkdownDocument;
import com.rethinkj.ui.kit.Theme;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for JSDoc and TSDoc parsing into {@link DocComment}. */
class JsDocTest {

  private static final JavaScript JS = new JavaScript(Theme.Code.DARK);
  private static final TypeScript TS = new TypeScript(Theme.Code.DARK);

  @Test
  void bodyStripsDelimitersAndPreservesMarkdown() {
    DocComment single = JS.docComment("/** Formats a *price*. */");
    assertNotNull(single.body());
    assertEquals("""
        paragraph
          text "Formats a "
          emphasis
            text "price"
          text "."
        """, single.body().tree());
    assertTrue(single.parameters().isEmpty());
    assertNull(single.returns());
    assertTrue(single.exceptions().isEmpty());
    assertTrue(single.see().isEmpty());
    assertNull(single.deprecated());

    DocComment multi = JS.docComment("""
        /**
         * First paragraph.
         *
         * - item one
         * - item two
         */""");
    assertEquals("""
        paragraph
          text "First paragraph."
        list bullet tight
          item
            paragraph
              text "item one"
          item
            paragraph
              text "item two"
        """, multi.body().tree());

    DocComment empty = JS.docComment("/**\n *\n */");
    assertNull(empty.body());
    assertNull(empty.returns());
    assertNull(empty.deprecated());
  }

  @Test
  void paramsSupportAliasesTypesOptionalsDefaultsDottedNamesAndHyphen() {
    DocComment doc = JS.docComment("""
        /**
         * Connects to a server.
         *
         * @param {string} host - Target hostname.
         * @arg {number} [port=5432] Port number.
         * @argument {Record<string, { id: number }>} [options] - Extra options.
         * @param {boolean} options.tls Enable TLS.
         * @param {string} [options.label="main db"] - Display label.
         * @param plain No type or hyphen.
         * @param indented
         *     Indented description on next line.
         * @param bare
         */""");
    List<DocComment.Entry> params = doc.parameters();
    assertEquals(8, params.size());

    assertEntry(params.get(0), "host", "string", "Target hostname.");
    assertEntry(params.get(1), "[port=5432]", "number", "Port number.");
    assertEntry(params.get(2), "[options]", "Record<string, { id: number }>", "Extra options.");
    assertEntry(params.get(3), "options.tls", "boolean", "Enable TLS.");
    assertEntry(params.get(4), "[options.label=\"main db\"]", "string", "Display label.");
    assertEntry(params.get(5), "plain", null, "No type or hyphen.");
    assertEntry(params.get(6), "indented", null, "Indented description on next line.");
    assertEquals("bare", params.get(7).name());
    assertNull(params.get(7).type());
    assertNull(params.get(7).description());
  }

  @Test
  void returnsHandlesPlainTypedAndTypeOnly() {
    DocComment plain = JS.docComment("/** @return The computed sum. */");
    assertEquals("The computed sum.", plainText(plain.returns()));

    DocComment typed = JS.docComment("/** @returns {Promise<number>} - The total count. */");
    assertEquals("""
        paragraph
          code "Promise<number>"
          text " — The total count."
        """, typed.returns().tree());

    DocComment linked = JS.docComment("/** @returns {@link Result} of the run. */");
    assertEquals("""
        paragraph
          link "Result"
            text "Result"
          text " of the run."
        """, linked.returns().tree());

    DocComment typeOnly = JS.docComment("/** @returns {boolean} */");
    assertEquals("""
        paragraph
          code "boolean"
        """, typeOnly.returns().tree());
  }

  @Test
  void throwsHandlesBracedTsDocAndPlainProse() {
    DocComment doc = JS.docComment("""
        /**
         * @throws {TypeError} When input is not a string.
         * @exception {RangeError} - When out of bounds.
         * @throws {NotFoundError}
         * @throws ValidationError - When validation fails.
         * @throws Will throw if the stream is closed.
         * @throws {@link SyntaxError} When the text is malformed.
         */""");
    List<DocComment.Entry> ex = doc.exceptions();
    assertEquals(6, ex.size());

    assertEntry(ex.get(0), "TypeError", null, "When input is not a string.");
    assertEntry(ex.get(1), "RangeError", null, "When out of bounds.");
    assertEquals("NotFoundError", ex.get(2).name());
    assertNull(ex.get(2).type());
    assertNull(ex.get(2).description());
    assertEntry(ex.get(3), "ValidationError", null, "When validation fails.");
    assertEntry(ex.get(4), "", null, "Will throw if the stream is closed.");
    assertEntry(ex.get(5), "SyntaxError", null, "When the text is malformed.");
  }

  @Test
  void seeDeprecatedAndUnknownTags() {
    DocComment doc = JS.docComment("""
        /**
         * Legacy helper.
         *
         * @author Jane Doe
         * @since 1.0
         * @see {@link parse}
         * @see https://example.com
         * @deprecated Use {@link parseV2} instead.
         */""");
    assertEquals("Legacy helper.", plainText(doc.body()));
    assertEquals(2, doc.see().size());
    assertEquals("""
        paragraph
          link "parse"
            text "parse"
        """, doc.see().get(0).tree());
    assertEquals("""
        paragraph
          link "https://example.com"
            text "https://example.com"
        """, doc.see().get(1).tree());
    assertNotNull(doc.deprecated());
    assertEquals("""
        paragraph
          text "Use "
          link "parseV2"
            text "parseV2"
          text " instead."
        """, doc.deprecated().tree());

    DocComment bareDeprecated = JS.docComment("/** @deprecated */");
    assertNotNull(bareDeprecated.deprecated());
    assertTrue(bareDeprecated.deprecated().blocks().isEmpty());
  }

  @Test
  void exampleAppendsFencedCodeBlockInModuleLanguage() {
    String comment = """
        /**
         * Adds two values.
         *
         * @example
         * const n = add(1, 2);
         * console.log(`sum=${n}`);
         */""";
    DocComment jsDoc = JS.docComment(comment);
    assertEquals(2, jsDoc.body().blocks().size());
    assertEquals(
        new MarkdownDocument.CodeBlock(
            "javascript", "const n = add(1, 2);\nconsole.log(`sum=${n}`);\n"),
        jsDoc.body().blocks().get(1));

    DocComment tsDoc = TS.docComment(comment);
    assertEquals(
        new MarkdownDocument.CodeBlock(
            "typescript", "const n = add(1, 2);\nconsole.log(`sum=${n}`);\n"),
        tsDoc.body().blocks().get(1));

    DocComment fencedAndCaption = TS.docComment("""
        /**
         * @example <caption>With fence</caption>
         * ```ts
         * const x: number = 1;
         * ```
         */""");
    assertEquals(2, fencedAndCaption.body().blocks().size());
    assertEquals(
        "With fence",
        plainText(new MarkdownDocument(List.of(fencedAndCaption.body().blocks().get(0)))));
    assertEquals(
        new MarkdownDocument.CodeBlock("ts", "const x: number = 1;\n"),
        fencedAndCaption.body().blocks().get(1));

    DocComment titled = TS.docComment("""
        /**
         * @example
         * Parsing a price, see {@link parse}:
         * ```ts
         * parse("1.00");
         * ```
         */""");
    assertEquals("""
        paragraph
          text "Parsing a price, see "
          link "parse"
            text "parse"
          text ":"
        code "ts" "parse(\\"1.00\\");\\n"
        """, titled.body().tree());
  }

  @Test
  void inlineLinksExpandOutsideCodeAndRespectVariants() {
    DocComment doc = JS.docComment("""
        /**
         * Uses {@link Foo#bar(x)}, {@link Foo|the foo}, {@link Foo | spaced},
         * {@link https://example.com Example}, {@linkcode Baz.qux},
         * {@linkcode Baz.qux|qux()}, {@linkplain PlainClass|plain text},
         * [the [new] guide]{@link https://example.com/guide}, \s
         * and keeps `{@link Ignored}` literal.
         *
         * ```js
         * // {@link AlsoIgnored}
         * @param notATag
         * ```
         */""");
    assertNotNull(doc.body());
    assertTrue(doc.parameters().isEmpty(), "@param inside fenced code block is not a tag");
    assertEquals("""
        paragraph
          text "Uses "
          link "Foo#bar(x)"
            text "Foo#bar(x)"
          text ", "
          link "Foo"
            text "the foo"
          text ", "
          link "Foo"
            text "spaced"
          text ","
          softbreak
          link "https://example.com"
            text "Example"
          text ", "
          link "Baz.qux"
            code "Baz.qux"
          text ","
          softbreak
          link "Baz.qux"
            code "qux()"
          text ", "
          link "PlainClass"
            text "plain text"
          text ","
          softbreak
          link "https://example.com/guide"
            text "the [new] guide"
          text ","
          hardbreak
          text "and keeps "
          code "{@link Ignored}"
          text " literal."
        code "js" "// {@link AlsoIgnored}\\n@param notATag\\n"
        """, doc.body().tree());
  }

  private static void assertEntry(
      DocComment.Entry entry, String name, String type, String expectedText) {
    assertEquals(name, entry.name());
    assertEquals(type, entry.type());
    assertNotNull(entry.description());
    assertEquals(expectedText, plainText(entry.description()));
  }

  private static String plainText(MarkdownDocument doc) {
    StringBuilder out = new StringBuilder();
    for (MarkdownDocument.Block block : doc.blocks()) {
      if (block instanceof MarkdownDocument.Paragraph p) {
        if (!out.isEmpty()) {
          out.append('\n');
        }
        out.append(MarkdownDocument.plainText(p.content()));
      }
    }
    return out.toString();
  }
}
