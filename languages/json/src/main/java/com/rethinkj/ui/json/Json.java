package com.rethinkj.ui.json;

import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Spans;
import com.rethinkj.ui.kit.Theme;

/**
 * A {@link Highlighter} of JSON as RFC 8259 writes it, in the colors of {@link Theme.Code}: the
 * names of members ({@link Theme.Code#property()}), strings with their escapes ({@code "a\"b"}),
 * numbers ({@code -1.5e10}), {@code true}, {@code false} and {@code null} as keywords, and
 * brackets and punctuation as operators. It marks the brackets outside strings ({@link
 * Spans#bracket}), which the editor pairs. What is not JSON breaks nothing: a string without its
 * end is colored to the end of its line, and words and characters JSON does not know keep the
 * text's color.
 *
 * <p>A record, so that two highlighters of the same colors are equal and a field keeps what it
 * asked of one while the view passes it again: {@code field.highlight(json, json)}. It scans each
 * line once, character by character, without regular expressions. A string of JSON ends on its
 * own line, so no line depends on the one before.
 */
public record Json(Theme.Code colors) implements Highlighter {

  /**
   * The highlighter in the colors for code of {@link Theme#CURRENT}; in the view, where the app
   * provides its theme.
   */
  public static Json of() {
    Theme theme = Theme.CURRENT.get();
    return new Json(theme.code());
  }

  private static final String PUNCTUATION = "{}[]:,";

  @Override
  public Object line(String line, Object state, Spans out) {
    int n = line.length();
    int i = 0;
    while (i < n) {
      char c = line.charAt(i);
      int start = i;
      if (c == '"') {
        i = string(line, i);
        // shortcut: a name whose colon starts the next line is colored as a string; the line
        // would have to wait for the next one, which formatted JSON never needs.
        out.color(start, i, colonAt(line, i) ? colors.property() : colors.string());
      } else if (isDigit(c) || c == '-' && i + 1 < n && isDigit(line.charAt(i + 1))) {
        i = number(line, i + 1);
        out.color(start, i, colors.number());
      } else if (Character.isLetter(c)) {
        i++;
        while (i < n && (Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == '_')) {
          i++;
        }
        if (isLiteral(line, start, i)) {
          out.color(start, i, colors.keyword());
        }
      } else if (PUNCTUATION.indexOf(c) >= 0) {
        out.color(i, i + 1, colors.operator());
        if (c != ':' && c != ',') {
          out.bracket(i);
        }
        i++;
      } else {
        i++;
      }
    }
    return null;
  }

  /** The end of the string that starts at {@code at}, past its closing quote or at the line's. */
  private static int string(String line, int at) {
    int n = line.length();
    int i = at + 1;
    while (i < n) {
      char c = line.charAt(i);
      if (c == '\\') {
        i += 2; // the character after it is itself, a quote too
      } else if (c == '"') {
        return i + 1;
      } else {
        i++;
      }
    }
    return n;
  }

  /** Whether a colon follows {@code at}, after white space: the string before it is a name. */
  private static boolean colonAt(String line, int at) {
    int i = at;
    while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
      i++;
    }
    return i < line.length() && line.charAt(i) == ':';
  }

  /** The end of a number whose first digit is before {@code at}: digits, fraction, exponent. */
  private static int number(String line, int at) {
    int n = line.length();
    int i = digits(line, at);
    if (i + 1 < n && line.charAt(i) == '.' && isDigit(line.charAt(i + 1))) {
      i = digits(line, i + 1);
    }
    if (i < n && (line.charAt(i) == 'e' || line.charAt(i) == 'E')) {
      int e = i + 1;
      if (e < n && (line.charAt(e) == '+' || line.charAt(e) == '-')) {
        e++;
      }
      if (e < n && isDigit(line.charAt(e))) {
        i = digits(line, e);
      }
    }
    return i;
  }

  private static int digits(String line, int at) {
    int i = at;
    while (i < line.length() && isDigit(line.charAt(i))) {
      i++;
    }
    return i;
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  /** Whether the word from {@code from} to {@code to} is {@code true}, {@code false} or null. */
  private static boolean isLiteral(String line, int from, int to) {
    return switch (to - from) {
      case 4 -> line.startsWith("true", from) || line.startsWith("null", from);
      case 5 -> line.startsWith("false", from);
      default -> false;
    };
  }

  /** Brackets in strings are not marked. */
  @Override
  public boolean marksBrackets() {
    return true;
  }
}
