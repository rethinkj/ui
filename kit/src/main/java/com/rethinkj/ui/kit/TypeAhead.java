package com.rethinkj.ui.kit;

import java.util.Locale;
import java.util.function.IntFunction;

/**
 * Where typing goes among the items of a list or a tree, as in file managers: to the item whose
 * name starts with the text typed so far ({@link com.rethinkj.ui.El#onTypeAhead}).
 */
final class TypeAhead {

  private TypeAhead() {}

  /**
   * The item that {@code typed} goes to among {@code count} items, or -1 for none: the next whose
   * name starts with it, from {@code current} on, or else the first from there whose name holds
   * it, around at the end, in either case. Case does not count. One character, or the same one
   * typed again, goes to the next such item after the current one, so that repeating it steps
   * through them.
   *
   * @param current the item that is selected, or -1 for none
   * @param name the name of an item, or null for one that typing passes by
   */
  static int find(int count, int current, String typed, IntFunction<String> name) {
    String text = typed.toLowerCase(Locale.ROOT);
    int first = text.codePointAt(0);
    boolean again = text.codePoints().allMatch(c -> c == first);
    String want = again ? Character.toString(first) : text;
    int start = again ? current + 1 : Math.max(current, 0);
    for (int pass = 0; pass < 2; pass++) {
      for (int i = 0; i < count; i++) {
        int at = Math.floorMod(start + i, count);
        String item = name.apply(at);
        if (item != null) {
          String low = item.toLowerCase(Locale.ROOT);
          if (pass == 0 ? low.startsWith(want) : low.contains(want)) {
            return at;
          }
        }
      }
    }
    return -1;
  }
}
