package com.rethinkj.ui.javascript;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.FieldState;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Spans;
import com.rethinkj.ui.editor.Comments;
import com.rethinkj.ui.editor.Language;
import com.rethinkj.ui.kit.Theme;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Highlighting, comment scanning and bracket marking of {@link JavaScript} and {@link TypeScript}.
 */
class JavaScriptTest {

  private static final Theme.Code C = new Theme.Code(1, 2, 3, 4, 5, 0, 0);
  private static final JavaScript JS = new JavaScript(C);
  private static final TypeScript TS = new TypeScript(C);

  /** Tokens of each line as "text:kind" using color IDs 1..5 from {@link #C}. */
  private static List<String> tokens(Language lang, String... lines) {
    List<String> out = new ArrayList<>();
    Object state = null;
    for (String line : lines) {
      Spans spans = new Spans(line.length());
      state = lang.line(line, state, spans);
      out.add(formatTokens(line, spans));
    }
    return out;
  }

  private static String formatTokens(String line, Spans spans) {
    StringBuilder out = new StringBuilder();
    for (String range : spans.toString().split("\n")) {
      if (range.isEmpty()) {
        continue;
      }
      String[] parts = range.split(" ");
      String[] at = parts[1].split("-");
      out.append(out.isEmpty() ? "" : " ")
          .append(line, Integer.parseInt(at[0]), Integer.parseInt(at[1]))
          .append(':')
          .append(parts[2]);
    }
    return out.toString();
  }

  @Test
  void languageMetadataAndEquality() {
    assertTrue(JS.marksBrackets());
    assertTrue(TS.marksBrackets());
    assertEquals("//", JS.lineComment());
    assertEquals("//", TS.lineComment());
    assertEquals("$", JS.wordCharacters());
    assertEquals("$", TS.wordCharacters());
    assertEquals(JS, new JavaScript(C));
    assertEquals(TS, new TypeScript(C));
    assertNotEquals(JS, TS);
  }

  @Test
  void keywordsNumbersOperatorsAndPropertyAccess() {
    assertEquals(
        List.of(
            "const:1 =:5 1_000:3 +:5 0xff_00:3 +:5 0b10_10:3 +:5 0o7_5_5:3 +:5 42n:3 +:5 .5e-2:3 ;:5"),
        tokens(JS, "const total = 1_000 + 0xff_00 + 0b10_10 + 0o7_5_5 + 42n + .5e-2;"));
    assertEquals(
        List.of("return:1 .:5 ?.:5 (:5 ...:5 ):5 ;:5"),
        tokens(JS, "return obj.default?.return(...args);"),
        "property names after . and ?. are not colored as keywords");
    assertEquals(
        List.of("class:1 {:5 =:5 1:3 ;:5 }:5"),
        tokens(JS, "class C { #default = 1; }"),
        "private fields are not keywords");
  }

  @Test
  void typeScriptAddsKeywordsAndPrimitiveTypes() {
    String line =
        "export interface User { readonly id: number; name: string; active: boolean; meta: unknown; }";
    assertEquals(
        List.of("export:1 {:5 ::5 ;:5 ::5 ;:5 ::5 ;:5 ::5 ;:5 }:5"),
        tokens(JS, line),
        "JavaScript leaves TypeScript keywords and primitive type names plain");
    assertEquals(
        List.of(
            "export:1 interface:1 {:5 readonly:1 ::5 number:1 ;:5 ::5 string:1 ;:5 ::5 boolean:1 ;:5 ::5 unknown:1 ;:5 }:5"),
        tokens(TS, line),
        "TypeScript colors its keywords and primitive type names as keywords");
    assertEquals(
        List.of("type:1 =:5 keyof:1 |:5 never:1 ;:5"), tokens(TS, "type K = keyof T | never;"));
    assertEquals(
        List.of("const:1 =:5 {:5 }:5 satisfies:1 ;:5"),
        tokens(TS, "const cfg = {} satisfies Config;"));
  }

  @Test
  void stringsEscapesAndBackslashLineContinuation() {
    assertEquals(
        List.of("const:1 =:5 'it\\'s':2 ,:5 =:5 \"a\\\\\":2 ;:5"),
        tokens(JS, "const a = 'it\\'s', b = \"a\\\\\";"));
    assertEquals(
        List.of("const:1 =:5 \"hello \\:2", "world\":2 ;:5"),
        tokens(JS, "const s = \"hello \\", "world\";"),
        "trailing unescaped backslash continues a string onto the next line");
    assertEquals(
        List.of("const:1 =:5 'ends\\\\:2", "const:1 =:5 1:3 ;:5"),
        tokens(JS, "const a = 'ends\\\\", "const b = 1;"),
        "escaped backslash at end of line does not continue the string");
    assertEquals(
        List.of("const:1 =:5 \"unclosed:2", "const:1 =:5 2:3 ;:5"),
        tokens(JS, "const a = \"unclosed", "const b = 2;"),
        "unclosed string without backslash ends at end of line");
  }

