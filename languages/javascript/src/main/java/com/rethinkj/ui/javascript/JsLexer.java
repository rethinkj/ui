package com.rethinkj.ui.javascript;

import com.rethinkj.ui.Spans;
import com.rethinkj.ui.editor.Language;
import com.rethinkj.ui.kit.Theme;
import java.util.HashSet;
import java.util.Set;

/**
 * Shared line-by-line lexer for {@link JavaScript} and {@link TypeScript}, driving both syntax
 * highlighting ({@link #highlight}) and comment range scanning ({@link #comments}) so that strings,
 * template literals and regular expression literals never produce false comments or brackets.
 */
final class JsLexer {

  private static final int MODE_CODE = 0;
  private static final int MODE_BLOCK_COMMENT = 1;
  private static final int MODE_DOC_COMMENT = 2;
  private static final int MODE_SINGLE_STRING = 3;
  private static final int MODE_DOUBLE_STRING = 4;
  private static final int MODE_TEMPLATE = 5;

  /**
   * One open {@code ${ ... }} interpolation inside a template literal. {@code braces} counts extra
   * nested {@code {}} pairs opened inside that interpolation so a matching {@code }} returns to
   * template text only when {@code braces == 0}.
   */
  private record TemplateFrame(int braces, TemplateFrame parent) {}

  /**
   * Line-end state; {@code null} stands for top-level code with no open templates, {@code
   * regexAllowed == true} and {@code prevWasDot == false}.
   */
  private record State(int mode, TemplateFrame frames, boolean regexAllowed, boolean prevWasDot) {}

  private static final State STATE_NO_REGEX = new State(MODE_CODE, null, false, false);
  private static final State STATE_AFTER_DOT = new State(MODE_CODE, null, false, true);
  private static final State STATE_BLOCK_COMMENT_REGEX =
      new State(MODE_BLOCK_COMMENT, null, true, false);
  private static final State STATE_BLOCK_COMMENT_NO_REGEX =
      new State(MODE_BLOCK_COMMENT, null, false, false);
  private static final State STATE_BLOCK_COMMENT_AFTER_DOT =
      new State(MODE_BLOCK_COMMENT, null, false, true);
  private static final State STATE_DOC_COMMENT_REGEX =
      new State(MODE_DOC_COMMENT, null, true, false);
  private static final State STATE_DOC_COMMENT_NO_REGEX =
      new State(MODE_DOC_COMMENT, null, false, false);
  private static final State STATE_DOC_COMMENT_AFTER_DOT =
      new State(MODE_DOC_COMMENT, null, false, true);
  private static final State STATE_SINGLE_STRING =
      new State(MODE_SINGLE_STRING, null, false, false);
  private static final State STATE_DOUBLE_STRING =
      new State(MODE_DOUBLE_STRING, null, false, false);
  private static final State STATE_TEMPLATE = new State(MODE_TEMPLATE, null, false, false);

  private static final String OPERATORS = "+-*/%<>=!&|^~?:;,.()[]{}@";
  private static final String BRACKETS = "()[]{}";

  private static final Set<String> JS_KEYWORDS = Set.of(
      "as",
      "async",
      "await",
      "break",
      "case",
      "catch",
      "class",
      "const",
      "continue",
      "debugger",
      "default",
      "delete",
      "do",
      "else",
      "export",
      "extends",
      "false",
      "finally",
      "for",
      "from",
      "function",
      "get",
      "if",
      "import",
      "in",
      "instanceof",
      "let",
      "new",
      "null",
      "of",
      "return",
      "set",
      "static",
      "super",
      "switch",
      "this",
      "throw",
      "true",
      "try",
      "typeof",
      "var",
      "void",
      "while",
      "with",
      "yield");

  private static final Set<String> TS_KEYWORDS;

  static {
    Set<String> ts = new HashSet<>(JS_KEYWORDS);
    ts.addAll(Set.of(
        // TypeScript keywords
        "abstract",
        "accessor",
        "asserts",
        "declare",
        "enum",
        "implements",
        "infer",
        "interface",
        "is",
        "keyof",
        "namespace",
        "override",
        "private",
        "protected",
        "public",
        "readonly",
        "satisfies",
        "type",
        "unique",
        "using",
        // Primitive and built-in type names
        "any",
        "bigint",
        "boolean",
        "never",
        "number",
        "object",
        "string",
        "symbol",
        "undefined",
        "unknown"));
    TS_KEYWORDS = Set.copyOf(ts);
  }

