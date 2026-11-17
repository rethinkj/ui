package com.rethinkj.ui;

/**
 * The range replaced by a single edit, in UTF-16 offsets. Both ranges begin at {@code start}
 * and end just before {@code oldEnd} in the old text and {@code newEnd} in the new text.
 */
public record TextChange(int start, int oldEnd, int newEnd) {

  /** Finds the changed ranges by keeping the longest common prefix, then the common suffix. */
  public static TextChange between(String before, String after) {
    int start = 0;
    int oldEnd = before.length();
    int newEnd = after.length();
    while (start < oldEnd && start < newEnd && before.charAt(start) == after.charAt(start)) {
      start++;
    }
    while (oldEnd > start
        && newEnd > start
        && before.charAt(oldEnd - 1) == after.charAt(newEnd - 1)) {
      oldEnd--;
      newEnd--;
    }
    return new TextChange(start, oldEnd, newEnd);
  }

  /** Maps a caret or selection endpoint from the old text into the new one. */
  int map(int index) {
    if (index <= start) {
      return index;
    }
    if (index >= oldEnd) {
      return newEnd + (index - oldEnd);
    }
    return Math.min(index, newEnd);
  }
}
