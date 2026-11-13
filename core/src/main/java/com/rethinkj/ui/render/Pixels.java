package com.rethinkj.ui.render;

/**
 * An image: 8 bits per channel, sRGB, straight (not premultiplied) alpha, rows from the top.
 *
 * @param rgba four bytes per pixel in the order red, green, blue, alpha
 */
public record Pixels(int width, int height, byte[] rgba) {

  public Pixels {
    if (width < 0 || height < 0 || rgba.length != (long) width * height * 4) {
      throw new IllegalArgumentException(
          "rgba has " + rgba.length + " bytes, expected 4 * " + width + " * " + height);
    }
  }

  /** The pixel as 0xAARRGGBB. */
  public int argb(int x, int y) {
    int i = (y * width + x) * 4;
    return (rgba[i + 3] & 0xFF) << 24
        | (rgba[i] & 0xFF) << 16
        | (rgba[i + 1] & 0xFF) << 8
        | (rgba[i + 2] & 0xFF);
  }
}
