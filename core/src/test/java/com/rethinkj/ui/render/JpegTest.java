package com.rethinkj.ui.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.rethinkj.ui.Image;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Reads the JPEG files in {@code src/test/resources/jpeg}, which ImageMagick (with libjpeg-turbo)
 * and Pillow wrote from {@code source.png}, a crop of the kit's gallery, at quality 90: against
 * the PNG that ImageMagick decoded each to, {@code <name>.expected.png}, and against the source,
 * which the compression changes by a few steps. Then EXIF orientations, broken files and what is
 * refused.
 */
class JpegTest {

  private static final Path FILES = Path.of("src/test/resources/jpeg");

  static Stream<String> files() {
    return Stream.of(
        "baseline-444",
        "baseline-422",
        "baseline-420",
        "baseline-440",
        "grey",
        "progressive-444",
        "progressive-420",
        "progressive-grey",
        "restart-420",
        "restart-progressive");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("files")
  void decodesAsLibjpegDoes(String name) throws IOException {
    Pixels decoded = Jpeg.decode(read(name + ".jpg"));
    Pixels expected = Png.decode(read(name + ".expected.png"));
    assertEquals(expected.width(), decoded.width());
    assertEquals(expected.height(), decoded.height());
    int[] worst = difference(expected, decoded);
    System.out.println(name + ": against libjpeg, largest difference " + worst[0] + ", " + worst[1]
        + " channels differ");
    assertEquals(0, worst[0], name + " differs from what libjpeg decodes");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("files")
  void decodesCloseToTheSource(String name) throws IOException {
    Pixels decoded = Jpeg.decode(read(name + ".jpg"));
    Pixels source = Png.decode(read("source.png"));
    boolean grey = name.contains("grey");
    long sum = 0;
    int count = 0;
    for (int y = 0; y < source.height(); y++) {
      for (int x = 0; x < source.width(); x++) {
        int a = source.argb(x, y);
        int b = decoded.argb(x, y);
        if (grey) {
          a = luma(a);
          b = b & 0xFF;
          sum += Math.abs(a - b);
          count++;
          continue;
        }
        for (int shift = 0; shift < 24; shift += 8) {
          sum += Math.abs((a >> shift & 0xFF) - (b >> shift & 0xFF));
          count++;
        }
      }
    }
    double mean = (double) sum / count;
    System.out.printf("%s: against the source, mean difference %.2f%n", name, mean);
    // What compression at quality 90 changes: with chroma at half resolution, the coloured edges
    // of the gallery's buttons most.
    assertTrue(mean < (name.contains("444") || name.contains("grey") ? 3 : 6), name + ": " + mean);
  }

  private static int luma(int argb) {
    return (int) Math.round(
        0.299 * (argb >> 16 & 0xFF) + 0.587 * (argb >> 8 & 0xFF) + 0.114 * (argb & 0xFF));
  }

  @Test
  void anImageDecodesJpegByItsFirstBytes() throws IOException {
    Image image = Image.decode(read("baseline-420.jpg"));
    assertEquals(61, image.width());
    assertEquals(45, image.height());
  }

  @Test
  void anExifOrientationTurnsTheImageUpright() throws IOException {
    byte[] jpeg = read("baseline-422.jpg");
    Pixels upright = Jpeg.decode(jpeg);
    int w = upright.width();
    int h = upright.height();
    for (boolean little : new boolean[] {true, false}) {
      for (int orientation = 1; orientation <= 8; orientation++) {
        Pixels turned = Jpeg.decode(withOrientation(jpeg, orientation, little));
        boolean swap = orientation >= 5;
        assertEquals(swap ? h : w, turned.width(), "orientation " + orientation);
        assertEquals(swap ? w : h, turned.height(), "orientation " + orientation);
        // Where the stored image's corners end up, as EXIF defines the eight orientations.
        int[][] corners = {{0, 0}, {w - 1, 0}, {0, h - 1}, {w - 1, h - 1}};
        for (int[] c : corners) {
          int[] at = displayed(c[0], c[1], w, h, orientation);
          assertEquals(
              upright.argb(c[0], c[1]),
              turned.argb(at[0], at[1]),
              "orientation " + orientation + ", corner " + c[0] + "," + c[1]);
        }
      }
    }
  }

  /** Where the pixel x, y of the stored image is shown with this orientation. */
  private static int[] displayed(int x, int y, int w, int h, int orientation) {
    return switch (orientation) {
      case 1 -> new int[] {x, y};
      case 2 -> new int[] {w - 1 - x, y};
      case 3 -> new int[] {w - 1 - x, h - 1 - y};
      case 4 -> new int[] {x, h - 1 - y};
      case 5 -> new int[] {y, x};
      case 6 -> new int[] {h - 1 - y, x};
      case 7 -> new int[] {h - 1 - y, w - 1 - x};
      default -> new int[] {y, w - 1 - x};
    };
  }

  /** The file with an EXIF block after its start that holds only the orientation. */
  private static byte[] withOrientation(byte[] jpeg, int orientation, boolean little) {
    ByteArrayOutputStream tiff = new ByteArrayOutputStream();
    tiff.writeBytes(
        little
            ? new byte[] {'I', 'I', 42, 0, 8, 0, 0, 0}
            : new byte[] {'M', 'M', 0, 42, 0, 0, 0, 8});
    short16(tiff, 2, little); // two entries: something else first, then the orientation
    entry(tiff, 0x010F, 2, 1, 0, little);
    entry(tiff, 0x0112, 3, 1, orientation, little);
    tiff.writeBytes(new byte[4]); // no further directory
    byte[] body = tiff.toByteArray();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(0xFF);
    out.write(0xD8);
    out.write(0xFF);
    out.write(0xE1);
    int length = 2 + 6 + body.length;
    out.write(length >> 8);
    out.write(length);
    out.writeBytes("Exif\0\0".getBytes());
    out.writeBytes(body);
    out.write(jpeg, 2, jpeg.length - 2);
    return out.toByteArray();
  }

  private static void entry(
      ByteArrayOutputStream out, int tag, int type, int count, int value, boolean little) {
    short16(out, tag, little);
    short16(out, type, little);
    if (little) {
      out.writeBytes(new byte[] {(byte) count, 0, 0, 0});
    } else {
      out.writeBytes(new byte[] {0, 0, 0, (byte) count});
    }
    short16(out, value, little); // a SHORT lies in the first two bytes of the value
    out.writeBytes(new byte[2]);
  }

  private static void short16(ByteArrayOutputStream out, int value, boolean little) {
    if (little) {
      out.write(value);
      out.write(value >> 8);
    } else {
      out.write(value >> 8);
      out.write(value);
    }
  }

  @Test
  void aFileThatEndsEarlyDecodesAsFarAsItGoes() throws IOException {
    byte[] jpeg = read("progressive-420.jpg");
    Pixels whole = Jpeg.decode(jpeg);
    // Without its last bytes, past the last scan's header: the image, its last scan's
    // refinements partly missing.
    Pixels cut = Jpeg.decode(Arrays.copyOf(jpeg, jpeg.length - 40));
    assertEquals(whole.width(), cut.width());
    int[] worst = difference(whole, cut);
    assertTrue(worst[0] < 64, "close to the whole, from the earlier scans: " + worst[0]);
    // Every length: decoded, or refused with a message; nothing else, and never for long.
    for (String name :
        new String[] {"baseline-420", "progressive-420", "restart-progressive", "grey"}) {
      byte[] file = read(name + ".jpg");
      assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
        for (int length = 0; length < file.length; length++) {
          readOrRefuse(Arrays.copyOf(file, length), name + " cut at " + length);
        }
      });
    }
  }