  /** Keywords after which a {@code /} begins a regular expression literal rather than division. */
  private static final Set<String> REGEX_PRECEDING_KEYWORDS = Set.of(
      "await",
      "break",
      "case",
      "continue",
      "default",
      "delete",
      "do",
      "else",
      "export",
      "in",
      "instanceof",
      "new",
      "return",
      "throw",
      "typeof",
      "void",
      "yield");

  private static final int LONGEST_KEYWORD = 10; // instanceof, implements

  private JsLexer() {}

  static Object highlight(
      String line, Object state, Spans out, Theme.Code colors, boolean typescript) {
    return scanLine(line, state, out, colors, null, typescript);
  }

  static Object comments(String line, Object state, Language.Out out, boolean typescript) {
    return scanLine(line, state, null, null, out, typescript);
  }

  private static Object scanLine(
      String line,
      Object state,
      Spans spans,
      Theme.Code colors,
      Language.Out commentsOut,
      boolean typescript) {
    State s = (State) state;
    int mode = s == null ? MODE_CODE : s.mode;
    TemplateFrame frames = s == null ? null : s.frames;
    boolean regexAllowed = s == null || s.regexAllowed;
    boolean prevWasDot = s != null && s.prevWasDot;
    boolean lineContinued = false;
    boolean seenToken = false;
    boolean afterControlKeyword = false;
    long controlParens = 0L;
    int parenDepth = 0;

    int n = line.length();
    int i = 0;
    int start = 0;

    // Continued block comment from an earlier line.
    if (mode == MODE_BLOCK_COMMENT || mode == MODE_DOC_COMMENT) {
      boolean doc = mode == MODE_DOC_COMMENT;
      int close = line.indexOf("*/");
      if (close < 0) {
        if (spans != null) {
          spans.color(0, n, colors.comment());
        }
        if (commentsOut != null && n > 0) {
          commentsOut.comment(0, n, doc, true);
        }
        return state;
      }
      i = close + 2;
      if (spans != null) {
        spans.color(0, i, colors.comment());
      }
      if (commentsOut != null) {
        commentsOut.comment(0, i, doc, true);
      }
      mode = MODE_CODE;
    }

    Set<String> keywords = typescript ? TS_KEYWORDS : JS_KEYWORDS;

    while (i < n) {
      char c = line.charAt(i);
      switch (mode) {
        case MODE_SINGLE_STRING, MODE_DOUBLE_STRING -> {
          char quote = mode == MODE_SINGLE_STRING ? '\'' : '"';
          if (c == '\\') {
            if (i + 1 == n) {
              lineContinued = true;
              i = n;
            } else {
              i += 2;
            }
          } else if (c == quote) {
            i++;
            if (spans != null) {
              spans.color(start, i, colors.string());
            }
            mode = MODE_CODE;
            regexAllowed = false;
            prevWasDot = false;
            seenToken = true;
            afterControlKeyword = false;
          } else {
            i++;
          }
        }
        case MODE_TEMPLATE -> {
          if (c == '\\') {
            i = Math.min(i + 2, n);
          } else if (c == '`') {
            i++;
            if (spans != null) {
              spans.color(start, i, colors.string());
            }
            mode = MODE_CODE;
            regexAllowed = false;
            prevWasDot = false;
            seenToken = true;
            afterControlKeyword = false;
          } else if (c == '$' && i + 1 < n && line.charAt(i + 1) == '{') {
            if (spans != null) {
              spans.color(start, i, colors.string());
              spans.color(i, i + 2, colors.operator());
              spans.bracket(i + 1);
            }
            frames = new TemplateFrame(0, frames);
            mode = MODE_CODE;
            regexAllowed = true;
            prevWasDot = false;
            seenToken = true;
            afterControlKeyword = false;
            i += 2;
          } else {
            i++;
          }
        }
        default -> {
          // MODE_CODE
          if (c == ' ' || c == '\t' || c == '\r' || c == '\f') {
            i++;
            continue;
          }
          start = i;
          char next = i + 1 < n ? line.charAt(i + 1) : 0;
          if (c == '/' && next == '/') {
            if (spans != null) {
              spans.color(i, n, colors.comment());
            }
            if (commentsOut != null) {
              commentsOut.comment(i, n, false, false);
            }
            i = n;
          } else if (c == '/' && next == '*') {
            boolean doc =
                i + 2 < n && line.charAt(i + 2) == '*' && !(i + 3 < n && line.charAt(i + 3) == '/');
            int close = line.indexOf("*/", i + (doc ? 3 : 2));
            if (close < 0) {
              if (spans != null) {
                spans.color(i, n, colors.comment());
              }
              if (commentsOut != null) {
                commentsOut.comment(i, n, doc, false);
              }
              mode = doc ? MODE_DOC_COMMENT : MODE_BLOCK_COMMENT;
              i = n;
            } else {
              if (spans != null) {
                spans.color(i, close + 2, colors.comment());
              }
              if (commentsOut != null) {
                commentsOut.comment(i, close + 2, doc, false);
              }
              i = close + 2;
            }
          } else if (c == '/' && regexAllowed) {
            int regexEnd = scanRegexEnd(line, i);
            if (regexEnd > 0) {
              if (spans != null) {
                spans.color(i, regexEnd, colors.string());
              }
              i = regexEnd;
              regexAllowed = false;
            } else {
              if (spans != null) {
                spans.color(i, i + 1, colors.operator());
              }
              i++;
              regexAllowed = true;
            }
            prevWasDot = false;
            seenToken = true;
            afterControlKeyword = false;
          } else if (c == '\'' || c == '"') {
            mode = c == '\'' ? MODE_SINGLE_STRING : MODE_DOUBLE_STRING;
            prevWasDot = false;
            seenToken = true;
            afterControlKeyword = false;
            i++;
          } else if (c == '`') {
            mode = MODE_TEMPLATE;
            prevWasDot = false;
            seenToken = true;
            afterControlKeyword = false;
            i++;
          } else if (isDigit(c) || c == '.' && isDigit(next)) {
            i = scanNumber(line, i);
            if (spans != null) {
              spans.color(start, i, colors.number());
            }
            regexAllowed = false;
            prevWasDot = false;
            seenToken = true;
            afterControlKeyword = false;
          } else if (c == '#' && isIdentStart(next)) {
            // Private class field or method (#field); never a keyword.
            i += 2;
            while (i < n && isIdentPart(line.charAt(i))) {
              i++;
            }
            regexAllowed = false;
            prevWasDot = false;
            seenToken = true;
            afterControlKeyword = false;
          } else if (isIdentStart(c)) {
            i++;
            while (i < n && isIdentPart(line.charAt(i))) {
              i++;
            }
            boolean wasAfterControl = afterControlKeyword;
            afterControlKeyword = false;
            if (!prevWasDot && i - start <= LONGEST_KEYWORD) {
              String word = line.substring(start, i);
              if (keywords.contains(word)) {
                if (spans != null) {
                  spans.color(start, i, colors.keyword());
                }
                // `of` after a binding on its line (`item of`) or in a for header is the for-of
                // separator, where a regular expression may follow; elsewhere it is a name.
                boolean inControlHeader = parenDepth > 0
                    && parenDepth <= 64
                    && (controlParens & (1L << (parenDepth - 1))) != 0;
                regexAllowed = REGEX_PRECEDING_KEYWORDS.contains(word)
                    || word.equals("of") && (!regexAllowed && seenToken || inControlHeader);
                afterControlKeyword =
                    isControlHeaderKeyword(word) || wasAfterControl && word.equals("await");
              } else {
                regexAllowed = false;
              }
            } else {
              regexAllowed = false;
            }
            prevWasDot = false;
            seenToken = true;
          } else if (c == '{') {
            if (spans != null) {
              spans.color(i, i + 1, colors.operator());
              spans.bracket(i);
            }
            if (frames != null) {
              frames = new TemplateFrame(frames.braces + 1, frames.parent);
            }
            i++;
            regexAllowed = true;
            prevWasDot = false;
            seenToken = true;
            afterControlKeyword = false;
          } else if (c == '}') {
            if (spans != null) {
              spans.color(i, i + 1, colors.operator());
              spans.bracket(i);
            }
            i++;
            if (frames != null) {
              if (frames.braces > 0) {
                frames = new TemplateFrame(frames.braces - 1, frames.parent);
                regexAllowed = true;
              } else {
                frames = frames.parent;
                mode = MODE_TEMPLATE;
                start = i;
              }
            } else {
              regexAllowed = true;
            }
            prevWasDot = false;
            seenToken = true;
            afterControlKeyword = false;
          } else if ((c == '+' && next == '+') || (c == '-' && next == '-')) {
            if (spans != null) {
              spans.color(i, i + 2, colors.operator());
            }
            i += 2;
            regexAllowed = false;
            prevWasDot = false;
            seenToken = true;
            afterControlKeyword = false;
          } else if (c == '.' && i + 2 < n && next == '.' && line.charAt(i + 2) == '.') {
            if (spans != null) {
              spans.color(i, i + 3, colors.operator());
            }
            i += 3;
            regexAllowed = true;
            prevWasDot = false;
            seenToken = true;
            afterControlKeyword = false;
          } else if (c == '?' && next == '.' && !(i + 2 < n && isDigit(line.charAt(i + 2)))) {
            if (spans != null) {
              spans.color(i, i + 2, colors.operator());
            }
            i += 2;
            regexAllowed = false;
            prevWasDot = true;
            seenToken = true;
            afterControlKeyword = false;
          } else if (c == '.') {
            if (spans != null) {
              spans.color(i, i + 1, colors.operator());
            }
            i++;
            regexAllowed = false;
            prevWasDot = true;
            seenToken = true;
            afterControlKeyword = false;
          } else if (OPERATORS.indexOf(c) >= 0) {
            if (spans != null) {
              spans.color(i, i + 1, colors.operator());
              if (BRACKETS.indexOf(c) >= 0) {
                spans.bracket(i);
              }
            }
            boolean closesControl = false;
            if (c == '(') {
              if (parenDepth < 64) {
                long bit = 1L << parenDepth;
                if (afterControlKeyword) {
                  controlParens |= bit;
                } else {
                  controlParens &= ~bit;
                }
              }
              parenDepth++;
            } else if (c == ')' && parenDepth > 0) {
              parenDepth--;
              closesControl = parenDepth < 64 && (controlParens & (1L << parenDepth)) != 0;
            }
            // A ! right after an operand on its line is TypeScript's non-null assertion: still an
            // operand.
            boolean nonNull = typescript && c == '!' && !regexAllowed && next != '=' && seenToken;
            regexAllowed = (c != ')' || closesControl) && c != ']' && !nonNull;
            prevWasDot = false;
            seenToken = true;
            afterControlKeyword = false;
            i++;
          } else {
            afterControlKeyword = false;
            i++;
          }
        }
      }
    }

    return switch (mode) {
      case MODE_BLOCK_COMMENT, MODE_DOC_COMMENT -> stateOf(mode, frames, regexAllowed, prevWasDot);
      case MODE_SINGLE_STRING, MODE_DOUBLE_STRING -> {
        if (spans != null) {
          spans.color(start, n, colors.string());
        }
        yield stateOf(lineContinued ? mode : MODE_CODE, frames, false, false);
      }
      case MODE_TEMPLATE -> {
        if (spans != null) {
          spans.color(start, n, colors.string());
        }
        yield stateOf(MODE_TEMPLATE, frames, false, false);
      }
      default -> stateOf(MODE_CODE, frames, regexAllowed, prevWasDot);
    };
  }

