package com.rethinkj.ui.text;

import java.lang.Character.UnicodeScript;
import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.IntPredicate;

/**
 * Splits a text into the runs that are shaped on their own: each in one font of a chain and in one
 * script, left to right or right to left.
 *
 * <p>Each grapheme cluster ({@link BreakIterator#getCharacterInstance}: a letter with its marks, an
 * emoji sequence, a flag) goes to the first font that has all its characters, an emoji to one with
 * color glyphs before the others, leaving out those
 * that are never drawn (joiners, variation selectors, other format and control characters), or
 * all of their composed form. A cluster that no font has goes to the first font with its first
 * such character, or else to the first font, which draws its "not defined" glyph.
 *
 * <p>Neutral clusters, those of the scripts Common and Inherited (spaces, punctuation, digits,
 * symbols) that the first font has, stay with the font of the clusters on both sides when that is
 * the same font and it has them, so that a run of a fallback font keeps its own spaces and
 * punctuation.
 *
 * <p>Scripts and directions are resolved as in a left-to-right paragraph: a neutral cluster is
 * right to left only between two right-to-left letters, so that the words of a right-to-left run
 * keep the spaces between them; other neutrals and numbers, whose digits stay in order, take the
 * left-to-right script before or after them, or Common.
 */
final class Runs {

  private Runs() {}

  /** Which characters the fonts of a chain have, by their number in the chain. */
  @FunctionalInterface
  interface Coverage {
    boolean has(int font, int codePoint);

    /** Whether the font has color glyphs, which emoji take before another font's outlines. */
    default boolean color(int font) {
      return false;
    }
  }

  /** Characters {@code from} to {@code to} of the text, in font {@code font} of the chain. */
  record Run(int from, int to, int font, boolean rtl) {}

  static List<Run> of(String text, int fonts, Coverage coverage) {
    return of(text, clusters(text), fonts, coverage);
  }

  /** The same, with the text's grapheme boundaries from {@link #clusters}. */
  static List<Run> of(String text, int[] bounds, int fonts, Coverage coverage) {
    int n = bounds.length - 1;
    int[] font = new int[n];
    boolean[] neutral = new boolean[n];
    UnicodeScript[] own = new UnicodeScript[n];
    for (int k = 0; k < n; k++) {
      int from = bounds[k];
      int to = bounds[k + 1];
      font[k] = own(text, from, to, fonts, coverage);
      own[k] = script(text, from, to);
      // An emoji the first font draws in color stays with it.
      neutral[k] = font[k] == 0 && own[k] == null && !(coverage.color(0) && emoji(text, from, to));
    }
    // Neutrals between two clusters of one font stay with it.
    for (int i = 0; i < n; ) {
      if (!neutral[i]) {
        i++;
        continue;
      }
      int j = i;
      while (j < n && neutral[j]) {
        j++;
      }
      if (i > 0 && j < n && font[i - 1] == font[j]) {
        for (int k = i; k < j; k++) {
          int from = bounds[k];
          int to = bounds[k + 1];
          // Only an emoji joins a font that draws in color: other symbols stay text.
          if (covers(text, from, to, font[j], coverage)
              && (emoji(text, from, to) || !coverage.color(font[j]))) {
            font[k] = font[j];
          }
        }
      }
      i = j;
    }
    // Scripts and directions, as in a left-to-right paragraph: a neutral is right to left only
    // between two right-to-left letters, not next to a number; neutrals and numbers take the
    // left-to-right script beside them.
    boolean[] ownRtl = new boolean[n];
    boolean[] isNumber = new boolean[n];
    for (int k = 0; k < n; k++) {
      ownRtl[k] = own[k] != null && rtl(text, bounds[k], bounds[k + 1]);
      isNumber[k] = number(text.codePointAt(bounds[k]));
    }
    int[] strongBefore = nearest(n, k -> own[k] != null, false);
    int[] strongAfter = nearest(n, k -> own[k] != null, true);
    int[] before = nearest(n, k -> own[k] != null || isNumber[k], false);
    int[] after = nearest(n, k -> own[k] != null || isNumber[k], true);
    UnicodeScript[] scripts = new UnicodeScript[n];
    boolean[] rtl = new boolean[n];
    for (int k = 0; k < n; k++) {
      int p = before[k];
      int q = after[k];
      if (own[k] != null) {
        scripts[k] = own[k];
        rtl[k] = ownRtl[k];
      } else if (!isNumber[k] && p >= 0 && q >= 0 && ownRtl[p] && ownRtl[q]) {
        scripts[k] = own[p];
        rtl[k] = true;
      } else {
        p = strongBefore[k];
        q = strongAfter[k];
        scripts[k] =
            p >= 0 && !ownRtl[p] ? own[p] : q >= 0 && !ownRtl[q] ? own[q] : UnicodeScript.COMMON;
      }
    }
    List<Run> runs = new ArrayList<>();
    for (int k = 0, start = 0; k < n; k++) {
      if (k + 1 == n
          || font[k + 1] != font[k]
          || rtl[k + 1] != rtl[k]
          || scripts[k + 1] != scripts[k]) {
        runs.add(new Run(bounds[start], bounds[k + 1], font[k], rtl[k]));
        start = k + 1;
      }
    }
    return runs;
  }

