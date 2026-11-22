package com.rethinkj.ui.editor;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The individual character edits between two strings, for moving positions and ranges across
 * single and bulk edits such as Replace All or formatting.
 *
 * <p>Edits are stored sorted and strictly separated by at least one unchanged character, each
 * replacing {@code [oldFrom, oldTo)} in the old string with {@code [newFrom, newTo)} in the new
 * string.
 */
final class TextEdits {

  // shortcut: beyond a Myers distance of 1000 characters the middles are diffed by lines, and
  // beyond 1000 lines they are one edit; a linear-space diff would lift the cap if that is hit.
  static final int MAX_DISTANCE = 1000;

  // Within a changed line, which keeps a diff of every line of a text below 128 times its length.
  private static final int LINE_DISTANCE = 128;

  static final TextEdits EMPTY = new TextEdits(new int[0], new int[0], new int[0], new int[0], 0);

  private final int[] oldFrom;
  private final int[] oldTo;
  private final int[] newFrom;
  private final int[] newTo;
  private final int count;

  private TextEdits(int[] oldFrom, int[] oldTo, int[] newFrom, int[] newTo, int count) {
    this.oldFrom = oldFrom;
    this.oldTo = oldTo;
    this.newFrom = newFrom;
    this.newTo = newTo;
    this.count = count;
  }

  /** Computes the edits from {@code before} to {@code after}. */
  static TextEdits between(String before, String after) {
    if (before.equals(after)) {
      return EMPTY;
    }
    int prefix = 0;
    int oldEnd = before.length();
    int newEnd = after.length();
    while (prefix < oldEnd && prefix < newEnd && before.charAt(prefix) == after.charAt(prefix)) {
      prefix++;
    }
    while (oldEnd > prefix
        && newEnd > prefix
        && before.charAt(oldEnd - 1) == after.charAt(newEnd - 1)) {
      oldEnd--;
      newEnd--;
    }
    if (prefix == oldEnd || prefix == newEnd) {
      return single(prefix, oldEnd, prefix, newEnd);
    }
    if (sameNonWhitespace(before, after, prefix, oldEnd, newEnd)) {
      return whitespaceEdits(before, after, prefix, oldEnd, newEnd);
    }
    Builder out = new Builder();
    if (myers(before, prefix, oldEnd, after, prefix, newEnd, MAX_DISTANCE, out)) {
      return out.build();
    }
    return byLine(before, after, prefix, oldEnd, newEnd);
  }

  /**
   * Maps a range {@code [from, to)} (or a point when {@code to <= from}) from the old string into
   * the new one. A range does not grow at its ends (text inserted at {@code from} lands before it,
   * at {@code to} after it), a point moves with the text after it (text inserted at it lands before
   * it), a range whose characters are deleted or replaced, or that gets text inserted strictly
   * inside, is touched and returns {@code -1}, and so is a point with characters replaced on both
   * sides of it. When untouched, a range keeps its length {@code to - from}.
   */
  int mapRange(int from, int to) {
    int end = Math.max(to, from);
    int lo = 0;
    int hi = count;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (oldTo[mid] <= from) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    if (lo < count && oldFrom[lo] < end) {
      return -1;
    }
    int shift = lo == 0 ? 0 : newTo[lo - 1] - oldTo[lo - 1];
    return from + shift;
  }

  /** Maps a point {@code at}, moving with the text after it, or returns {@code -1} if touched. */
  int mapPoint(int at) {
    return mapRange(at, at);
  }

  private static TextEdits single(int oldFrom, int oldTo, int newFrom, int newTo) {
    return new TextEdits(
        new int[] {oldFrom}, new int[] {oldTo}, new int[] {newFrom}, new int[] {newTo}, 1);
  }

  /** Whether {@code before[prefix..oldEnd)} and {@code after[prefix..newEnd)} have the same non-white-space characters in the same order. */
  private static boolean sameNonWhitespace(
      String before, String after, int prefix, int oldEnd, int newEnd) {
    int i = prefix;
    int j = prefix;
    while (true) {
      while (i < oldEnd && Character.isWhitespace(before.charAt(i))) {
        i++;
      }
      while (j < newEnd && Character.isWhitespace(after.charAt(j))) {
        j++;
      }
      if (i == oldEnd || j == newEnd) {
        return i == oldEnd && j == newEnd;
      }
      if (before.charAt(i) != after.charAt(j)) {
        return false;
      }
      i++;
      j++;
    }
  }

  /** Aligns two middles with identical non-white-space characters in O(n) so only white space runs change. */
  private static TextEdits whitespaceEdits(
      String before, String after, int prefix, int oldEnd, int newEnd) {
    Builder out = new Builder();
    int i = prefix;
    int j = prefix;
    while (true) {
      int i0 = i;
      while (i < oldEnd && Character.isWhitespace(before.charAt(i))) {
        i++;
      }
      int j0 = j;
      while (j < newEnd && Character.isWhitespace(after.charAt(j))) {
        j++;
      }
      out.addTrimmed(before, after, i0, i, j0, j, 0);
      if (i == oldEnd && j == newEnd) {
        return out.build();
      }
      while (i < oldEnd
          && j < newEnd
          && !Character.isWhitespace(before.charAt(i))
          && !Character.isWhitespace(after.charAt(j))) {
        i++;
        j++;
      }
    }
  }

