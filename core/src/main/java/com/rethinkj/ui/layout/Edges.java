package com.rethinkj.ui.layout;

import static com.rethinkj.ui.layout.Maybe.resolveOrZero;

import com.rethinkj.ui.Style;

/** Four resolved edge widths. Percentages on all four sides refer to the parent's width. */
record Edges(float left, float top, float right, float bottom) {

  static Edges padding(Style s, float parentWidth) {
    return new Edges(
        resolveOrZero(s.paddingLeft, parentWidth),
        resolveOrZero(s.paddingTop, parentWidth),
        resolveOrZero(s.paddingRight, parentWidth),
        resolveOrZero(s.paddingBottom, parentWidth));
  }

  static Edges border(Style s, float parentWidth) {
    return new Edges(
        resolveOrZero(s.borderLeft, parentWidth),
        resolveOrZero(s.borderTop, parentWidth),
        resolveOrZero(s.borderRight, parentWidth),
        resolveOrZero(s.borderBottom, parentWidth));
  }

  /** Auto margins count as zero. */
  static Edges margin(Style s, float parentWidth) {
    return new Edges(
        resolveOrZero(s.marginLeft, parentWidth),
        resolveOrZero(s.marginTop, parentWidth),
        resolveOrZero(s.marginRight, parentWidth),
        resolveOrZero(s.marginBottom, parentWidth));
  }

  float horizontal() {
    return left + right;
  }

  float vertical() {
    return top + bottom;
  }
}
