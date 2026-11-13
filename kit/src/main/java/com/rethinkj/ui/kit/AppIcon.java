package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;

import com.rethinkj.ui.El;
import com.rethinkj.ui.text.Font;

/**
 * Views for an app's icon and the badge on its taskbar button, drawn at a size in pixels: the view
 * of {@code com.rethinkj.ui.window.WindowIcon.of(size -> ...)}, which lays it out in a square of
 * {@code size} logical pixels at scale 1.
 *
 * <pre>{@code
 * WindowIcon icon = WindowIcon.of(size -> AppIcon.appIcon(t, size, brand, Symbol.DATABASE));
 * }</pre>
 */
public final class AppIcon {

  private AppIcon() {}

  /**
   * A rounded square in {@code fill}, the app's own colour, with {@code symbol} in the theme's
   * {@link Theme.TextColors#onAccent} in its middle, as the icons of Windows 11's own apps are:
   * the square leaves a margin of a sixteenth of the size, at least a pixel, its corners are
   * rounded by a fifth of it, and the symbol is the drawing of the icon font nearest to five
   * eighths of the size, which needs {@code rethinkj-ui-icons}.
   *
   * @param size the size of the image in pixels
   */
  public static El appIcon(Theme t, int size, int fill, Symbol symbol) {
    int margin = Math.max(1, Math.round(size / 16f));
    int inner = size - 2 * margin;
    float glyph = Math.round(size * 0.625f);
    return el().wFull()
        .hFull()
        .padding(margin)
        .child(el().size(inner, inner)
            .radius(inner * 0.2f)
            .fill(fill)
            .center()
            .child(El.text(Character.toString(symbol.codePoint(glyph)))
                .font(Font.icons())
                .fontSize(glyph)
                .color(t.text().onAccent())
                .shrink(0)));
  }

  /**
   * A dot in {@code fill} with a ring in {@link Theme.TextColors#onAccent}, filling the image:
   * the badge of a status, such as "connected" or "failed". Without text, so it draws without the
   * native libraries.
   *
   * @param size the size of the image in pixels
   */
  public static El dot(Theme t, int size, int fill) {
    float ring = Math.max(1, Math.round(size / 8f));
    return el().wFull()
        .hFull()
        .radius(size / 2f)
        .fill(t.text().onAccent())
        .padding(ring)
        .child(el().wFull().hFull().radius(size / 2f - ring).fill(fill));
  }

  /**
   * A count in {@link Theme.TextColors#onAccent} on a circle in {@code fill}: the badge of a
   * number of things waiting, such as running queries. Counts above 99 show as "99+".
   *
   * @param size the size of the image in pixels
   */
  public static El count(Theme t, int size, int fill, int count) {
    String text = count > 99 ? "99+" : Integer.toString(Math.max(0, count));
    float fontSize =
        Math.round(size * (text.length() == 1 ? 0.75f : text.length() == 2 ? 0.6f : 0.45f));
    return el().wFull()
        .hFull()
        .radius(size / 2f)
        .fill(fill)
        .center()
        .child(El.text(text)
            .font(Font.sansBold())
            .fontSize(fontSize)
            .color(t.text().onAccent())
            .textBackground(fill)
            .shrink(0));
  }
}
