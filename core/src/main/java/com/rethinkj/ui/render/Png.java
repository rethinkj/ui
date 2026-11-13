package com.rethinkj.ui.render;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Reads and writes PNG files, because {@code javax.imageio} lives in {@code java.desktop}.
 *
 * <p>Compare images by their pixels, never by their PNG bytes: zlib compresses differently from
 * platform to platform.
 */
public final class Png {

  private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

  private Png() {}

  public static byte[] encode(Pixels pixels) {
    int width = pixels.width();
    int height = pixels.height();
    int rowBytes = width * 4;
    // Each row is prefixed with its filter type; 0 means the bytes are stored as they are.
    byte[] raw = new byte[(rowBytes + 1) * height];
    for (int y = 0; y < height; y++) {
      System.arraycopy(pixels.rgba(), y * rowBytes, raw, y * (rowBytes + 1) + 1, rowBytes);
    }

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(SIGNATURE);
    // 8 bits per channel, colour type 6 (RGBA), default compression and filter, not interlaced.
    chunk(
        out,
        "IHDR",
        ByteBuffer.allocate(13)
            .putInt(width)
            .putInt(height)
            .put(new byte[] {8, 6, 0, 0, 0})
            .array());
    chunk(out, "IDAT", deflate(raw));
    chunk(out, "IEND", new byte[0]);
    return out.toByteArray();
  }

  /**
   * Reads a PNG file into straight RGBA: grey, grey with alpha, RGB, RGBA and palette images, of
   * 1, 2, 4, 8 or 16 bits per sample as the format allows them, with the transparency of a {@code
   * tRNS} chunk, all five row filters and the CRC of every chunk checked. Sixteen bits keep their
   * high byte. Interlaced images are refused, as are images of more than 2^28 pixels.
   *
   * @throws IllegalArgumentException if the bytes are not such a PNG file, saying why
   */
  public static Pixels decode(byte[] png) {
    ByteBuffer in = ByteBuffer.wrap(png);
    byte[] signature = new byte[SIGNATURE.length];
    if (png.length < SIGNATURE.length || !java.util.Arrays.equals(read(in, signature), SIGNATURE)) {
      throw new IllegalArgumentException("not a PNG file");
    }
    int width = -1;
    int height = -1;
    int depth = 0;
    int colour = 0;
    byte[] palette = null;
    byte[] transparency = null;
    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    while (in.remaining() >= 12) {
      int length = in.getInt();
      byte[] name = read(in, new byte[4]);
      String type = new String(name, StandardCharsets.US_ASCII);
      if (length < 0 || length > in.remaining() - 4) {
        throw new IllegalArgumentException("truncated PNG chunk " + type);
      }
      byte[] data = read(in, new byte[length]);
      CRC32 crc = new CRC32();
      crc.update(name);
      crc.update(data);
      if (in.getInt() != (int) crc.getValue()) {
        throw new IllegalArgumentException("corrupt PNG chunk " + type + ": its CRC differs");
      }
      switch (type) {
        case "IHDR" -> {
          if (length != 13) {
            throw new IllegalArgumentException("PNG header of " + length + " bytes, not 13");
          }
          ByteBuffer header = ByteBuffer.wrap(data);
          width = header.getInt();
          height = header.getInt();
          depth = header.get();
          colour = header.get();
          if (header.get() != 0 || header.get() != 0) {
            throw new IllegalArgumentException("unknown PNG compression or filter method");
          }
          if (header.get() != 0) {
            throw new IllegalArgumentException(
                "interlaced PNGs are not read; save the image" + " without interlacing (Adam7)");
          }
          if ((width == 0) != (height == 0)) {
            throw new IllegalArgumentException("PNG of " + Integer.toUnsignedString(width) + " by "
                + Integer.toUnsignedString(height) + " pixels has no pixels but is not empty");
          }
          if (width < 0 || height < 0 || (long) width * height > 1 << 28) {
            throw new IllegalArgumentException("PNG of " + Integer.toUnsignedString(width) + " by "
                + Integer.toUnsignedString(height) + " pixels is too large");
          }
          boolean known = switch (colour) {
            case 0 -> depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 16;
            case 3 -> depth == 1 || depth == 2 || depth == 4 || depth == 8;
            case 2, 4, 6 -> depth == 8 || depth == 16;
            default -> false;
          };
          if (!known) {
            throw new IllegalArgumentException(
                "PNG colour type " + colour + " with " + depth + " bits per sample is not valid");
          }
        }
        case "PLTE" -> palette = data;
        case "tRNS" -> transparency = data;
        case "IDAT" -> compressed.writeBytes(data);
        case "IEND" -> in.position(in.limit());
        default -> {
          if ((name[0] & 0x20) == 0) { // a critical chunk that a reader must understand
            throw new IllegalArgumentException("unknown critical PNG chunk " + type);
          }
        }
      }
    }
    if (width < 0) {
      throw new IllegalArgumentException("PNG without IHDR chunk");
    }
    if (colour == 3 && palette == null) {
      throw new IllegalArgumentException("PNG palette image without PLTE chunk");
    }
    int channels = switch (colour) {
      case 2 -> 3;
      case 4 -> 2;
      case 6 -> 4;
      default -> 1;
    };
    int bits = channels * depth;
    int bytesPerPixel = Math.max(1, bits / 8); // the distance a filter looks back
    long rowLength = ((long) width * bits + 7) / 8;
    if ((rowLength + 1) * height > Integer.MAX_VALUE - 8) {
      throw new IllegalArgumentException(
          "PNG of " + width + " by " + height + " pixels of " + bits + " bits is too large");
    }
    int rowBytes = (int) rowLength;
    byte[] raw = inflate(compressed.toByteArray(), (int) ((rowLength + 1) * height));
    byte[] rgba = new byte[width * height * 4];
    byte[] previous = new byte[rowBytes];
    byte[] row = new byte[rowBytes];
    for (int y = 0; y < height; y++) {
      int at = y * (rowBytes + 1);
      unfilter(raw[at], raw, at + 1, row, previous, bytesPerPixel);
      for (int x = 0; x < width; x++) {
        pixel(row, x, depth, colour, palette, transparency, rgba, (y * width + x) * 4);
      }
      byte[] swap = previous;
      previous = row;
      row = swap;
    }
    return new Pixels(width, height, rgba);
  }

