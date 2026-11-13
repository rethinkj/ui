package com.rethinkj.ui.kit;

import java.text.BreakIterator;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The search behind {@link Find}, without native libraries: a query made into a {@link Pattern},
 * the matches in a part of a text up to a limit and a deadline, and the replacement of a match.
 *
 * <p>A match is never empty, and begins and ends on grapheme boundaries: a search for {@code e}
 * does not find the {@code e} of an {@code e} with a combining accent. Without {@code matchCase}
 * letters are compared by Unicode's simple case folding, as {@link Pattern#UNICODE_CASE} does it:
 * {@code ä} finds {@code Ä} and {@code ß} finds {@code ẞ}, but {@code ß} does not find {@code SS},
 * which is two characters. A whole word has no letter, digit, mark or {@code _} right before or
 * after it, the words of {@link com.rethinkj.ui.El#codeWords}.
 */
final class Search {

  private Search() {}

  /** What to look for, as the find bar's field and switches say. */
  record Query(String text, boolean matchCase, boolean wholeWord, boolean regex) {

    /** The pattern, or null for an empty text. */
    Pattern compile() throws PatternSyntaxException {
      if (text.isEmpty()) {
        return null;
      }
      String body = regex ? text : Pattern.quote(text);
      if (wholeWord) {
        body = "(?<![\\p{L}\\p{M}\\p{N}_])(?:" + body + ")(?![\\p{L}\\p{M}\\p{N}_])";
      }
      int flags = matchCase ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
      return Pattern.compile(body, flags | (regex ? Pattern.MULTILINE : 0));
    }
  }

  /** Thrown out of a search that went past its deadline or whose thread was interrupted. */
  static final class Stopped extends RuntimeException {
    Stopped() {
      super("The search took too long", null, false, false);
    }
  }

  /**
   * The matches of a pattern in a part of a text, in order, up to a limit.
   *
   * @param more whether the part has more matches than {@link #count}
   * @param stopped whether the search went past its deadline before the end, so that there may be
   *     more matches after the last
   */
  record Matches(int[] starts, int[] ends, int count, boolean more, boolean stopped) {

    static final Matches NONE = new Matches(new int[0], new int[0], 0, false, false);

    /** Whether the matches are all there are in the part. */
    boolean complete() {
      return !more && !stopped;
    }

    /** The index of the match from {@code start} to {@code end}, or -1. */
    int indexOf(int start, int end) {
      int i = Arrays.binarySearch(starts, 0, count, start);
      return i >= 0 && ends[i] == end ? i : -1;
    }

    /** The index of the first match that ends after {@code at}, or {@link #count}. */
    int firstEndingAfter(int at) {
      int low = 0;
      int high = count;
      while (low < high) {
        int mid = (low + high) >>> 1;
        if (ends[mid] <= at) {
          low = mid + 1;
        } else {
          high = mid;
        }
      }
      return low;
    }

    /** The index of the first match that starts at or after {@code at}, or {@link #count}. */
    int firstStartingFrom(int at) {
      int i = Arrays.binarySearch(starts, 0, count, at);
      return i >= 0 ? i : -i - 1;
    }
  }

  /**
   * A text whose reading ends with {@link Stopped} once a deadline has passed or the thread is
   * interrupted, so that a pattern that backtracks without end cannot hang the app: the clock and
   * the interrupt are asked every 4096 characters read.
   */
  static final class Bounded implements CharSequence {
    private final CharSequence text;
    private final long deadline;
    private int reads;

    /** @param deadline a value of {@link System#nanoTime}, or {@link Long#MAX_VALUE} for none */
    Bounded(CharSequence text, long deadline) {
      this.text = text;
      this.deadline = deadline;
    }

    @Override
    public char charAt(int index) {
      if ((++reads & 0xFFF) == 0
          && (System.nanoTime() - deadline > 0 || Thread.currentThread().isInterrupted())) {
        throw new Stopped();
      }
      return text.charAt(index);
    }

    @Override
    public int length() {
      return text.length();
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return new Bounded(text.subSequence(start, end), deadline);
    }

    @Override
    public String toString() {
      return text.toString();
    }
  }

  /** The deadline {@code millis} from now, for {@link Bounded}. */
  static long deadline(long millis) {
    return System.nanoTime() + millis * 1_000_000;
  }

  /**
   * A matcher of {@code pattern} for the part from {@code from} to {@code to} of the text, read
   * through {@link Bounded}; lookbehinds and lookaheads see the text around the part, and {@code
   * ^} and {@code $} match at lines, not at the part's ends.
   */
  private static Matcher matcher(Pattern pattern, String text, int from, int to, long deadline) {
    return pattern
        .matcher(new Bounded(text, deadline))
        .region(from, to)
        .useTransparentBounds(true)
        .useAnchoringBounds(false);
  }

  /**
   * The next match of {@code m} that counts, or false; {@code to} ends the part.
   *
   * @throws Stopped past the deadline, and for a pattern whose matching recursed too deep for the
   *     thread's stack, as {@code (a|b)+} does on a long run of {@code a}
   */
  private static boolean find(Matcher m, Graphemes graphemes, int to) {
    try {
      while (m.find()) {
        if (accepted(m, graphemes)) {
          return true;
        }
        if (m.start() + 1 > to) {
          return false;
        }
        m.region(m.start() + 1, to); // past a match inside a grapheme, which may hide another
      }
      return false;
    } catch (StackOverflowError deep) {
      throw new Stopped();
    }
  }

  /**
   * The matches of {@code pattern} from {@code from} to {@code to}, at most {@code limit} of them,
   * searched until the deadline.
   */
  static Matches scan(Pattern pattern, String text, int from, int to, int limit, long deadline) {
    int[] starts = new int[16];
    int[] ends = new int[16];
    int n = 0;
    Matcher m = matcher(pattern, text, from, to, deadline);
    Graphemes graphemes = new Graphemes(text);
    try {
      while (find(m, graphemes, to)) {
        if (n == limit) {
          return new Matches(starts, ends, n, true, false);
        }
        if (n == starts.length) {
          starts = Arrays.copyOf(starts, n * 2);
          ends = Arrays.copyOf(ends, n * 2);
        }
        starts[n] = m.start();
        ends[n] = m.end();
        n++;
      }
    } catch (Stopped stopped) {
      return new Matches(starts, ends, n, false, true);
    }
    return new Matches(starts, ends, n, false, false);
  }

  /**
   * The first match from {@code lo} to {@code hi} that starts at or after {@code from}, going
   * round to {@code lo} if there is none after it, as {start, end}; null for none.
   *
   * @throws Stopped past the deadline
   */
  static int[] next(Pattern pattern, String text, int lo, int hi, int from, long deadline) {
    from = Math.clamp(from, lo, hi);
    Graphemes graphemes = new Graphemes(text);
    Matcher m = matcher(pattern, text, from, hi, deadline);
    if (find(m, graphemes, hi)) {
      return new int[] {m.start(), m.end()};
    }
    m = matcher(pattern, text, lo, hi, deadline);
    return find(m, graphemes, hi) && m.start() < from ? new int[] {m.start(), m.end()} : null;
  }

  /**
   * The last match from {@code lo} to {@code hi} that starts before {@code before}, going round to
   * the end if there is none before it; null for none. It reads from {@code floor}, at or before
   * which the caller knows that no match it wants starts, or {@code lo}.
   *
   * @throws Stopped past the deadline
   */
  static int[] previous(
      Pattern pattern, String text, int lo, int hi, int before, int floor, long deadline) {
    before = Math.clamp(before, lo, hi);
    int[] found = last(pattern, text, Math.clamp(floor, lo, before), hi, before, deadline);
    return found != null ? found : last(pattern, text, before, hi, hi, deadline);
  }

  /** The last match from {@code from} to {@code hi} that starts before {@code before}. */
  private static int[] last(
      Pattern pattern, String text, int from, int hi, int before, long deadline) {
    Matcher m = matcher(pattern, text, from, hi, deadline);
    Graphemes graphemes = new Graphemes(text);
    int[] found = null;
    while (find(m, graphemes, hi) && m.start() < before) {
      found = new int[] {m.start(), m.end()};
    }
    return found;
  }

  /** Whether a match counts: it is not empty and lies on grapheme boundaries. */
  private static boolean accepted(Matcher m, Graphemes graphemes) {
    return m.end() > m.start() && graphemes.boundary(m.start()) && graphemes.boundary(m.end());
  }

  /** Whether a position of the text lies between two graphemes. */
  static boolean boundary(String text, int at) {
    return new Graphemes(text).boundary(at);
  }

  /**
   * The grapheme boundaries of a text, asked about mostly in the order of positions, as a search
   * does. Below U+0300, the combining marks, every pair of characters but CR LF is a boundary, and
   * so is every position next to a line feed. Elsewhere the JDK's iterator answers, over a window
   * that starts at such a boundary or at one it found before, never in the middle of a run that
   * depends on what came before it, as regional indicators pair up from the run's start; the
   * window ends 256 characters after the position, as a boundary depends on one character after
   * it. Each new window starts at the last boundary the window before knew, so a long line costs
   * in proportion to its length, not to the positions asked.
   */
  static final class Graphemes {
    private final String text;
    private final BreakIterator iterator = BreakIterator.getCharacterInstance();
    private int from = -1; // the window, from a boundary
    private int to = -1;

    Graphemes(String text) {
      this.text = text;
    }

    boolean boundary(int at) {
      if (at <= 0 || at >= text.length()) {
        return true;
      }
      if (known(at)) {
        return !crlf(at);
      }
      if (from < 0 || at <= from || at + 1 >= to && to < text.length()) {
        int start = 0;
        if (from >= 0 && at > from) {
          start = from + iterator.preceding(Math.min(at, to - 1) - from);
        }
        for (int i = at - 1; i > start; i--) {
          if (known(i) && !crlf(i)) {
            start = i;
            break;
          }
        }
        from = start;
        to = Math.min(text.length(), at + 256);
        iterator.setText(text.substring(from, to));
      }
      return iterator.isBoundary(at - from);
    }

    private boolean crlf(int at) {
      return text.charAt(at - 1) == '\r' && text.charAt(at) == '\n';
    }

    /** Whether the rule without the JDK decides at {@code at}, from 1 to the text's length less 1. */
    private boolean known(int at) {
      char before = text.charAt(at - 1);
      char after = text.charAt(at);
      return before < 0x300 && after < 0x300 || before == '\n' || after == '\n';
    }
  }

  /**
   * What replaces the selection from {@code start} to {@code end} if it is a match of a search of
   * the part that ends at {@code to}, as {@link #scan} finds them; null if it is not one. That is
   * the replacement as it is for a search that is not a regular expression; for one, with {@code
   * $1}, {@code ${name}} and {@code $0} the groups of the match, as {@link
   * Matcher#appendReplacement} writes them, and {@code \$} and {@code \\} a dollar and a
   * backslash.
   *
   * @throws IllegalArgumentException for a group that the pattern does not have
   * @throws Stopped past the deadline
   */
  static String replacement(
      Query query,
      Pattern pattern,
      String text,
      int start,
      int end,
      int to,
      String replacement,
      long deadline) {
    if (start >= end || end > to) {
      return null;
    }
    Matcher m = matcher(pattern, text, start, to, deadline);
    Graphemes graphemes = new Graphemes(text);
    try {
      if (!m.lookingAt() || m.end() != end || !accepted(m, graphemes)) {
        return null;
      }
    } catch (StackOverflowError deep) {
      throw new Stopped();
    }
    return query.regex() ? expand(m, replacement) : replacement;
  }

  /** {@code replacement} with the groups of the match {@code m} put in. */
  static String expand(Matcher m, String replacement) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < replacement.length(); i++) {
      char c = replacement.charAt(i);
      if (c == '\\' && i + 1 < replacement.length()) {
        out.append(replacement.charAt(++i));
      } else if (c == '$' && i + 1 < replacement.length()) {
        char next = replacement.charAt(i + 1);
        if (next == '{') {
          int close = replacement.indexOf('}', i + 2);
          if (close < 0) {
            throw new IllegalArgumentException("A ${ without its }");
          }
          String group = m.group(replacement.substring(i + 2, close));
          out.append(group == null ? "" : group);
          i = close;
        } else if (next >= '0' && next <= '9') {
          int group = next - '0';
          i++;
          // More digits while the pattern has a group of that number, as Java's own replacement.
          while (i + 1 < replacement.length()) {
            char d = replacement.charAt(i + 1);
            if (d < '0' || d > '9' || group * 10 + (d - '0') > m.groupCount()) {
              break;
            }
            group = group * 10 + (d - '0');
            i++;
          }
          if (group > m.groupCount()) {
            throw new IllegalArgumentException("No group " + group);
          }
          String found = m.group(group);
          out.append(found == null ? "" : found);
        } else {
          out.append(c);
        }
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  /**
   * Every match from {@code from} to {@code to} replaced: the span from the first match's start to
   * the last one's end and the text that takes its place; null for no match.
   *
   * @throws Stopped past the deadline
   */
  static Replaced replaceAll(
      Query query,
      Pattern pattern,
      String text,
      int from,
      int to,
      String replacement,
      long deadline) {
    Matcher m = matcher(pattern, text, from, to, deadline);
    Graphemes graphemes = new Graphemes(text);
    StringBuilder out = new StringBuilder();
    int first = -1;
    int last = -1;
    int count = 0;
    while (find(m, graphemes, to)) {
      if (first < 0) {
        first = m.start();
      } else {
        out.append(text, last, m.start());
      }
      out.append(query.regex() ? expand(m, replacement) : replacement);
      last = m.end();
      count++;
    }
    return first < 0 ? null : new Replaced(first, last, out.toString(), count);
  }

  /** What {@link #replaceAll} replaces: {@code from} to {@code to} by {@code text}. */
  record Replaced(int from, int to, String text, int count) {}
}
