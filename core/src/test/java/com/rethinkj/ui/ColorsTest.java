package com.rethinkj.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class ColorsTest {

  @Test
  void writesAndReadsChannels() {
    assertEquals(0xFF3D6FE6, Colors.rgb(0x3D6FE6));
    assertEquals(0xFF3D6FE6, Colors.rgb(0x3D, 0x6F, 0xE6));
    assertEquals(0x663D6FE6, Colors.argb(0x66, 0x3D, 0x6F, 0xE6));
    int c = 0x80C0A0FF;
    assertEquals(0x80, Colors.alpha(c));
    assertEquals(0xC0, Colors.red(c));
    assertEquals(0xA0, Colors.green(c));
    assertEquals(0xFF, Colors.blue(c));
    assertThrows(IllegalArgumentException.class, () -> Colors.rgb(0xFF3D6FE6));
    assertThrows(IllegalArgumentException.class, () -> Colors.rgb(256, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> Colors.argb(-1, 0, 0, 0));
  }

  /** The shares the default themes are written with give the alphas they had as literals. */
  @Test
  void alphaRoundsSharesToTheBytesTheThemesHad() {
    int blue = 0xFF3D6FE6;
    assertEquals(0x663D6FE6, Colors.alpha(blue, 0.4f));
    assertEquals(0x593D6FE6, Colors.alpha(blue, 0.35f));
    assertEquals(0x4D3D6FE6, Colors.alpha(blue, 0.3f));
    assertEquals(0x403D6FE6, Colors.alpha(blue, 0.25f));
    assertEquals(0x733D6FE6, Colors.alpha(blue, 0.45f));
    assertEquals(0x803D6FE6, Colors.alpha(blue, 0.5f));
    assertEquals(0x993D6FE6, Colors.alpha(blue, 0.6f));
    assertEquals(0xCC3D6FE6, Colors.alpha(blue, 0.8f));
    assertEquals(0x003D6FE6, Colors.alpha(blue, 0));
    assertEquals(0xFF3D6FE6, Colors.alpha(0x003D6FE6, 1));
    for (int a = 0; a < 256; a++) {
      assertEquals(a, Colors.alpha(Colors.alpha(blue, a / 255f)), "alpha " + a);
    }
    assertThrows(IllegalArgumentException.class, () -> Colors.alpha(blue, 1.5f));
    assertThrows(IllegalArgumentException.class, () -> Colors.alpha(blue, Float.NaN));
  }

  @Test
  void hexReadsWhatToHexWrites() {
    assertEquals(0xFF3D6FE6, Colors.hex("#3D6FE6"));
    assertEquals(0xFF3D6FE6, Colors.hex("3d6fe6"));
    assertEquals(0x663D6FE6, Colors.hex("#663D6FE6"));
    assertEquals("#3D6FE6", Colors.toHex(0xFF3D6FE6));
    assertEquals("#663D6FE6", Colors.toHex(0x663D6FE6));
    assertEquals("#00000000", Colors.toHex(Colors.TRANSPARENT));
    SplittableRandom random = new SplittableRandom(1);
    for (int i = 0; i < 1000; i++) {
      int c = random.nextInt();
      assertEquals(c, Colors.hex(Colors.toHex(c)));
    }
    for (String wrong : new String[] {"", "#", "#12345", "#1234567", "#GG0000", "red", "#+12345"}) {
      IllegalArgumentException e =
          assertThrows(IllegalArgumentException.class, () -> Colors.hex(wrong), wrong);
      assertTrue(e.getMessage().contains("#RRGGBB"), e.getMessage());
    }
  }

  @Test
  void contrastAndLightnessAreWcagsAndCielabs() {
    assertEquals(21, Colors.contrast(Colors.BLACK, Colors.WHITE), 1e-12);
    assertEquals(1, Colors.contrast(0xFF777777, 0xFF777777), 1e-12);
    assertEquals(
        Colors.contrast(0xFF3D6FE6, Colors.WHITE), Colors.contrast(Colors.WHITE, 0xFF3D6FE6));
    assertEquals(4.55, Colors.contrast(Colors.WHITE, 0xFF3D6FE6), 0.005);
    assertEquals(100, Colors.lightness(Colors.WHITE), 1e-9);
    assertEquals(0, Colors.lightness(Colors.BLACK), 1e-12);
    assertEquals(50, Colors.lightness(0xFF777777), 0.5);
  }

  /** A translucent fill over a colour, and a mix, end at both ends where they should. */
  @Test
  void overAndMix() {
    int a = 0xFF17181C;
    int b = 0xFF86A8F8;
    assertEquals(a, Colors.over(a, Colors.alpha(b, 0)));
    assertEquals(b, Colors.over(a, b));
    assertEquals(a, Colors.mix(a, b, 0));
    assertEquals(b, Colors.mix(a, b, 1));
    for (int s = 0; s <= 255; s++) {
      assertEquals(
          Colors.over(a, Colors.alpha(b, s / 255f)), Colors.mix(a, b, s / 255.0), "share " + s);
    }
    assertEquals(0x80FFFFFF, Colors.mix(0xFF000000, 0x00FFFFFF, 0.5) | 0x00FFFFFF);
  }

  /**
   * Each colour calculation that stood in several places before gives the same bits here: the
   * kit's {@code Theme.over}, the sRGB conversions of {@code Scene}, {@code Image} and {@code
   * CpuRenderer}, and the contrast and lightness of the kit's tests and the examples, which used
   * {@code Math.pow} and {@code Math.cbrt} where this uses {@link StrictMath}.
   */
  @Test
  void theCalculationsThatWereRepeatedGiveTheSameBits() {
    for (int i = 0; i < 256; i++) {
      double c = i / 255.0;
      assertEquals(
          Double.doubleToRawLongBits(oldLinear(c)),
          Double.doubleToRawLongBits(Colors.linear(c)),
          "linear " + i);
      assertEquals(
          Double.doubleToRawLongBits(oldLinear((i - 0.5) / 255.0)),
          Double.doubleToRawLongBits(Colors.linear((i - 0.5) / 255.0)),
          "threshold " + i);
    }
    for (int i = 0; i <= 4096; i++) {
      double l = i / 4096.0;
      assertEquals(oldEncode(l), (int) Math.round(Colors.srgb(l) * 255), "encode " + l);
    }
    SplittableRandom random = new SplittableRandom(7);
    int mathDiffers = 0;
    for (int i = 0; i < 200_000; i++) {
      int base = random.nextInt();
      int overlay = random.nextInt();
      assertEquals(oldOver(base, overlay), Colors.over(base, overlay));
      if (Double.doubleToRawLongBits(oldContrast(base, overlay))
              != Double.doubleToRawLongBits(Colors.contrast(base, overlay))
          || Double.doubleToRawLongBits(oldLightness(base))
              != Double.doubleToRawLongBits(Colors.lightness(base))) {
        mathDiffers++;
      }
      // Math's and StrictMath's powers may differ in the last bit; never by more than that.
      assertEquals(oldContrast(base, overlay), Colors.contrast(base, overlay), 1e-12);
      assertEquals(oldLightness(base), Colors.lightness(base), 1e-10);
    }
    System.out.println("contrast or lightness differed from Math's in the last bits for "
        + mathDiffers + " of 200000 colours");
  }

  // The calculations as they were.

  private static int oldOver(int base, int overlay) {
    double alpha = (overlay >>> 24) / 255.0;
    int argb = 0xFF;
    for (int shift = 16; shift >= 0; shift -= 8) {
      double mixed = oldLinear((overlay >>> shift & 0xFF) / 255.0) * alpha
          + oldLinear((base >>> shift & 0xFF) / 255.0) * (1 - alpha);
      double srgb =
          mixed <= 0.0031308 ? mixed * 12.92 : 1.055 * StrictMath.pow(mixed, 1 / 2.4) - 0.055;
      argb = argb << 8 | (int) Math.round(srgb * 255);
    }
    return argb;
  }

  private static double oldLinear(double c) {
    return c <= 0.04045 ? c / 12.92 : StrictMath.pow((c + 0.055) / 1.055, 2.4);
  }

  private static int oldEncode(double linear) {
    double srgb =
        linear <= 0.0031308 ? linear * 12.92 : 1.055 * StrictMath.pow(linear, 1 / 2.4) - 0.055;
    return (int) Math.round(srgb * 255);
  }

  private static double oldLightness(int argb) {
    double y = oldLuminance(argb);
    return y > 216.0 / 24389 ? 116 * Math.cbrt(y) - 16 : y * 24389 / 27;
  }

  private static double oldContrast(int a, int b) {
    double la = oldLuminance(a);
    double lb = oldLuminance(b);
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
  }

  private static double oldLuminance(int argb) {
    return 0.2126 * channel(argb >> 16) + 0.7152 * channel(argb >> 8) + 0.0722 * channel(argb);
  }

  private static double channel(int value) {
    double c = (value & 0xFF) / 255.0;
    return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  }
}