  private static State stateOf(
      int mode, TemplateFrame frames, boolean regexAllowed, boolean prevWasDot) {
    if (frames == null) {
      return switch (mode) {
        case MODE_CODE -> prevWasDot ? STATE_AFTER_DOT : (regexAllowed ? null : STATE_NO_REGEX);
        case MODE_BLOCK_COMMENT ->
          prevWasDot
              ? STATE_BLOCK_COMMENT_AFTER_DOT
              : (regexAllowed ? STATE_BLOCK_COMMENT_REGEX : STATE_BLOCK_COMMENT_NO_REGEX);
        case MODE_DOC_COMMENT ->
          prevWasDot
              ? STATE_DOC_COMMENT_AFTER_DOT
              : (regexAllowed ? STATE_DOC_COMMENT_REGEX : STATE_DOC_COMMENT_NO_REGEX);
        case MODE_SINGLE_STRING -> STATE_SINGLE_STRING;
        case MODE_DOUBLE_STRING -> STATE_DOUBLE_STRING;
        case MODE_TEMPLATE -> STATE_TEMPLATE;
        default -> null;
      };
    }
    return new State(mode, frames, regexAllowed, prevWasDot);
  }

  private static boolean isControlHeaderKeyword(String word) {
    return switch (word) {
      case "if", "while", "for", "with" -> true;
      default -> false;
    };
  }