  @Test
  void templateLiteralsWithNestedExpressionsAndBraces() {
    assertEquals(
        List.of(
            "const:1 =:5 `outer :2 ${:5",
            "{:5 ::5 `inner :2 ${:5 (:5 {:5 ::5 2:3 }:5 ):5 }:5  end`:2 }:5",
            "}:5  tail`:2 ;:5"),
        tokens(JS, "const s = `outer ${", "  { a: `inner ${fn({ x: 2 })} end` }", "} tail`;"));
    assertEquals(
        List.of("const:1 =:5 `\\`\\${not} :2 ${:5 1:3 }:5 `:2 ;:5"),
        tokens(JS, "const s = `\\`\\${not} ${1}`;"),
        "escaped backticks and ${ stay template text");
    assertNull(
        JS.line("`done`;", null, new Spans(7)),
        "closed template followed by semicolon returns null state");
  }

  @Test
  void regularExpressionLiteralsVsDivision() {
    assertEquals(
        List.of("const:1 =:5 /foo[/]bar\\/baz/gi:2 ;:5"),
        tokens(JS, "const re = /foo[/]bar\\/baz/gi;"),
        "regex with slash inside character class, escaped slash and flags");
    assertEquals(
        List.of("return:1 /abc/u:2 .:5 (:5 ):5 ;:5"),
        tokens(JS, "return /abc/u.test(s);"),
        "regex after expression-preceding keyword");
    assertEquals(
        List.of("const:1 =:5 /:5 /:5 ;:5"),
        tokens(JS, "const q = a / b / c;"),
        "division after identifiers");
    assertEquals(
        List.of("const:1 =:5 (:5 +:5 ):5 /:5 2:3 +:5 [:5 0:3 ]:5 /:5 3:3 +:5 ++:5 /:5 4:3 ;:5"),
        tokens(JS, "const q = (a + b) / 2 + arr[0] / 3 + x++ / 4;"),
        "division after ), ] and ++");
    assertEquals(
        List.of("const:1 =:5 as:1 number:1 /:5 2:3 ;:5"),
        tokens(TS, "const half = value as number / 2;"),
        "division after TypeScript primitive type name");
    assertEquals(
        List.of("const:1 =:5 !:5 /:5 ;:5 // normalize:4"),
        tokens(TS, "const avg = total! / count; // normalize"),
        "division after a non-null assertion");
    assertEquals(
        List.of("!:5 /:5 ;:5 // normalize:4"),
        tokens(TS, "total ! / count; // normalize"),
        "division after a spaced non-null assertion");
    assertEquals(
        List.of("const:1 =:5 (:5 ):5", "!:5 /[/*]/:2 .:5 (:5 ):5 ;:5"),
        tokens(TS, "const path = readPath()", "!/[/*]/.test(path);"),
        "a ! that starts a line is a negation");
    assertEquals(
        List.of("const:1 =:5 (:5 ):5", "!:5 /[/*]/:2 .:5 (:5 ):5 ;:5"),
        tokens(TS, "const path = readPath()", "  !/[/*]/.test(path);"),
        "an indented ! that starts a line is a negation");
    assertEquals(
        List.of("if:1 (:5 ):5 /[/*]/:2 .:5 (:5 ):5 ;:5"),
        tokens(JS, "if (ready) /[/*]/.test(input);"),
        "regex after control header on the same line");
    assertEquals(
        List.of(
            "for:1 await:1 (:5 const:1 of:1 ):5 /[/*]/:2 .:5 (:5 ):5 ;:5",
            "for:1 (:5 const:1 of:1 /[/*]/:2 .:5 (:5 ):5 ?:5 ?:5 [:5 ]:5 ):5 (:5 ):5 ;:5"),
        tokens(
            JS,
            "for await (const item of stream) /[/*]/.test(item);",
            "for (const match of /[/*]/.exec(input) ?? []) consume(match);"),
        "regex after a for await header and after of in a for header");
    assertEquals(
        List.of("(:5 ):5 /:5 ;:5", "(:5 ):5 /:5 ;:5", ".:5 (:5 ):5 /:5 ;:5"),
        tokens(JS, "(x) / y;", "f(x) / y;", "obj.if(x) / y;"),
        "division after grouped, call and property-named parens");
    assertEquals(
        List.of("of:1 /:5 // note:4"),
        tokens(JS, "of / count // note"),
        "division after of used as an identifier");
    assertEquals(
        List.of(".:5", "/:5"),
        tokens(JS, "obj.", "return / count"),
        "keyword after trailing dot on previous line is a property name");
    assertEquals(
        List.of("/:5 ;:5"),
        tokens(JS, "return\u0301 / count;"),
        "combining mark continues an identifier so it is not a keyword");
    assertEquals(
        List.of("const:1 =:5", "/multi/g:2 ;:5"),
        tokens(JS, "const re =", "  /multi/g;"),
        "regex allowed across line break after =");
    assertEquals(
        List.of("const:1 =:5", "/:5 ;:5"),
        tokens(JS, "const q = total", "  / count;"),
        "division across line break after identifier");
    assertEquals(
        List.of("const:1 =:5 /* keep:4", "*/:4 /after/i:2 ;:5"),
        tokens(JS, "const re = /* keep", "*/ /after/i;"),
        "regex state survives a multi-line block comment");
  }