  @Test
  void aBrokenFileIsReadOrRefusedNeverMore() throws IOException {
    Random random = new Random(20261113);
    assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
      for (String name : files().toList()) {
        byte[] file = read(name + ".jpg");
        for (int n = 0; n < 300; n++) {
          byte[] broken = file.clone();
          int changes = 1 + random.nextInt(8);
          for (int i = 0; i < changes; i++) {
            int at = random.nextInt(broken.length);
            broken[at] = switch (random.nextInt(4)) {
              case 0 -> (byte) 0xFF;
              case 1 -> 0;
              default -> (byte) random.nextInt(256);
            };
          }
          readOrRefuse(broken, name + " broken " + n);
        }
      }
    });
  }

  private static void readOrRefuse(byte[] jpeg, String what) {
    try {
      Pixels pixels = Jpeg.decode(jpeg);
      assertTrue(pixels.width() > 0 && pixels.height() > 0, what);
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage() != null && !e.getMessage().isEmpty(), what);
    } catch (RuntimeException | OutOfMemoryError e) {
      fail(what + ": " + e, e);
    }
  }

  @Test
  void refusesWhatItDoesNotReadSayingWhy() throws IOException {
    assertRefused(read("cmyk.jpg"), "CMYK");
    byte[] jpeg = read("baseline-444.jpg");
    int frame = marker(jpeg, 0xC0);
    byte[] arithmetic = jpeg.clone();
    arithmetic[frame + 1] = (byte) 0xC9;
    assertRefused(arithmetic, "arithmetic coding");
    byte[] lossless = jpeg.clone();
    lossless[frame + 1] = (byte) 0xC3;
    assertRefused(lossless, "lossless");
    byte[] twelve = jpeg.clone();
    twelve[frame + 4] = 12;
    assertRefused(twelve, "12 bits");
    byte[] huge = jpeg.clone();
    Arrays.fill(huge, frame + 5, frame + 9, (byte) 0xFF);
    assertRefused(huge, "65535x65535 pixels");
    byte[] noHeight = jpeg.clone();
    noHeight[frame + 5] = 0;
    noHeight[frame + 6] = 0;
    assertRefused(noHeight, "DNL");
    assertRefused(new byte[] {1, 2, 3, 4}, "FF D8");
    assertRefused(Arrays.copyOf(jpeg, frame), "no frame header");
  }

  /** The position of the first marker FF {@code code} in the file. */
  private static int marker(byte[] jpeg, int code) {
    for (int i = 0; i + 1 < jpeg.length; i++) {
      if ((jpeg[i] & 0xFF) == 0xFF && (jpeg[i + 1] & 0xFF) == code) {
        return i;
      }
    }
    throw new AssertionError("no marker FF" + Integer.toHexString(code));
  }

  private static void assertRefused(byte[] jpeg, String saying) {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> Jpeg.decode(jpeg));
    assertTrue(e.getMessage().contains(saying), e.getMessage());
  }

  /** The largest difference of a channel and how many channels differ. */
  private static int[] difference(Pixels a, Pixels b) {
    int worst = 0;
    int differing = 0;
    for (int i = 0; i < a.rgba().length; i++) {
      int d = Math.abs((a.rgba()[i] & 0xFF) - (b.rgba()[i] & 0xFF));
      worst = Math.max(worst, d);
      differing += d > 0 ? 1 : 0;
    }
    return new int[] {worst, differing};
  }

  private static byte[] read(String name) throws IOException {
    return Files.readAllBytes(FILES.resolve(name));
  }
}
