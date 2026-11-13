package com.rethinkj.ui.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

  private static byte[] resource(String name) throws IOException {
    try (InputStream in = PngTest.class.getResourceAsStream("/png/" + name)) {
      return in.readAllBytes();
    }
  }

  /**
   * Images that Pillow wrote, of every colour type and of 1, 4, 8 and 16 bits, filtered as it
   * chose and with transparency by palette and by colour key, against the pixels Pillow reads.
   */
  @ParameterizedTest
  @ValueSource(strings = {"rgba8", "rgb8", "gray8", "graya8", "palette8", "palette4", "palette1",
      "gray1", "gray16", "rgbkey"})
  void readsWhatOtherToolsWrite(String name) throws IOException {
    Pixels read = Png.decode(resource(name + ".png"));
    assertEquals(13, read.width());
    assertEquals(7, read.height());
    // Sixteen bits keep their high byte: the sixteen-bit grey was the eight-bit grey times 257.
    assertArrayEquals(resource((name.equals("gray16") ? "gray8" : name) + ".rgba"), read.rgba());
  }

  /** Each of the five filters on rows of RGBA: row y filtered with type y % 5. */
  @Test
  void allFiveFiltersAreUndone() {
    int width = 9;
    int height = 10;
    byte[] rgba = new byte[width * height * 4];
    for (int i = 0; i < rgba.length; i++) {
      rgba[i] = (byte) (i * 37 + i / 11 * 5);
    }
    int rowBytes = width * 4;
    java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
    for (int y = 0; y < height; y++) {
      int filter = y % 5;
      raw.write(filter);
      for (int i = 0; i < rowBytes; i++) {
        int x = rgba[y * rowBytes + i] & 0xFF;
        int a = i >= 4 ? rgba[y * rowBytes + i - 4] & 0xFF : 0;
        int b = y > 0 ? rgba[(y - 1) * rowBytes + i] & 0xFF : 0;
        int c = i >= 4 && y > 0 ? rgba[(y - 1) * rowBytes + i - 4] & 0xFF : 0;
        int p = a + b - c;
        int paeth = Math.abs(p - a) <= Math.abs(p - b) && Math.abs(p - a) <= Math.abs(p - c) ? a
            : Math.abs(p - b) <= Math.abs(p - c) ? b : c;
        raw.write(x - switch (filter) {
          case 1 -> a;
          case 2 -> b;
          case 3 -> (a + b) / 2;
          case 4 -> paeth;
          default -> 0;
        });
      }
    }
    java.util.zip.Deflater deflater = new java.util.zip.Deflater();
    deflater.setInput(raw.toByteArray());
    deflater.finish();
    byte[] buffer = new byte[raw.size() + 64];
    byte[] idat = java.util.Arrays.copyOf(buffer, deflater.deflate(buffer));
    deflater.end();
    java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
    png.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
    chunk(png, "IHDR", java.nio.ByteBuffer.allocate(13).putInt(width).putInt(height)
        .put(new byte[] {8, 6, 0, 0, 0}).array());
    chunk(png, "IDAT", idat);
    chunk(png, "IEND", new byte[0]);
    assertArrayEquals(rgba, Png.decode(png.toByteArray()).rgba());
  }

  private static void chunk(java.io.ByteArrayOutputStream out, String type, byte[] data) {
    byte[] name = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
    crc.update(name);
    crc.update(data);
    out.writeBytes(java.nio.ByteBuffer.allocate(4).putInt(data.length).array());
    out.writeBytes(name);
    out.writeBytes(data);
    out.writeBytes(java.nio.ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
  }

  @Test
  void refusesInterlacedImagesAndSaysWhy() throws IOException {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> Png.decode(resource("interlaced.png")));
    assertTrue(e.getMessage().contains("interlac"), e.getMessage());
  }

  @Test
  void refusesAChunkWhoseCrcIsWrong() throws IOException {
    byte[] png = resource("rgba8.png");
    png[20] ^= 1; // inside IHDR
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> Png.decode(png));
    assertTrue(e.getMessage().contains("CRC"), e.getMessage());
  }

  @Test
  void rejectsWhatIsNotPng() {
    assertThrows(IllegalArgumentException.class, () -> Png.decode(new byte[] {1, 2, 3}));
    assertThrows(IllegalArgumentException.class, () -> Png.decode("not a png at all".getBytes()));
  }
}