  @Test
  void commentsScanDistinguishesOrdinaryAndDocAndIgnoresLiterals() {
    String text = """
        // heading
        const a = "// not" + '/* not */';
        const b = `/** not */ ${/* in expr */ 1} // not`;
        const re = /http:\\/\\/example.com\\/*/g; // after regex
        if (ready) /[/*]/.test(input);
        /** After control regex. */
        /**/ const empty = 1;
        /* block
           line 2 */
        /**
         * Doc body.

         * More.
         */
        /** single */ const x = 2;""";

    Comments scanned = Comments.scan(text, JS, null);
    assertTrue(scanned.complete());
    List<Comments.Comment> list = scanned.comments();
    assertEquals(8, list.size(), list.toString());

    assertComment(text, list.get(0), "// heading", false);
    assertComment(text, list.get(1), "/* in expr */", false);
    assertComment(text, list.get(2), "// after regex", false);
    assertComment(text, list.get(3), "/** After control regex. */", true);
    assertComment(text, list.get(4), "/**/", false);
    assertComment(text, list.get(5), "/* block\n   line 2 */", false);
    assertComment(text, list.get(6), "/**\n * Doc body.\n\n * More.\n */", true);
    assertComment(text, list.get(7), "/** single */", true);

    assertSame(scanned, Comments.scan(text, new JavaScript(C), scanned), "cached for equal lang");
  }

  @Test
  void bracketsMatchOutsideStringsTemplatesRegexAndComments() {
    TextLibraries.assumeLoaded();
    assertArrayEquals(new int[] {1, 8}, matching(JS, "f(')', 1)", 2), "skips string");
    assertArrayEquals(new int[] {1}, matching(JS, "f(')'", 1), "unmatched ignores string");
    assertArrayEquals(new int[] {1, 10}, matching(JS, "f(/[)]/, 1)", 2), "skips regex");
    assertArrayEquals(new int[] {1, 11}, matching(JS, "f(`)` + `(`)", 2), "skips template text");
    assertArrayEquals(new int[] {4, 9}, matching(JS, "`a ${f(1)}`", 5), "pairs ${ with }");
    assertArrayEquals(new int[] {1, 16}, matching(JS, "f(a // )\n/* ( */)", 2), "skips comments");
  }

  @Test
  void incrementalRehighlightMatchesFullPassAfterEdits() {
    String[] lines = {
      "/** doc",
      " * line */",
      "const s = `a ${",
      "  { k: `b ${1}` }",
      "} c`;",
      "const t = \"hi \\",
      "there\";",
      "const re = /[/]/g;"
    };
    String[] edits = {"`", "${", "}", "/*", "*/", "\"", "\\", "/"};
    for (int editLine = 0; editLine < lines.length; editLine++) {
      for (String edit : edits) {
        String[] before = lines.clone();
        Object[] beforeStates = new Object[before.length];
        String[] beforeTokens = new String[before.length];
        highlightAll(before, beforeStates, beforeTokens);

        String[] after = lines.clone();
        after[editLine] = edit + after[editLine];

        // Incremental pass starting at editLine, stopping once state matches beforeStates[i].
        String[] incremental = beforeTokens.clone();
        Object state = editLine == 0 ? null : beforeStates[editLine - 1];
        for (int i = editLine; i < after.length; i++) {
          Spans spans = new Spans(after[i].length());
          state = TS.line(after[i], state, spans);
          incremental[i] = formatTokens(after[i], spans);
          if (i > editLine && java.util.Objects.equals(state, beforeStates[i])) {
            break;
          }
        }

        Object[] fullStates = new Object[after.length];
        String[] fullTokens = new String[after.length];
        highlightAll(after, fullStates, fullTokens);
        assertArrayEquals(fullTokens, incremental, "edit '" + edit + "' on line " + editLine);
      }
    }
  }

  private static void highlightAll(String[] lines, Object[] states, String[] tokens) {
    Object state = null;
    for (int i = 0; i < lines.length; i++) {
      Spans spans = new Spans(lines[i].length());
      state = TS.line(lines[i], state, spans);
      states[i] = state;
      tokens[i] = formatTokens(lines[i], spans);
    }
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

  private static void assertComment(
      String text, Comments.Comment comment, String expected, boolean doc) {
    assertEquals(expected, text.substring(comment.start(), comment.end()));
    assertEquals(doc, comment.doc());
  }
}
