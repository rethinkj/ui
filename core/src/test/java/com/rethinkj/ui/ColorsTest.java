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
}
