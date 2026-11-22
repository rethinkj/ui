package com.rethinkj.ui.java;

import com.rethinkj.ui.Spans;
import com.rethinkj.ui.editor.DocComment;
import com.rethinkj.ui.editor.Language;
import com.rethinkj.ui.kit.Theme;
import java.util.Objects;
import java.util.Set;

/**
 * A {@link Language} of Java in the colors of {@link Theme.Code}: keywords and boolean/{@code null}
 * literals, annotations ({@link Theme.Code#property()}), strings, character literals and text
 * blocks ({@code """..."""}), numbers (decimal, hexadecimal, binary, underscores, floating-point
 * and type suffixes), line comments ({@code //}), Markdown doc comments ({@code ///}), block
 * comments ({@code /* ... *}{@code /}), Javadoc comments ({@code /** ... *}{@code /}), and
 * operators, brackets and punctuation. It marks the brackets outside strings, character literals,
 * text blocks and comments ({@link Spans#bracket}), which the editor pairs and folds.
 *
 * <p>{@link #comments} reports ordinary comments ({@code //}, {@code /* ... *}{@code /}) and doc
 * comments ({@code /** ... *}{@code /} and consecutive lines whose first non-blank characters are
 * {@code ///}), recognizing strings, character literals and text blocks first so delimiters inside
 * them are not comments. {@link #docComment} parses both Javadoc and {@code ///} Markdown doc
 * comments into {@link DocComment}.
 *
 * <p>A record, so that two languages of the same colors are equal and caches keep what they asked
 * of one while the view passes it again. It is lexical only and scans each line once, character by
 * character, without regular expressions.
 */
public record Java(Theme.Code colors) implements Language {

  public Java {
    Objects.requireNonNull(colors, "colors");
  }

  /**
   * The Java language in the colors for code of {@link Theme#CURRENT}; in the view, where the app
   * provides its theme.
   */
  public static Java of() {
    Theme theme = Theme.CURRENT.get();
    return new Java(theme.code());
  }

  private enum LineState {
    BLOCK_COMMENT,
    TEXT_BLOCK
  }

  private enum CommentState {
    BLOCK,
    DOC_BLOCK,
    DOC_LINE,
    TEXT_BLOCK
  }

  private static final String OPERATORS = "+-*/%<>=!~^&|?:;,.()[]{}@";
  private static final String BRACKETS = "()[]{}";

  private static final Set<String> KEYWORDS = Set.of(
      "abstract",
      "assert",
      "boolean",
      "break",
      "byte",
      "case",
      "catch",
      "char",
      "class",
      "const",
      "continue",
      "default",
      "do",
      "double",
      "else",
      "enum",
      "extends",
      "false",
      "final",
      "finally",
      "float",
      "for",
      "goto",
      "if",
      "implements",
      "import",
      "instanceof",
      "int",
      "interface",
      "long",
      "native",
      "new",
      "null",
      "package",
      "permits",
      "private",
      "protected",
      "public",
      "record",
      "return",
      "sealed",
      "short",
      "static",
      "strictfp",
      "super",
      "switch",
      "synchronized",
      "this",
      "throw",
      "throws",
      "transient",
      "true",
      "try",
      "var",
      "void",
      "volatile",
      "while",
      "yield");

  private static final int LONGEST_KEYWORD = 12; // synchronized

