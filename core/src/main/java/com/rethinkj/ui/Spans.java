package com.rethinkj.ui;

import com.rethinkj.ui.text.Font;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * What a {@link Highlighter} or a {@link Decorator} says about one line of a text: colours and
 * fonts of ranges, backgrounds behind ranges and behind the whole line, and lines under ranges.
 * Positions are indexes into the line's string, from 0; a range runs from {@code from} to {@code
 * to}, exclusive, is clamped to the line and left out when empty. Where ranges of one kind
 * overlap, the later one wins.
 *
 * <p>Colours ({@link #color}) cost nothing beyond the glyphs: they need no shaping. A font
 * ({@link #font}) is another cut at the text's size, such as the bold one: its range is shaped on
 * its own, so carets, selection and wrapping follow its wider glyphs. Backgrounds, underlines and
 * the line's background and struck-out ranges are drawn in a {@link El#multiline multi-line} field only, before its
 * selection and glyphs.
 */
public final class Spans {

  static final int COLOR = 0;
  static final int FONT = 1;
  static final int BACKGROUND = 2;
  static final int UNDERLINE = 3;
  static final int STRIKE = 4;

  private final int length;
  private final boolean discard; // only the state is wanted: adds do nothing
  int count;
  int[] kinds = new int[4];
  int[] froms = new int[4];
  int[] tos = new int[4];
  int[] colors = new int[4];
  Font[] fonts = new Font[4];
  int lineBackground;
  private boolean styled; // a colour or a font, which the glyphs need

  // For Lines: the state the line was highlighted from and the one it ended in.
  Object startState;
  Object endState;

  Spans(int length, boolean discard) {
    this.length = length;
    this.discard = discard;
  }

  /**
   * Empty spans for a line of {@code length} characters, to test a highlighter with; {@link
   * #toString} lists what it was told.
   */
  public Spans(int length) {
    this(length, false);
  }

  /**
   * What the spans hold, one range per line in the order given, as {@code color 0-6 FF7C3AED},
   * with {@code line FFE0E7FF} first for a line's background.
   */
  @Override
  public String toString() {
    StringBuilder out = new StringBuilder();
    if (lineBackground != 0) {
      out.append("line ").append(Integer.toHexString(lineBackground).toUpperCase(Locale.ROOT))
          .append('\n');
    }
    String[] names = {"color", "font", "background", "underline", "strike"};
    for (int i = 0; i < count; i++) {
      out.append(names[kinds[i]]).append(' ').append(froms[i]).append('-').append(tos[i])
          .append(' ').append(kinds[i] == FONT ? fonts[i].toString()
              : Integer.toHexString(colors[i]).toUpperCase(Locale.ROOT)).append('\n');
    }
    return out.toString();
  }

  /** The text of the range in colour {@code argb}, as 0xAARRGGBB, instead of the text's own. */
  public Spans color(int from, int to, int argb) {
    return add(COLOR, from, to, argb, null);
  }

  /**
   * The text of the range in {@code font} at the text's size, such as {@link Font#monoBold} in a
   * field of {@link Font#mono}. The font is used as given: give it the fallbacks the text's font
   * has with {@link Font#withFallback} where the range may hold characters it lacks.
   */
  public Spans font(int from, int to, Font font) {
    return add(FONT, from, to, 0, Objects.requireNonNull(font, "font"));
  }

  /**
   * A rectangle of colour {@code argb} behind the range, a line high, as behind a match of a
   * search or a matching bracket; a range that ends at the end of the line ends there.
   */
  public Spans background(int from, int to, int argb) {
    return add(BACKGROUND, from, to, argb, null);
  }

  /**
   * A straight line under the range, in colour {@code argb}, as under an error; it lies where the
   * line under an input method's composition does, and is as thick.
   */
  public Spans underline(int from, int to, int argb) {
    return add(UNDERLINE, from, to, argb, null);
  }

  /**
   * A straight line through the middle of the range's lower-case letters, in colour {@code argb},
   * as thick as an {@link #underline}: struck-out text. It lies behind the glyphs.
   */
  public Spans strike(int from, int to, int argb) {
    return add(STRIKE, from, to, argb, null);
  }

  /**
   * A background across the whole width of the field behind this line, all of its rows when it
   * wraps, in colour {@code argb}: the line of the caret, or a line with an error. Drawn before
   * the backgrounds of ranges.
   */
  public Spans lineBackground(int argb) {
    if (!discard) {
      lineBackground = argb;
    }
    return this;
  }

  private Spans add(int kind, int from, int to, int argb, Font font) {
    from = Math.clamp(from, 0, length);
    to = Math.clamp(to, 0, length);
    if (discard || to <= from) {
      return this;
    }
    if (count == kinds.length) {
      int size = count * 2;
      kinds = Arrays.copyOf(kinds, size);
      froms = Arrays.copyOf(froms, size);
      tos = Arrays.copyOf(tos, size);
      colors = Arrays.copyOf(colors, size);
      fonts = Arrays.copyOf(fonts, size);
    }
    kinds[count] = kind;
    froms[count] = from;
    tos[count] = to;
    colors[count] = argb;
    fonts[count] = font;
    count++;
    styled |= kind == COLOR || kind == FONT;
    return this;
  }

  /** Whether a colour or a font is set on a range: the glyphs depend on it. */
  boolean styled() {
    return styled;
  }

  /** Whether a range of this kind is set. */
  boolean has(int kind) {
    for (int i = 0; i < count; i++) {
      if (kinds[i] == kind) {
        return true;
      }
    }
    return false;
  }
}
