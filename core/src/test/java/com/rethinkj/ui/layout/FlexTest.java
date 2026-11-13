package com.rethinkj.ui.layout;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Measure;
import com.rethinkj.ui.Style;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** What Taffy's test cases do not cover. {@link TaffyFlexTest} checks the layout itself. */
class FlexTest {

  @Test
  void measuresContentWithoutPaddingAndBorder() {
    float[] seen = new float[2];
    El text =
        el().padding(5)
            .border(1, 0xFF000000)
            .measure(
                (knownWidth, knownHeight, availableWidth, availableHeight) -> {
                  seen[0] = availableWidth;
                  seen[1] = knownHeight;
                  return new Measure.Size(40, 10);
                });
    El root = col().w(100).align(Style.Align.START).child(text);
    Flex.layout(root, 300, 300);

    assertEquals(52, text.width());
    assertEquals(22, text.height());
    // The last call is the one for the final box: 52 wide, less 2 * (5 + 1).
    assertEquals(40, seen[0]);
    assertEquals(Float.NaN, seen[1]);
  }

  // Without the cache of measured sizes, every level multiplies the work of the levels below.
  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void layoutOfADeepTreeDoesNotExplode() {
    El leaf = el().size(10, 10);
    El root = leaf;
    for (int depth = 0; depth < 200; depth++) {
      root = (depth % 2 == 0 ? row() : col()).padding(1).child(root).child(el().size(5, 5));
    }
    Flex.layout(root, 1000, 1000);

    assertEquals(10, leaf.width());
    assertEquals(10, leaf.height());
  }

  @Test
  void rejectsNaN() {
    assertThrows(IllegalArgumentException.class, () -> Flex.layout(el(), Float.NaN, 100));
  }
}
