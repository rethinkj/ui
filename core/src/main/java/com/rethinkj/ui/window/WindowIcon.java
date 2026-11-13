package com.rethinkj.ui.window;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Headless;
import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.text.TextRendering;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * An icon of a window, for its taskbar button, Alt+Tab, its title bar and system menu, and a dock
 * on Linux ({@link Window#icon}, {@link Window.Options#icon}), or a badge on its taskbar button
 * ({@link Window#badge}). The system asks for square images of several sizes: on Windows 16 and 32
 * pixels times the display's scale, so 16 to 40 and 32 to 80 pixels from 100 to 250 %; on Linux
 * 64, 128 and 256 pixels, from which docks pick.
 *
 * <p>An icon is either a set of images or a view that {@link Headless#render} draws at each size
 * the system asks for, with the CPU renderer, as the image of an app's own look. A view without
 * text draws without native libraries; one with text, such as a symbol of the icon font, needs
 * FreeType and HarfBuzz, which a window's app has anyway.
 *
 * <pre>{@code
 * WindowIcon icon = WindowIcon.of(size -> el().wFull().hFull().radius(size / 5f).fill(blue));
 * try (Window window = Window.open("Lights", 360, 240, Window.options().icon(icon))) { ... }
 * }</pre>
 *
 * <p>An icon is a value that may be shared between windows. It keeps each image it has drawn;
 * use it on the windows' thread.
 */
public final class WindowIcon {

  /** The largest image an icon draws, as Windows' largest icons are. */
  public static final int MAX_SIZE = 1024;

  private final List<Pixels> images;
  private final IntFunction<El> view;
  private final Map<Integer, Pixels> drawn = new HashMap<>();

  private WindowIcon(List<Pixels> images, IntFunction<El> view) {
    this.images = images;
    this.view = view;
  }

  /**
   * An icon of these images. Each is square, in sRGB with straight (not premultiplied) alpha, as
   * {@link Pixels} holds it; give the sizes the system asks for, or larger ones. For a size the
   * images lack, the system gets the smallest image that is larger, or else the largest one, and
   * scales it itself.
   *
   * @throws IllegalArgumentException if there is no image, or one is not square or is empty or
   *     larger than {@link #MAX_SIZE}
   */
  public static WindowIcon of(Pixels... images) {
    if (images.length == 0) {
      throw new IllegalArgumentException("An icon needs at least one image");
    }
    for (Pixels image : images) {
      Objects.requireNonNull(image, "image");
      if (image.width() != image.height() || image.width() < 1 || image.width() > MAX_SIZE) {
        throw new IllegalArgumentException("An icon's image is square, from 1 to " + MAX_SIZE
            + " pixels; this one is " + image.width() + "x" + image.height());
      }
    }
    Pixels[] sorted = images.clone();
    Arrays.sort(sorted, Comparator.comparingInt(Pixels::width));
    return new WindowIcon(List.of(sorted), null);
  }

  /**
   * An icon drawn from a view. {@code view.apply(size)} builds the view for an image of {@code
   * size} by {@code size} pixels: it is laid out as the root of a window that many logical pixels
   * wide and high at scale 1, so that a view can place its strokes on whole pixels and pick a
   * symbol's drawing for the size, and drawn on a transparent background with the platform's
   * {@link TextRendering}. Each size is drawn once and kept.
   */
  public static WindowIcon of(IntFunction<El> view) {
    return new WindowIcon(null, Objects.requireNonNull(view, "view"));
  }

  /**
   * The image for {@code size} by {@code size} pixels: the view drawn at that size, or the image
   * as {@link #of(Pixels...)} says, which may have another size.
   */
  public Pixels image(int size) {
    if (size < 1 || size > MAX_SIZE) {
      throw new IllegalArgumentException("Icon size " + size + " is not from 1 to " + MAX_SIZE);
    }
    if (images != null) {
      for (Pixels image : images) {
        if (image.width() >= size) {
          return image;
        }
      }
      return images.getLast();
    }
    Pixels image = drawn.get(size);
    if (image == null) {
      El root = Objects.requireNonNull(view.apply(size), "the icon's view");
      image = Headless.render(root, size, size, 1, TextRendering.platform()).pixels();
      drawn.put(size, image);
    }
    return image;
  }
}
