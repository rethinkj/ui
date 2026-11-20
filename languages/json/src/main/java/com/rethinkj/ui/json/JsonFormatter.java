package com.rethinkj.ui.json;

import com.rethinkj.ui.Formatted;
import com.rethinkj.ui.Formatted.Kind;
import com.rethinkj.ui.Formatted.Problem;
import java.util.BitSet;

/**
 * {@link Json#format}: a scan of the tokens, without recursion, that counts what the formatted text
 * takes and finds the first problem, and, for a valid text that is not too long, a second that
 * writes it into a builder of that size.
 */
final class JsonFormatter {

  /** The most characters a formatted text may have: 64 Mi. */
  static final int MAX_LENGTH = 1 << 26;

  // Tokens besides the punctuation, which is its character.
  private static final int END = -1;
  private static final int STRING = '"';
  private static final int SCALAR = '0'; // a number, true, false or null
  private static final int OTHER = -3; // a character or word JSON does not know

  // What may come next.
  private static final int VALUE = 0;
  private static final int FIRST_ELEMENT = 1; // a value or ]
  private static final int FIRST_MEMBER = 2; // a name or }
  private static final int NAME = 3;
  private static final int COLON = 4;
  private static final int AFTER_VALUE = 5; // a comma, the closing bracket or the end
  private static final int DONE = 6;

  /** A state: the problem is found. */
  private static final int ERROR = -2;

  private final String text;
  private final StringBuilder out; // null while counting
  private final BitSet objects = new BitSet(); // per depth: whether an object, not an array
  private long length;
  private int depth;
  private int start; // of the token
  private int at; // after it
  private Problem invalid; // of the token, a string or a number, where one may stand
  private Problem problem;

  private JsonFormatter(String text, StringBuilder out) {
    this.text = text;
    this.out = out;
  }

  static Formatted format(String text) {
    JsonFormatter count = new JsonFormatter(text, null);
    Problem problem = count.run();
    if (problem != null) {
      int from = fit(text, problem.from());
      return new Formatted(null, new Problem(from, fit(text, problem.to()), problem.kind()));
    }
    if (count.length > MAX_LENGTH) {
      return new Formatted(null, new Problem(0, 0, Kind.TOO_LARGE));
    }
    JsonFormatter write = new JsonFormatter(text, new StringBuilder((int) count.length));
    write.run();
    return new Formatted(write.out.toString(), null);
  }

  /** Goes through the tokens; the first problem, or null. */
  private Problem run() {
    int state = VALUE;
    while (state != DONE && state != ERROR) {
      int token = next();
      state = switch (state) {
        case VALUE -> value(token);
        case FIRST_ELEMENT -> first(false, token);
        case FIRST_MEMBER -> first(true, token);
        case NAME -> name(token);
        case COLON -> colon(token);
        default -> afterValue(token);
      };
    }
    return problem;
  }

  private int value(int token) {
    if (token == STRING || token == SCALAR) {
      return copy(AFTER_VALUE);
    }
    if (token != '[' && token != '{') {
      return unexpected(token);
    }
    put(token);
    depth++;
    objects.set(depth, token == '{');
    return token == '[' ? FIRST_ELEMENT : FIRST_MEMBER;
  }

  /** The first token in an array or object: its closing bracket stays on the opening's line. */
  private int first(boolean object, int token) {
    if (token == (object ? '}' : ']')) {
      depth--;
      put(token);
      return AFTER_VALUE;
    }
    newline();
    return object ? name(token) : value(token);
  }

  private int name(int token) {
    return token == STRING ? copy(COLON) : unexpected(token);
  }

  private int colon(int token) {
    if (token != ':') {
      return unexpected(token);
    }
    put(':');
    put(' ');
    return VALUE;
  }

  private int afterValue(int token) {
    if (depth == 0) {
      return token == END ? DONE : unexpected(token);
    }
    boolean object = objects.get(depth);
    if (token == ',') {
      put(',');
      newline();
      return object ? NAME : VALUE;
    }
    if (token != (object ? '}' : ']')) {
      return unexpected(token);
    }
    depth--;
    newline();
    put(token);
    return AFTER_VALUE;
  }

  private int unexpected(int token) {
    return token == END ? fail(at, at, Kind.UNEXPECTED_END) : fail(start, at, Kind.UNEXPECTED);
  }

  private int fail(int from, int to, Kind kind) {
    problem = new Problem(from, to, kind);
    return ERROR;
  }

  // ---- Writing

  private void put(int c) {
    length++;
    if (out != null) {
      out.append((char) c);
    }
  }

  /** The string or number as it is written, then {@code state}; unless it is invalid. */
  private int copy(int state) {
    if (invalid != null) {
      problem = invalid;
      return ERROR;
    }
    length += at - start;
    if (out != null) {
      out.append(text, start, at);
    }
    return state;
  }

  /** A line feed and the indentation of the depth: four spaces a step. */
  private void newline() {
    length += 1 + 4L * depth;
    if (out != null) {
      out.append('\n').repeat(' ', 4 * depth);
    }
  }

