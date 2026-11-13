package com.rethinkj.ui.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Wrapping, gaps, {@code align-content} and auto margins against the rectangles a browser
 * (headless Chrome) gives for the same boxes, in {@code flex-browser-matrix.tsv}.
 *
 * <p>Every case is a container of 150 by 100 with {@code align-items: flex-start}, a column gap of
 * 8 and a row gap of 7, holding four items of 65 by 20, 75 by 30, 20 by 10 and 90 by 120 that do
 * not shrink. A line of the table has 20 columns, separated by tabs: the {@link Style.Direction}
 * and the {@link Style.Wrap} by their ordinal, the {@code align-content} as the ordinal of a
 * {@link Style.Content}, the auto margins of every item (0 none, 1 top, 2 bottom, 3 top and
 * bottom, 4 left, 5 right, 6 left and right), and then x, y, width and height of each of the four
 * items, as the browser laid them out.
 */
class FlexBrowserMatrixTest {

  private static final int[][] SIZES = {{65, 20}, {75, 30}, {20, 10}, {90, 120}};

  @Test
  void wrappingGapsAlignContentAndAutoMarginsMatchABrowser() throws IOException {
    List<String> cases;
    try (InputStream in = getClass().getResourceAsStream("/flex-browser-matrix.tsv")) {
      cases = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    }
    assertEquals(756, cases.size());
    StringBuilder failures = new StringBuilder();
    int failed = 0;
    for (String line : cases) {
      String[] parts = line.split("\t");
      int direction = Integer.parseInt(parts[0]);
      int wrap = Integer.parseInt(parts[1]);
      int content = Integer.parseInt(parts[2]);
      int margins = Integer.parseInt(parts[3]);
      El root = El.el().size(150, 100);
      root.style().direction = Style.Direction.values()[direction];
      root.style().wrap = Style.Wrap.values()[wrap];
      root.style().alignContent = Style.Content.values()[content];
      root.style().alignItems = Style.Align.FLEX_START;
      root.style().columnGap = Len.px(8);
      root.style().rowGap = Len.px(7);
      for (int[] size : SIZES) {
        El child = El.el().size(size[0], size[1]).shrink(0);
        if (margins == 1 || margins == 3) child.style().marginTop = Len.AUTO;
        if (margins == 2 || margins == 3) child.style().marginBottom = Len.AUTO;
        if (margins == 4 || margins == 6) child.style().marginLeft = Len.AUTO;
        if (margins == 5 || margins == 6) child.style().marginRight = Len.AUTO;
        root.child(child);
      }
      Flex.layout(root, 150, 100, false);
      boolean wrong = false;
      for (int i = 0; i < SIZES.length; i++) {
        El child = root.children().get(i);
        float[] actual = {child.x(), child.y(), child.width(), child.height()};
        for (int k = 0; k < 4; k++) {
          float expected = Float.parseFloat(parts[4 + i * 4 + k]);
          if (Math.abs(actual[k] - expected) > .05f) {
            wrong = true;
            if (failed < 8) {
              failures.append("%s item %d: %s %s, expected %s%n"
                  .formatted(line.substring(0, 7), i, "xywh".charAt(k), actual[k], expected));
            }
          }
        }
      }
      if (wrong) failed++;
    }
    assertEquals(0, failed, failures.toString());
  }
}
