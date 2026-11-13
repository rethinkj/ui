package com.rethinkj.ui.render;

import java.util.Objects;

/**
 * Reads JPEG files, because {@code javax.imageio} lives in {@code java.desktop}: baseline and
 * extended sequential and progressive files with Huffman coding, 8 bits per sample, greyscale
 * (one component) and YCbCr or RGB (three), with any whole sampling factors from 1 to 4, among
 * them 4:4:4, 4:2:2, 4:2:0 and 4:4:0, restart markers, and the orientation of an EXIF block,
 * which it applies, so that a photo taken upright comes out upright.
 *
 * <p>It decodes as libjpeg does with its defaults, so that it gives what other programs show: the
 * integer inverse DCT of libjpeg ({@code jidctint.c}), libjpeg's smooth ("fancy") upsampling of
 * chroma halved across, down or both, and its fixed-point conversion from YCbCr to RGB. A file
 * whose data ends early, or whose scan breaks off at a marker, decodes as far as it goes, the rest
 * of its blocks left at their mean grey or at what earlier progressive scans gave them, as
 * libjpeg does.
 *
 * <p>Refused with an {@link IllegalArgumentException} that says why: four components (CMYK and
 * YCCK), arithmetic coding, lossless and hierarchical files, 12 bits per sample, fractional
 * sampling factors, a height that only a DNL marker gives, images of more than {@link
 * #MAX_PIXELS} pixels, and data that is not JPEG where the format leaves no doubt. The memory a
 * file can make it take is bounded by the size its header gives, within that limit: about 3 bytes
 * a pixel for the coefficients of 4:2:0, 6 for 4:4:4, and 4 for the result.
 */
public final class Jpeg {

  /** The most pixels an image may have: 40 million, a photo of 7700 by 5200. */
  public static final long MAX_PIXELS = 40_000_000;

  /** The position in a block, in rows of 8, of each coefficient in the order of the file. */
  private static final int[] ZIGZAG = {0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12,
      19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28, 35, 42, 49, 56, 57, 50, 43, 36, 29,
      22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63};

  private Jpeg() {}

  /**
   * Reads a JPEG file into straight RGBA, every pixel opaque.
   *
   * @throws IllegalArgumentException if the bytes are not a JPEG file this reads, saying why
   */
  public static Pixels decode(byte[] jpeg) {
    return new Decoder(Objects.requireNonNull(jpeg, "jpeg")).decode();
  }

  /** A Huffman table: for each code length, the largest code, and where its values start. */
  private static final class Huffman {
    final int[] maxCode = new int[18];
    final int[] valueOffset = new int[17];
    final int[] values;

    Huffman(int[] counts, int[] values) {
      this.values = values;
      int code = 0;
      int k = 0;
      for (int length = 1; length <= 16; length++) {
        valueOffset[length] = k - code;
        code += counts[length - 1];
        k += counts[length - 1];
        maxCode[length] = counts[length - 1] == 0 ? -1 : code - 1;
        code <<= 1;
      }
      maxCode[17] = Integer.MAX_VALUE;
    }
  }

  /** A component of the frame: its sampling, its tables and its coefficients. */
  private static final class Component {
    int id;
    int h;
    int v;
    int quant;
    /** Samples across and down, rounded up: the component's own size. */
    int width;
    int height;
    /** Blocks across and down that hold its samples, and that the file has room for. */
    int blocksAcross;
    int blocksDown;
    int stride;
    int rows;
    short[] coefficients;
    int dcPrediction;
    Huffman dc;
    Huffman ac;
    /** The samples after the inverse DCT, {@code stride * 8} a row. */
    byte[] samples;
  }

  private static final class Decoder {
    private final byte[] data;
    private int pos;
    private final int[][] quant = new int[4][];
    private final Huffman[] dcTables = new Huffman[4];
    private final Huffman[] acTables = new Huffman[4];
    private int restartInterval;
    private Component[] components;
    private int width;
    private int height;
    private boolean progressive;
    private int hMax;
    private int vMax;
    private int mcusAcross;
    private int mcusDown;
    private int orientation = 1;
    private int adobeTransform = -1;
    private boolean jfif;
    // The entropy-coded data.
    private int bits;
    private int bitCount;
    /** The data ran out or reached a marker while a scan still needed bits. */
    private boolean exhausted;
    private int eobRun;

    Decoder(byte[] data) {
      this.data = data;
    }

