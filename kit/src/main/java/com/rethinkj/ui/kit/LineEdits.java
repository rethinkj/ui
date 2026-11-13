package com.rethinkj.ui.kit;

/**
 * The line commands of {@link CodeEditor}, as edits of a text with line feeds: each replaces one
 * range, so that it is one step of the undo history, and says where the caret and the anchor go.
 * The lines a command acts on are the caret's, or those the selection touches; a selection that
 * ends at the start of a line leaves that line out, as editors do.
 */
final class LineEdits {

  private LineEdits() {}

  /** {@code from} to {@code to} of the text replaced by {@code text}; then the selection. */
  record Edit(int from, int to, String text, int caret, int anchor) {}

  /** The start of the line that holds {@code at}. */
  static int lineStart(String text, int at) {
    return text.lastIndexOf('\n', at - 1) + 1;
  }

  /** The end of the line that holds {@code at}: its line feed, or the end of the text. */
  static int lineEnd(String text, int at) {
    int feed = text.indexOf('\n', at);
    return feed < 0 ? text.length() : feed;
  }

  /** {first line's start, last line's end} of the lines of the selection. */
  static int[] block(String text, int caret, int anchor) {
    int s = Math.min(caret, anchor);
    int e = Math.max(caret, anchor);
    int last = e > s && e == lineStart(text, e) ? e - 1 : e;
    return new int[] {lineStart(text, s), lineEnd(text, last)};
  }

  /** A copy of the lines below them, selected as they were. */
  static Edit duplicate(String text, int caret, int anchor) {
    int[] b = block(text, caret, anchor);
    String copy = "\n" + text.substring(b[0], b[1]);
    int shift = copy.length();
    return new Edit(b[1], b[1], copy, caret + shift, anchor + shift);
  }

  /** The lines moved up ({@code -1}) or down ({@code 1}) past one line; null at the end. */
  static Edit move(String text, int caret, int anchor, int direction) {
    int[] b = block(text, caret, anchor);
    String lines = text.substring(b[0], b[1]);
    if (direction < 0) {
      if (b[0] == 0) {
        return null;
      }
      int above = lineStart(text, b[0] - 1);
      String line = text.substring(above, b[0] - 1);
      int shift = -(line.length() + 1);
      return new Edit(above, b[1], lines + "\n" + line, caret + shift, anchor + shift);
    }
    if (b[1] == text.length()) {
      return null;
    }
    int below = lineEnd(text, b[1] + 1);
    String line = text.substring(b[1] + 1, below);
    int shift = line.length() + 1;
    return new Edit(b[0], below, line + "\n" + lines, caret + shift, anchor + shift);
  }

  /**
   * The lines deleted with their line feed; the caret on the line that takes their place, in its
   * column as far as that line reaches.
   */
  static Edit delete(String text, int caret, int anchor) {
    int[] b = block(text, caret, anchor);
    int column = caret - lineStart(text, caret);
    if (b[1] < text.length()) { // the next line moves up into their place
      int next = lineEnd(text, b[1] + 1) - (b[1] + 1);
      int at = b[0] + Math.min(column, next);
      return new Edit(b[0], b[1] + 1, "", at, at);
    }
    if (b[0] > 0) { // the last lines: the line feed before them goes, and the caret goes up
      int above = lineStart(text, b[0] - 1);
      int at = above + Math.min(column, b[0] - 1 - above);
      return new Edit(b[0] - 1, b[1], "", at, at);
    }
    return new Edit(0, b[1], "", 0, 0);
  }

  /**
   * The lines commented out with {@code prefix} and a space at the indent of the least indented
   * line that is not blank, or, when each line that is not blank is a comment, the prefix and a
   * space after it taken away; blank lines stay as they are. Null when every line is blank.
   */
  static Edit comment(String text, int caret, int anchor, String prefix) {
    int[] b = block(text, caret, anchor);
    String[] lines = text.substring(b[0], b[1]).split("\n", -1);
    int indent = Integer.MAX_VALUE;
    boolean all = true;
    for (String line : lines) {
      int i = indent(line);
      if (i == line.length()) {
        continue; // blank
      }
      indent = Math.min(indent, i);
      all &= line.startsWith(prefix, i);
    }
    if (indent == Integer.MAX_VALUE) {
      return null;
    }
    StringBuilder out = new StringBuilder();
    // Per line: where it changed, the characters taken away there and the ones put in.
    int[] at = new int[lines.length];
    int[] removed = new int[lines.length];
    int[] added = new int[lines.length];
    for (int n = 0; n < lines.length; n++) {
      String line = lines[n];
      int i = indent(line);
      if (n > 0) {
        out.append('\n');
      }
      if (i == line.length()) {
        out.append(line);
        continue;
      }
      if (all) {
        int cut = prefix.length() + (line.startsWith(" ", i + prefix.length()) ? 1 : 0);
        at[n] = i;
        removed[n] = cut;
        out.append(line, 0, i).append(line, i + cut, line.length());
      } else {
        at[n] = indent;
        added[n] = prefix.length() + 1;
        out.append(line, 0, indent).append(prefix).append(' ').append(line, indent, line.length());
      }
    }
    return new Edit(
        b[0],
        b[1],
        out.toString(),
        shifted(text, b[0], caret, at, removed, added),
        shifted(text, b[0], anchor, at, removed, added));
  }

  /** Where a position of the lines from {@code start} on lies after the per-line changes. */
  private static int shifted(
      String text, int start, int position, int[] at, int[] removed, int[] added) {
    int lineStart = start;
    int delta = 0;
    for (int n = 0; n < at.length; n++) {
      int lineEnd = lineEnd(text, lineStart);
      if (position <= lineEnd || n == at.length - 1) {
        int column = position - lineStart;
        if (column >= at[n]) {
          delta += added[n] - Math.min(removed[n], column - at[n]);
        }
        return position + delta;
      }
      delta += added[n] - removed[n];
      lineStart = lineEnd + 1;
    }
    return position + delta;
  }

  private static int indent(String line) {
    int i = 0;
    while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
      i++;
    }
    return i;
  }
}
