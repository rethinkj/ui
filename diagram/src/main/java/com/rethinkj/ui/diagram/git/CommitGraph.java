package com.rethinkj.ui.diagram.git;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Assigns each commit of a history to a lane in one pass, keeping the per-row result in compact
 * primitive arrays for histories of 100k+ commits.
 *
 * <p>Commits are appended newest first in topological order via {@link #append(List)}. A commit
 * takes the leftmost open lane that expects it and closes any other lanes expecting it, or opens
 * the leftmost free lane in a new color when no lane expects it. Its first parent continues in the
 * same lane and color; each remaining parent joins an existing lane expecting it or opens the
 * leftmost free lane in a new color. Appending a history in pages produces the same rows as
 * appending all commits at once.
 */
public final class CommitGraph {

  private static final int UP = 1;
  private static final int DOWN = 2;
  private static final int HORIZONTAL = 4;

  /**
   * A line in the gap between two adjacent rows.
   *
   * @param from the column at the upper row
   * @param to the column at the lower row
   * @param color the lane's unbounded chain index
   */
  public record Line(int from, int to, int color) {}

  private int size;
  private int width;
  private int[] columns = new int[0];
  private int[] colors = new int[0];
  // gapOffsets[r]..gapOffsets[r + 1] is the slice of gapLines (in ints, 3 per line) for above(r).
  private int[] gapOffsets = {0};
  private int[] gapLines = new int[0];
  private int gapLength;

  // Open-lane state after the last appended row, kept between pages.
  private int laneCount;
  private String[] laneHash = new String[0];
  private int[] laneColor = new int[0];
  private int[] laneFrom = new int[0];
  private int joinCount;
  private int[] joinTo = new int[0];
  private String[] joinHash = new String[0];
  private int[] joinColor = new int[0];
  private int nextColor;

  /** Creates an empty commit graph. */
  public CommitGraph() {
    // no rows and no open lanes: the first page starts the history
  }

  /**
   * Appends a page of commits (newest first, in topological order), continuing from any open lanes
   * left by earlier pages.
   *
   * @param page the commits to append
   */
  public void append(List<Commit> page) {
    Objects.requireNonNull(page, "page");
    for (Commit commit : page) {
      Objects.requireNonNull(commit, "commit");
    }
    if (page.isEmpty()) {
      return;
    }
    ensureRowCapacity(size + page.size());
    for (Commit commit : page) {
      add(commit);
    }
  }

  /** Returns the number of rows laid out so far. */
  public int size() {
    return size;
  }

  /** Returns the most lanes any row uses, or 0 when the graph is empty. */
  public int width() {
    return width;
  }

  /** Returns the zero-based lane column of the commit at {@code row}. */
  public int column(int row) {
    Objects.checkIndex(row, size);
    return columns[row];
  }

  /**
   * Returns the unbounded chain index of the commit at {@code row}; callers map it into a palette
   * modulo the palette size.
   */
  public int color(int row) {
    Objects.checkIndex(row, size);
    return colors[row];
  }

  /**
   * Returns the lines in the gap above {@code row}, with straight lines before curved lines. The
   * first row has no lines above it.
   */
  public List<Line> above(int row) {
    Objects.checkIndex(row, size);
    int start = gapOffsets[row];
    int end = gapOffsets[row + 1];
    int count = (end - start) / 3;
    if (count == 0) {
      return List.of();
    }
    Line[] lines = new Line[count];
    for (int i = 0, p = start; i < count; i++, p += 3) {
      lines[i] = new Line(gapLines[p], gapLines[p + 1], gapLines[p + 2]);
    }
    return List.of(lines);
  }

  /**
   * Returns the lines in the gap below {@code row}, with straight lines before curved lines. For
   * the last row laid out so far, this reflects the open lanes straight down plus lines from the
   * commit to new or joined lanes until another page is appended.
   */
  public List<Line> below(int row) {
    Objects.checkIndex(row, size);
    if (row + 1 < size) {
      return above(row + 1);
    }
    return provisionalBelow(row);
  }

  /**
   * Returns the lane string of {@code row}, such as {@code "│ ● ╮"}, for tests and tree dumps: a
   * symbol per column, separated by spaces. {@code ●} is the commit and {@code │} a lane passing
   * by. A line that ends at the commit coming from above is {@code ╯} right of it and {@code ╰} left
   * of it, one that leaves the commit downward is {@code ╮} or {@code ╭}, and a column with both is
   * {@code ┤} or {@code ├}; {@code ─} bridges an empty column between them and the commit.
   */
  public String text(int row) {
    Objects.checkIndex(row, size);
    int col = columns[row];
    List<Line> above = above(row);
    List<Line> below = below(row);

    int maxCol = col;
    for (Line line : above) {
      maxCol = Math.max(maxCol, Math.max(line.from(), line.to()));
    }
    for (Line line : below) {
      maxCol = Math.max(maxCol, Math.max(line.from(), line.to()));
    }

    int len = maxCol + 1;
    byte[] flags = new byte[len];
    int prevCol = row > 0 ? columns[row - 1] : -1;
    for (Line line : above) {
      int a = line.from();
      int b = line.to();
      if (a == b || a == prevCol) {
        flags[b] |= UP;
      } else {
        flags[a] |= UP | HORIZONTAL;
      }
    }
    for (Line line : below) {
      int a = line.from();
      int b = line.to();
      if (a == b) {
        flags[a] |= DOWN;
      } else if (a == col) {
        flags[b] |= DOWN | HORIZONTAL;
      } else {
        flags[a] |= DOWN;
      }
    }

    int leftH = col;
    int rightH = col;
    for (int k = 0; k < len; k++) {
      if (k != col && (flags[k] & HORIZONTAL) != 0) {
        if (k < leftH) {
          leftH = k;
        }
        if (k > rightH) {
          rightH = k;
        }
      }
    }

    int last = col;
    for (int k = len - 1; k > col; k--) {
      if (flags[k] != 0) {
        last = k;
        break;
      }
    }

    StringBuilder out = new StringBuilder(last * 2 + 1);
    for (int k = 0; k <= last; k++) {
      if (k > 0) {
        out.append(' ');
      }
      if (k == col) {
        out.append('●');
        continue;
      }
      int f = flags[k];
      if (f == 0) {
        out.append(k > leftH && k < rightH ? '─' : ' ');
      } else if ((f & HORIZONTAL) == 0) {
        out.append('│');
      } else if (k > col) {
        out.append(
            switch (f & (UP | DOWN)) {
              case UP -> '╯';
              case DOWN -> '╮';
              default -> '┤';
            });
      } else {
        out.append(
            switch (f & (UP | DOWN)) {
              case UP -> '╰';
              case DOWN -> '╭';
              default -> '├';
            });
      }
    }
    return out.toString();
  }

  private void add(Commit commit) {
    String id = commit.id();
    int firstInto = findLane(id);

    int col;
    int color;
    if (firstInto >= 0) {
      col = firstInto;
      color = laneColor[firstInto];
    } else {
      col = firstFreeLane();
      color = nextColor++;
    }

    int row = size;
    if (row > 0) {
      emitFinalizedGap(id, col, columns[row - 1]);
    }

    for (int k = 0; k < laneCount; k++) {
      if (laneHash[k] != null) {
        if (id.equals(laneHash[k])) {
          laneHash[k] = null;
        } else {
          laneFrom[k] = k;
        }
      }
    }

    if (joinCount > 0) {
      Arrays.fill(joinHash, 0, joinCount, null);
      joinCount = 0;
    }

    List<String> parents = commit.parents();
    if (!parents.isEmpty()) {
      setLane(col, parents.get(0), color, col);
      for (int i = 1; i < parents.size(); i++) {
        String parent = parents.get(i);
        int existing = findLane(parent);
        if (existing >= 0) {
          addJoin(existing, parent, laneColor[existing]);
        } else {
          int free = firstFreeLane();
          setLane(free, parent, nextColor++, col);
        }
      }
    }

    while (laneCount > 0 && laneHash[laneCount - 1] == null) {
      laneCount--;
    }

    columns[row] = col;
    colors[row] = color;
    gapOffsets[row + 1] = gapLength;
    size = row + 1;
    width = Math.max(width, Math.max(col + 1, laneCount));
  }

  private void emitFinalizedGap(String nextId, int nextCol, int prevCol) {
    for (int pass = 0; pass < 2; pass++) {
      boolean wantStraight = pass == 0;
      for (int k = 0; k < laneCount; k++) {
        String hash = laneHash[k];
        if (hash != null) {
          int from = laneFrom[k];
          int to = nextId.equals(hash) ? nextCol : k;
          if ((from == to) == wantStraight) {
            appendGapLine(from, to, laneColor[k]);
          }
        }
      }
      for (int j = 0; j < joinCount; j++) {
        int to = nextId.equals(joinHash[j]) ? nextCol : joinTo[j];
        if ((prevCol == to) == wantStraight) {
          appendGapLine(prevCol, to, joinColor[j]);
        }
      }
    }
  }

  private List<Line> provisionalBelow(int row) {
    int count = joinCount;
    for (int k = 0; k < laneCount; k++) {
      if (laneHash[k] != null) {
        count++;
      }
    }
    if (count == 0) {
      return List.of();
    }
    int prevCol = columns[row];
    Line[] lines = new Line[count];
    int out = 0;
    for (int pass = 0; pass < 2; pass++) {
      boolean wantStraight = pass == 0;
      for (int k = 0; k < laneCount; k++) {
        if (laneHash[k] != null) {
          int from = laneFrom[k];
          if ((from == k) == wantStraight) {
            lines[out++] = new Line(from, k, laneColor[k]);
          }
        }
      }
      for (int j = 0; j < joinCount; j++) {
        int to = joinTo[j];
        if ((prevCol == to) == wantStraight) {
          lines[out++] = new Line(prevCol, to, joinColor[j]);
        }
      }
    }
    return List.of(lines);
  }

  private int findLane(String hash) {
    for (int k = 0; k < laneCount; k++) {
      if (hash.equals(laneHash[k])) {
        return k;
      }
    }
    return -1;
  }

  private int firstFreeLane() {
    for (int k = 0; k < laneCount; k++) {
      if (laneHash[k] == null) {
        return k;
      }
    }
    return laneCount;
  }

  private void setLane(int index, String hash, int color, int from) {
    if (index >= laneHash.length) {
      int capacity = Math.max(index + 1, Math.max(8, laneHash.length << 1));
      laneHash = Arrays.copyOf(laneHash, capacity);
      laneColor = Arrays.copyOf(laneColor, capacity);
      laneFrom = Arrays.copyOf(laneFrom, capacity);
    }
    if (index >= laneCount) {
      laneCount = index + 1;
    }
    laneHash[index] = hash;
    laneColor[index] = color;
    laneFrom[index] = from;
  }

  private void addJoin(int to, String hash, int color) {
    if (joinCount == joinTo.length) {
      int capacity = Math.max(4, joinTo.length << 1);
      joinTo = Arrays.copyOf(joinTo, capacity);
      joinHash = Arrays.copyOf(joinHash, capacity);
      joinColor = Arrays.copyOf(joinColor, capacity);
    }
    joinTo[joinCount] = to;
    joinHash[joinCount] = hash;
    joinColor[joinCount] = color;
    joinCount++;
  }

  private void ensureRowCapacity(int needed) {
    if (needed <= columns.length) {
      return;
    }
    int grown = columns.length + (columns.length >> 1);
    int capacity = Math.max(needed, Math.max(16, grown));
    columns = Arrays.copyOf(columns, capacity);
    colors = Arrays.copyOf(colors, capacity);
    gapOffsets = Arrays.copyOf(gapOffsets, capacity + 1);
  }

  private void appendGapLine(int from, int to, int color) {
    if (gapLength + 3 > gapLines.length) {
      int grown = gapLines.length + (gapLines.length >> 1);
      int capacity = Math.max(gapLength + 3, Math.max(48, grown));
      gapLines = Arrays.copyOf(gapLines, capacity);
    }
    gapLines[gapLength++] = from;
    gapLines[gapLength++] = to;
    gapLines[gapLength++] = color;
  }
}