    Pixels decode() {
      if (data.length < 4 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
        throw new IllegalArgumentException("Not a JPEG file: it does not start with FF D8.");
      }
      pos = 2;
      boolean scanned = false;
      while (true) {
        int marker = nextMarker();
        if (marker < 0 || marker == 0xD9) { // the end of the data, or EOI
          break;
        }
        if (marker >= 0xD0 && marker <= 0xD7 || marker == 0x01) {
          continue; // a restart marker between scans, or TEM: no segment
        }
        int length = length(marker);
        int end = pos + length - 2;
        switch (marker) {
          case 0xC0, 0xC1, 0xC2 -> frame(marker == 0xC2, end);
          case 0xC3 -> refuse("lossless JPEG");
          case 0xC5, 0xC6, 0xC7 -> refuse("hierarchical JPEG");
          case 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF -> refuse("arithmetic coding");
          case 0xC4 -> huffman(end);
          case 0xCC -> refuse("arithmetic coding");
          case 0xDB -> quantization(end);
          case 0xDD -> restartInterval = length >= 4 ? u16(pos) : 0;
          case 0xDA -> {
            scan(end);
            scanned = true;
            continue; // scan() leaves pos at the marker after the data
          }
          case 0xE0 -> jfif = length >= 7 && ascii(pos, "JFIF\0");
          case 0xE1 -> exif(end);
          case 0xEE -> {
            if (length >= 14 && ascii(pos, "Adobe")) {
              adobeTransform = data[pos + 11] & 0xFF;
            }
          }
          default -> {} // other application data, comments and DNL: skipped
        }
        pos = end;
      }
      if (components == null) {
        throw new IllegalArgumentException("The JPEG file has no frame header (SOF).");
      }
      if (!scanned) {
        throw new IllegalArgumentException("The JPEG file has no image data (SOS).");
      }
      return orient(pixels());
    }

    private static void refuse(String what) {
      throw new IllegalArgumentException("The JPEG file uses " + what + ", which is not"
          + " supported; baseline and progressive files with Huffman coding are.");
    }

    /**
     * The next marker after {@code pos}, with pos after it, skipping what lies between; -1 at the
     * end of the data.
     */
    private int nextMarker() {
      while (pos + 1 < data.length) {
        if ((data[pos] & 0xFF) == 0xFF) {
          int marker = data[pos + 1] & 0xFF;
          if (marker != 0 && marker != 0xFF) {
            pos += 2;
            return marker;
          }
          pos += marker == 0xFF ? 1 : 2; // a fill byte, or a stuffed FF of entropy data
        } else {
          pos++;
        }
      }
      pos = data.length;
      return -1;
    }

    /** The length of the segment at pos, its two bytes included, checked against the data. */
    private int length(int marker) {
      if (pos + 2 > data.length) {
        throw truncated();
      }
      int length = u16(pos);
      if (length < 2 || pos + length > data.length) {
        throw new IllegalArgumentException(String.format("The JPEG segment of marker FF%02X"
            + " says it has %d bytes, but %d are left.", marker, length, data.length - pos));
      }
      pos += 2;
      return length;
    }

    private static IllegalArgumentException truncated() {
      return new IllegalArgumentException("The JPEG file ends in the middle of a header.");
    }

    private int u16(int at) {
      return (data[at] & 0xFF) << 8 | data[at + 1] & 0xFF;
    }

    private boolean ascii(int at, String text) {
      if (at + text.length() > data.length) {
        return false;
      }
      for (int i = 0; i < text.length(); i++) {
        if ((data[at + i] & 0xFF) != text.charAt(i)) {
          return false;
        }
      }
      return true;
    }

    private void frame(boolean progressive, int end) {
      if (components != null) {
        throw new IllegalArgumentException("The JPEG file has more than one frame header.");
      }
      if (end - pos < 6) {
        throw truncated();
      }
      int precision = data[pos] & 0xFF;
      if (precision != 8) {
        throw new IllegalArgumentException("The JPEG file has " + precision + " bits per sample;"
            + " only 8 are supported.");
      }
      height = u16(pos + 1);
      width = u16(pos + 3);
      int count = data[pos + 5] & 0xFF;
      if (width == 0 || height == 0) {
        throw new IllegalArgumentException("The JPEG image is " + width + "x" + height
            + " pixels; a height given by a DNL marker is not supported.");
      }
      if ((long) width * height > MAX_PIXELS) {
        throw new IllegalArgumentException("The JPEG image is " + width + "x" + height
            + " pixels, more than the " + MAX_PIXELS + " that are read.");
      }
      if (count == 4) {
        throw new IllegalArgumentException("The JPEG file has four components, CMYK or YCCK,"
            + " which are not supported; greyscale and colour (YCbCr or RGB) files are.");
      }
      if (count != 1 && count != 3) {
        throw new IllegalArgumentException("The JPEG file has " + count + " components; one"
            + " (greyscale) or three (colour) are supported.");
      }
      if (end - pos < 6 + 3 * count) {
        throw truncated();
      }
      this.progressive = progressive;
      components = new Component[count];
      for (int i = 0; i < count; i++) {
        int at = pos + 6 + 3 * i;
        Component c = new Component();
        c.id = data[at] & 0xFF;
        c.h = (data[at + 1] & 0xFF) >> 4;
        c.v = data[at + 1] & 0x0F;
        c.quant = data[at + 2] & 0xFF;
        if (c.h < 1 || c.h > 4 || c.v < 1 || c.v > 4 || c.quant > 3) {
          throw new IllegalArgumentException("A component of the JPEG file has sampling factors "
              + c.h + "x" + c.v + " or quantization table " + c.quant + "; they must lie from 1"
              + " to 4, and the table from 0 to 3.");
        }
        components[i] = c;
        hMax = Math.max(hMax, c.h);
        vMax = Math.max(vMax, c.v);
      }
      mcusAcross = Math.ceilDiv(width, 8 * hMax);
      mcusDown = Math.ceilDiv(height, 8 * vMax);
      for (Component c : components) {
        if (hMax % c.h != 0 || vMax % c.v != 0) {
          throw new IllegalArgumentException("The JPEG file samples a component at "
              + c.h + "x" + c.v + " of " + hMax + "x" + vMax + "; fractional sampling is not"
              + " supported.");
        }
        c.width = Math.ceilDiv(width * c.h, hMax);
        c.height = Math.ceilDiv(height * c.v, vMax);
        c.blocksAcross = Math.ceilDiv(c.width, 8);
        c.blocksDown = Math.ceilDiv(c.height, 8);
        c.stride = mcusAcross * c.h;
        c.rows = mcusDown * c.v;
        c.coefficients = new short[c.stride * c.rows * 64];
      }
    }