  @Override
  public Object line(String line, Object state, Spans out) {
    LineState s = (LineState) state;
    int n = line.length();
    int i = 0;
    int start = 0;
    while (i < n) {
      if (s == LineState.BLOCK_COMMENT) {
        int close = line.indexOf("*/", i);
        if (close < 0) {
          out.color(start, n, colors.comment());
          return LineState.BLOCK_COMMENT;
        }
        i = close + 2;
        out.color(start, i, colors.comment());
        s = null;
        continue;
      }
      if (s == LineState.TEXT_BLOCK) {
        int close = scanTextBlock(line, i);
        if (close < 0) {
          out.color(start, n, colors.string());
          return LineState.TEXT_BLOCK;
        }
        i = close;
        out.color(start, i, colors.string());
        s = null;
        continue;
      }
      start = i;
      char c = line.charAt(i);
      char next = i + 1 < n ? line.charAt(i + 1) : 0;
      if (opensTextBlock(line, i)) {
        s = LineState.TEXT_BLOCK;
        i += 3;
      } else if (c == '"') {
        i = scanQuoted(line, i, '"');
        out.color(start, i, colors.string());
      } else if (c == '\'') {
        i = scanQuoted(line, i, '\'');
        out.color(start, i, colors.string());
      } else if (c == '/' && next == '/') {
        out.color(i, n, colors.comment());
        return null;
      } else if (c == '/' && next == '*') {
        s = LineState.BLOCK_COMMENT;
        i += 2;
      } else if (c == '@' && isIdentStart(next)) {
        i = scanIdent(line, i + 1);
        while (i + 1 < n && line.charAt(i) == '.' && isIdentStart(line.charAt(i + 1))) {
          i = scanIdent(line, i + 1); // a qualified name: @java.lang.Override
        }
        if (i - start == 10 && line.startsWith("interface", start + 1)) {
          out.color(start, i, colors.keyword());
        } else {
          out.color(start, i, colors.property());
        }
      } else if (isDigit(c) || c == '.' && isDigit(next)) {
        i = scanNumber(line, i);
        out.color(start, i, colors.number());
      } else if (isIdentStart(c)) {
        i = scanIdent(line, i);
        int len = i - start;
        if (len == 3
            && line.startsWith("non", start)
            && line.startsWith("-sealed", i)
            && (i + 7 >= n || !isIdentPart(line.charAt(i + 7)))) {
          i += 7;
          out.color(start, i, colors.keyword());
        } else if (len <= LONGEST_KEYWORD && KEYWORDS.contains(line.substring(start, i))) {
          out.color(start, i, colors.keyword());
        }
      } else if (OPERATORS.indexOf(c) >= 0) {
        out.color(i, i + 1, colors.operator());
        if (BRACKETS.indexOf(c) >= 0) {
          out.bracket(i);
        }
        i++;
      } else {
        i++;
      }
    }
    if (s == LineState.BLOCK_COMMENT) {
      out.color(start, n, colors.comment());
      return LineState.BLOCK_COMMENT;
    }
    if (s == LineState.TEXT_BLOCK) {
      out.color(start, n, colors.string());
      return LineState.TEXT_BLOCK;
    }
    return null;
  }

  @Override
  public Object comments(String line, Object state, Out out) {
    CommentState s = (CommentState) state;
    int n = line.length();
    int i = 0;
    if (s == CommentState.BLOCK || s == CommentState.DOC_BLOCK) {
      boolean doc = s == CommentState.DOC_BLOCK;
      int close = line.indexOf("*/");
      if (close < 0) {
        out.comment(0, n, doc, true); // an empty line too, so an unclosed comment ends at the end
        return s;
      }
      out.comment(0, close + 2, doc, true);
      i = close + 2;
      s = null;
    } else if (s == CommentState.TEXT_BLOCK) {
      int close = scanTextBlock(line, 0);
      if (close < 0) {
        return CommentState.TEXT_BLOCK;
      }
      i = close;
      s = null;
    } else {
      int indent = 0;
      while (indent < n && Character.isWhitespace(line.charAt(indent))) {
        indent++;
      }
      if (line.startsWith("///", indent)) {
        out.comment(indent, n, true, s == CommentState.DOC_LINE);
        return CommentState.DOC_LINE;
      }
      i = indent;
      s = null;
    }
    while (i < n) {
      char c = line.charAt(i);
      char next = i + 1 < n ? line.charAt(i + 1) : 0;
      if (opensTextBlock(line, i)) {
        int close = scanTextBlock(line, i + 3);
        if (close < 0) {
          return CommentState.TEXT_BLOCK;
        }
        i = close;
      } else if (c == '"') {
        i = scanQuoted(line, i, '"');
      } else if (c == '\'') {
        i = scanQuoted(line, i, '\'');
      } else if (c == '/' && next == '/') {
        out.comment(i, n, false, false);
        return null;
      } else if (c == '/' && next == '*') {
        boolean doc =
            i + 2 < n && line.charAt(i + 2) == '*' && !(i + 3 < n && line.charAt(i + 3) == '/');
        int close = line.indexOf("*/", i + (doc ? 3 : 2));
        if (close < 0) {
          out.comment(i, n, doc, false);
          return doc ? CommentState.DOC_BLOCK : CommentState.BLOCK;
        }
        out.comment(i, close + 2, doc, false);
        i = close + 2;
      } else {
        i++;
      }
    }
    return null;
  }

  @Override
  public DocComment docComment(String text) {
    return JavadocParser.parse(Objects.requireNonNull(text, "text"));
  }

  /** Brackets in strings, character literals, text blocks and comments are not marked. */
  @Override
  public boolean marksBrackets() {
    return true;
  }

  /** Java's comments to the end of a line start with {@code //}. */
  @Override
  public String lineComment() {
    return "//";
  }

  /** End of a single-line string or char literal starting at {@code at}, past its closing quote. */
  private static int scanQuoted(String line, int at, char quote) {
    int n = line.length();
    int i = at + 1;
    while (i < n) {
      char c = line.charAt(i);
      if (c == '\\') {
        i = Math.min(i + 2, n);
      } else if (c == quote) {
        return i + 1;
      } else {
        i++;
      }
    }
    return n;
  }