  /**
   * Diffs the middles by their lines, as for a Replace All that changes many lines, so that marks
   * on the lines it leaves keep their place: each line is one character for {@link #myers}, and
   * each block of changed lines is trimmed to one edit, or, where it has as many lines before and
   * after, as after a Replace All, each line is diffed on its own. Beyond {@link #MAX_DISTANCE} lines too, the
   * middles are one such block.
   */
  private static TextEdits byLine(String before, String after, int prefix, int oldEnd, int newEnd) {
    Map<String, Character> ids = new HashMap<>();
    int[] oldLines = lineStarts(before, prefix, oldEnd);
    int[] newLines = lineStarts(after, prefix, newEnd);
    String oldIds = lineIds(before, oldLines, ids);
    String newIds = lineIds(after, newLines, ids);
    Builder lines = new Builder(); // the blocks of changed lines
    if (oldIds == null
        || newIds == null
        || !myers(oldIds, 0, oldIds.length(), newIds, 0, newIds.length(), MAX_DISTANCE, lines)) {
      lines.add(0, oldLines.length - 1, 0, newLines.length - 1);
    }
    Builder out = new Builder();
    for (int i = 0; i < lines.count; i++) {
      int o = lines.oldFrom[i];
      int n = lines.newFrom[i];
      int count = lines.oldTo[i] - o;
      if (count == lines.newTo[i] - n) { // as many lines: each with its own edit
        for (int k = 0; k < count; k++) {
          out.addTrimmed(
              before,
              after,
              oldLines[o + k],
              oldLines[o + k + 1],
              newLines[n + k],
              newLines[n + k + 1],
              LINE_DISTANCE);
        }
      } else {
        out.addTrimmed(
            before,
            after,
            oldLines[o],
            oldLines[lines.oldTo[i]],
            newLines[n],
            newLines[lines.newTo[i]],
            0);
      }
    }
    return out.build();
  }

  /** Where each line of {@code text[from, to)} starts, its line feed with it, and {@code to}. */
  private static int[] lineStarts(String text, int from, int to) {
    int[] starts = new int[16];
    int n = 0;
    for (int at = from; at < to; ) {
      if (n + 1 >= starts.length) {
        starts = Arrays.copyOf(starts, starts.length * 2);
      }
      starts[n++] = at;
      int feed = text.indexOf('\n', at);
      at = feed < 0 || feed >= to ? to : feed + 1;
    }
    starts[n++] = to;
    return Arrays.copyOf(starts, n);
  }

  /** The lines as one character each, the same for the same line; null for too many lines. */
  private static String lineIds(String text, int[] starts, Map<String, Character> ids) {
    StringBuilder out = new StringBuilder(starts.length - 1);
    for (int i = 0; i + 1 < starts.length; i++) {
      String line = text.substring(starts[i], starts[i + 1]);
      Character id = ids.get(line);
      if (id == null) {
        if (ids.size() > Character.MAX_VALUE) {
          return null;
        }
        id = (char) ids.size();
        ids.put(line, id);
      }
      out.append(id.charValue());
    }
    return out.toString();
  }

  /**
   * Myers' O(ND) character diff of {@code before[oFrom, oTo)} and {@code after[nFrom, nTo)}, its
   * edits added to {@code out} in order; false, with nothing added, beyond {@code distance}.
   */
  private static boolean myers(
      String before,
      int oFrom,
      int oTo,
      String after,
      int nFrom,
      int nTo,
      int distance,
      Builder out) {
    int n = oTo - oFrom;
    int m = nTo - nFrom;
    if (Math.abs(n - m) > distance) {
      return false;
    }
    int maxD = Math.min(distance, n + m);
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
        while (x < n && y < m && before.charAt(oFrom + x) == after.charAt(nFrom + y)) {
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
      return false;
    }
    Builder reversed = new Builder();
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
        reversed.add(oFrom + eOldFrom, oFrom + eOldTo, nFrom + eNewFrom, nFrom + eNewTo);
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
      reversed.add(oFrom + eOldFrom, oFrom + eOldTo, nFrom + eNewFrom, nFrom + eNewTo);
    }
    for (int i = reversed.count - 1; i >= 0; i--) {
      out.add(reversed.oldFrom[i], reversed.oldTo[i], reversed.newFrom[i], reversed.newTo[i]);
    }
    return true;
  }

  private static final class Builder {
    private int[] oldFrom = new int[8];
    private int[] oldTo = new int[8];
    private int[] newFrom = new int[8];
    private int[] newTo = new int[8];
    private int count;

    void add(int of, int ot, int nf, int nt) {
      if (count == oldFrom.length) {
        int size = count * 2;
        oldFrom = Arrays.copyOf(oldFrom, size);
        oldTo = Arrays.copyOf(oldTo, size);
        newFrom = Arrays.copyOf(newFrom, size);
        newTo = Arrays.copyOf(newTo, size);
      }
      oldFrom[count] = of;
      oldTo[count] = ot;
      newFrom[count] = nf;
      newTo[count] = nt;
      count++;
    }

    /**
     * Adds the edit of {@code before[a, aEnd)} to {@code after[b, bEnd)} without what they start
     * and end with alike, as the edits of {@link #myers} within {@code distance}, else as one.
     */
    void addTrimmed(String before, String after, int a, int aEnd, int b, int bEnd, int distance) {
      while (a < aEnd && b < bEnd && before.charAt(a) == after.charAt(b)) {
        a++;
        b++;
      }
      while (aEnd > a && bEnd > b && before.charAt(aEnd - 1) == after.charAt(bEnd - 1)) {
        aEnd--;
        bEnd--;
      }
      if ((a < aEnd || b < bEnd)
          && !(a < aEnd && b < bEnd && myers(before, a, aEnd, after, b, bEnd, distance, this))) {
        add(a, aEnd, b, bEnd);
      }
    }

    TextEdits build() {
      return count == 0 ? EMPTY : new TextEdits(oldFrom, oldTo, newFrom, newTo, count);
    }
  }
}