    private void quantization(int end) {
      while (pos < end) {
        int precision = (data[pos] & 0xFF) >> 4;
        int id = data[pos] & 0x0F;
        int size = precision == 0 ? 64 : 128;
        if (id > 3 || precision > 1 || pos + 1 + size > end) {
          throw new IllegalArgumentException("A quantization table of the JPEG file is"
              + " malformed.");
        }
        int[] table = new int[64];
        for (int i = 0; i < 64; i++) {
          table[ZIGZAG[i]] = precision == 0 ? data[pos + 1 + i] & 0xFF : u16(pos + 1 + 2 * i);
        }
        quant[id] = table;
        pos += 1 + size;
      }
    }

    private void huffman(int end) {
      while (pos < end) {
        if (pos + 17 > end) {
          throw new IllegalArgumentException("A Huffman table of the JPEG file is malformed.");
        }
        int kind = (data[pos] & 0xFF) >> 4;
        int id = data[pos] & 0x0F;
        int[] counts = new int[16];
        int total = 0;
        for (int i = 0; i < 16; i++) {
          counts[i] = data[pos + 1 + i] & 0xFF;
          total += counts[i];
        }
        if (kind > 1 || id > 3 || total > 256 || pos + 17 + total > end) {
          throw new IllegalArgumentException("A Huffman table of the JPEG file is malformed.");
        }
        int[] values = new int[total];
        for (int i = 0; i < total; i++) {
          values[i] = data[pos + 17 + i] & 0xFF;
        }
        (kind == 0 ? dcTables : acTables)[id] = new Huffman(counts, values);
        pos += 17 + total;
      }
    }

    /** The orientation of an EXIF block: tag 0x0112 of the first image file directory. */
    private void exif(int end) {
      if (!ascii(pos, "Exif\0\0")) {
        return;
      }
      int tiff = pos + 6;
      if (tiff + 8 > end) {
        return;
      }
      boolean little = data[tiff] == 'I' && data[tiff + 1] == 'I';
      if (!little && !(data[tiff] == 'M' && data[tiff + 1] == 'M')) {
        return;
      }
      long directory = tiff + exifInt(tiff + 4, little);
      if (directory < tiff || directory + 2 > end) {
        return;
      }
      int entries = exifShort((int) directory, little);
      for (int i = 0; i < entries; i++) {
        long at = directory + 2 + 12L * i;
        if (at + 12 > end) {
          return;
        }
        if (exifShort((int) at, little) == 0x0112 && exifShort((int) at + 2, little) == 3) {
          int value = exifShort((int) at + 8, little);
          orientation = value >= 1 && value <= 8 ? value : 1;
          return;
        }
      }
    }

    private int exifShort(int at, boolean little) {
      int a = data[at] & 0xFF;
      int b = data[at + 1] & 0xFF;
      return little ? b << 8 | a : a << 8 | b;
    }

    private long exifInt(int at, boolean little) {
      long high = exifShort(at + (little ? 2 : 0), little);
      long low = exifShort(at + (little ? 0 : 2), little);
      return high << 16 | low;
    }

