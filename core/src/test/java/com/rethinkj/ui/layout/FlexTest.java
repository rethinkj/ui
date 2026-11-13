package com.rethinkj.ui.layout;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
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

  // Taffy's cases with fit-content use min-content and max-content beside it, which Len lacks.
  @Test
  void fitContentAcrossIsNotStretchedButFollowsTheOtherAlignments() {
    El fits = row().padding(0, 4).child(el().size(30, 10)).child(el().size(20, 10));
    fits.style().width = Len.FIT_CONTENT;
    El stretched = row().child(el().size(30, 10));
    El column = col().w(200).children(fits, stretched);
    Flex.layout(column, 300, 300);

    assertEquals(58, fits.width(), "its content and padding, not the column's width");
    assertEquals(0, fits.x(), "at the start, where stretch places what it cannot stretch");
    assertEquals(10, fits.height());
    assertEquals(200, stretched.width(), "auto is stretched as before");

    column.align(Style.Align.CENTER);
    Flex.layout(column, 300, 300);
    assertEquals(58, fits.width());
    assertEquals(71, fits.x(), "centred like any other child");

    column.align(Style.Align.STRETCH);
    fits.alignSelf(Style.Align.END);
    Flex.layout(column, 300, 300);
    assertEquals(142, fits.x());

    El wide = row().child(el().size(300, 10));
    wide.style().width = Len.FIT_CONTENT;
    El narrow = col().w(100).align(Style.Align.START).child(wide);
    narrow.style().overflowX = Style.Overflow.CLIP;
    Flex.layout(narrow, 300, 300);
    assertEquals(300, wide.width(), "content wider than the column is not squeezed: max-content");
  }

  @Test
  void rejectsNaN() {
    assertThrows(IllegalArgumentException.class, () -> Flex.layout(el(), Float.NaN, 100));
  }
}