  /** Undoes a row's filter into {@code row}, from the filtered bytes at {@code from}. */
  private static void unfilter(
      int filter, byte[] raw, int from, byte[] row, byte[] previous, int bpp) {
    for (int i = 0; i < row.length; i++) {
      int x = raw[from + i] & 0xFF;
      int a = i >= bpp ? row[i - bpp] & 0xFF : 0;
      int b = previous[i] & 0xFF;
      int c = i >= bpp ? previous[i - bpp] & 0xFF : 0;
      row[i] = (byte)
          switch (filter) {
            case 0 -> x;
            case 1 -> x + a;
            case 2 -> x + b;
            case 3 -> x + (a + b) / 2;
            case 4 -> {
              int p = a + b - c;
              int pa = Math.abs(p - a);
              int pb = Math.abs(p - b);
              int pc = Math.abs(p - c);
              yield x + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
            }
            default ->
              throw new IllegalArgumentException("PNG row filter " + filter + " is unknown");
          };
    }
  }

  /** The sample with this index of a row of samples of {@code depth} bits, at full depth. */
  private static int sample(byte[] row, int index, int depth) {
    return switch (depth) {
      case 16 -> (row[index * 2] & 0xFF) << 8 | row[index * 2 + 1] & 0xFF;
      case 8 -> row[index] & 0xFF;
      default -> {
        int bit = index * depth;
        yield (row[bit >> 3] & 0xFF) >> (8 - depth - (bit & 7)) & (1 << depth) - 1;
      }
    };
  }

