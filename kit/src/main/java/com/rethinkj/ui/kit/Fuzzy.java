package com.rethinkj.ui.kit;

/**
 * Fuzzy matching as editors have it for their quick open and command palettes: a text matches a
 * query when the query's characters occur in it in order, not necessarily next to each other, and
 * a score says how well, so that the best matches come first. {@code "ot"} matches {@code
 * orders_total} and {@code customer_note}, the first better.
 *
 * <p>Each matched character scores {@value #MATCH}; one at the start of a word scores more: at the
 * start of the text or after a space {@value #BOUNDARY}, after a separator ({@code . _ - / \ : ,
 * ;}) {@value #SEPARATOR}, an upper-case letter after a lower-case one (camelCase) or a digit
 * after a letter {@value #CAMEL}, and the query's first character twice that. A character right
 * after the one matched before scores {@value #CONSECUTIVE} more, and a gap between two matched
 * characters costs {@value #GAP_START} and {@value #GAP_EXTENSION} more for each further character
 * it skips. Of the ways the query can be matched in the text, the best one counts ({@link
 * #positions} gives its characters, to highlight them), computed in time proportional to the
 * lengths of query and text multiplied.
 *
 * <p>Case does not count unless the query has an upper-case letter ("smart case"): {@code "ord"}
 * matches {@code Orders}, {@code "Ord"} does not match {@code orders}. Letters are compared one
 * UTF-16 unit at a time after {@link Character#toLowerCase(char)}, so {@code "ü"} matches {@code
 * Übersicht}, but {@code "u"} does not match {@code ü}, nor {@code "ss"} {@code ß}.
 *
 * <p>A {@link Matcher} keeps the query and its work arrays, so that scoring many texts allocates
 * nothing; it belongs to one thread. Needs no native library.
 */
public final class Fuzzy {

  /** What {@link #score} returns for a text that does not match. */
  public static final int NO_MATCH = Integer.MIN_VALUE;

  static final int MATCH = 16;
  static final int BOUNDARY = 10;
  static final int SEPARATOR = 9;
  static final int CAMEL = 7;
  static final int CONSECUTIVE = 6;
  static final int GAP_START = 3;
  static final int GAP_EXTENSION = 1;

  private static final int NONE = Integer.MIN_VALUE / 4; // no match there, safe to add to

  private Fuzzy() {}

  /** The score of {@code text} for {@code query}, higher for a better match, or {@link #NO_MATCH}. */
  public static int score(String query, String text) {
    return matcher(query).score(text);
  }

  /**
   * The indexes of the characters of {@code text} that the best match of {@code query} takes, in
   * order, one per character of the query; null when it does not match.
   */
  public static int[] positions(String query, String text) {
    return matcher(query).positions(text);
  }

  /** A matcher for this query, to score many texts. */
  public static Matcher matcher(String query) {
    return new Matcher(query);
  }

  /** A query, ready to score texts with, and its work arrays. Not thread-safe. */
  public static final class Matcher {
    private final char[] query;
    private final boolean exactCase;
    private int[] previous = new int[32];
    private int[] current = new int[32];
    private int[] bonus = new int[32];

    Matcher(String query) {
      this.query = query.toCharArray();
      boolean upper = false;
      for (char c : this.query) {
        upper |= Character.isUpperCase(c);
      }
      exactCase = upper;
      if (!exactCase) {
        for (int i = 0; i < this.query.length; i++) {
          this.query[i] = Character.toLowerCase(this.query[i]);
        }
      }
    }

    /** Whether the query is empty, which every text matches with the score 0. */
    public boolean isEmpty() {
      return query.length == 0;
    }

    private char at(String text, int i) {
      char c = text.charAt(i);
      return exactCase ? c : Character.toLowerCase(c);
    }

    /** Whether the query's characters occur in the text in order: the quick test before scoring. */
    public boolean matches(String text) {
      int j = 0;
      for (int i = 0, n = text.length(); i < n && j < query.length; i++) {
        if (at(text, i) == query[j]) {
          j++;
        }
      }
      return j == query.length;
    }

