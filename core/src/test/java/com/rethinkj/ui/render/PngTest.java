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
   * Images that Pillow wrote, of every color type and of 1, 4, 8 and 16 bits, filtered as it
   * chose and with transparency by palette and by color key, against the pixels Pillow reads. The
   * interlaced one is the RGBA image saved as an Adam7 interlaced palette image.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "rgba8",
        "rgb8",
        "gray8",
        "graya8",
        "palette8",
        "palette4",
        "palette1",
        "gray1",
        "gray16",
        "rgbkey",
        "interlaced"
      })
  void readsWhatOtherToolsWrite(String name) throws IOException {
    Pixels read = Png.decode(resource(name + ".png"));
    assertEquals(13, read.width());
    assertEquals(7, read.height());
    // Sixteen bits keep their high byte: the sixteen-bit gray was the eight-bit gray times 257.
    String expected = switch (name) {
      case "gray16" -> "gray8";
      case "interlaced" -> "rgba8";
      default -> name;
    };
    assertArrayEquals(resource(expected + ".rgba"), read.rgba());
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
        int paeth = Math.abs(p - a) <= Math.abs(p - b) && Math.abs(p - a) <= Math.abs(p - c)
            ? a
            : Math.abs(p - b) <= Math.abs(p - c) ? b : c;
        raw.write(x
            - switch (filter) {
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
    chunk(
        png,
        "IHDR",
        java.nio.ByteBuffer.allocate(13)
            .putInt(width)
            .putInt(height)
            .put(new byte[] {8, 6, 0, 0, 0})
            .array());
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

  /**
   * Adam7 interlaced images of every supported color type and bit depth, across empty, sub-8x8
   * (with empty passes) and odd sizes, decode to the same pixels as non-interlaced images.
   */
  @Test
  void readsAdam7InterlacedImages() {
    int[][] formats = {
      {0, 1}, {0, 2}, {0, 4}, {0, 8}, {0, 16}, {2, 8}, {2, 16}, {3, 1}, {3, 2}, {3, 4}, {3, 8},
      {4, 8}, {4, 16}, {6, 8}, {6, 16},
    };
    int[][] sizes = {{0, 0}, {1, 1}, {3, 2}, {5, 3}, {13, 7}};
    for (int[] format : formats) {
      int color = format[0];
      int depth = format[1];
      for (int[] size : sizes) {
        int width = size[0];
        int height = size[1];
        Pixels plain = Png.decode(encodeSynthetic(width, height, depth, color, 0));
        Pixels interlaced = Png.decode(encodeSynthetic(width, height, depth, color, 1));
        assertEquals(plain.width(), interlaced.width());
        assertEquals(plain.height(), interlaced.height());
        assertArrayEquals(
            plain.rgba(),
            interlaced.rgba(),
            "color=" + color + " depth=" + depth + " size=" + width + "x" + height);
      }
    }
    IllegalArgumentException unknown = assertThrows(
        IllegalArgumentException.class, () -> Png.decode(encodeSynthetic(3, 2, 8, 6, 2)));
    assertTrue(unknown.getMessage().contains("interlace"), unknown.getMessage());
  }

  private static byte[] encodeSynthetic(
      int width, int height, int depth, int color, int interlace) {
    int channels = switch (color) {
      case 2 -> 3;
      case 4 -> 2;
      case 6 -> 4;
      default -> 1;
    };
    int bits = channels * depth;
    int bpp = Math.max(1, bits / 8);
    int mask = (1 << depth) - 1;
    int[][] passes = interlace == 1
        ? new int[][] {
          {0, 0, 8, 8},
          {4, 0, 8, 8},
          {0, 4, 4, 8},
          {2, 0, 4, 4},
          {0, 2, 2, 4},
          {1, 0, 2, 2},
          {0, 1, 1, 2},
        }
        : new int[][] {{0, 0, 1, 1}};
    java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
    for (int[] pass : passes) {
      int startX = pass[0];
      int startY = pass[1];
      int stepX = pass[2];
      int stepY = pass[3];
      int passWidth = width <= startX ? 0 : (width - 1 - startX) / stepX + 1;
      int passHeight = height <= startY ? 0 : (height - 1 - startY) / stepY + 1;
      if (passWidth == 0 || passHeight == 0) {
        continue;
      }
      int rowBytes = (passWidth * bits + 7) / 8;
      byte[] previous = new byte[rowBytes];
      for (int py = 0; py < passHeight; py++) {
        int y = startY + py * stepY;
        byte[] row = new byte[rowBytes];
        for (int px = 0; px < passWidth; px++) {
          int x = startX + px * stepX;
          for (int c = 0; c < channels; c++) {
            int value = (x * 997 + y * 1543 + c * 2053 + 269) & mask;
            int index = px * channels + c;
            if (depth == 16) {
              row[index * 2] = (byte) (value >> 8);
              row[index * 2 + 1] = (byte) value;
            } else if (depth == 8) {
              row[index] = (byte) value;
            } else {
              int bit = index * depth;
              row[bit >> 3] |= (byte) (value << (8 - depth - (bit & 7)));
            }
          }
        }
        // Start each pass with Up (2) so forgetting to reset the previous row fails.
        filterRow(raw, row, previous, bpp, (py + 2) % 5);
        previous = row;
      }
    }
    java.util.zip.Deflater deflater = new java.util.zip.Deflater();
    deflater.setInput(raw.toByteArray());
    deflater.finish();
    java.io.ByteArrayOutputStream idat = new java.io.ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    while (!deflater.finished()) {
      idat.write(buffer, 0, deflater.deflate(buffer));
    }
    deflater.end();
    java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
    png.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
    chunk(
        png,
        "IHDR",
        java.nio.ByteBuffer.allocate(13)
            .putInt(width)
            .putInt(height)
            .put(new byte[] {(byte) depth, (byte) color, 0, 0, (byte) interlace})
            .array());
    if (color == 3) {
      int entries = 1 << depth;
      byte[] palette = new byte[entries * 3];
      for (int i = 0; i < entries; i++) {
        palette[i * 3] = (byte) (i * 29 + 11);
        palette[i * 3 + 1] = (byte) (i * 59 + 23);
        palette[i * 3 + 2] = (byte) (i * 89 + 37);
      }
      chunk(png, "PLTE", palette);
    }
    chunk(png, "IDAT", idat.toByteArray());
    chunk(png, "IEND", new byte[0]);
    return png.toByteArray();
  }

  private static void filterRow(
      java.io.ByteArrayOutputStream out, byte[] row, byte[] previous, int bpp, int filter) {
    out.write(filter);
    for (int i = 0; i < row.length; i++) {
      int x = row[i] & 0xFF;
      int a = i >= bpp ? row[i - bpp] & 0xFF : 0;
      int b = previous[i] & 0xFF;
      int c = i >= bpp ? previous[i - bpp] & 0xFF : 0;
      int p = a + b - c;
      int paeth = Math.abs(p - a) <= Math.abs(p - b) && Math.abs(p - a) <= Math.abs(p - c)
          ? a
          : Math.abs(p - b) <= Math.abs(p - c) ? b : c;
      out.write(x
          - switch (filter) {
            case 1 -> a;
            case 2 -> b;
            case 3 -> (a + b) / 2;
            case 4 -> paeth;
            default -> 0;
          });
    }
  }

  @Test
  void refusesAChunkWhoseCrcIsWrong() throws IOException {
    byte[] png = resource("rgba8.png");
    png[20] ^= 1; // inside IHDR
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> Png.decode(png));
    assertTrue(e.getMessage().contains("CRC"), e.getMessage());
  }

  @Test
  void refusesAHeaderWithoutPixelsOnOneSideOrWithARowTooLongForAnArray() {
    int[][] sizes = {{Integer.MAX_VALUE, 0}, {0, 5}, {1 << 28, 1}};
    for (int[] size : sizes) {
      java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
      png.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
      chunk(
          png,
          "IHDR",
          java.nio.ByteBuffer.allocate(13)
              .putInt(size[0])
              .putInt(size[1])
              .put(new byte[] {16, 6, 0, 0, 0})
              .array());
      chunk(png, "IEND", new byte[0]);
      assertThrows(IllegalArgumentException.class, () -> Png.decode(png.toByteArray()));
    }
  }

  @Test
  void rejectsWhatIsNotPng() {
    assertThrows(IllegalArgumentException.class, () -> Png.decode(new byte[] {1, 2, 3}));
    assertThrows(IllegalArgumentException.class, () -> Png.decode("not a png at all".getBytes()));
  }
}
