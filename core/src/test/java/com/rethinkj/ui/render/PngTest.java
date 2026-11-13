package com.rethinkj.ui.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PngTest {

  @Test
  void pixelsSurviveWritingAndReading() {
    int width = 37;
    int height = 23;
    byte[] rgba = new byte[width * height * 4];
    for (int i = 0; i < rgba.length; i++) {
      rgba[i] = (byte) (i * 31 + i / 7);
    }

    Pixels read = Png.decode(Png.encode(new Pixels(width, height, rgba)));

    assertEquals(width, read.width());
    assertEquals(height, read.height());
    assertArrayEquals(rgba, read.rgba());
  }

  @Test
  void anEmptyImageSurvivesToo() {
    Pixels read = Png.decode(Png.encode(new Pixels(0, 0, new byte[0])));
    assertEquals(0, read.width());
  }

  @Test
  void rejectsWhatIsNotPng() {
    assertThrows(IllegalArgumentException.class, () -> Png.decode(new byte[] {1, 2, 3}));
    assertThrows(IllegalArgumentException.class, () -> Png.decode("not a png at all".getBytes()));
  }
}