    /** The score of a text, as {@link Fuzzy#score} has it. */
    public int score(String text) {
      if (query.length == 0) {
        return 0;
      }
      if (!matches(text)) {
        return NO_MATCH;
      }
      int n = text.length();
      prepare(text);
      first(text, previous);
      for (int j = 1; j < query.length; j++) {
        next(text, j, previous, current);
        int[] swap = previous;
        previous = current;
        current = swap;
      }
      int best = NONE;
      for (int i = 0; i < n; i++) {
        best = Math.max(best, previous[i]);
      }
      return best <= NONE / 2 ? NO_MATCH : best;
    }

    /** The positions of the best match, as {@link Fuzzy#positions} has them. */
    public int[] positions(String text) {
      if (!matches(text)) {
        return null;
      }
      int n = text.length();
      int m = query.length;
      if (m == 0) {
        return new int[0];
      }
      prepare(text);
      int[][] rows = new int[m][n];
      first(text, rows[0]);
      for (int j = 1; j < m; j++) {
        next(text, j, rows[j - 1], rows[j]);
      }
      int[] at = new int[m];
      int i = 0;
      for (int k = 1; k < n; k++) {
        if (rows[m - 1][k] > rows[m - 1][i]) {
          i = k;
        }
      }
      at[m - 1] = i;
      for (int j = m - 1; j > 0; j--) {
        int own = rows[j][i] - MATCH - bonus[i];
        int[] before = rows[j - 1];
        int from;
        if (i > 0 && before[i - 1] > NONE / 2 && before[i - 1] + CONSECUTIVE == own) {
          from = i - 1;
        } else {
          from = -1;
          for (int k = i - 2; k >= 0; k--) {
            if (before[k] > NONE / 2
                && before[k] - GAP_START - GAP_EXTENSION * (i - k - 2) == own) {
              from = k;
              break;
            }
          }
        }
        i = from;
        at[j - 1] = i;
      }
      return at;
    }

    /** Makes the work arrays large enough for the text and sets its bonuses. */
    private void prepare(String text) {
      int n = text.length();
      if (current.length < n) {
        int size = Math.max(n, current.length * 2);
        previous = new int[size];
        current = new int[size];
        bonus = new int[size];
      }
      bonuses(text, bonus);
    }

    /** The bonus of each position of the text, for a match there. */
    private void bonuses(String text, int[] into) {
      char before = ' ';
      for (int i = 0, n = text.length(); i < n; i++) {
        char c = text.charAt(i);
        into[i] = before == ' ' || before == '\t' ? BOUNDARY
            : ".-_/\\:,;".indexOf(before) >= 0 ? SEPARATOR
            : Character.isLowerCase(before) && Character.isUpperCase(c) ? CAMEL
            : Character.isLetter(before) && Character.isDigit(c) ? CAMEL
            : 0;
        before = c;
      }
    }

    /** The scores of the query's first character at each position. */
    private void first(String text, int[] into) {
      char q = query[0];
      for (int i = 0, n = text.length(); i < n; i++) {
        into[i] = at(text, i) == q ? MATCH + 2 * bonus[i] : NONE;
      }
    }

    /** The scores of the query's character {@code j} at each position, from those of {@code j-1}. */
    private void next(String text, int j, int[] before, int[] into) {
      char q = query[j];
      int gap = NONE; // the best of the positions two or more before, less its gap
      into[0] = NONE;
      for (int i = 1, n = text.length(); i < n; i++) {
        if (i >= 2) {
          gap = Math.max(gap - GAP_EXTENSION, before[i - 2] - GAP_START);
        }
        if (at(text, i) != q) {
          into[i] = NONE;
          continue;
        }
        int from = Math.max(before[i - 1] + CONSECUTIVE, gap);
        into[i] = from <= NONE / 2 ? NONE : from + MATCH + bonus[i];
      }
    }
  }
}
