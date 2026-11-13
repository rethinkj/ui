package com.rethinkj.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One element of the view tree: a box with a style, a look and children. Views build a fresh tree
 * with chained calls:
 *
 * <pre>{@code
 * col().center().gap(16).fill(0xFF101014)
 *     .child(el().key("switch").size(38, 22).radius(11).fill(0xFF3B82F6));
 * }</pre>
 */
public final class El {

  private final Style style = new Style();
  private final List<El> children = new ArrayList<>();
  private String key;
  private String role;
  private String label;
  private int fill;
  private float radius;
  private int borderColor;
  private boolean placed;
  private float x;
  private float y;
  private float width;
  private float height;

  private El() {}

  /** A box whose children flow in a row, as in CSS. */
  public static El el() {
    return new El();
  }

  public static El row() {
    return new El();
  }

  public static El col() {
    El el = new El();
    el.style.direction = Style.Direction.COLUMN;
    return el;
  }

  // Identity and accessibility

  /** Identifies this element among its siblings across rebuilds of the tree. */
  public El key(String key) {
    this.key = key;
    return this;
  }

  public El role(String role) {
    this.role = role;
    return this;
  }

  public El label(String label) {
    this.label = label;
    return this;
  }

  // Size

  public El w(float pixels) {
    style.width = Len.px(pixels);
    return this;
  }

  public El h(float pixels) {
    style.height = Len.px(pixels);
    return this;
  }

  public El size(float width, float height) {
    return w(width).h(height);
  }

  public El wFull() {
    style.width = Len.percent(1);
    return this;
  }

  public El hFull() {
    style.height = Len.percent(1);
    return this;
  }

  public El minSize(float width, float height) {
    style.minWidth = Len.px(width);
    style.minHeight = Len.px(height);
    return this;
  }

  public El maxSize(float width, float height) {
    style.maxWidth = Len.px(width);
    style.maxHeight = Len.px(height);
    return this;
  }

  // Spacing

  public El padding(float all) {
    return padding(all, all, all, all);
  }

  public El padding(float vertical, float horizontal) {
    return padding(vertical, horizontal, vertical, horizontal);
  }

  public El padding(float top, float right, float bottom, float left) {
    style.paddingTop = Len.px(top);
    style.paddingRight = Len.px(right);
    style.paddingBottom = Len.px(bottom);
    style.paddingLeft = Len.px(left);
    return this;
  }

  public El margin(float all) {
    return margin(all, all, all, all);
  }

  public El margin(float top, float right, float bottom, float left) {
    style.marginTop = Len.px(top);
    style.marginRight = Len.px(right);
    style.marginBottom = Len.px(bottom);
    style.marginLeft = Len.px(left);
    return this;
  }

  /** Space between children, along both axes. */
  public El gap(float pixels) {
    style.rowGap = Len.px(pixels);
    style.columnGap = Len.px(pixels);
    return this;
  }

  // Flow

  public El wrap() {
    style.wrap = Style.Wrap.WRAP;
    return this;
  }

  /** Places the children along the direction they flow in. */
  public El justify(Style.Content justify) {
    style.justifyContent = justify;
    return this;
  }

  /** Places the children across the direction they flow in. */
  public El align(Style.Align align) {
    style.alignItems = align;
    return this;
  }

  public El alignSelf(Style.Align align) {
    style.alignSelf = align;
    return this;
  }

  /** Centers the children on both axes. */
  public El center() {
    return justify(Style.Content.CENTER).align(Style.Align.CENTER);
  }

  public El grow(float factor) {
    style.grow = factor;
    return this;
  }

  public El shrink(float factor) {
    style.shrink = factor;
    return this;
  }

  public El basis(float pixels) {
    style.basis = Len.px(pixels);
    return this;
  }

  /** Takes this element out of the flow and places it relative to its parent's top left. */
  public El absolute(float left, float top) {
    style.position = Style.Position.ABSOLUTE;
    style.left = Len.px(left);
    style.top = Len.px(top);
    return this;
  }

  // Look

  /** @param argb the colour as 0xAARRGGBB */
  public El fill(int argb) {
    this.fill = argb;
    return this;
  }

  public El radius(float pixels) {
    this.radius = pixels;
    return this;
  }

  /** @param argb the colour as 0xAARRGGBB */
  public El border(float width, int argb) {
    Len len = Len.px(width);
    style.borderTop = len;
    style.borderRight = len;
    style.borderBottom = len;
    style.borderLeft = len;
    this.borderColor = argb;
    return this;
  }

  // Tree

  public El child(El child) {
    children.add(child);
    return this;
  }

  public El children(El... children) {
    Collections.addAll(this.children, children);
    return this;
  }

  // Reading: for layout, rendering and tests

  /** The layout properties, for everything the chained methods do not cover. */
  public Style style() {
    return style;
  }

  public List<El> children() {
    return children;
  }

  public String key() {
    return key;
  }

  public String role() {
    return role;
  }

  public String label() {
    return label;
  }

  public int fill() {
    return fill;
  }

  public float radius() {
    return radius;
  }

  public int borderColor() {
    return borderColor;
  }

  /** Called by layout: the border box, with the position relative to the parent's border box. */
  public void place(float x, float y, float width, float height) {
    this.x = x;
    this.y = y;
    this.width = width;
    this.height = height;
    this.placed = true;
  }

  public float x() {
    return x;
  }

  public float y() {
    return y;
  }

  public float width() {
    return width;
  }

  public float height() {
    return height;
  }

  /**
   * The tree as text, one element per line, children indented by two spaces. After layout each
   * line ends with the element's rectangle in window coordinates, as {@code x,y widthxheight}.
   * Tests and agents read this, so the format only changes deliberately.
   */
  public String dump() {
    StringBuilder out = new StringBuilder();
    dump(out, 0, 0, 0);
    return out.toString();
  }

  private void dump(StringBuilder out, int depth, float parentX, float parentY) {
    out.repeat("  ", depth).append("el");
    if (key != null) {
      out.append(" #").append(key);
    }
    if (role != null) {
      out.append(" role=").append(role);
    }
    if (label != null) {
      out.append(" label=\"").append(label.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
    }
    float left = parentX + x;
    float top = parentY + y;
    if (placed) {
      out.append(' ');
      number(out, left).append(',');
      number(out, top).append(' ');
      number(out, width).append('x');
      number(out, height);
    }
    out.append('\n');
    for (El child : children) {
      child.dump(out, depth + 1, left, top);
    }
  }

  // Not String.format: its decimal separator depends on the locale.
  private static StringBuilder number(StringBuilder out, float value) {
    long hundredths = Math.round(value * 100.0);
    if (hundredths < 0) {
      out.append('-');
      hundredths = -hundredths;
    }
    out.append(hundredths / 100);
    long fraction = hundredths % 100;
    if (fraction != 0) {
      out.append('.').append(fraction / 10);
      if (fraction % 10 != 0) {
        out.append(fraction % 10);
      }
    }
    return out;
  }
}
