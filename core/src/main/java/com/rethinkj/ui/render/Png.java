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
        ByteBuffer.allocate(13).putInt(width).putInt(height).put(new byte[] {8, 6, 0, 0, 0}).array());
    chunk(out, "IDAT", deflate(raw));
    chunk(out, "IEND", new byte[0]);
    return out.toByteArray();
  }

  // shortcut: reads only what encode writes (8-bit RGBA, filter 0, not interlaced) and does not
  // check CRCs; widen it when goldens start coming from other tools.
  public static Pixels decode(byte[] png) {
    ByteBuffer in = ByteBuffer.wrap(png);
    byte[] signature = new byte[SIGNATURE.length];
    if (png.length < SIGNATURE.length || !java.util.Arrays.equals(read(in, signature), SIGNATURE)) {
      throw new IllegalArgumentException("not a PNG file");
    }
    int width = -1;
    int height = -1;
    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    while (in.remaining() >= 12) {
      int length = in.getInt();
      String type = new String(read(in, new byte[4]), StandardCharsets.US_ASCII);
      if (length < 0 || length > in.remaining() - 4) {
        throw new IllegalArgumentException("truncated PNG chunk " + type);
      }
      byte[] data = read(in, new byte[length]);
      in.getInt(); // CRC
      switch (type) {
        case "IHDR" -> {
          ByteBuffer header = ByteBuffer.wrap(data);
          width = header.getInt();
          height = header.getInt();
          if (header.get() != 8 || header.get() != 6 || header.get(12) != 0) {
            throw new IllegalArgumentException("only 8-bit RGBA PNGs without interlacing are read");
          }
        }
        case "IDAT" -> compressed.writeBytes(data);
        default -> {}
      }
    }
    if (width < 0) {
      throw new IllegalArgumentException("PNG without IHDR chunk");
    }

    int rowBytes = width * 4;
    byte[] raw = inflate(compressed.toByteArray(), (rowBytes + 1) * height);
    byte[] rgba = new byte[rowBytes * height];
    for (int y = 0; y < height; y++) {
      int filter = raw[y * (rowBytes + 1)];
      if (filter != 0) {
        throw new IllegalArgumentException("PNG row filter " + filter + " is not supported");
      }
      System.arraycopy(raw, y * (rowBytes + 1) + 1, rgba, y * rowBytes, rowBytes);
    }
    return new Pixels(width, height, rgba);
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
        throw new IllegalArgumentException("PNG image data is " + read + " bytes, expected " + size);
      }
      return raw;
    } catch (DataFormatException e) {
      throw new IllegalArgumentException("corrupt PNG image data", e);
    } finally {
      inflater.end();
    }
  }
}