  /** For each cluster, the nearest other one before it, or after it, that is {@code wanted}. */
  private static int[] nearest(int n, IntPredicate wanted, boolean after) {
    int[] found = new int[n];
    for (int i = 0, last = -1; i < n; i++) {
      int k = after ? n - 1 - i : i;
      found[k] = last;
      last = wanted.test(k) ? k : last;
    }
    return found;
  }

  /** The grapheme boundaries of the text, from 0 to its length. */
  static int[] clusters(String text) {
    int[] bounds = new int[text.length() + 1];
    int n = 0;
    if (text.chars().allMatch(c -> c < 0x300)) {
      // Below the first combining mark, every character is a cluster of its own but CR LF.
      for (int i = 0; i <= text.length(); i++) {
        if (i == 0 || i == text.length() || text.charAt(i - 1) != '\r' || text.charAt(i) != '\n') {
          bounds[n++] = i;
        }
      }
    } else {
      BreakIterator chars = BreakIterator.getCharacterInstance(Locale.ROOT);
      chars.setText(text);
      for (int b = chars.first(); b != BreakIterator.DONE; b = chars.next()) {
        bounds[n++] = b;
      }
    }
    return n == bounds.length ? bounds : Arrays.copyOf(bounds, n);
  }

  /** The first font that has the cluster, else the first with its first character, else 0. */
  private static int own(String text, int from, int to, int fonts, Coverage coverage) {
    for (int f = 0; f < fonts && emoji(text, from, to); f++) {
      if (coverage.color(f) && covers(text, from, to, f, coverage)) {
        return f;
      }
    }
    for (int f = 0; f < fonts; f++) {
      if (covers(text, from, to, f, coverage)) {
        return f;
      }
    }
    for (int i = from; i < to; ) {
      int c = text.codePointAt(i);
      if (!hidden(c)) {
        for (int f = 0; f < fonts; f++) {
          if (coverage.has(f, c)) {
            return f;
          }
        }
        return 0;
      }
      i += Character.charCount(c);
    }
    return 0;
  }

  /**
   * Whether a cluster is drawn as an emoji: by default or asked for with U+FE0F, and not asked to
   * be text with U+FE0E.
   */
  static boolean emoji(String cluster) {
    return emoji(cluster, 0, cluster.length());
  }

  /** Whether characters {@code from} to {@code to} of the text, a cluster, are an emoji. */
  static boolean emoji(String text, int from, int to) {
    return text.indexOf('\uFE0E', from, to) < 0
        && (Character.isEmojiPresentation(text.codePointAt(from))
            || text.indexOf('\uFE0F', from, to) >= 0);
  }

  static boolean covers(String cluster, int font, Coverage coverage) {
    return covers(cluster, 0, cluster.length(), font, coverage);
  }

  /** Whether the font has characters {@code from} to {@code to} of the text, or their NFC form. */
  static boolean covers(String text, int from, int to, int font, Coverage coverage) {
    if (has(text, from, to, font, coverage)) {
      return true;
    }
    String cluster = text.substring(from, to);
    String composed = Normalizer.normalize(cluster, Normalizer.Form.NFC);
    return !composed.equals(cluster) && has(composed, 0, composed.length(), font, coverage);
  }

  private static boolean has(String text, int from, int to, int font, Coverage coverage) {
    for (int i = from; i < to; ) {
      int c = text.codePointAt(i);
      if (!hidden(c) && !coverage.has(font, c)) {
        return false;
      }
      i += Character.charCount(c);
    }
    return true;
  }

  /**
   * Whether a character is never drawn, so a font need not have it: format characters (joiners,
   * marks of direction, the soft hyphen, tags), controls and variation selectors.
   */
  static boolean hidden(int c) {
    int type = Character.getType(c);
    return type == Character.FORMAT
        || type == Character.CONTROL
        || c >= 0xFE00 && c <= 0xFE0F
        || c >= 0xE0100 && c <= 0xE01EF
        || c >= 0x180B && c <= 0x180F
        || c == 0x034F;
  }

  /** The script of the cluster's first character of a script of its own, or null for neutrals. */
  static UnicodeScript script(String cluster) {
    return script(cluster, 0, cluster.length());
  }

  private static UnicodeScript script(String text, int from, int to) {
    for (int i = from; i < to; ) {
      int c = text.codePointAt(i);
      UnicodeScript s = UnicodeScript.of(c);
      if (s != UnicodeScript.COMMON
          && s != UnicodeScript.INHERITED
          && s != UnicodeScript.UNKNOWN
          && !number(c)) {
        return s;
      }
      i += Character.charCount(c);
    }
    return null;
  }

  private static boolean rtl(String text, int from, int to) {
    for (int i = from; i < to; ) {
      int c = text.codePointAt(i);
      byte d = Character.getDirectionality(c);
      if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT
          || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
        return true;
      }
      if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
        return false;
      }
      i += Character.charCount(c);
    }
    return false;
  }

  private static boolean number(int c) {
    byte d = Character.getDirectionality(c);
    return d == Character.DIRECTIONALITY_EUROPEAN_NUMBER
        || d == Character.DIRECTIONALITY_ARABIC_NUMBER;
  }
}
