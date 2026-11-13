package com.rethinkj.ui;

/**
 * The layout properties of an element. They follow CSS flexbox, with the same names and defaults,
 * so a CSS layout translates field by field.
 *
 * <p>Fields are public and mutable: this is plain data that the layout reads. {@link El} offers
 * chained methods for the common cases.
 */
public final class Style {

  public enum Display {
    FLEX,
    NONE
  }

  public enum Position {
    RELATIVE,
    ABSOLUTE
  }

  public enum BoxSizing {
    BORDER_BOX,
    CONTENT_BOX
  }

  public enum Overflow {
    VISIBLE,
    CLIP,
    HIDDEN,
    SCROLL
  }

  public enum Direction {
    ROW,
    COLUMN,
    ROW_REVERSE,
    COLUMN_REVERSE
  }

  public enum Wrap {
    NO_WRAP,
    WRAP,
    WRAP_REVERSE
  }

  /** Values of {@code align-items} and {@code align-self}. */
  public enum Align {
    START,
    END,
    FLEX_START,
    FLEX_END,
    CENTER,
    BASELINE,
    STRETCH
  }

  /** Values of {@code justify-content} and {@code align-content}. */
  public enum Content {
    START,
    END,
    FLEX_START,
    FLEX_END,
    CENTER,
    STRETCH,
    SPACE_BETWEEN,
    SPACE_EVENLY,
    SPACE_AROUND
  }

  public Display display = Display.FLEX;
  public Position position = Position.RELATIVE;
  public BoxSizing boxSizing = BoxSizing.BORDER_BOX;
  public Overflow overflowX = Overflow.VISIBLE;
  public Overflow overflowY = Overflow.VISIBLE;
  public float scrollbarWidth;

  public Direction direction = Direction.ROW;
  public Wrap wrap = Wrap.NO_WRAP;

  /** {@code null} is the CSS default, {@code stretch}. */
  public Align alignItems;

  /** {@code null} is {@code auto}: take the parent's {@link #alignItems}. */
  public Align alignSelf;

  /** {@code null} is the CSS default, {@code flex-start}. */
  public Content justifyContent;

  /** {@code null} is the CSS default, {@code stretch}. */
  public Content alignContent;

  public Len rowGap = Len.ZERO;
  public Len columnGap = Len.ZERO;

  public float grow;
  public float shrink = 1;
  public Len basis = Len.AUTO;

  public Len width = Len.AUTO;
  public Len height = Len.AUTO;
  public Len minWidth = Len.AUTO;
  public Len minHeight = Len.AUTO;
  public Len maxWidth = Len.AUTO;
  public Len maxHeight = Len.AUTO;

  /** Width divided by height; NaN when unset. */
  public float aspectRatio = Float.NaN;

  public Len marginLeft = Len.ZERO;
  public Len marginRight = Len.ZERO;
  public Len marginTop = Len.ZERO;
  public Len marginBottom = Len.ZERO;

  public Len paddingLeft = Len.ZERO;
  public Len paddingRight = Len.ZERO;
  public Len paddingTop = Len.ZERO;
  public Len paddingBottom = Len.ZERO;

  public Len borderLeft = Len.ZERO;
  public Len borderRight = Len.ZERO;
  public Len borderTop = Len.ZERO;
  public Len borderBottom = Len.ZERO;

  /** Offsets from the parent's edges, used when {@link #position} is absolute or relative. */
  public Len left = Len.AUTO;

  public Len right = Len.AUTO;
  public Len top = Len.AUTO;
  public Len bottom = Len.AUTO;
}