  /**
   * Scans a regular expression literal starting at {@code slashAt} on the same line, handling
   * backslash escapes, {@code [...]} character classes and trailing flag letters. Returns the
   * exclusive end offset, or {@code -1} when no closing {@code /} appears on the line.
   */
  static int scanRegexEnd(String line, int slashAt) {
    int n = line.length();
    int i = slashAt + 1;
    if (i >= n) {
      return -1;
    }
    boolean inClass = false;
    while (i < n) {
      char c = line.charAt(i);
      if (c == '\\') {
        if (i + 1 >= n) {
          return -1;
        }
        i += 2;
      } else if (inClass) {
        if (c == ']') {
          inClass = false;
        }
        i++;
      } else if (c == '[') {
        inClass = true;
        i++;
      } else if (c == '/') {
        i++;
        while (i < n && isRegexFlag(line.charAt(i))) {
          i++;
        }
        return i;
      } else {
        i++;
      }
    }
    return -1;
  }

  /** Scans a numeric literal (decimal, hex, binary, octal, separators, exponent, BigInt). */
  private static int scanNumber(String line, int at) {
    int n = line.length();
    int i = at;
    char first = line.charAt(i);
    if (first == '0' && i + 1 < n) {
      char prefix = line.charAt(i + 1);
      if ((prefix == 'x' || prefix == 'X') && i + 2 < n && isHexDigit(line.charAt(i + 2))) {
        i = scanHexDigits(line, i + 2);
        return i < n && line.charAt(i) == 'n' ? i + 1 : i;
      }
      if ((prefix == 'b' || prefix == 'B') && i + 2 < n && isBinDigit(line.charAt(i + 2))) {
        i = scanBinDigits(line, i + 2);
        return i < n && line.charAt(i) == 'n' ? i + 1 : i;
      }
      if ((prefix == 'o' || prefix == 'O') && i + 2 < n && isOctDigit(line.charAt(i + 2))) {
        i = scanOctDigits(line, i + 2);
        return i < n && line.charAt(i) == 'n' ? i + 1 : i;
      }
    }
    boolean hasDot = false;
    boolean hasExp = false;
    if (first == '.') {
      hasDot = true;
      i = scanDecimalDigits(line, i + 1);
    } else {
      i = scanDecimalDigits(line, i);
      if (i < n && line.charAt(i) == '.') {
        hasDot = true;
        i++;
        if (i < n && isDigit(line.charAt(i))) {
          i = scanDecimalDigits(line, i);
        }
      }
    }
    if (i < n && (line.charAt(i) == 'e' || line.charAt(i) == 'E')) {
      int e = i + 1;
      if (e < n && (line.charAt(e) == '+' || line.charAt(e) == '-')) {
        e++;
      }
      if (e < n && isDigit(line.charAt(e))) {
        hasExp = true;
        i = scanDecimalDigits(line, e);
      }
    }
    if (!hasDot && !hasExp && i < n && line.charAt(i) == 'n') {
      i++;
    }
    return i;
  }

