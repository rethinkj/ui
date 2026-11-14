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
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** What Taffy's test cases do not cover. {@link TaffyFlexTest} checks the layout itself. */
class FlexTest {

  @Test
  void measuresContentWithoutPaddingAndBorder() {
    float[] seen = new float[2];
    El text = el().padding(5)
        .border(1, 0xFF000000)
        .measure((knownWidth, knownHeight, availableWidth, availableHeight) -> {
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

  // The cases below follow the flexbox specification where Taffy's cases have none.

  @Test
  void flexFactorsBelowOneShareTheSpaceTheGapsLeave() {
    El first = el().size(20, 10).grow(0.25f);
    El second = el().size(20, 10).grow(0.25f);
    El grown = row().size(100, 10).gap(10).child(first).child(second);
    Flex.layout(grown, 100, 10, false);
    // 50 are free after the gap, half of them are shared: 12.5 each.
    assertEquals(32.5f, first.width());
    assertEquals(32.5f, second.width());

    El third = el().size(80, 10).shrink(0.25f).minSize(0, 0);
    El fourth = el().size(80, 10).shrink(0.25f).minSize(0, 0);
    El shrunk = row().size(100, 10).gap(10).child(third).child(fourth);
    Flex.layout(shrunk, 100, 10, false);
    // 70 too many, half of them are taken: 17.5 each.
    assertEquals(62.5f, third.width());
    assertEquals(62.5f, fourth.width());
  }

  @Test
  void aContainerSizedByItsItemsCountsNoMoreThanAnItemsMaximum() {
    El wide = el().size(200, 10);
    wide.style().maxWidth = Len.px(100);
    El across = row().absolute(0, 0).child(wide);
    Flex.layout(el().size(400, 100).child(across), 400, 100);
    assertEquals(100, wide.width());
    assertEquals(100, across.width());

    El high = el().size(10, 200);
    high.style().maxHeight = Len.px(100);
    El down = col().absolute(0, 0).child(high);
    Flex.layout(el().size(400, 400).child(down), 400, 400);
    assertEquals(100, down.height());
  }

  @Test
  void anItemShrinksNoFurtherThanItsPaddingAndTheOthersTakeTheRest() {
    El padded = el().size(100, 10).padding(0, 10).shrink(2).minSize(0, 0);
    El other = el().size(100, 10).minSize(0, 0);
    Flex.layout(row().size(50, 10).child(padded).child(other), 50, 10);
    assertEquals(20, padded.width());
    assertEquals(30, other.width()); // was 42, past the row's end
  }

  @Test
  void autoMarginsAcrossLeaveAnItemLargerThanItsLineAtTheStart() {
    El both = el().size(10, 80);
    both.style().marginTop = Len.AUTO;
    both.style().marginBottom = Len.AUTO;
    El start = el().size(10, 80);
    start.style().marginTop = Len.AUTO;
    El fits = el().size(10, 20);
    fits.style().marginTop = Len.AUTO;
    fits.style().marginBottom = Len.AUTO;
    Flex.layout(row().size(40, 40).child(both).child(start).child(fits), 40, 40);
    assertEquals(0, both.y());
    assertEquals(0, start.y());
    assertEquals(10, fits.y()); // free space is still shared
  }

  @Test
  void displayNoneIsNotPlacedNorIsAnythingInsideIt() {
    El inside = el().size(5, 5);
    El none = el().size(10, 10).child(inside);
    none.style().display = Style.Display.NONE;
    El shown = el().size(10, 10);
    Flex.layout(row().size(40, 40).child(none).child(shown), 40, 40);
    assertEquals(false, none.isPlaced());
    assertEquals(false, inside.isPlaced());
    assertEquals(true, shown.isPlaced());
    assertEquals(0, shown.x());
  }
  // The aspect ratio and the limits, as Chrome lays them out (CSS Sizing 4, sections 4.2 and 4.4).

  @Test
  void anExplicitHeightIsNotRaisedByTheAspectRatio() {
    El box = el().size(100, 10);
    box.style().aspectRatio = 2;
    Flex.layout(box, 200, 200);

    assertEquals(10, box.height());
  }

  @Test
  void theMaximumHeightClampsTheHeightFromTheAspectRatio() {
    El box = el().w(100);
    box.style().aspectRatio = 2;
    box.style().maxHeight = Len.px(20);
    Flex.layout(box, 200, 200);

    assertEquals(20, box.height());
  }

  @Test
  void theAspectRatioTakesTheWidthAfterItsMaximum() {
    El wide = el().w(100);
    wide.style().aspectRatio = 2;
    wide.style().maxWidth = Len.px(50);
    El high = el().h(100);
    high.style().aspectRatio = 2;
    high.style().maxHeight = Len.px(20);
    El item = el().w(100);
    item.style().aspectRatio = 2;
    item.style().maxWidth = Len.px(50);
    Flex.layout(wide, 200, 200);
    Flex.layout(high, 200, 200);
    Flex.layout(row().size(200, 200).align(Style.Align.START).child(item), 200, 200);

    assertEquals(50, wide.width());
    assertEquals(25, wide.height());
    assertEquals(40, high.width());
    assertEquals(20, high.height());
    assertEquals(50, item.width());
    assertEquals(25, item.height());
  }

  @Test
  void anAbsoluteBoxTakesTheOtherSizeFromItsSetSizeAfterItsLimits() {
    El raised = el().absolute(0, 0).w(80).minSize(100, 0);
    El narrowed = el().absolute(0, 0).w(100);
    narrowed.style().maxWidth = Len.px(50);
    El lowered = el().absolute(0, 0).h(100);
    lowered.style().maxHeight = Len.px(20);
    El heightened = el().absolute(0, 0).h(20);
    heightened.style().minHeight = Len.px(40);
    for (El box : List.of(raised, narrowed, lowered, heightened)) {
      box.style().aspectRatio = 2;
      Flex.layout(el().size(200, 200).child(box), 200, 200);
    }

    assertEquals(100, raised.width());
    assertEquals(50, raised.height());
    assertEquals(50, narrowed.width());
    assertEquals(25, narrowed.height());
    assertEquals(40, lowered.width());
    assertEquals(20, lowered.height());
    assertEquals(80, heightened.width());
    assertEquals(40, heightened.height());
  }

  @Test
  void aMaximumDoesNotGoThroughTheAspectRatioToASizeThatIsSet() {
    El absolute = el().absolute(0, 0).size(100, 100);
    absolute.style().aspectRatio = 1;
    absolute.style().maxHeight = Len.px(20);
    El item = el().size(100, 100);
    item.style().aspectRatio = 1;
    item.style().maxHeight = Len.px(20);
    Flex.layout(el().size(200, 200).child(absolute), 200, 200);
    Flex.layout(row().size(200, 200).align(Style.Align.START).child(item), 200, 200);

    assertEquals(100, absolute.width());
    assertEquals(20, absolute.height());
    assertEquals(100, item.width());
    assertEquals(20, item.height());
  }

  // space-between falls back to flex-start, which under wrap-reverse is the bottom edge.
  @Test
  void spaceBetweenWithoutRoomFallsBackToFlexStart() {
    El first = el().size(60, 20).shrink(0);
    El second = el().size(60, 20).shrink(0);
    El lines = row().size(100, 30).gap(10).children(first, second);
    lines.style().wrap = Style.Wrap.WRAP_REVERSE;
    lines.style().alignContent = Style.Content.SPACE_BETWEEN;
    El third = el().size(60, 20).shrink(0);
    El fourth = el().size(60, 20).shrink(0);
    El reversed = row().size(100, 20).justify(Style.Content.SPACE_BETWEEN).children(third, fourth);
    reversed.style().direction = Style.Direction.ROW_REVERSE;
    Flex.layout(lines, 100, 30);
    Flex.layout(reversed, 100, 20);

    assertEquals(10, first.y());
    assertEquals(-20, second.y());
    assertEquals(40, third.x());
    assertEquals(-20, fourth.x());
  }
}
