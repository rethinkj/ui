package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.Drag;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * Two parts side by side or one above the other, with a line between them that the user drags to
 * share the room differently. The app keeps where the line is and changes it when the split
 * reports a move:
 *
 * <pre>{@code
 * split(theme, Direction.ROW, s.sidebar, Position.first(240), p -> s.sidebar = p,
 *         schema.label("Schema"), 160,
 *         split(theme, Direction.COLUMN, s.editor, Position.share(0.5f), p -> s.editor = p,
 *             editor.label("Query"), 80, results, 80),
 *         320)
 *     .grow(1);
 * }</pre>
 *
 * <p>The {@link Position} says what keeps its size when the split grows or shrinks: the first
 * part, as a side panel keeps its width when the window grows; the second; or both their share,
 * as an editor over its results. Each part has a minimum, which layout keeps also when the split
 * becomes smaller than the position allows; the position stays as it was, so the parts are back
 * where they were once there is room again. Where there is not even room for both minimums, the
 * second part is cut off.
 *
 * <p>The line is {@link Theme.Borders#subtle} and {@link Theme.Sizes#stroke} wide, and a strip of
 * {@link Theme.Sizes#handle} along it takes the pointer: it shows the resize cursor of its
 * direction, the strong border under the pointer and the accent while it is dragged. A double
 * press puts it back where the caller says. The strip is one stop for Tab, and while it has the
 * focus the arrow keys along the split move it by {@link Theme.Sizes#step}.
 *
 * <p>Each part fills its side: the split makes it {@link El#grow} in a box of its own that clips
 * it. The split itself needs a size of its own or room to grow, like a list of rows. In a tree
 * dump the boxes are {@code #first} and {@code #second}, the latter in {@code #rest}, and the
 * strip is {@code #divider} with {@code role=separator} and the label {@code Resize} and the first
 * part's label, if it has one.
 */
public final class Split {

  /** How the parts lie. */
  public enum Direction {
    /** Side by side, the first on the left, with an upright line between them. */
    ROW,
    /** One above the other, the first on top. */
    COLUMN
  }

  /**
   * Where the line between the parts is, and what keeps its size when the split's size changes.
   * The split reports a new position of the same kind while the line moves.
   *
   * @param kind what the value means
   * @param value logical pixels, or a share from 0 to 1
   */
  public record Position(Kind kind, float value) {

    public enum Kind {
      /** The first part is {@code value} logical pixels; the second takes the rest. */
      FIRST,
      /** The second part is {@code value} logical pixels; the first takes the rest. */
      SECOND,
      /** The first part has this share of the room both parts have, the second the rest. */
      SHARE
    }

    public Position {
      Objects.requireNonNull(kind, "kind");
      if (!(value >= 0 && value <= (kind == Kind.SHARE ? 1 : Float.MAX_VALUE))) {
        throw new IllegalArgumentException(
            kind == Kind.SHARE
                ? "a share must be from 0 to 1, was " + value
                : "a size must be 0 or more and finite, was " + value);
      }
    }

    /** The first part this many logical pixels wide or high, as a side panel on the left. */
    public static Position first(float pixels) {
      return new Position(Kind.FIRST, pixels);
    }

    /** The second part this many logical pixels wide or high, as a panel on the right. */
    public static Position second(float pixels) {
      return new Position(Kind.SECOND, pixels);
    }

    /** The first part this share of the room, from 0 to 1, as an editor over its results. */
    public static Position share(float fraction) {
      return new Position(Kind.SHARE, fraction);
    }
  }

  private Split() {}

  /**
   * @param position where the line is now, from the app's state
   * @param reset where a double press on the line puts it; null for nowhere
   * @param onMove told the new position, of the same kind as {@code position}, while the line is
   *     dragged or moved with the keys, then {@code reset} on a double press, and {@code position}
   *     again if a drag is cancelled. The position it is told leaves both parts their minimums in
   *     the split's size of the moment. Null fixes the line: it takes no pointer and no focus.
   * @param first the part on the left or on top
   * @param minFirst the least width or height of the first part, in logical pixels
   * @param second the part on the right or below
   * @param minSecond the least width or height of the second part
   */
  public static El split(
      Theme t, Direction direction, Position position, Position reset, Consumer<Position> onMove,
      El first, float minFirst, El second, float minSecond) {
    Objects.requireNonNull(position, "position");
    boolean across = direction == Direction.ROW;
    float stroke = t.size().stroke();
    // The strip around the line reaches over both parts by this much. It comes after the second
    // part in the tree, so that it is on top of both: the second part and the strip are in a box
    // of their own, in reverse order, that begins where the strip does.
    float over = Math.max(t.size().handle() - stroke, 0) / 2;
    float strip = stroke + 2 * over;

    El line = el().fill(t.border().subtle());
    El divider = (across ? row() : col()).key("divider").role("separator")
        .label(first.label() == null ? "Resize" : "Resize " + first.label())
        .value(position.kind().name().toLowerCase(Locale.ROOT) + " "
            + new BigDecimal(Float.toString(position.value())).stripTrailingZeros().toPlainString())
        .justify(Style.Content.CENTER)
        .shrink(0)
        .child(line);
    El one = pane(first, across, minFirst).key("first");
    El two = pane(second, across, minSecond).key("second").basis(0).grow(1);
    El rest = el().key("rest").child(two).child(divider);
    El split = (across ? row() : col()).clip().children(one, rest);
    rest.style().direction =
        across ? Style.Direction.ROW_REVERSE : Style.Direction.COLUMN_REVERSE;
    zeroMin(rest);
    if (across) {
      line.w(stroke);
      divider.w(strip);
      rest.style().marginLeft = Len.px(-over);
      two.style().marginLeft = Len.px(-over);
      rest.style().minWidth = Len.px(minSecond + stroke + over);
    } else {
      line.h(stroke);
      divider.h(strip);
      rest.style().marginTop = Len.px(-over);
      two.style().marginTop = Len.px(-over);
      rest.style().minHeight = Len.px(minSecond + stroke + over);
    }
    // The second part with the line is "rest": stroke + over more than the second part.
    switch (position.kind()) {
      case FIRST -> {
        one.basis(position.value()).grow(0).shrink(1);
        rest.basis(0).grow(1).shrink(0);
      }
      case SECOND -> {
        one.basis(0).grow(1).shrink(0);
        rest.basis(position.value() + stroke + over).grow(0).shrink(1);
      }
      case SHARE -> {
        one.basis(0).grow(position.value()).shrink(0);
        rest.basis(stroke + over).grow(1 - position.value()).shrink(0);
      }
    }
    if (onMove == null) {
      return split;
    }

    // Moves the line by a distance from where it is in the view the drag began in, or in the view
    // a key goes to; keys that reach a view before the next is built go on from each other.
    float[] keyed = {0};
    DoubleConsumer move = delta -> {
      float room = Math.max(size(split, across) - stroke, 0);
      float size = Math.max(minFirst,
          Math.min(size(one, across) + (float) delta, room - minSecond));
      onMove.accept(switch (position.kind()) {
        case FIRST -> Position.first(size);
        case SECOND -> Position.second(Math.max(room - size, 0));
        case SHARE -> room > 0 ? Position.share(Math.clamp(size / room, 0, 1)) : position;
      });
    };
    float step = t.size().step();
    String back = across ? "ArrowLeft" : "ArrowUp";
    String forth = across ? "ArrowRight" : "ArrowDown";
    divider.focusable()
        .cursor(across ? Cursor.EW_RESIZE : Cursor.NS_RESIZE)
        .whenHovered(e -> line.fill(t.border().strong()))
        .whenPressed(e -> line.fill(t.accent().color()))
        .onDrag(d -> {
          if (d.phase() == Drag.Phase.CANCEL) {
            onMove.accept(position);
          } else {
            move.accept(across ? d.dx() : d.dy());
          }
        })
        .onKey(k -> {
          if (k.modifiers() != 0 || !(k.name().equals(back) || k.name().equals(forth))) {
            return false;
          }
          keyed[0] += k.name().equals(back) ? -step : step;
          move.accept(keyed[0]);
          return true;
        });
    if (reset != null) {
      divider.onPress(clicks -> {
        if (clicks == 2) {
          onMove.accept(reset);
        }
      });
    }
    return split;
  }

  /** A part's box: it clips the part, which fills it, and keeps the minimum along the split. */
  private static El pane(El part, boolean across, float min) {
    El pane = col().clip().child(part.grow(1));
    zeroMin(pane);
    if (across) {
      pane.style().minWidth = Len.px(min);
    } else {
      pane.style().minHeight = Len.px(min);
    }
    return pane;
  }

  /** No minimum from the content: the split decides the sizes. */
  private static void zeroMin(El el) {
    el.style().minWidth = Len.ZERO;
    el.style().minHeight = Len.ZERO;
  }

  private static float size(El el, boolean across) {
    return across ? el.width() : el.height();
  }
}
