package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.El;
import java.util.List;
import java.util.Locale;

/**
 * A round mark with initials or an icon, in a colour that comes from a name, so that the user
 * knows a server or a connection again by its colour wherever it shows: the same name gets the
 * same colour of {@link Theme.Badges#avatars} on every run and in both default themes. In a tree
 * dump it is {@code role=img} with the name as its label.
 *
 * <pre>{@code
 * Avatar.avatar(t, "prod-eu")                      // "PE" on a colour of its own
 * Avatar.avatar(t, "localhost", Symbol.SERVER, 20) // an icon instead of initials
 * }</pre>
 */
public final class Avatar {

  private Avatar() {}

  /** A mark with the name's initials, as high as a control. */
  public static El avatar(Theme t, String name) {
    return avatar(t, name, null, t.size().control());
  }

  /**
   * @param symbol in place of the initials; null for the initials
   * @param size the diameter in logical pixels
   */
  public static El avatar(Theme t, String name, Symbol symbol, float size) {
    Theme.Badges b = t.badge();
    El mark = row().role("img").label(name).size(size, size).shrink(0).center()
        .radius(size / 2).fill(color(b.avatars(), name));
    if (symbol != null) {
      return mark.child(Icon.icon(t, symbol, Icon.Variant.NORMAL, Math.round(size * 0.6f))
          .color(b.onAvatar()));
    }
    Theme.TextStyle style = t.type().heading();
    return mark.child(El.text(initials(name)).font(style.font().get())
        .fontSize(Math.round(size * 0.42f)).color(b.onAvatar()));
  }

  /** The colour of a name: the same for the same name, from its characters alone. */
  static int color(List<Integer> palette, String name) {
    return palette.get(Math.floorMod(name.hashCode(), palette.size()));
  }

  /**
   * The first letter or digit of each of the first two words, words being runs of letters and
   * digits: {@code prod-eu} is {@code PE}, {@code localhost} {@code L}.
   */
  static String initials(String name) {
    StringBuilder out = new StringBuilder();
    boolean inWord = false;
    for (int i = 0; i < name.length() && out.length() < 2; ) {
      int c = name.codePointAt(i);
      boolean letter = Character.isLetterOrDigit(c);
      if (letter && !inWord) {
        out.appendCodePoint(c);
      }
      inWord = letter;
      i += Character.charCount(c);
    }
    return out.toString().toUpperCase(Locale.ROOT);
  }
}
