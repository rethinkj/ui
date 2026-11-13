package com.rethinkj.ui.window;

import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Headless;
import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.text.TextRendering;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Icons without a window or native libraries: what each size draws, and Windows' resource. */
class WindowIconTest {

  /** A rounded square with a dot, all on whole pixels at every size; no text. */
  private static El view(int size) {
    return el().wFull().hFull().padding(1).child(el().wFull().hFull().radius(size / 5f)
        .fill(0xFF2E62D9).center().child(el().size(size / 4f, size / 4f).radius(size / 8f)
            .fill(0xFFFFFFFF)));
  }

  @Test
  void aViewIsDrawnAtEachSizeAsHeadlessDrawsIt() {
    List<Integer> asked = new ArrayList<>();
    WindowIcon icon = WindowIcon.of(size -> {
      asked.add(size);
      return view(size);
    });
    for (int size : new int[] {16, 20, 24, 32, 40, 48, 64, 256}) {
      Pixels image = icon.image(size);
      assertEquals(size, image.width());
      assertEquals(size, image.height());
      Pixels expected = Headless.render(view(size), size, size, 1, TextRendering.platform())
          .pixels();
      assertArrayEquals(expected.rgba(), image.rgba(), "size " + size);
    }
    // Transparent outside the square, opaque inside it.
    Pixels image = icon.image(32);
    assertEquals(0, image.argb(0, 0) >>> 24);
    assertEquals(0xFF2E62D9, image.argb(16, 4));
    assertEquals(0xFFFFFFFF, image.argb(16, 16));
    // Each size is drawn once.
    icon.image(32);
    assertEquals(List.of(16, 20, 24, 32, 40, 48, 64, 256), asked);
  }

  @Test
  void imagesGiveTheSmallestThatIsLargeEnoughElseTheLargest() {
    Pixels p16 = square(16);
    Pixels p32 = square(32);
    Pixels p256 = square(256);
    WindowIcon icon = WindowIcon.of(p256, p16, p32);
    assertSame(p16, icon.image(16));
    assertSame(p32, icon.image(20));
    assertSame(p32, icon.image(32));
    assertSame(p256, icon.image(48));
    assertSame(p256, icon.image(512));
  }

  @Test
  void imagesAreSquareAndNotTooLarge() {
    assertThrows(IllegalArgumentException.class, WindowIcon::of);
    assertThrows(IllegalArgumentException.class,
        () -> WindowIcon.of(new Pixels(16, 8, new byte[16 * 8 * 4])));
    assertThrows(IllegalArgumentException.class, () -> WindowIcon.of(square(2048)));
    assertThrows(IllegalArgumentException.class, () -> WindowIcon.of(square(16)).image(0));
  }

  @Test
  void windowsGetsAnIconResourceOfStraightBgraFromTheBottomUpWithAMask() {
    // 33 wide, so that a row of the mask takes two DWORDs; one transparent pixel at the top left.
    int w = 33;
    int h = 2;
    byte[] rgba = new byte[w * h * 4];
    for (int i = 0; i < w * h; i++) {
      rgba[i * 4] = 0x11; // red
      rgba[i * 4 + 1] = 0x22;
      rgba[i * 4 + 2] = 0x33;
      rgba[i * 4 + 3] = (byte) 0x80; // half transparent, not premultiplied
    }
    rgba[3] = 0;
    byte[] resource = Win32Shell.iconResource(new Pixels(w, h, rgba));
    ByteBuffer b = ByteBuffer.wrap(resource).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(40 + w * h * 4 + 8 * h, resource.length);
    assertEquals(40, b.getInt(0));
    assertEquals(w, b.getInt(4));
    assertEquals(2 * h, b.getInt(8)); // colours and mask
    assertEquals(32, b.getShort(14));
    // The bottom row comes first: the image's top left pixel starts the second row.
    int topLeft = 40 + w * 4;
    assertEquals(0x33, resource[topLeft]); // blue
    assertEquals(0x22, resource[topLeft + 1]);
    assertEquals(0x11, resource[topLeft + 2]);
    assertEquals(0, resource[topLeft + 3]);
    assertEquals((byte) 0x80, resource[40 + 3]);
    // The mask: the bottom row clear, the top row's first bit set.
    int mask = 40 + w * h * 4;
    assertEquals(0, b.getInt(mask));
    assertEquals((byte) 0x80, resource[mask + 8]);
  }

  private static Pixels square(int size) {
    return new Pixels(size, size, new byte[size * size * 4]);
  }
}
