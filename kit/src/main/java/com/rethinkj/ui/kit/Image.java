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
 * state.app.background("cover", () -> com.rethinkj.ui.Image.decode(Files.readAllBytes(file)),
 *     image -> state.cover = image, error -> state.cover = null);
 * // The view shows the placeholder until it is there:
 * Image.image(state.cover, "The cover of the report").w(240);
 * }</pre>
 *
 * <p>The image is as large as its pixels at its scale in logical pixels unless the view sizes it;
 * a width or a height alone keeps its aspect ratio. The placeholder is as large as its icon and
 * text with the theme's padding, or as the view sizes it, its content centered.
 */
public final class Image {

  private Image() {}

  /**
   * The image, or the placeholder while there is none.
   *
   * @param image what to show; null for the placeholder
   * @param alt the text that stands for the image: the label of both, and the placeholder's text
   */
  public static El image(com.rethinkj.ui.Image image, String alt) {
    Theme theme = Theme.CURRENT.get();
    if (image != null) {
      return El.image(image).label(alt).radius(theme.radius().medium());
    }
    return row()
        .role("img")
        .label(alt)
        .gap(theme.space().m())
        .align(Style.Align.CENTER)
        .justify(Style.Content.CENTER)
        .padding(theme.space().l())
        .radius(theme.radius().medium())
        .border(theme.size().stroke(), theme.border().subtle())
        .fill(theme.surface().band())
        .children(
            Icon.icon(Symbol.IMAGE, Icon.Variant.MUTED),
            Label.label(Label.Variant.MUTED, alt).wrapText().shrink(1).minSize(0, 0));
  }
}