  private static int scanDecimalDigits(String line, int at) {
    int n = line.length();
    int i = at;
    while (i < n) {
      char c = line.charAt(i);
      if (isDigit(c)) {
        i++;
      } else if (c == '_' && i + 1 < n && isDigit(line.charAt(i + 1))) {
        i += 2;
      } else {
        break;
      }
    }
    return i;
  }

  private static int scanHexDigits(String line, int at) {
    int n = line.length();
    int i = at;
    while (i < n) {
      char c = line.charAt(i);
      if (isHexDigit(c)) {
        i++;
      } else if (c == '_' && i + 1 < n && isHexDigit(line.charAt(i + 1))) {
        i += 2;
      } else {
        break;
      }
    }
    return i;
  }

  private static int scanBinDigits(String line, int at) {
    int n = line.length();
    int i = at;
    while (i < n) {
      char c = line.charAt(i);
      if (isBinDigit(c)) {
        i++;
      } else if (c == '_' && i + 1 < n && isBinDigit(line.charAt(i + 1))) {
        i += 2;
      } else {
        break;
      }
    }
    return i;
  }

  private static int scanOctDigits(String line, int at) {
    int n = line.length();
    int i = at;
    while (i < n) {
      char c = line.charAt(i);
      if (isOctDigit(c)) {
        i++;
      } else if (c == '_' && i + 1 < n && isOctDigit(line.charAt(i + 1))) {
        i += 2;
      } else {
        break;
      }
    }
    return i;
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean isHexDigit(char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }

  private static boolean isBinDigit(char c) {
    return c == '0' || c == '1';
  }

  private static boolean isOctDigit(char c) {
    return c >= '0' && c <= '7';
  }

  private static boolean isRegexFlag(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  private static boolean isIdentStart(char c) {
    return Character.isLetter(c) || c == '_' || c == '$';
  }

  private static boolean isIdentPart(char c) {
    if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '\u200C' || c == '\u200D') {
      return true;
    }
    int type = Character.getType(c);
    return type == Character.NON_SPACING_MARK
        || type == Character.COMBINING_SPACING_MARK
        || type == Character.CONNECTOR_PUNCTUATION;
  }
}