    /** A scan: its header, then its entropy-coded data, after which pos is at the next marker. */
    private void scan(int end) {
      if (components == null) {
        throw new IllegalArgumentException("The JPEG file has image data (SOS) before its frame"
            + " header (SOF).");
      }
      int count = pos < end ? data[pos] & 0xFF : 0;
      if (count < 1 || count > 4 || pos + 1 + 2 * count + 3 > end) {
        throw new IllegalArgumentException("A scan header of the JPEG file is malformed.");
      }
      Component[] scanned = new Component[count];
      for (int i = 0; i < count; i++) {
        int id = data[pos + 1 + 2 * i] & 0xFF;
        int tables = data[pos + 2 + 2 * i] & 0xFF;
        for (Component c : components) {
          if (c.id == id) {
            scanned[i] = c;
          }
        }
        if (scanned[i] == null) {
          throw new IllegalArgumentException("A scan of the JPEG file names component " + id
              + ", which the frame does not have.");
        }
        scanned[i].dc = dcTables[(tables >> 4) & 3];
        scanned[i].ac = acTables[tables & 3];
      }
      int at = pos + 1 + 2 * count;
      int start = data[at] & 0xFF;
      int stop = data[at + 1] & 0xFF;
      int high = (data[at + 2] & 0xFF) >> 4;
      int low = data[at + 2] & 0x0F;
      if (!progressive) {
        start = 0;
        stop = 63;
        high = 0;
        low = 0;
      }
      if (start > stop || stop > 63 || low > 13 || start == 0 && stop != 0 && progressive
          || start > 0 && count != 1) {
        throw new IllegalArgumentException("A scan of the JPEG file has the spectral selection "
            + start + " to " + stop + " and approximation " + high + "/" + low + ", which the"
            + " format does not allow.");
      }
      for (Component c : scanned) {
        boolean needsDc = start == 0 && high == 0;
        boolean needsAc = stop > 0;
        if (needsDc && c.dc == null || needsAc && c.ac == null) {
          throw new IllegalArgumentException("A scan of the JPEG file uses a Huffman table it"
              + " has not defined.");
        }
      }
      pos = end;
      entropy(scanned, start, stop, high, low);
      // The scan's data ends at a marker: one that is not a restart.
      while (pos + 1 < data.length) {
        int b = data[pos] & 0xFF;
        int next = data[pos + 1] & 0xFF;
        if (b == 0xFF && next != 0 && next != 0xFF && (next < 0xD0 || next > 0xD7)) {
          break;
        }
        pos++;
      }
    }

    /** Decodes the entropy-coded data of a scan into the coefficients of its components. */
    private void entropy(Component[] scanned, int start, int stop, int high, int low) {
      bits = 0;
      bitCount = 0;
      exhausted = false;
      eobRun = 0;
      for (Component c : scanned) {
        c.dcPrediction = 0;
      }
      boolean single = scanned.length == 1;
      Component only = scanned[0];
      int across = single ? only.blocksAcross : mcusAcross;
      int down = single ? only.blocksDown : mcusDown;
      long total = (long) across * down;
      int untilRestart = restartInterval;
      for (long mcu = 0; mcu < total; mcu++) {
        if (restartInterval > 0 && untilRestart == 0) {
          restart(scanned);
          untilRestart = restartInterval;
        }
        untilRestart--;
        if (exhausted) {
          continue; // as libjpeg: the blocks after the data ran out stay as they are
        }
        int row = (int) (mcu / across);
        int column = (int) (mcu % across);
        if (single) {
          block(only, row, column, start, stop, high, low);
          continue;
        }
        for (Component c : scanned) {
          for (int y = 0; y < c.v; y++) {
            for (int x = 0; x < c.h; x++) {
              block(c, row * c.v + y, column * c.h + x, start, stop, high, low);
            }
          }
        }
      }
    }

    /** A restart marker: the bits left are dropped, the predictions and the run reset. */
    private void restart(Component[] scanned) {
      bits = 0;
      bitCount = 0;
      eobRun = 0;
      for (Component c : scanned) {
        c.dcPrediction = 0;
      }
      int at = pos;
      while (at + 1 < data.length) {
        if ((data[at] & 0xFF) == 0xFF) {
          int next = data[at + 1] & 0xFF;
          if (next >= 0xD0 && next <= 0xD7) {
            pos = at + 2;
            exhausted = false;
            return;
          }
          if (next != 0 && next != 0xFF) {
            return; // another marker: the scan ends here, and stays exhausted
          }
        }
        at++;
      }
    }

    private void block(Component c, int row, int column, int start, int stop, int high,
        int low) {
      int offset = (row * c.stride + column) * 64;
      short[] coefficients = c.coefficients;
      if (start == 0) {
        if (high == 0) {
          int size = decode(c.dc);
          if (size > 16) {
            if (exhausted) {
              return;
            }
            throw new IllegalArgumentException("The JPEG file's data is corrupt: a DC difference"
                + " of " + size + " bits.");
          }
          c.dcPrediction += receive(size);
          coefficients[offset] = (short) (c.dcPrediction * (1 << low));
        } else if (bit() != 0) {
          coefficients[offset] |= (short) (1 << low);
        }
        if (stop == 0) {
          return;
        }
        start = 1;
      }
      if (!progressive) {
        sequentialAc(c, coefficients, offset);
      } else if (high == 0) {
        firstAc(c, coefficients, offset, start, stop, low);
      } else {
        refineAc(c, coefficients, offset, start, stop, low);
      }
    }

