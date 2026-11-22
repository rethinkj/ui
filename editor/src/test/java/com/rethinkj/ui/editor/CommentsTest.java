package com.rethinkj.ui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.Highlighter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Finding the comments of a text with {@link Comments#scan}. */
class CommentsTest {

  private enum State {
    BLOCK,
    DOC_BLOCK,
    DOC_LINE
  }

  /**
   * Minimal C-like language for testing: double-quoted strings with backslash escapes, {@code //}
   * line comments, {@code ///} doc-comment runs, {@code /* *}{@code /} block comments and {@code
   * /** *}{@code /} doc comments.
   */
  private static final class CLike implements Language {
    private static final Highlighter PLAIN = (line, state, out) -> null;
    private final String id;
    private final AtomicInteger calls;

    CLike(String id, AtomicInteger calls) {
      this.id = id;
      this.calls = calls;
    }

    @Override
    public Highlighter highlighter() {
      return PLAIN;
    }

    @Override
    public Object comments(String line, Object state, Out out) {
      if (calls != null) {
        calls.incrementAndGet();
      }
      int len = line.length();
      int i = 0;
      if (state == State.BLOCK || state == State.DOC_BLOCK) {
        boolean doc = state == State.DOC_BLOCK;
        int close = line.indexOf("*/");
        if (close < 0) {
          if (len > 0) {
            out.comment(0, len, doc, true);
          }
          return state;
        }
        out.comment(0, close + 2, doc, true);
        i = close + 2;
      } else {
        int indent = 0;
        while (indent < len && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
          indent++;
        }
        if (line.startsWith("///", indent)) {
          out.comment(indent, len, true, state == State.DOC_LINE);
          return State.DOC_LINE;
        }
      }
      while (i < len) {
        char c = line.charAt(i);
        if (c == '"') {
          i++;
          while (i < len) {
            char q = line.charAt(i++);
            if (q == '\\' && i < len) {
              i++;
            } else if (q == '"') {
              break;
            }
          }
          continue;
        }
        if (c == '/' && i + 1 < len) {
          char next = line.charAt(i + 1);
          if (next == '/') {
            out.comment(i, len, false, false);
            return null;
          }
          if (next == '*') {
            boolean doc = i + 2 < len
                && line.charAt(i + 2) == '*'
                && !(i + 3 < len && line.charAt(i + 3) == '/');
            int close = line.indexOf("*/", i + (doc ? 3 : 2));
            if (close < 0) {
              out.comment(i, len, doc, false);
              return doc ? State.DOC_BLOCK : State.BLOCK;
            }
            out.comment(i, close + 2, doc, false);
            i = close + 2;
            continue;
          }
        }
        i++;
      }
      return null;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof CLike other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
      return id.hashCode();
    }
  }

  @Test
  void findsLineBlockDocAndTripleSlashCommentsIgnoringStrings() {
    String text = """
        // heading
        String s = "/* not a comment */ // neither";
        /* one-line */ int a; /* starts
        and ends */ int b;
        /**
         * Doc body.

         * More.
         */
          /// First markdown doc line
          /// Second markdown doc line
        /** separate */
        void run() {} // trailing""";
    CLike lang = new CLike("c", null);
    Comments scanned = Comments.scan(text, lang, null);
    assertTrue(scanned.complete());
    assertEquals(13, scanned.scannedLines());

    List<Comments.Comment> list = scanned.comments();
    assertEquals(7, list.size(), list.toString());

    assertComment(text, list.get(0), "// heading", false);
    assertComment(text, list.get(1), "/* one-line */", false);
    assertComment(text, list.get(2), "/* starts\nand ends */", false);
    assertComment(text, list.get(3), "/**\n * Doc body.\n\n * More.\n */", true);
    assertComment(
        text, list.get(4), "/// First markdown doc line\n  /// Second markdown doc line", true);
    assertComment(text, list.get(5), "/** separate */", true);
    assertComment(text, list.get(6), "// trailing", false);
  }

  @Test
  void scansLargeTextInBoundedStepsAndResumesCleanly() {
    int totalLines = 20_000;
    StringBuilder sb = new StringBuilder(totalLines * 24);
    for (int line = 0; line < totalLines; line++) {
      if (line > 0) {
        sb.append('\n');
      }
      if (line == 10) {
        sb.append("// early");
      } else if (line == Comments.LINE_BUDGET - 1) {
        sb.append("/**");
      } else if (line == Comments.LINE_BUDGET) {
        sb.append(" * crosses budget");
      } else if (line == Comments.LINE_BUDGET + 1) {
        sb.append(" */");
      } else if (line == totalLines - 2) {
        sb.append("/// end a");
      } else if (line == totalLines - 1) {
        sb.append("/// end b");
      } else {
        sb.append("int x = \"/* ignored */\";");
      }
    }
    String large = sb.toString();

    AtomicInteger calls = new AtomicInteger();
    CLike lang = new CLike("c", calls);

    Comments first = Comments.scan(large, lang, null);
    assertFalse(first.complete());
    assertEquals(Comments.LINE_BUDGET, first.scannedLines());
    assertEquals(Comments.LINE_BUDGET, calls.get());
    assertEquals(2, first.comments().size());
    assertComment(large, first.comments().get(0), "// early", false);
    assertComment(large, first.comments().get(1), "/**", true); // goes on in the next step

    Comments cur = first;
    int steps = 1;
    while (!cur.complete()) {
      int beforeCalls = calls.get();
      cur = Comments.scan(large, lang, cur);
      assertTrue(calls.get() - beforeCalls <= Comments.LINE_BUDGET);
      steps++;
    }
    assertEquals(5, steps);
    assertEquals(totalLines, cur.scannedLines());
    assertEquals(totalLines, calls.get());

    List<Comments.Comment> expected = cur.comments();
    assertEquals(3, expected.size());
    assertComment(large, expected.get(0), "// early", false);
    assertComment(large, expected.get(1), "/**\n * crosses budget\n */", true);
    assertComment(large, expected.get(2), "/// end a\n/// end b", true);

    // Repeating on a complete result with an equal language returns it as-is without work.
    int callsAtDone = calls.get();
    Comments again = Comments.scan(large, new CLike("c", calls), cur);
    assertSame(cur, again);
    assertEquals(callsAtDone, calls.get());

    // Changing text or language restarts from line 0.
    Comments changedText = Comments.scan(large + "\n// extra", lang, cur);
    assertFalse(changedText.complete());
    assertEquals(Comments.LINE_BUDGET, changedText.scannedLines());

    Comments otherLang = Comments.scan(large, new CLike("other", null), cur);
    assertFalse(otherLang.complete());
    assertEquals(Comments.LINE_BUDGET, otherLang.scannedLines());
  }

  private static void assertComment(
      String text, Comments.Comment comment, String expected, boolean doc) {
    assertEquals(expected, text.substring(comment.start(), comment.end()));
    assertEquals(doc, comment.doc());
  }
}
