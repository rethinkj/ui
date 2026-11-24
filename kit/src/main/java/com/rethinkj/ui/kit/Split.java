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
 * split(Direction.ROW, state.sidebar, Position.first(240),
 *         position -> state.sidebar = position,
 *         schema.label("Schema"), 160,
 *         split(Direction.COLUMN, state.editor, Position.share(0.5f),
 *             position -> state.editor = position,
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
 * <p>The line is {@link Theme.Borders#divider} and {@link Theme.Sizes#stroke} wide, and a strip of
 * {@link Theme.Sizes#handle} along it takes the pointer: it shows the resize cursor of its
 * direction, the strong border under the pointer and the accent while it is dragged. A double
 * press or Enter on the strip restores a collapsed part, or otherwise puts the line at {@code
 * reset}; when {@code reset} is itself collapsed, those gestures collapse that part while keeping
 * the current position for restore. The strip is one stop for Tab, and while it has the focus the
 * arrow keys along the split move it by {@link Theme.Sizes#step}. Dragging or an arrow key away
 * from a collapsed part restores it, at least at its minimum. Tab reaches the strip after the
 * second part: the strip has to be drawn, and so come in the tree, after both parts to lie on top
 * of them, and Tab follows the tree. A press or a drag on it leaves the focus where it is, in the
 * part that has it ({@link El#keepFocus}).
 *
 * <p>A collapsed part ({@link Position#collapse}) takes zero size and leaves the tree, so what core
 * keeps of its elements by identity, such as a scroll offset, starts anew when it is restored; the
 * other part has no minimum then, and the divider stays at the edge and can still be grabbed. In {@link #cards} the
 * gap goes too while a part is collapsed.
 *
 * <p>{@link #cards} splits two cards on the window ({@link Card}) instead: the parts are {@link
 * Theme.Spacing#m} apart, the line shows in the middle of that gap only under the pointer and
 * while it is dragged, and nothing clips the open parts, so that the cards' lifts show.
 *
 * <p>Each part fills its side: the split makes it {@link El#grow} in a box of its own that clips
 * it, with no minimum size of its own. The split itself needs a size of its own or room to grow,
 * like a list of rows, and takes no minimum size from its parts either: a list of rows or a tree
 * in a part scrolls inside the room the split has, also in a column that grows. In a tree
 * dump the boxes are {@code #first} and {@code #second}, the latter in {@code #rest}, and the
 * strip is {@code #divider} with {@code role=separator}, the label {@code Resize} and the first
 * part's label, if it has one, and its position as {@code value} (with {@code collapsed first} or
 * {@code collapsed second} appended while a part is collapsed).
 */
public final class Split {

  /** How the parts lie. */
  public enum Direction {
    /** Side by side, the first on the left, with an upright line between them. */
    ROW,
    /** One above the other, the first on top. */
    COLUMN
  }

  /** Which of the two parts. */
  public enum Part {
    /** The part on the left or on top. */
    FIRST,
    /** The part on the right or below. */
    SECOND
  }

  /**
   * Where the line between the parts is, what keeps its size when the split's size changes, and
   * whether one of the parts is collapsed. The split reports a new position of the same kind while
   * the line moves; collapsing keeps {@code kind} and {@code value} so {@link #restore} returns to
   * the last open position.
   *
   * @param kind what the value means
   * @param value logical pixels, or a share from 0 to 1
   * @param collapsed the collapsed part, or null when both parts are open
   */
  public record Position(Kind kind, float value, Part collapsed) {

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

    /** An open position with neither part collapsed. */
    public Position(Kind kind, float value) {
      this(kind, value, null);
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

    /** This position with {@code part} collapsed, keeping {@code kind} and {@code value}. */
    public Position collapse(Part part) {
      Objects.requireNonNull(part, "part");
      return collapsed == part ? this : new Position(kind, value, part);
    }

    /** This position with neither part collapsed. */
    public Position restore() {
      return collapsed == null ? this : new Position(kind, value, null);
    }

    /** Whether either part is collapsed. */
    public boolean isCollapsed() {
      return collapsed != null;
    }
  }

  private Split() {}

  /**
   * @param position where the line is now, from the app's state
   * @param reset where a double press or Enter on the line puts it when open, or a collapsed
   *     position to collapse that part while keeping the current {@code kind} and {@code value};
   *     null for nowhere (a collapsed split still restores on double press or Enter)
   * @param onMove told the new position, of the same kind as {@code position}, while the line is
   *     dragged or moved with the keys (dragging or an arrow key away from a collapsed part
   *     restores it, at least at its minimum), on double press or Enter, and {@code position}
   *     again if a drag is cancelled. The position it is told when moving leaves both parts their
   *     minimums in the split's size of the moment; where there is no room for both, it is {@code
   *     position} unchanged, unless a part is collapsed, which is restored all the same. Null fixes the line: it takes no pointer and no focus.
   * @param first the part on the left or on top
   * @param minFirst the least width or height of the first part, in logical pixels
   * @param second the part on the right or below
   * @param minSecond the least width or height of the second part
   */
  public static El split(
      Direction direction,
      Position position,
      Position reset,
      Consumer<Position> onMove,
      El first,
      float minFirst,
      El second,
      float minSecond) {
    Theme theme = Theme.CURRENT.get();
    return split(
        theme, direction, position, reset, onMove, first, minFirst, second, minSecond, false);
  }

  /**
   * A split between two cards on the window: as {@link #split}, but the parts lie {@link
   * Theme.Spacing#m} apart, the line between them shows only under the pointer and while it is
   * dragged, and their boxes do not clip them, so that the cards' lifts show.
   */
  public static El cards(
      Direction direction,
      Position position,
      Position reset,
      Consumer<Position> onMove,
      El first,
      float minFirst,
      El second,
      float minSecond) {
    Theme theme = Theme.CURRENT.get();
    return split(
        theme, direction, position, reset, onMove, first, minFirst, second, minSecond, true);
  }

  private static El split(
      Theme theme,
      Direction direction,
      Position position,
      Position reset,
      Consumer<Position> onMove,
      El first,
      float minFirst,
      El second,
      float minSecond,
      boolean cards) {
    Objects.requireNonNull(position, "position");
    boolean across = direction == Direction.ROW;
    Part collapsed = position.collapsed();
    float hair = theme.size().stroke();
    // Between cards the gap takes the line's place, which shows in its middle; while a part is
    // collapsed the gap goes too.
    float openStroke = cards ? theme.space().m() : hair;
    float stroke = cards && collapsed != null ? 0 : openStroke;
    // The strip around the line reaches over both parts when open, or lies inside the open side at
    // the edge when a part is collapsed. It comes after the second part in the tree, so that it is
    // on top of both: the second part and the strip are in a box of their own, in reverse order,
    // that begins where the strip does.
    float extra = Math.max(theme.size().handle() - stroke, 0);
    float overFirst = collapsed == null ? extra / 2 : collapsed == Part.FIRST ? 0 : extra;
    float overSecond = collapsed == null ? extra / 2 : collapsed == Part.FIRST ? extra : 0;
    float strip = stroke + overFirst + overSecond;
    Style.Content align = collapsed == null
        ? Style.Content.CENTER
        : collapsed == Part.FIRST ? Style.Content.START : Style.Content.END;

    El mark = el().fill(cards ? 0 : theme.border().divider());
    El line = cards ? (across ? row() : col()).justify(Style.Content.CENTER).child(mark) : mark;
    String value = position.kind().name().toLowerCase(Locale.ROOT)
        + " "
        + new BigDecimal(Float.toString(position.value())).stripTrailingZeros().toPlainString()
        + (collapsed == null ? "" : " collapsed " + collapsed.name().toLowerCase(Locale.ROOT));
    El divider = (across ? row() : col())
        .key("divider")
        .role("separator")
        .label(
            first.label() == null
                ? Texts.CURRENT.get().resize()
                : Texts.CURRENT.get().resize(first.label()))
        .value(value)
        .justify(align)
        .shrink(0)
        .child(line);
    // The only open part has no minimum, so that a collapsed second part's divider stays inside.
    El one = pane(
            first, across, collapsed == Part.SECOND ? 0 : minFirst, !cards, collapsed == Part.FIRST)
        .key("first");
    El two = pane(second, across, minSecond, !cards, collapsed == Part.SECOND)
        .key("second")
        .basis(0)
        .grow(collapsed == Part.SECOND ? 0 : 1);
    El rest = el().key("rest").child(two).child(divider);
    El split = (across ? row() : col()).children(one, rest);
    if (!cards) {
      split.clip();
    }
    zeroMin(split); // it fills the room it is given, whatever the parts hold
    rest.style().direction = across ? Style.Direction.ROW_REVERSE : Style.Direction.COLUMN_REVERSE;
    zeroMin(rest);
    float minRest = (collapsed == Part.SECOND ? 0 : minSecond) + stroke + overFirst;
    float lineSize = Math.max(stroke, hair);
    if (across) {
      line.w(lineSize);
      mark.w(hair);
      divider.w(strip);
      rest.style().marginLeft = Len.px(-overFirst);
      two.style().marginLeft = Len.px(-overSecond);
      rest.style().minWidth = Len.px(minRest);
    } else {
      line.h(lineSize);
      mark.h(hair);
      divider.h(strip);
      rest.style().marginTop = Len.px(-overFirst);
      two.style().marginTop = Len.px(-overSecond);
      rest.style().minHeight = Len.px(minRest);
    }
    switch (collapsed) {
      case FIRST -> {
        one.basis(0).grow(0).shrink(0);
        rest.basis(0).grow(1).shrink(0);
      }
      case SECOND -> {
        one.basis(0).grow(1).shrink(0);
        two.shrink(0);
        rest.basis(stroke + overFirst).grow(0).shrink(0);
      }
      // The second part with the line is "rest": stroke + overFirst more than the second part.
      case null -> {
        switch (position.kind()) {
          case FIRST -> {
            one.basis(position.value()).grow(0).shrink(1);
            rest.basis(0).grow(1).shrink(0);
          }
          case SECOND -> {
            one.basis(0).grow(1).shrink(0);
            rest.basis(position.value() + stroke + overFirst).grow(0).shrink(1);
          }
          case SHARE -> {
            one.basis(0).grow(position.value()).shrink(0);
            rest.basis(stroke + overFirst).grow(1 - position.value()).shrink(0);
          }
        }
      }
    }
    if (onMove == null) {
      return split;
    }

    // Moves the line by a distance from where it is in the view the drag began in, or in the view
    // a key goes to, which is built after the key before it.
    DoubleConsumer move = delta -> {
      if (collapsed == Part.FIRST && delta <= 0 || collapsed == Part.SECOND && delta >= 0) {
        onMove.accept(position);
        return;
      }
      // The parts fill the content box, whatever the split's padding and border resolve to.
      float room = Math.max(size(one, across) + size(rest, across) - overFirst - openStroke, 0);
      float from = collapsed == null ? size(one, across) : collapsed == Part.FIRST ? 0 : room;
      // Without room for both minimums the bounds would cross; minFirst wins.
      float size = Math.clamp(from + (float) delta, minFirst, Math.max(minFirst, room - minSecond));
      // Without room for both minimums the line cannot move, and keeps its position; the pixel
      // allows for the rounding of the parts' sizes. A collapsed part is restored all the same,
      // and layout keeps the minimums as it does for a narrow split.
      onMove.accept(
          collapsed == null && room < minFirst + minSecond + 1
              ? position
              : switch (position.kind()) {
                case FIRST -> Position.first(size);
                case SECOND -> Position.second(Math.max(room - size, 0));
                case SHARE ->
                  room > 0 ? Position.share(Math.clamp(size / room, 0, 1)) : position.restore();
              });
    };
    Position toggle = collapsed != null
        ? position.restore()
        : reset == null ? null : reset.isCollapsed() ? position.collapse(reset.collapsed()) : reset;
    float step = theme.size().step();
    String back = across ? "ArrowLeft" : "ArrowUp";
    String forth = across ? "ArrowRight" : "ArrowDown";
    // Tab reaches it; a press or a drag leaves the focus in the part that has it.
    divider
        .focusable()
        .keepFocus()
        .focusRing(theme.accent().edge())
        .cursor(across ? Cursor.EW_RESIZE : Cursor.NS_RESIZE)
        .whenHovered(e -> mark.fill(theme.border().strong()))
        .whenPressed(e -> mark.fill(theme.accent().color()))
        .onDrag(d -> {
          if (d.phase() == Drag.Phase.CANCEL) {
            onMove.accept(position);
          } else {
            move.accept(across ? d.dx() : d.dy());
          }
        })
        .onKey(k -> {
          if (k.modifiers() != 0) {
            return false;
          }
          if (toggle != null && (k.name().equals("Enter") || k.name().equals("NumpadEnter"))) {
            if (!k.repeat()) {
              onMove.accept(toggle);
            }
            return true;
          }
          if (k.name().equals(back)) {
            if (collapsed != Part.FIRST) {
              move.accept(-step);
            }
            return true;
          }
          if (k.name().equals(forth)) {
            if (collapsed != Part.SECOND) {
              move.accept(step);
            }
            return true;
          }
          return false;
        });
    if (toggle != null) {
      divider.onPress(clicks -> {
        if (clicks == 2) {
          onMove.accept(toggle);
        }
      });
    }
    return split;
  }

  /**
   * A part's box: it clips the part, which fills it, and keeps the minimum along the split. A
   * collapsed part's box is empty: the part leaves the tree, so that core moves a focus out of it
   * and ends a drag in it.
   */
  private static El pane(El part, boolean across, float min, boolean clip, boolean collapsed) {
    if (collapsed) {
      El pane = col();
      zeroMin(pane);
      return pane;
    }
    El pane = col().child(part.grow(1));
    if (clip) {
      pane.clip();
    }
    zeroMin(part); // a list of rows in it is no minimum either
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