  /**
   * Whether a text block opens at {@code at}: {@code """} with only white space after it on the
   * line. Otherwise, as while typing {@code """;}, the quotes are strings on this line only.
   */
  private static boolean opensTextBlock(String line, int at) {
    return line.startsWith("\"\"\"", at) && line.substring(at + 3).isBlank();
  }

  /**
   * Position right after the closing {@code """} of a text block starting at {@code from}, or
   * {@code -1} when the text block goes on past this line.
   */
  private static int scanTextBlock(String line, int from) {
    int n = line.length();
    int i = from;
    while (i < n) {
      char c = line.charAt(i);
      if (c == '\\') {
        i = Math.min(i + 2, n);
      } else if (c == '"' && i + 2 < n && line.charAt(i + 1) == '"' && line.charAt(i + 2) == '"') {
        return i + 3;
      } else {
        i++;
      }
    }
    return -1;
  }

  /** End of an identifier starting at {@code at}. */
  private static int scanIdent(String line, int at) {
    int n = line.length();
    int i = at + 1;
    while (i < n && isIdentPart(line.charAt(i))) {
      i++;
    }
    return i;
  }

  /** End of a Java numeric literal starting at {@code at}. */
  private static int scanNumber(String line, int at) {
    int n = line.length();
    int i = at;
    if (line.charAt(i) == '0' && i + 1 < n) {
      char prefix = line.charAt(i + 1);
      if ((prefix == 'x' || prefix == 'X')
          && i + 2 < n
          && (isHexDigit(line.charAt(i + 2))
              || line.charAt(i + 2) == '.' && i + 3 < n && isHexDigit(line.charAt(i + 3)))) {
        i = scanHexDigits(line, i + 2);
        boolean isFloat = false;
        if (i < n && line.charAt(i) == '.') {
          isFloat = true;
          i = scanHexDigits(line, i + 1);
        }
        if (i < n && (line.charAt(i) == 'p' || line.charAt(i) == 'P')) {
          int e = scanExponent(line, i + 1);
          if (e > i) {
            isFloat = true;
            i = e;
          }
        }
        return scanNumericSuffix(line, i, isFloat);
      }
      if ((prefix == 'b' || prefix == 'B')
          && i + 2 < n
          && (line.charAt(i + 2) == '0' || line.charAt(i + 2) == '1')) {
        i += 2;
        while (i < n && (line.charAt(i) == '0' || line.charAt(i) == '1' || line.charAt(i) == '_')) {
          i++;
        }
        return scanNumericSuffix(line, i, false);
      }
    }
    i = scanDecDigits(line, i);
    boolean isFloat = false;
    if (i < n && line.charAt(i) == '.' && !(i + 1 < n && line.charAt(i + 1) == '.')) {
      isFloat = true;
      i = scanDecDigits(line, i + 1);
    }
    if (i < n && (line.charAt(i) == 'e' || line.charAt(i) == 'E')) {
      int e = scanExponent(line, i + 1);
      if (e > i) {
        isFloat = true;
        i = e;
      }
    }
    return scanNumericSuffix(line, i, isFloat);
  }

  private static int scanDecDigits(String line, int at) {
    int n = line.length();
    int i = at;
    while (i < n && (isDigit(line.charAt(i)) || line.charAt(i) == '_')) {
      i++;
    }
    return i;
  }

  private static int scanHexDigits(String line, int at) {
    int n = line.length();
    int i = at;
    while (i < n && (isHexDigit(line.charAt(i)) || line.charAt(i) == '_')) {
      i++;
    }
    return i;
  }

  /** Scans an optional sign and at least one decimal digit, or returns {@code at - 1}. */
  private static int scanExponent(String line, int at) {
    int n = line.length();
    int i = at;
    if (i < n && (line.charAt(i) == '+' || line.charAt(i) == '-')) {
      i++;
    }
    if (i < n && isDigit(line.charAt(i))) {
      return scanDecDigits(line, i);
    }
    return at - 1;
  }

  private static int scanNumericSuffix(String line, int at, boolean isFloat) {
    int n = line.length();
    if (at >= n) {
      return at;
    }
    char c = line.charAt(at);
    boolean ok = c == 'f' || c == 'F' || c == 'd' || c == 'D' || !isFloat && (c == 'l' || c == 'L');
    if (ok && (at + 1 >= n || !isIdentPart(line.charAt(at + 1)))) {
      return at + 1;
    }
    return at;
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean isHexDigit(char c) {
    return isDigit(c) || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
  }

  private static boolean isIdentStart(char c) {
    return Character.isJavaIdentifierStart(c);
  }

  private static boolean isIdentPart(char c) {
    return Character.isJavaIdentifierPart(c);
  }
}
