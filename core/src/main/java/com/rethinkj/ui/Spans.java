package com.rethinkj.ui;

import com.rethinkj.ui.text.Font;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * What a {@link Highlighter} or a {@link Decorator} says about one line of a text: colors and
 * fonts of ranges, backgrounds behind ranges and behind the whole line, and lines under ranges.
 * Positions are indexes into the line's string, from 0; a range runs from {@code from} to {@code
 * to}, exclusive, is clamped to the line and left out when empty. Where ranges of one kind
 * overlap, the later one wins.
 *
 * <p>Colors ({@link #color}) cost nothing beyond the glyphs: they need no shaping. A font
 * ({@link #font}) is another cut at the text's size, such as the bold one: its range is shaped on
 * its own, so carets, selection and wrapping follow its wider glyphs. Backgrounds, underlines,
 * squiggles, the line's background, struck-out ranges and guides are drawn in a {@link
 * El#multiline multi-line} field only, before its selection and glyphs.
 */
public final class Spans {

  static final int COLOR = 0;
  static final int FONT = 1;
  static final int BACKGROUND = 2;
  static final int UNDERLINE = 3;
  static final int STRIKE = 4;
  static final int GUIDE = 5;
  static final int SQUIGGLE = 6;

  private final int length;
  private final boolean discard; // only the state is wanted: adds do nothing
  int count;
  int[] kinds = new int[4];
  int[] froms = new int[4];
  int[] tos = new int[4];
  int[] colors = new int[4];
  Font[] fonts = new Font[4];
  int[] lineBackgrounds = new int[0]; // from the bottom up
  int[] brackets = new int[0]; // the positions of brackets of the code, sorted once read
  int bracketCount;
  private boolean bracketsSorted = true;
  private boolean styled; // a color or a font, which the glyphs need

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
   * with {@code line FFE0E7FF} first for each of a line's backgrounds.
   */
  @Override
  public String toString() {
    StringBuilder out = new StringBuilder();
    for (int argb : lineBackgrounds) {
      out.append("line ")
          .append(Integer.toHexString(argb).toUpperCase(Locale.ROOT))
          .append('\n');
    }
    String[] names = {"color", "font", "background", "underline", "strike", "guide", "squiggle"};
    for (int i = 0; i < count; i++) {
      out.append(names[kinds[i]])
          .append(' ')
          .append(froms[i])
          .append('-')
          .append(tos[i])
          .append(' ')
          .append(
              kinds[i] == FONT
                  ? fonts[i].toString()
                  : Integer.toHexString(colors[i]).toUpperCase(Locale.ROOT))
          .append('\n');
    }
    return out.toString();
  }

  /** The text of the range in color {@code argb}, as 0xAARRGGBB, instead of the text's own. */
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
   * A rectangle of color {@code argb} behind the range, a line high, as behind a match of a
   * search or a matching bracket; a range that ends at the end of the line ends there.
   */
  public Spans background(int from, int to, int argb) {
    return add(BACKGROUND, from, to, argb, null);
  }

  /**
   * A straight line under the range, in color {@code argb}, as under a link or a match of a
   * search; it lies where the line under an input method's composition does, and is as thick.
   */
  public Spans underline(int from, int to, int argb) {
    return add(UNDERLINE, from, to, argb, null);
  }

  /**
   * A wavy line under the range, in color {@code argb}, as under an error or a warning; it lies
   * where an {@link #underline} does, with amplitude and wavelength that follow the font size and
   * the scale, and continues across wrapped rows and line feeds.
   */
  public Spans squiggle(int from, int to, int argb) {
    return add(SQUIGGLE, from, to, argb, null);
  }

  /**
   * A straight line through the middle of the range's lower-case letters, in color {@code argb},
   * as thick as an {@link #underline}: struck-out text. It lies behind the glyphs.
   */
  public Spans strike(int from, int to, int argb) {
    return add(STRIKE, from, to, argb, null);
  }

  /**
   * A thin vertical line a line high at the left edge of character {@code at}, in color {@code
   * argb}, as thick as an {@link #underline}: an indentation guide in the leading white space of a
   * line of code. It lies behind the glyphs.
   */
  public Spans guide(int at, int argb) {
    return add(GUIDE, at, at + 1, argb, null);
  }

  /**
   * A background across the whole width of the field behind this line, all of its rows when it
   * wraps, in color {@code argb}: the line of the caret, or a line with an error. Drawn before
   * the backgrounds of ranges; a second one is laid over the first, so that the caret's line
   * inside a marked block shows both.
   */
  public Spans lineBackground(int argb) {
    if (!discard && argb >>> 24 != 0) {
      lineBackgrounds = Arrays.copyOf(lineBackgrounds, lineBackgrounds.length + 1);
      lineBackgrounds[lineBackgrounds.length - 1] = argb;
    }
    return this;
  }

  /**
   * Character {@code at} is a bracket of the code, one of {@code ( ) [ ] { }}, which bracket
   * matching pairs ({@link El#matchingBracket}); a highlighter that {@link
   * Highlighter#marksBrackets marks brackets} gives those outside strings and comments, and a
   * bracket it does not give pairs with none. Draws nothing; a decorator's are ignored.
   */
  public Spans bracket(int at) {
    if (!discard && at >= 0 && at < length) {
      if (bracketCount == brackets.length) {
        brackets = Arrays.copyOf(brackets, Math.max(4, bracketCount * 2));
      }
      bracketsSorted &= bracketCount == 0 || brackets[bracketCount - 1] < at;
      brackets[bracketCount++] = at;
    }
    return this;
  }

  /** The brackets of the code, ascending, in {@code brackets[0]} to {@code bracketCount - 1}. */
  int[] brackets() {
    if (!bracketsSorted) {
      Arrays.sort(brackets, 0, bracketCount);
      bracketsSorted = true;
    }
    return brackets;
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

  /** Whether a color or a font is set on a range: the glyphs depend on it. */
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
