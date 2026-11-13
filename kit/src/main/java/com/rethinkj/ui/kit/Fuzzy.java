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

  // Quick answers for ASCII, which most names are; the rest asks Character.

  static char lower(char c) {
    return c < 128 ? c >= 'A' && c <= 'Z' ? (char) (c + 32) : c : Character.toLowerCase(c);
  }

  private static boolean isLower(char c) {
    return c < 128 ? c >= 'a' && c <= 'z' : Character.isLowerCase(c);
  }

  private static boolean isUpper(char c) {
    return c < 128 ? c >= 'A' && c <= 'Z' : Character.isUpperCase(c);
  }

  private static boolean isLetter(char c) {
    return c < 128 ? isLower(c) || isUpper(c) : Character.isLetter(c);
  }

  private static boolean isDigit(char c) {
    return c < 128 ? c >= '0' && c <= '9' : Character.isDigit(c);
  }

  private static boolean separator(char c) {
    return switch (c) {
      case '.', '-', '_', '/', '\\', ':', ',', ';' -> true;
      default -> false;
    };
  }

  /**
   * The text with each character lower-cased, as a query in which case does not count is compared
   * with it; the text itself where that changes nothing. A list of many entries keeps it with each.
   */
  static String lowered(String text) {
    for (int i = 0, n = text.length(); i < n; i++) {
      char c = text.charAt(i);
      if (lower(c) != c) {
        char[] lowered = text.toCharArray();
        for (int k = i; k < n; k++) {
          lowered[k] = lower(lowered[k]);
        }
        return new String(lowered);
      }
    }
    return text;
  }

  /** The bonus of a match of {@code c} after {@code before}. */
  private static int bonus(char before, char c) {
    return before == ' ' || before == '\t' ? BOUNDARY
        : separator(before) ? SEPARATOR
        : isLower(before) && isUpper(c) || isLetter(before) && isDigit(c) ? CAMEL
        : 0;
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
    private char[] chars = new char[32];

    Matcher(String query) {
      this.query = query.toCharArray();
      boolean upper = false;
      for (char c : this.query) {
        upper |= Character.isUpperCase(c);
      }
      exactCase = upper;
      if (!exactCase) {
        for (int i = 0; i < this.query.length; i++) {
          this.query[i] = lower(this.query[i]);
        }
      }
    }

    /** Whether the query is empty, which every text matches with the score 0. */
    public boolean isEmpty() {
      return query.length == 0;
    }

    /** Whether the query's characters occur in the text in order, as compared. */
    public boolean matches(String text) {
      return start(compared(text)) >= 0;
    }

    /** The text as the query is compared with it: lower-cased unless case counts. */
    private String compared(String text) {
      return exactCase ? text : lowered(text);
    }

    /**
     * Where the query's first character first occurs in the text as compared, if the query's
     * characters occur there in order, else -1; {@link String#indexOf(int, int)} looks, which the
     * JDK makes fast.
     */
    private int start(String compared) {
      int from = 0;
      int first = -1;
      for (char q : query) {
        int at = compared.indexOf(q, from);
        if (at < 0) {
          return -1;
        }
        if (first < 0) {
          first = at;
        }
        from = at + 1;
      }
      return Math.max(first, 0);
    }

    /** The score of a text, as {@link Fuzzy#score} has it. */
    public int score(String text) {
      return score(text, exactCase ? text : lowered(text));
    }

    /** The score of a text, with the text as {@link Fuzzy#lowered} gives it. */
    int score(String text, String lowered) {
      String compared = exactCase ? text : lowered;
      if (query.length == 0) {
        return 0;
      }
      int lo = start(compared);
      if (lo < 0) {
        return NO_MATCH;
      }
      if (query.length == 1) {
        int best = NO_MATCH;
        for (int i = lo; i >= 0; i = compared.indexOf(query[0], i + 1)) {
          best = Math.max(best, MATCH + 2 * bonus(i == 0 ? ' ' : text.charAt(i - 1),
              text.charAt(i)));
        }
        return best;
      }
      int n = text.length();
      grow(n);
      prepare(text, compared, lo, previous);
      int best = NONE;
      for (int j = 1; j < query.length; j++) {
        best = next(n, lo, j, previous, current);
        int[] swap = previous;
        previous = current;
        current = swap;
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
      int[][] rows = new int[m][n];
      grow(n);
      prepare(text, compared(text), 0, rows[0]);
      for (int j = 1; j < m; j++) {
        next(n, 0, j, rows[j - 1], rows[j]);
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

    /** Makes the work arrays large enough for a text this long. */
    private void grow(int n) {
      if (current.length < n) {
        int size = Math.max(n, current.length * 2);
        previous = new int[size];
        current = new int[size];
        bonus = new int[size];
        chars = new char[size];
      }
    }

    /**
     * Keeps the text's characters as compared and the bonus of each position from {@code lo} on,
     * where the query's first character first occurs, and puts the scores of that character at
     * each of those positions into {@code first}, and none just before them.
     */
    private void prepare(String text, String compared, int lo, int[] first) {
      int n = text.length();
      char q = query[0];
      char before = lo == 0 ? ' ' : text.charAt(lo - 1);
      if (lo > 0) {
        first[lo - 1] = NONE;
      }
      for (int i = lo; i < n; i++) {
        char c = text.charAt(i);
        char was = compared.charAt(i);
        chars[i] = was;
        int b = bonus(before, c);
        bonus[i] = b;
        first[i] = was == q ? MATCH + 2 * b : NONE;
        before = c;
      }
    }

    /**
     * The scores of the query's character {@code j} at each position from {@code lo} on, from
     * those of {@code j-1}, and none just before them; returns the best of them.
     */
    private int next(int n, int lo, int j, int[] before, int[] into) {
      char q = query[j];
      int gap = NONE; // the best of the positions two or more before, less its gap
      int best = NONE;
      into[lo] = NONE;
      if (lo > 0) {
        into[lo - 1] = NONE;
      }
      for (int i = lo + 1; i < n; i++) {
        if (i >= 2) {
          gap = Math.max(gap - GAP_EXTENSION, before[i - 2] - GAP_START);
        }
        if (chars[i] != q) {
          into[i] = NONE;
          continue;
        }
        int from = Math.max(before[i - 1] + CONSECUTIVE, gap);
        into[i] = from <= NONE / 2 ? NONE : from + MATCH + bonus[i];
        best = Math.max(best, into[i]);
      }
      return best;
    }
  }
}