    private void sequentialAc(Component c, short[] coefficients, int offset) {
      for (int k = 1; k < 64; ) {
        int rs = decode(c.ac);
        int run = rs >> 4;
        int size = rs & 15;
        if (size == 0) {
          if (run != 15) {
            return;
          }
          k += 16;
          continue;
        }
        k += run;
        if (k > 63) {
          return; // corrupt: a run past the block's end
        }
        coefficients[offset + ZIGZAG[k]] = (short) receive(size);
        k++;
      }
    }

    private void firstAc(Component c, short[] coefficients, int offset, int start, int stop,
        int low) {
      if (eobRun > 0) {
        eobRun--;
        return;
      }
      for (int k = start; k <= stop; ) {
        int rs = decode(c.ac);
        int run = rs >> 4;
        int size = rs & 15;
        if (size == 0) {
          if (run < 15) {
            eobRun = (1 << run) - 1 + (run > 0 ? bits(run) : 0);
            return;
          }
          k += 16;
          continue;
        }
        k += run;
        if (k > 63) {
          return;
        }
        coefficients[offset + ZIGZAG[k]] = (short) (receive(size) * (1 << low));
        k++;
      }
    }

    /** A refining scan of AC coefficients, as libjpeg's {@code decode_mcu_AC_refine}. */
    private void refineAc(Component c, short[] coefficients, int offset, int start, int stop,
        int low) {
      int plus = 1 << low;
      int minus = -1 << low;
      int k = start;
      if (eobRun == 0) {
        for (; k <= stop; k++) {
          int rs = decode(c.ac);
          int run = rs >> 4;
          int size = rs & 15;
          int value = 0;
          if (size != 0) {
            value = bit() != 0 ? plus : minus;
          } else if (run != 15) {
            eobRun = (1 << run) + (run > 0 ? bits(run) : 0);
            break;
          }
          // Over coefficients already nonzero, correcting them, and run zero ones.
          do {
            int at = offset + ZIGZAG[k];
            if (coefficients[at] != 0) {
              correct(coefficients, at, plus, minus);
            } else if (--run < 0) {
              break;
            }
            k++;
          } while (k <= stop);
          if (value != 0 && k <= stop) {
            coefficients[offset + ZIGZAG[k]] = (short) value;
          }
        }
      }
      if (eobRun > 0) {
        for (; k <= stop; k++) {
          int at = offset + ZIGZAG[k];
          if (coefficients[at] != 0) {
            correct(coefficients, at, plus, minus);
          }
        }
        eobRun--;
      }
    }

    private void correct(short[] coefficients, int at, int plus, int minus) {
      if (bit() != 0 && (coefficients[at] & plus) == 0) {
        coefficients[at] += (short) (coefficients[at] >= 0 ? plus : minus);
      }
    }

    /** The value of the next Huffman code; 0 once the data ran out. */
    private int decode(Huffman table) {
      int code = 0;
      for (int length = 1; length <= 16; length++) {
        code = code << 1 | bit();
        if (code <= table.maxCode[length]) {
          int index = table.valueOffset[length] + code;
          if (index >= 0 && index < table.values.length) {
            return table.values[index];
          }
          break;
        }
      }
      if (exhausted) {
        return 0;
      }
      throw new IllegalArgumentException("The JPEG file's data is corrupt: a Huffman code that"
          + " its table does not have.");
    }

    /** {@code size} bits as a signed value, as the format extends them. */
    private int receive(int size) {
      if (size == 0) {
        return 0;
      }
      int value = bits(size);
      return value < 1 << (size - 1) ? value - (1 << size) + 1 : value;
    }

    private int bits(int count) {
      int value = 0;
      for (int i = 0; i < count; i++) {
        value = value << 1 | bit();
      }
      return value;
    }

    private int bit() {
      if (bitCount == 0) {
        bits = nextByte();
        bitCount = 8;
      }
      bitCount--;
      return bits >> bitCount & 1;
    }

    /** The next byte of entropy-coded data, a stuffed FF as FF; 0 at a marker or the end. */
    private int nextByte() {
      if (pos >= data.length) {
        exhausted = true;
        return 0;
      }
      int b = data[pos] & 0xFF;
      if (b == 0xFF) {
        if (pos + 1 < data.length && data[pos + 1] == 0) {
          pos += 2;
          return 0xFF;
        }
        exhausted = true; // a marker: the scan's data ends here
        return 0;
      }
      pos++;
      return b;
    }

    // ---- from coefficients to pixels --------------------------------------------------------