  /** A sample in 8 bits: fewer bits spread over 0 to 255, sixteen by their high byte. */
  private static int eight(int sample, int depth) {
    return depth == 16 ? sample >> 8 : sample * 255 / ((1 << depth) - 1);
  }

  /** One pixel of a row as straight RGBA in 8 bits. */
  private static void pixel(
      byte[] row,
      int x,
      int depth,
      int colour,
      byte[] palette,
      byte[] transparency,
      byte[] out,
      int at) {
    switch (colour) {
      case 3 -> {
        int index = sample(row, x, depth);
        if (index * 3 + 2 >= palette.length) {
          throw new IllegalArgumentException("PNG pixel with palette index " + index
              + " beyond the palette's " + palette.length / 3 + " colours");
        }
        out[at] = palette[index * 3];
        out[at + 1] = palette[index * 3 + 1];
        out[at + 2] = palette[index * 3 + 2];
        out[at + 3] =
            transparency != null && index < transparency.length ? transparency[index] : (byte) 0xFF;
      }
      case 0, 4 -> {
        int grey = sample(row, x * (colour == 4 ? 2 : 1), depth);
        byte value = (byte) eight(grey, depth);
        out[at] = value;
        out[at + 1] = value;
        out[at + 2] = value;
        if (colour == 4) {
          out[at + 3] = (byte) eight(sample(row, x * 2 + 1, depth), depth);
        } else {
          boolean keyed = transparency != null
              && transparency.length >= 2
              && grey == ((transparency[0] & 0xFF) << 8 | transparency[1] & 0xFF);
          out[at + 3] = keyed ? 0 : (byte) 0xFF;
        }
      }
      default -> { // 2: RGB, 6: RGBA
        int n = colour == 6 ? 4 : 3;
        int r = sample(row, x * n, depth);
        int g = sample(row, x * n + 1, depth);
        int b = sample(row, x * n + 2, depth);
        out[at] = (byte) eight(r, depth);
        out[at + 1] = (byte) eight(g, depth);
        out[at + 2] = (byte) eight(b, depth);
        if (colour == 6) {
          out[at + 3] = (byte) eight(sample(row, x * n + 3, depth), depth);
        } else {
          boolean keyed = transparency != null
              && transparency.length >= 6
              && r == ((transparency[0] & 0xFF) << 8 | transparency[1] & 0xFF)
              && g == ((transparency[2] & 0xFF) << 8 | transparency[3] & 0xFF)
              && b == ((transparency[4] & 0xFF) << 8 | transparency[5] & 0xFF);
          out[at + 3] = keyed ? 0 : (byte) 0xFF;
        }
      }
    }
  }

  private static byte[] read(ByteBuffer in, byte[] into) {
    in.get(into);
    return into;
  }

  private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
    byte[] name = type.getBytes(StandardCharsets.US_ASCII);
    CRC32 crc = new CRC32();
    crc.update(name);
    crc.update(data);
    out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
    out.writeBytes(name);
    out.writeBytes(data);
    out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
  }

  private static byte[] deflate(byte[] raw) {
    Deflater deflater = new Deflater();
    try {
      deflater.setInput(raw);
      deflater.finish();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      while (!deflater.finished()) {
        out.write(buffer, 0, deflater.deflate(buffer));
      }
      return out.toByteArray();
    } finally {
      deflater.end();
    }
  }

  private static byte[] inflate(byte[] compressed, int size) {
    Inflater inflater = new Inflater();
    try {
      inflater.setInput(compressed);
      byte[] raw = new byte[size];
      int read = 0;
      while (read < size && !inflater.finished()) {
        int n = inflater.inflate(raw, read, size - read);
        if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
          break;
        }
        read += n;
      }
      if (read != size) {
        throw new IllegalArgumentException(
            "PNG image data is " + read + " bytes, expected " + size);
      }
      return raw;
    } catch (DataFormatException e) {
      throw new IllegalArgumentException("corrupt PNG image data", e);
    } finally {
      inflater.end();
    }
  }
}
