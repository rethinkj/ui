package com.rethinkj.ui;

import java.util.Objects;

/**
 * Code-word characters and their contiguous runs, without native libraries: Unicode letters,
 * decimal digits, combining marks and {@code _}. Points, spaces and other punctuation end a run.
 *
 * <p>Positions are UTF-16 indexes on code-point boundaries, from 0 to the string's length. The
 * scans stop at the first non-word code point; they neither skip punctuation nor snap to grapheme
 * boundaries. A mark counts wherever it stands. A field with {@link El#codeWords} uses this
 * classifier but scans whole graphemes, keeping marks with their base even after punctuation or
 * emoji, and applies the platform's word-navigation rules.
 */
public final class CodeWords {

  private CodeWords() {}

  /** Whether a code point is a letter, decimal digit, combining mark or {@code _}. */
  public static boolean isWordChar(int codePoint) {
    if (codePoint == '_' || Character.isLetterOrDigit(codePoint)) {
      return true;
    }
    int type = Character.getType(codePoint);
    return type == Character.NON_SPACING_MARK
        || type == Character.COMBINING_SPACING_MARK
        || type == Character.ENCLOSING_MARK;
  }

  /**
   * The start of the run immediately before {@code index}, or {@code index} if no word character
   * precedes it. From inside a run, scans only its part before the index.
   *
   * @param index a UTF-16 index on a code-point boundary
   * @throws IndexOutOfBoundsException if the index is outside the string
   */
  public static int start(String text, int index) {
    Objects.checkFromToIndex(index, index, text.length());
    while (index > 0) {
      int c = text.codePointBefore(index);
      if (!isWordChar(c)) {
        break;
      }
      index -= Character.charCount(c);
    }
    return index;
  }

  /**
   * The exclusive end of the run immediately after {@code index}, or {@code index} if no word
   * character follows it. From inside a run, scans only its part after the index.
   *
   * @param index a UTF-16 index on a code-point boundary
   * @throws IndexOutOfBoundsException if the index is outside the string
   */
  public static int end(String text, int index) {
    Objects.checkFromToIndex(index, index, text.length());
    while (index < text.length()) {
      int c = text.codePointAt(index);
      if (!isWordChar(c)) {
        break;
      }
      index += Character.charCount(c);
    }
    return index;
  }
}