    private Pixels pixels() {
      for (Component c : components) {
        int[] table = quant[c.quant];
        if (table == null) {
          throw new IllegalArgumentException("The JPEG file uses quantization table " + c.quant
              + ", which it does not define.");
        }
        c.samples = new byte[c.stride * 8 * c.rows * 8];
        int[] block = new int[64];
        int[] work = new int[64];
        for (int row = 0; row < c.blocksDown; row++) {
          for (int column = 0; column < c.blocksAcross; column++) {
            int offset = (row * c.stride + column) * 64;
            for (int i = 0; i < 64; i++) {
              block[i] = c.coefficients[offset + i] * table[i];
            }
            idct(block, work, c.samples, row * 8 * c.stride * 8 + column * 8, c.stride * 8);
          }
        }
        c.coefficients = null;
      }
      byte[][] planes = new byte[components.length][];
      int[] strides = new int[components.length];
      for (int i = 0; i < components.length; i++) {
        Component c = components[i];
        planes[i] = upsample(c);
        strides[i] = c.width * (hMax / c.h);
      }
      byte[] rgba = new byte[width * height * 4];
      boolean rgb = components.length == 3 && (adobeTransform == 0 || !jfif
          && adobeTransform < 0 && components[0].id == 'R' && components[1].id == 'G'
          && components[2].id == 'B');
      for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
          int at = (y * width + x) * 4;
          int luma = planes[0][y * strides[0] + x] & 0xFF;
          if (components.length == 1) {
            rgba[at] = (byte) luma;
            rgba[at + 1] = (byte) luma;
            rgba[at + 2] = (byte) luma;
          } else {
            int cb = planes[1][y * strides[1] + x] & 0xFF;
            int cr = planes[2][y * strides[2] + x] & 0xFF;
            if (rgb) {
              rgba[at] = (byte) luma;
              rgba[at + 1] = (byte) cb;
              rgba[at + 2] = (byte) cr;
            } else {
              rgba[at] = clamp(luma + CR_R[cr]);
              rgba[at + 1] = clamp(luma + (CB_G[cb] + CR_G[cr] >> 16));
              rgba[at + 2] = clamp(luma + CB_B[cb]);
            }
          }
          rgba[at + 3] = (byte) 0xFF;
        }
      }
      return new Pixels(width, height, rgba);
    }

    private static byte clamp(int value) {
      return (byte) Math.clamp(value, 0, 255);
    }

    /**
     * A component's samples at the size of the image's grid: as they are where it is sampled
     * fully, else smoothly upsampled where it is halved across, down or both, as libjpeg's {@code
     * h2v1_fancy_upsample}, {@code h1v2_fancy_upsample} and {@code h2v2_fancy_upsample} do, and
     * repeated for other factors. Its rows are {@code c.width * (hMax / c.h)} samples.
     */
    private byte[] upsample(Component c) {
      int sx = hMax / c.h;
      int sy = vMax / c.v;
      int in = c.stride * 8;
      if (sx == 1 && sy == 1) {
        byte[] out = new byte[c.width * c.height];
        for (int y = 0; y < c.height; y++) {
          System.arraycopy(c.samples, y * in, out, y * c.width, c.width);
        }
        return out;
      }
      int outWidth = c.width * sx;
      int outHeight = c.height * sy;
      byte[] out = new byte[outWidth * outHeight];
      byte[] s = c.samples;
      if (sx == 2 && sy == 1) {
        for (int y = 0; y < c.height; y++) {
          h2v1(s, y * in, c.width, out, y * outWidth);
        }
      } else if (sx == 1 && sy == 2) {
        for (int y = 0; y < c.height; y++) {
          int above = Math.max(y - 1, 0) * in;
          int below = Math.min(y + 1, c.height - 1) * in;
          for (int x = 0; x < c.width; x++) {
            int near = (s[y * in + x] & 0xFF) * 3;
            out[2 * y * outWidth + x] = (byte) (near + (s[above + x] & 0xFF) + 1 >> 2);
            out[(2 * y + 1) * outWidth + x] = (byte) (near + (s[below + x] & 0xFF) + 2 >> 2);
          }
        }
      } else if (sx == 2 && sy == 2) {
        int[] sums = new int[c.width + 1];
        for (int y = 0; y < c.height; y++) {
          for (int half = 0; half < 2; half++) {
            int far = (half == 0 ? Math.max(y - 1, 0) : Math.min(y + 1, c.height - 1)) * in;
            // Read one column beyond the component, as libjpeg does: it lies in the block.
            for (int x = 0; x <= c.width && x < in; x++) {
              sums[x] = (s[y * in + x] & 0xFF) * 3 + (s[far + x] & 0xFF);
            }
            h2v2(sums, c.width, out, (2 * y + half) * outWidth);
          }
        }
      } else {
        for (int y = 0; y < outHeight; y++) {
          for (int x = 0; x < outWidth; x++) {
            out[y * outWidth + x] = s[y / sy * in + x / sx];
          }
        }
      }
      return out;
    }

    /** One row halved across: three quarters of the nearer sample, one of the farther. */
    private static void h2v1(byte[] s, int from, int width, byte[] out, int to) {
      int value = s[from] & 0xFF;
      out[to] = (byte) value;
      out[to + 1] = (byte) (value * 3 + (s[from + 1] & 0xFF) + 2 >> 2);
      for (int x = 1; x < width - 1; x++) {
        value = (s[from + x] & 0xFF) * 3;
        out[to + 2 * x] = (byte) (value + (s[from + x - 1] & 0xFF) + 1 >> 2);
        out[to + 2 * x + 1] = (byte) (value + (s[from + x + 1] & 0xFF) + 2 >> 2);
      }
      if (width > 1) {
        value = s[from + width - 1] & 0xFF;
        out[to + 2 * width - 2] = (byte) (value * 3 + (s[from + width - 2] & 0xFF) + 1 >> 2);
        out[to + 2 * width - 1] = (byte) value;
      }
    }

    /** One row of an output pair from the sums of three quarters of the nearer row. */
    private static void h2v2(int[] sums, int width, byte[] out, int to) {
      int current = sums[0];
      int next = sums[1];
      out[to] = (byte) (current * 4 + 8 >> 4);
      out[to + 1] = (byte) (current * 3 + next + 7 >> 4);
      int last = current;
      current = next;
      for (int x = 1; x < width - 1; x++) {
        next = sums[x + 1];
        out[to + 2 * x] = (byte) (current * 3 + last + 8 >> 4);
        out[to + 2 * x + 1] = (byte) (current * 3 + next + 7 >> 4);
        last = current;
        current = next;
      }
      if (width > 1) {
        out[to + 2 * width - 2] = (byte) (current * 3 + last + 8 >> 4);
        out[to + 2 * width - 1] = (byte) (current * 4 + 7 >> 4);
      }
    }

    // libjpeg's fixed-point YCbCr to RGB (jdcolor.c), 16 fraction bits.
    private static final int[] CR_R = new int[256];
    private static final int[] CB_B = new int[256];
    private static final int[] CR_G = new int[256];
    private static final int[] CB_G = new int[256];

    static {
      for (int i = 0; i < 256; i++) {
        int x = i - 128;
        CR_R[i] = fix(1.40200) * x + (1 << 15) >> 16;
        CB_B[i] = fix(1.77200) * x + (1 << 15) >> 16;
        CR_G[i] = -fix(0.71414) * x;
        CB_G[i] = -fix(0.34414) * x + (1 << 15);
      }
    }

    private static int fix(double x) {
      return (int) (x * 65536 + 0.5);
    }

    // libjpeg's integer inverse DCT (jidctint.c, jpeg_idct_islow).
    private static final int CONST_BITS = 13;
    private static final int PASS1_BITS = 2;
    private static final int FIX_0_298631336 = 2446;
    private static final int FIX_0_390180644 = 3196;
    private static final int FIX_0_541196100 = 4433;
    private static final int FIX_0_765366865 = 6270;
    private static final int FIX_0_899976223 = 7373;
    private static final int FIX_1_175875602 = 9633;
    private static final int FIX_1_501321110 = 12299;
    private static final int FIX_1_847759065 = 15137;
    private static final int FIX_1_961570560 = 16069;
    private static final int FIX_2_053119869 = 16819;
    private static final int FIX_2_562915447 = 20995;
    private static final int FIX_3_072711026 = 25172;

    /** A dequantized block, in rows of 8, into 8 by 8 samples at {@code to}, rows apart. */
    private static void idct(int[] in, int[] work, byte[] out, int to, int stride) {
      for (int column = 0; column < 8; column++) {
        if (in[8 + column] == 0 && in[16 + column] == 0 && in[24 + column] == 0
            && in[32 + column] == 0 && in[40 + column] == 0 && in[48 + column] == 0
            && in[56 + column] == 0) {
          int dc = in[column] << PASS1_BITS;
          for (int row = 0; row < 8; row++) {
            work[row * 8 + column] = dc;
          }
          continue;
        }
        int z2 = in[16 + column];
        int z3 = in[48 + column];
        int z1 = (z2 + z3) * FIX_0_541196100;
        int tmp2 = z1 + z3 * -FIX_1_847759065;
        int tmp3 = z1 + z2 * FIX_0_765366865;
        z2 = in[column];
        z3 = in[32 + column];
        int tmp0 = (z2 + z3) << CONST_BITS;
        int tmp1 = (z2 - z3) << CONST_BITS;
        int tmp10 = tmp0 + tmp3;
        int tmp13 = tmp0 - tmp3;
        int tmp11 = tmp1 + tmp2;
        int tmp12 = tmp1 - tmp2;
        tmp0 = in[56 + column];
        tmp1 = in[40 + column];
        tmp2 = in[24 + column];
        tmp3 = in[8 + column];
        int[] odd = odd(tmp0, tmp1, tmp2, tmp3);
        int shift = CONST_BITS - PASS1_BITS;
        work[column] = descale(tmp10 + odd[3], shift);
        work[56 + column] = descale(tmp10 - odd[3], shift);
        work[8 + column] = descale(tmp11 + odd[2], shift);
        work[48 + column] = descale(tmp11 - odd[2], shift);
        work[16 + column] = descale(tmp12 + odd[1], shift);
        work[40 + column] = descale(tmp12 - odd[1], shift);
        work[24 + column] = descale(tmp13 + odd[0], shift);
        work[32 + column] = descale(tmp13 - odd[0], shift);
      }
      for (int row = 0; row < 8; row++) {
        int w = row * 8;
        int z2 = work[w + 2];
        int z3 = work[w + 6];
        int z1 = (z2 + z3) * FIX_0_541196100;
        int tmp2 = z1 + z3 * -FIX_1_847759065;
        int tmp3 = z1 + z2 * FIX_0_765366865;
        int tmp0 = (work[w] + work[w + 4]) << CONST_BITS;
        int tmp1 = (work[w] - work[w + 4]) << CONST_BITS;
        int tmp10 = tmp0 + tmp3;
        int tmp13 = tmp0 - tmp3;
        int tmp11 = tmp1 + tmp2;
        int tmp12 = tmp1 - tmp2;
        int[] odd = odd(work[w + 7], work[w + 5], work[w + 3], work[w + 1]);
        int shift = CONST_BITS + PASS1_BITS + 3;
        int o = to + row * stride;
        out[o] = sample(tmp10 + odd[3], shift);
        out[o + 7] = sample(tmp10 - odd[3], shift);
        out[o + 1] = sample(tmp11 + odd[2], shift);
        out[o + 6] = sample(tmp11 - odd[2], shift);
        out[o + 2] = sample(tmp12 + odd[1], shift);
        out[o + 5] = sample(tmp12 - odd[1], shift);
        out[o + 3] = sample(tmp13 + odd[0], shift);
        out[o + 4] = sample(tmp13 - odd[0], shift);
      }
    }

    /** The odd part of a pass, from the inputs 7, 5, 3 and 1: tmp0 to tmp3 of libjpeg. */
    private static int[] odd(int tmp0, int tmp1, int tmp2, int tmp3) {
      int z1 = tmp0 + tmp3;
      int z2 = tmp1 + tmp2;
      int z3 = tmp0 + tmp2;
      int z4 = tmp1 + tmp3;
      int z5 = (z3 + z4) * FIX_1_175875602;
      tmp0 *= FIX_0_298631336;
      tmp1 *= FIX_2_053119869;
      tmp2 *= FIX_3_072711026;
      tmp3 *= FIX_1_501321110;
      z1 *= -FIX_0_899976223;
      z2 *= -FIX_2_562915447;
      z3 *= -FIX_1_961570560;
      z4 *= -FIX_0_390180644;
      z3 += z5;
      z4 += z5;
      return new int[] {tmp0 + z1 + z3, tmp1 + z2 + z4, tmp2 + z2 + z3, tmp3 + z1 + z4};
    }

    private static int descale(int x, int n) {
      return x + (1 << (n - 1)) >> n;
    }

    private static byte sample(int x, int n) {
      return (byte) Math.clamp(descale(x, n) + 128, 0, 255);
    }

    // ---- orientation ------------------------------------------------------------------------

    /** The pixels turned and mirrored as the EXIF orientation says, upright. */
    private Pixels orient(Pixels in) {
      if (orientation == 1) {
        return in;
      }
      int w = in.width();
      int h = in.height();
      boolean swap = orientation >= 5;
      int outWidth = swap ? h : w;
      int outHeight = swap ? w : h;
      int[] pixels = new int[w * h];
      java.nio.ByteBuffer.wrap(in.rgba()).asIntBuffer().get(pixels);
      int[] turned = new int[w * h];
      for (int y = 0; y < outHeight; y++) {
        for (int x = 0; x < outWidth; x++) {
          int sx;
          int sy;
          switch (orientation) {
            case 2 -> { sx = w - 1 - x; sy = y; }
            case 3 -> { sx = w - 1 - x; sy = h - 1 - y; }
            case 4 -> { sx = x; sy = h - 1 - y; }
            case 5 -> { sx = y; sy = x; }
            case 6 -> { sx = y; sy = h - 1 - x; }
            case 7 -> { sx = w - 1 - y; sy = h - 1 - x; }
            default -> { sx = w - 1 - y; sy = x; } // 8
          }
          turned[y * outWidth + x] = pixels[sy * w + sx];
        }
      }
      byte[] rgba = new byte[w * h * 4];
      java.nio.ByteBuffer.wrap(rgba).asIntBuffer().put(turned);
      return new Pixels(outWidth, outHeight, rgba);
    }
  }
}