  // ---- Tokens

  /**
   * The next token after white space, from {@link #start} to {@link #at}; a string or number that
   * is not valid to the end of its {@link #invalid} problem, which counts only where one may stand.
   */
  private int next() {
    invalid = null;
    int n = text.length();
    while (at < n && isSpace(text.charAt(at))) {
      at++;
    }
    start = at;
    if (at == n) {
      return END;
    }
    char c = text.charAt(at);
    switch (c) {
      case '{', '}', '[', ']', ':', ',' -> {
        at++;
        return c;
      }
      case '"' -> {
        return string();
      }
      default -> {
        if (c == '-' || Json.isDigit(c)) {
          return number();
        }
        if (isWordChar(c)) {
          return word();
        }
        at += Character.charCount(text.codePointAt(at));
        return OTHER;
      }
    }
  }

  /** A string, which ends with its quote on its line. */
  private int string() {
    int n = text.length();
    int i = start + 1;
    while (i < n) {
      char c = text.charAt(i);
      if (c == '"') {
        at = i + 1;
        return STRING;
      } else if (c == '\\') {
        i = escape(i);
        if (i < 0) {
          return STRING;
        }
      } else if (c == '\n' || c == '\r') {
        return invalid(start, i, Kind.UNCLOSED_STRING);
      } else if (c < 0x20) { // a control character, which JSON escapes
        return invalid(i, i + 1, Kind.UNEXPECTED);
      } else {
        i++;
      }
    }
    return invalid(start, n, Kind.UNCLOSED_STRING);
  }

  /** A string that is not valid: its problem, and its end there. */
  private int invalid(int from, int to, Kind kind) {
    invalid = new Problem(from, to, kind);
    at = to;
    return STRING;
  }

  /** The end of the escape whose backslash is at {@code i}; -1 for an invalid one. */
  private int escape(int i) {
    int n = text.length();
    if (i + 1 == n) {
      invalid(start, n, Kind.UNCLOSED_STRING);
      return -1;
    }
    char e = text.charAt(i + 1);
    switch (e) {
      case '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> {
        return i + 2;
      }
      case 'u' -> {
        int end = i + 2;
        while (end < n && end < i + 6 && isHex(text.charAt(end))) {
          end++;
        }
        if (end == i + 6) {
          return end;
        }
        invalid(i, end, Kind.INVALID_ESCAPE);
      }
      case '\n', '\r' -> invalid(i, i + 1, Kind.INVALID_ESCAPE);
      default ->
        invalid(i, i + 1 + Character.charCount(text.codePointAt(i + 1)), Kind.INVALID_ESCAPE);
    }
    return -1;
  }

  /**
   * A number: the characters that may belong to one, digits, letters, points and signs, and
   * whether they make one as RFC 8259 writes it.
   */
  private int number() {
    int n = text.length();
    int i = start + 1;
    while (i < n && (isWordChar(text.charAt(i)) || "+-.".indexOf(text.charAt(i)) >= 0)) {
      i++;
    }
    at = i;
    if (!isNumber()) {
      invalid = new Problem(start, at, Kind.INVALID_NUMBER);
    }
    return SCALAR;
  }

  /** Whether the token is {@code -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?}. */
  private boolean isNumber() {
    int i = text.charAt(start) == '-' ? start + 1 : start;
    i = i < at && text.charAt(i) == '0' ? i + 1 : digits(i);
    if (i >= 0 && i < at && text.charAt(i) == '.') {
      i = digits(i + 1);
    }
    if (i >= 0 && i < at && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
      i++;
      if (i < at && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
        i++;
      }
      i = digits(i);
    }
    return i == at;
  }

  /** The end of the digits from {@code from}, at least one, in the token; else -1. */
  private int digits(int from) {
    int i = from;
    while (i < at && Json.isDigit(text.charAt(i))) {
      i++;
    }
    return i > from ? i : -1;
  }

  /** {@code true}, {@code false} or {@code null}; another word is one token, unexpected. */
  private int word() {
    int i = start + 1;
    while (i < text.length() && isWordChar(text.charAt(i))) {
      i++;
    }
    at = i;
    return Json.isLiteral(text, start, at) ? SCALAR : OTHER;
  }

  private static boolean isSpace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r';
  }

  /** A hexadecimal digit, as JSON writes it: of ASCII. */
  private static boolean isHex(char c) {
    return Json.isDigit(c) || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
  }

  private static boolean isWordChar(char c) {
    return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || Json.isDigit(c) || c == '_';
  }

  /**
   * Where {@code index} of {@code text} is once each carriage return with a line feed after it is
   * one character, as the code editor counts.
   */
  private static int fit(String text, int index) {
    int pairs = 0;
    for (int i = text.indexOf("\r\n"); i >= 0 && i + 1 < index; i = text.indexOf("\r\n", i + 2)) {
      pairs++;
    }
    return index - pairs;
  }
}
