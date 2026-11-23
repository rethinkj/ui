package com.rethinkj.ui.editor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A line-by-line diff between two strings, with changed character ranges inside paired modified
 * lines: the data model of {@link DiffView}. The app supplies both texts; the framework holds no
 * version control state.
 *
 * <p>Lines are compared after normalizing line breaks ({@code \r\n}, {@code \r}, U+2028, U+2029) to
 * {@code \n}, matching how {@link CodeEditor} displays a string. An empty string has zero lines;
 * any non-empty string splits on {@code \n} keeping trailing empty lines so 0-based line indices
 * match the editor's lines.
 *
 * <p>Line alignment uses Myers' {@code O(ND)} algorithm bounded to edit distance {@link
 * #MAX_DISTANCE}. When the remaining middle between the common prefix and common suffix requires a
 * greater distance, it falls back to a single {@link Kind#MODIFIED} hunk covering that middle,
 * without changed words.
 *
 * @param oldLineCount number of lines in the original text (0 when empty)
 * @param newLineCount number of lines in the modified text (0 when empty)
 * @param hunks changed regions in ascending line order, separated by at least one unchanged line
 */
public record Diff(int oldLineCount, int newLineCount, List<Hunk> hunks) {

  /**
   * Maximum Myers edit distance (in lines) before falling back to a single {@link Kind#MODIFIED}
   * hunk for the remaining middle between the common prefix and common suffix.
   */
  public static final int MAX_DISTANCE = 2_000;

  /** Maximum Myers edit distance (in tokens) for intraline changes on a paired modified line. */
  private static final int MAX_LINE_DISTANCE = 256;

  public Diff {
    if (oldLineCount < 0 || newLineCount < 0) {
      throw new IllegalArgumentException(
          "line counts must be 0 or more, were " + oldLineCount + " and " + newLineCount);
    }
    hunks = List.copyOf(Objects.requireNonNull(hunks, "hunks"));
  }

  /** What changed in a {@link Hunk}. */
  public enum Kind {
    /** Lines added in the new text ({@code oldStart == oldEnd}). */
    ADDED,
    /** Lines removed from the old text ({@code newStart == newEnd}). */
    DELETED,
    /** Lines replaced between old and new text ({@code oldEnd > oldStart && newEnd > newStart}). */
    MODIFIED
  }

  /**
   * A non-empty changed character range {@code [from, to)} within a line (UTF-16 offsets).
   *
   * @param from start offset, 0 or more
   * @param to end offset, strictly greater than {@code from}
   */
  public record Span(int from, int to) {
    public Span {
      if (from < 0 || to <= from) {
        throw new IllegalArgumentException(
            "span must have 0 <= from < to, were " + from + " and " + to);
      }
    }
  }

  /**
   * Intraline changes for one paired line inside a {@link Kind#MODIFIED} hunk.
   *
   * @param oldLine 0-based line index in the original text
   * @param newLine 0-based line index in the modified text
   * @param oldSpans changed character ranges within {@code oldLine}
   * @param newSpans changed character ranges within {@code newLine}
   */
  public record LineChange(int oldLine, int newLine, List<Span> oldSpans, List<Span> newSpans) {
    public LineChange {
      if (oldLine < 0 || newLine < 0) {
        throw new IllegalArgumentException(
            "line indices must be 0 or more, were " + oldLine + " and " + newLine);
      }
      oldSpans = List.copyOf(Objects.requireNonNull(oldSpans, "oldSpans"));
      newSpans = List.copyOf(Objects.requireNonNull(newSpans, "newSpans"));
    }
  }

  /**
   * One contiguous changed region: lines {@code [oldStart, oldEnd)} in the original text correspond
   * to lines {@code [newStart, newEnd)} in the modified text.
   *
   * @param kind whether lines were added, deleted or modified
   * @param oldStart first old line (0-based, inclusive)
   * @param oldEnd end old line (0-based, exclusive)
   * @param newStart first new line (0-based, inclusive)
   * @param newEnd end new line (0-based, exclusive)
   * @param lineChanges intraline changes for paired lines in a {@link Kind#MODIFIED} hunk
   */
  public record Hunk(
      Kind kind, int oldStart, int oldEnd, int newStart, int newEnd, List<LineChange> lineChanges) {
    public Hunk {
      Objects.requireNonNull(kind, "kind");
      if (oldStart < 0 || oldEnd < oldStart || newStart < 0 || newEnd < newStart) {
        throw new IllegalArgumentException("invalid hunk ranges: old [" + oldStart + "," + oldEnd
            + "), new [" + newStart + "," + newEnd + ")");
      }
      lineChanges = List.copyOf(Objects.requireNonNull(lineChanges, "lineChanges"));
    }
  }

  /** Computes the line diff between {@code oldText} and {@code newText}. */
  public static Diff lines(String oldText, String newText) {
    Objects.requireNonNull(oldText, "oldText");
    Objects.requireNonNull(newText, "newText");
    String[] oldLines = splitLines(CodeEditor.displayed(oldText));
    String[] newLines = splitLines(CodeEditor.displayed(newText));
    return diffLines(oldLines, newLines);
  }

  static String[] splitLines(String displayed) {
    return displayed.isEmpty() ? new String[0] : displayed.split("\n", -1);
  }

  static Diff diffLines(String[] oldLines, String[] newLines) {
    int oldLen = oldLines.length;
    int newLen = newLines.length;
    int prefix = 0;
    while (prefix < oldLen && prefix < newLen && oldLines[prefix].equals(newLines[prefix])) {
      prefix++;
    }
    int oldEnd = oldLen;
    int newEnd = newLen;
    while (oldEnd > prefix
        && newEnd > prefix
        && oldLines[oldEnd - 1].equals(newLines[newEnd - 1])) {
      oldEnd--;
      newEnd--;
    }
    if (prefix == oldEnd && prefix == newEnd) {
      return new Diff(oldLen, newLen, List.of());
    }
    if (prefix == oldEnd) {
      return new Diff(
          oldLen, newLen, List.of(new Hunk(Kind.ADDED, prefix, prefix, prefix, newEnd, List.of())));
    }
    if (prefix == newEnd) {
      return new Diff(
          oldLen,
          newLen,
          List.of(new Hunk(Kind.DELETED, prefix, oldEnd, prefix, prefix, List.of())));
    }
    int n = oldEnd - prefix;
    int m = newEnd - prefix;
    Map<String, Integer> ids = HashMap.newHashMap(n + m);
    int[] oldIds = new int[n];
    for (int i = 0; i < n; i++) {
      oldIds[i] = ids.computeIfAbsent(oldLines[prefix + i], k -> ids.size());
    }
    int[] newIds = new int[m];
    for (int i = 0; i < m; i++) {
      newIds[i] = ids.computeIfAbsent(newLines[prefix + i], k -> ids.size());
    }
    int[] edits = myers(oldIds, newIds, MAX_DISTANCE);
    if (edits == null) { // too far apart to pair lines: the middle is one hunk, without words
      Hunk middle = prefix == oldEnd || prefix == newEnd
          ? hunk(oldLines, newLines, prefix, oldEnd, prefix, newEnd)
          : new Hunk(Kind.MODIFIED, prefix, oldEnd, prefix, newEnd, List.of());
      return new Diff(oldLen, newLen, List.of(middle));
    }
    List<Hunk> hunks = new ArrayList<>(edits.length / 4);
    for (int i = 0; i < edits.length; i += 4) {
      hunks.add(hunk(
          oldLines,
          newLines,
          prefix + edits[i],
          prefix + edits[i + 1],
          prefix + edits[i + 2],
          prefix + edits[i + 3]));
    }
    return new Diff(oldLen, newLen, hunks);
  }

  private static Hunk hunk(
      String[] oldLines, String[] newLines, int oldStart, int oldEnd, int newStart, int newEnd) {
    if (oldStart == oldEnd) {
      return new Hunk(Kind.ADDED, oldStart, oldEnd, newStart, newEnd, List.of());
    }
    if (newStart == newEnd) {
      return new Hunk(Kind.DELETED, oldStart, oldEnd, newStart, newEnd, List.of());
    }
    int oldCount = oldEnd - oldStart;
    int newCount = newEnd - newStart;
    List<LineChange> changes = new ArrayList<>();
    if (oldCount == 1 && newCount == 1) {
      LineChange lc = intraline(oldStart, newStart, oldLines[oldStart], newLines[newStart]);
      if (lc != null) {
        changes.add(lc);
      }
    } else if ((long) oldCount * newCount <= 64) {
      pairLines(oldLines, newLines, oldStart, oldCount, newStart, newCount, changes);
    } else {
      int pairs = Math.min(oldCount, newCount);
      for (int i = 0; i < pairs; i++) {
        LineChange lc =
            intraline(oldStart + i, newStart + i, oldLines[oldStart + i], newLines[newStart + i]);
        if (lc != null) {
          changes.add(lc);
        }
      }
    }
    return new Hunk(Kind.MODIFIED, oldStart, oldEnd, newStart, newEnd, changes);
  }

  /** Minimum non-whitespace character similarity (0..1) required to emit intraline spans. */
  private static final double MIN_SIMILARITY = 0.35;

  /**
   * Monotonically pairs lines inside a small {@link Kind#MODIFIED} hunk by maximum intraline
   * similarity so an inserted or deleted line beside a modified line does not misalign pairs.
   */
  private static void pairLines(
      String[] oldLines,
      String[] newLines,
      int oldStart,
      int oldCount,
      int newStart,
      int newCount,
      List<LineChange> out) {
    LineChange[][] grid = new LineChange[oldCount][newCount];
    double[][] score = new double[oldCount][newCount];
    for (int i = 0; i < oldCount; i++) {
      for (int j = 0; j < newCount; j++) {
        LineChange lc =
            intraline(oldStart + i, newStart + j, oldLines[oldStart + i], newLines[newStart + j]);
        if (lc != null) {
          grid[i][j] = lc;
          score[i][j] = similarity(oldLines[oldStart + i], newLines[newStart + j], lc);
        }
      }
    }
    double[][] dp = new double[oldCount + 1][newCount + 1];
    for (int i = oldCount - 1; i >= 0; i--) {
      for (int j = newCount - 1; j >= 0; j--) {
        double best = Math.max(dp[i + 1][j], dp[i][j + 1]);
        if (grid[i][j] != null) {
          best = Math.max(best, score[i][j] + dp[i + 1][j + 1]);
        }
        dp[i][j] = best;
      }
    }
    int i = 0;
    int j = 0;
    while (i < oldCount && j < newCount) {
      if (grid[i][j] != null && dp[i][j] == score[i][j] + dp[i + 1][j + 1]) {
        out.add(grid[i][j]);
        i++;
        j++;
      } else if (dp[i + 1][j] >= dp[i][j + 1]) {
        i++;
      } else {
        j++;
      }
    }
  }

  private static double similarity(String oldText, String newText, LineChange lc) {
    int oldNonWs = nonWhitespaceCount(oldText);
    int newNonWs = nonWhitespaceCount(newText);
    int denom = Math.max(oldNonWs, newNonWs);
    if (denom == 0) {
      return 0;
    }
    int oldChanged = 0;
    for (Span s : lc.oldSpans()) {
      oldChanged += nonWhitespaceCount(oldText, s.from(), s.to());
    }
    int newChanged = 0;
    for (Span s : lc.newSpans()) {
      newChanged += nonWhitespaceCount(newText, s.from(), s.to());
    }
    int shared = Math.min(oldNonWs - oldChanged, newNonWs - newChanged);
    return (double) Math.max(0, shared) / denom;
  }

  private static int nonWhitespaceCount(String text) {
    return nonWhitespaceCount(text, 0, text.length());
  }

  private static int nonWhitespaceCount(String text, int from, int to) {
    int count = 0;
    for (int i = from; i < to; i++) {
      char c = text.charAt(i);
      // A surrogate pair counts once, as tokenize keeps it in one token.
      if (c != ' ' && c != '\t' && !Character.isLowSurrogate(c)) {
        count++;
      }
    }
    return count;
  }

  /**
   * Computes word/token-level intraline spans for two paired lines, returning null when the lines
   * are identical or share too little non-whitespace text to be meaningfully paired.
   */
  private static LineChange intraline(int oldLine, int newLine, String oldText, String newText) {
    if (oldText.equals(newText)) {
      return null;
    }
    int[] oldTok = tokenize(oldText);
    int[] newTok = tokenize(newText);
    int oldCount = oldTok.length / 2;
    int newCount = newTok.length / 2;
    int prefix = 0;
    while (prefix < oldCount
        && prefix < newCount
        && tokenEquals(oldText, oldTok, prefix, newText, newTok, prefix)) {
      prefix++;
    }
    int oldEnd = oldCount;
    int newEnd = newCount;
    while (oldEnd > prefix
        && newEnd > prefix
        && tokenEquals(oldText, oldTok, oldEnd - 1, newText, newTok, newEnd - 1)) {
      oldEnd--;
      newEnd--;
    }
    if (prefix == oldEnd && prefix == newEnd) {
      return null;
    }
    int n = oldEnd - prefix;
    int m = newEnd - prefix;
    int[] edits;
    if (n == 0 || m == 0) {
      edits = new int[] {0, n, 0, m};
    } else {
      Map<String, Integer> ids = HashMap.newHashMap(n + m);
      int[] oldIds = new int[n];
      for (int i = 0; i < n; i++) {
        String tok = oldText.substring(oldTok[(prefix + i) * 2], oldTok[(prefix + i) * 2 + 1]);
        oldIds[i] = ids.computeIfAbsent(tok, k -> ids.size());
      }
      int[] newIds = new int[m];
      for (int i = 0; i < m; i++) {
        String tok = newText.substring(newTok[(prefix + i) * 2], newTok[(prefix + i) * 2 + 1]);
        newIds[i] = ids.computeIfAbsent(tok, k -> ids.size());
      }
      edits = myers(oldIds, newIds, MAX_LINE_DISTANCE);
      if (edits == null) {
        edits = new int[] {0, n, 0, m};
      }
    }
    List<Span> oldSpans = new ArrayList<>(edits.length / 4);
    List<Span> newSpans = new ArrayList<>(edits.length / 4);
    for (int i = 0; i < edits.length; i += 4) {
      int of = prefix + edits[i];
      int ot = prefix + edits[i + 1];
      int nf = prefix + edits[i + 2];
      int nt = prefix + edits[i + 3];
      if (ot > of) {
        oldSpans.add(new Span(oldTok[of * 2], oldTok[(ot - 1) * 2 + 1]));
      }
      if (nt > nf) {
        newSpans.add(new Span(newTok[nf * 2], newTok[(nt - 1) * 2 + 1]));
      }
    }
    if (oldSpans.isEmpty() && newSpans.isEmpty()) {
      return null;
    }
    LineChange lc = new LineChange(oldLine, newLine, oldSpans, newSpans);
    if (similarity(oldText, newText, lc) < MIN_SIMILARITY) {
      return null;
    }
    return lc;
  }

  /**
   * Splits a line into alternating [from, to) token boundaries: word runs (letters, digits, '_'),
   * horizontal whitespace runs, and single punctuation characters.
   */
  private static int[] tokenize(String line) {
    int len = line.length();
    if (len == 0) {
      return new int[0];
    }
    int[] out = new int[Math.min(len * 2, 32)];
    int count = 0;
    int i = 0;
    while (i < len) {
      int start = i;
      char c = line.charAt(i);
      if (c == ' ' || c == '\t') {
        while (i < len && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
          i++;
        }
      } else if (Character.isLetterOrDigit(c) || c == '_') {
        while (i < len && (Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == '_')) {
          i++;
        }
      } else {
        i += Character.charCount(line.codePointAt(i));
      }
      if (count + 2 > out.length) {
        out = Arrays.copyOf(out, out.length * 2);
      }
      out[count++] = start;
      out[count++] = i;
    }
    return Arrays.copyOf(out, count);
  }

  private static boolean tokenEquals(String a, int[] aTok, int ai, String b, int[] bTok, int bi) {
    int aStart = aTok[ai * 2];
    int aLen = aTok[ai * 2 + 1] - aStart;
    int bStart = bTok[bi * 2];
    int bLen = bTok[bi * 2 + 1] - bStart;
    return aLen == bLen && a.regionMatches(aStart, b, bStart, aLen);
  }

  /**
   * Myers' {@code O(ND)} diff on integer sequences, returning packed quadruples {@code (oldFrom,
   * oldTo, newFrom, newTo)} in ascending order, or {@code null} when the distance exceeds {@code
   * maxDistance}.
   */
  private static int[] myers(int[] a, int[] b, int maxDistance) {
    int n = a.length;
    int m = b.length;
    if (Math.abs(n - m) > maxDistance) {
      return null;
    }
    int maxD = Math.min(maxDistance, n + m);
    int[][] trace = new int[maxD + 1][];
    int foundD = -1;
    for (int d = 0; d <= maxD && foundD < 0; d++) {
      int[] prev = d == 0 ? null : trace[d - 1];
      int[] cur = new int[d + 1];
      trace[d] = cur;
      for (int idx = 0; idx <= d; idx++) {
        int k = 2 * idx - d;
        int x;
        if (d == 0) {
          x = 0;
        } else {
          int down = idx < d && prev[idx] >= 0 && prev[idx] - k <= m ? prev[idx] : -1;
          int right = idx > 0 && prev[idx - 1] >= 0 && prev[idx - 1] < n ? prev[idx - 1] + 1 : -1;
          x = Math.max(down, right);
        }
        if (x < 0) {
          cur[idx] = -1;
          continue;
        }
        int y = x - k;
        while (x < n && y < m && a[x] == b[y]) {
          x++;
          y++;
        }
        cur[idx] = x;
        if (x == n && y == m) {
          foundD = d;
          break;
        }
      }
    }
    if (foundD < 0) {
      return null;
    }
    int[] reversed = new int[16];
    int revCount = 0;
    int x = n;
    int y = m;
    boolean open = false;
    int eOldFrom = 0;
    int eOldTo = 0;
    int eNewFrom = 0;
    int eNewTo = 0;
    for (int d = foundD; d >= 1; d--) {
      int k = x - y;
      int idx = (k + d) >> 1;
      int[] prev = trace[d - 1];
      int down = idx < d && prev[idx] >= 0 && prev[idx] - k <= m ? prev[idx] : -1;
      int right = idx > 0 && prev[idx - 1] >= 0 && prev[idx - 1] < n ? prev[idx - 1] + 1 : -1;
      int startX = Math.max(down, right);
      int startY = startX - k;
      int prevX = down >= right ? startX : startX - 1;
      int prevY = down >= right ? startY - 1 : startY;
      if (open && x > startX) {
        if (revCount + 4 > reversed.length) {
          reversed = Arrays.copyOf(reversed, reversed.length * 2);
        }
        reversed[revCount++] = eOldFrom;
        reversed[revCount++] = eOldTo;
        reversed[revCount++] = eNewFrom;
        reversed[revCount++] = eNewTo;
        open = false;
      }
      if (!open) {
        eOldTo = startX;
        eNewTo = startY;
        open = true;
      }
      eOldFrom = prevX;
      eNewFrom = prevY;
      x = prevX;
      y = prevY;
    }
    if (open) {
      if (revCount + 4 > reversed.length) {
        reversed = Arrays.copyOf(reversed, reversed.length * 2);
      }
      reversed[revCount++] = eOldFrom;
      reversed[revCount++] = eOldTo;
      reversed[revCount++] = eNewFrom;
      reversed[revCount++] = eNewTo;
    }
    int[] out = new int[revCount];
    for (int i = 0; i < revCount; i += 4) {
      int src = revCount - 4 - i;
      out[i] = reversed[src];
      out[i + 1] = reversed[src + 1];
      out[i + 2] = reversed[src + 2];
      out[i + 3] = reversed[src + 3];
    }
    return out;
  }
}
