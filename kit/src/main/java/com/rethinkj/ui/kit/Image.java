package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;

/**
 * A picture in the theme: core's image element ({@link El#image}) with the theme's medium radius,
 * or, while the app has no image to show, because it is still being read or could not be, a
 * placeholder with an icon and the text that stands for the image, on the quiet band with a
 * hairline, so that the reader knows what is missing. Both have {@code role=img} and that text as
 * their label.
 *
 * <pre>{@code
 * // The app reads the file off its thread and keeps the image in its state:
 * s.app.background("cover", () -> com.rethinkj.ui.Image.decode(Files.readAllBytes(file)),
 *     image -> s.cover = image, e -> s.cover = null);
 * // The view shows the placeholder until it is there:
 * Image.image(theme, s.cover, "The cover of the report").w(240);
 * }</pre>
 *
 * <p>The image is as large as its pixels at its scale in logical pixels unless the view sizes it;
 * a width or a height alone keeps its aspect ratio. The placeholder is as large as its icon and
 * text with the theme's padding, or as the view sizes it, its content centred.
 */
public final class Image {

  private Image() {}

  /**
   * The image, or the placeholder while there is none.
   *
   * @param image what to show; null for the placeholder
   * @param alt the text that stands for the image: the label of both, and the placeholder's text
   */
  public static El image(Theme t, com.rethinkj.ui.Image image, String alt) {
    if (image != null) {
      return El.image(image).label(alt).radius(t.radius().medium());
    }
    return row().role("img").label(alt).gap(t.space().m()).align(Style.Align.CENTER)
        .justify(Style.Content.CENTER).padding(t.space().l()).radius(t.radius().medium())
        .border(t.size().stroke(), t.border().subtle()).fill(t.surface().band())
        .children(Icon.icon(t, Symbol.IMAGE, Icon.Variant.MUTED),
            Label.label(t, Label.Variant.MUTED, alt).wrapText().shrink(1).minSize(0, 0));
  }
}
