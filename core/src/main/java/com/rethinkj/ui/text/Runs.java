package com.rethinkj.ui.text;

import java.lang.Character.UnicodeScript;
import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntPredicate;

/**
 * Splits a text into the runs that are shaped on their own: each in one font of a chain and in one
 * script, left to right or right to left.
 *
 * <p>Each grapheme cluster ({@link BreakIterator#getCharacterInstance}: a letter with its marks, an
 * emoji sequence, a flag) goes to the first font that has all its characters, leaving out those
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
  }

  /** Characters {@code from} to {@code to} of the text, in font {@code font} of the chain. */
  record Run(int from, int to, int font, boolean rtl) {}

  static List<Run> of(String text, int fonts, Coverage coverage) {
    List<Integer> bounds = clusters(text);
    int n = bounds.size() - 1;
    int[] font = new int[n];
    boolean[] neutral = new boolean[n];
    for (int k = 0; k < n; k++) {
      String cluster = text.substring(bounds.get(k), bounds.get(k + 1));
      font[k] = own(cluster, fonts, coverage);
      neutral[k] = font[k] == 0 && script(cluster) == null;
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
          if (covers(text.substring(bounds.get(k), bounds.get(k + 1)), font[j], coverage)) {
            font[k] = font[j];
          }
        }
      }
      i = j;
    }
    // Scripts and directions, as in a left-to-right paragraph: a neutral is right to left only
    // between two right-to-left letters, not next to a number; neutrals and numbers take the
    // left-to-right script beside them.
    UnicodeScript[] own = new UnicodeScript[n];
    boolean[] ownRtl = new boolean[n];
    boolean[] isNumber = new boolean[n];
    for (int k = 0; k < n; k++) {
      String cluster = text.substring(bounds.get(k), bounds.get(k + 1));
      own[k] = script(cluster);
      ownRtl[k] = own[k] != null && rtl(cluster);
      isNumber[k] = number(cluster.codePointAt(0));
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
        scripts[k] = p >= 0 && !ownRtl[p] ? own[p]
            : q >= 0 && !ownRtl[q] ? own[q] : UnicodeScript.COMMON;
      }
    }
    List<Run> runs = new ArrayList<>();
    for (int k = 0; k < n; k++) {
      Run last = runs.isEmpty() ? null : runs.getLast();
      if (last != null && last.font == font[k] && last.rtl == rtl[k]
          && scripts[k] == scripts[k - 1]) {
        runs.set(runs.size() - 1, new Run(last.from, bounds.get(k + 1), font[k], rtl[k]));
      } else {
        runs.add(new Run(bounds.get(k), bounds.get(k + 1), font[k], rtl[k]));
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
  static List<Integer> clusters(String text) {
    BreakIterator chars = BreakIterator.getCharacterInstance(Locale.ROOT);
    chars.setText(text);
    List<Integer> bounds = new ArrayList<>();
    for (int b = chars.first(); b != BreakIterator.DONE; b = chars.next()) {
      bounds.add(b);
    }
    return bounds;
  }

  /** The first font that has the cluster, else the first with its first character, else 0. */
  private static int own(String cluster, int fonts, Coverage coverage) {
    for (int f = 0; f < fonts; f++) {
      if (covers(cluster, f, coverage)) {
        return f;
      }
    }
    int first = cluster.codePoints().filter(c -> !hidden(c)).findFirst().orElse(-1);
    for (int f = 0; first >= 0 && f < fonts; f++) {
      if (coverage.has(f, first)) {
        return f;
      }
    }
    return 0;
  }

  static boolean covers(String cluster, int font, Coverage coverage) {
    if (cluster.codePoints().allMatch(c -> hidden(c) || coverage.has(font, c))) {
      return true;
    }
    String composed = Normalizer.normalize(cluster, Normalizer.Form.NFC);
    return !composed.equals(cluster)
        && composed.codePoints().allMatch(c -> hidden(c) || coverage.has(font, c));
  }

  /**
   * Whether a character is never drawn, so a font need not have it: format characters (joiners,
   * marks of direction, the soft hyphen, tags), controls and variation selectors.
   */
  static boolean hidden(int c) {
    int type = Character.getType(c);
    return type == Character.FORMAT || type == Character.CONTROL
        || c >= 0xFE00 && c <= 0xFE0F || c >= 0xE0100 && c <= 0xE01EF
        || c >= 0x180B && c <= 0x180F || c == 0x034F;
  }

  /** The script of the cluster's first character of a script of its own, or null for neutrals. */
  static UnicodeScript script(String cluster) {
    for (int i = 0; i < cluster.length(); ) {
      int c = cluster.codePointAt(i);
      UnicodeScript s = UnicodeScript.of(c);
      if (s != UnicodeScript.COMMON && s != UnicodeScript.INHERITED
          && s != UnicodeScript.UNKNOWN && !number(c)) {
        return s;
      }
      i += Character.charCount(c);
    }
    return null;
  }

  private static boolean rtl(String cluster) {
    for (int i = 0; i < cluster.length(); ) {
      int c = cluster.codePointAt(i);
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
