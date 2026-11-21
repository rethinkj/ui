package com.rethinkj.ui;

import java.util.Locale;

/** Writes a tree as text, in the format {@link El#dump} describes, and the parts of its lines. */
final class TreeDumper {

  /** The longest string that {@link #quoteText} writes whole, in UTF-16 units. */
  static final int QUOTED = 200;

  private TreeDumper() {}

  /** The tree of this root and then its {@link El#shownOverlays overlays}; see {@link El#dump}. */
  static String dump(El root) {
    StringBuilder out = new StringBuilder();
    dump(out, root, 0, 0, 0);
    for (El overlay : root.shownOverlays()) {
      El.Layer l = overlay.layer;
      out.append("overlay");
      if (l.centered) {
        out.append(" center");
      } else if (l.edge != null) {
        out.append(" edge ")
            .append(l.edge.name().toLowerCase(Locale.ROOT))
            .append(' ')
            .append(l.align.name().toLowerCase(Locale.ROOT));
        if (l.gap != 0) {
          number(out.append(" gap "), l.gap);
        }
      } else {
        if (!Float.isNaN(l.pointX)) {
          number(out.append(" at "), l.pointX).append(',');
          number(out, l.pointY);
        }
        out.append(' ')
            .append(l.side.name().toLowerCase(Locale.ROOT))
            .append(' ')
            .append(l.align.name().toLowerCase(Locale.ROOT));
        if (l.gap != 0) {
          number(out.append(" gap "), l.gap);
        }
      }
      if (l.modal) {
        out.append(" modal");
      }
      if (l.scrim >>> 24 != 0) {
        out.append(" scrim");
      }
      out.append(" on");
      El a = l.anchor;
      if (a.key() != null) {
        out.append(" #").append(a.key());
      }
      if (a.role() != null) {
        out.append(" role=").append(a.role());
      }
      if (a.label() != null) {
        out.append(" label=").append(quote(a.label()));
      }
      number(out.append(' '), l.anchorX).append(',');
      number(out, l.anchorY).append(' ');
      number(out, a.width()).append('x');
      number(out, a.height()).append('\n');
      dump(out, overlay, 1, 0, 0);
    }
    return out.toString();
  }

  private static void dump(StringBuilder out, El el, int depth, float parentX, float parentY) {
    float left = parentX + el.x();
    float top = parentY + el.y();
    describe(out.repeat("  ", depth), el, left, top);
    for (El child : el.children()) {
      dump(out, child, depth + 1, left, top);
    }
  }

  /** One line of {@link El#dump}, for this element with its border box at this window position. */
  static void describe(StringBuilder out, El el, float left, float top) {
    Text text = el.text();
    out.append(text != null ? "text" : el.image() != null ? "image" : "el");
    if (el.key() != null) {
      out.append(" #").append(el.key());
    }
    if (el.role() != null) {
      out.append(" role=").append(el.role());
    }
    String label = el.label();
    if (label != null) {
      out.append(" label=").append(quote(label));
    }
    // A field without a label of its own takes its placeholder as label; that is not repeated.
    if (text != null
        && text.placeholder() != null
        && !text.placeholder().isEmpty()
        && !text.placeholder().equals(label)) {
      out.append(" placeholder=").append(quote(text.placeholder()));
    }
    if (text != null) {
      out.append(' ').append(quoteText(text.string()));
    }
    if (el.windowPart() != null) {
      out.append(" window=").append(el.windowPart().name().toLowerCase(Locale.ROOT));
    }
    if (el.isPlaced()) {
      out.append(' ');
      number(out, left).append(',');
      number(out, top).append(' ');
      number(out, el.width()).append('x');
      number(out, el.height());
    }
    states(out, el);
    if (el.value() != null) {
      out.append(" value=").append(quote(el.value()));
    }
    if (el.errorMessage() != null) {
      out.append(" error=").append(quote(el.errorMessage()));
    }
    if (el.warningMessage() != null) {
      out.append(" warning=").append(quote(el.warningMessage()));
    }
    if (el.isVirtualList()) {
      out.append(" rows ");
      int children = el.children().size();
      if (children == 0) {
        out.append('-');
      } else {
        out.append(el.firstRow()).append('-').append(el.firstRow() + children - 1);
      }
      out.append('/').append(el.rowCount());
    }
    if (text != null && text.multiline() && el.isPlaced()) {
      out.append(" lines ");
      int first = text.firstLineInView();
      if (first < 0) {
        out.append('-');
      } else {
        out.append(first + 1).append('-').append(text.lastLineInView() + 1);
      }
      out.append('/').append(text.lineTotal());
      int[] under = text.underlined();
      for (int i = 0; i < under.length; i += 2) {
        out.append(i == 0 ? " underline " : ",").append(under[i]).append('-').append(under[i + 1]);
      }
    }
    if (el.scrollX() != 0 || el.scrollY() != 0) {
      number(out.append(" scroll "), el.scrollX()).append(',');
      number(out, el.scrollY());
    }
    if (el.horizontalBar() != null) {
      thumb(out.append(" thumb-x "), el.horizontalBar());
    }
    if (el.verticalBar() != null) {
      thumb(out.append(" thumb-y "), el.verticalBar());
    }
    if (el.hovered) {
      out.append(" hovered");
    }
    if (el.focused) {
      out.append(" focused");
      Editor editor = el.editor();
      if (editor != null) {
        // A masked field's positions count in its mask, which is what the line quotes.
        out.append(" caret ").append(text.maskIndex(editor.text(), editor.caret()));
        if (text.multiline()) {
          int[] at = editor.lineAndColumn();
          out.append(" (").append(at[0]).append(':').append(at[1]).append(')');
        }
        if (editor.anchor() != editor.caret()) {
          out.append(" anchor ").append(text.maskIndex(editor.text(), editor.anchor()));
        }
        String composing = editor.composition();
        if (!composing.isEmpty()) {
          out.append(" composing ").append(quote(text.masked() ? text.mask(composing) : composing));
        }
      }
    }
    if (el.isExiting()) {
      out.append(" exiting");
    }
    out.append('\n');
  }

  /** The states of {@link El#dump} but the value, each after a space, in their fixed order. */
  static void states(StringBuilder out, El el) {
    int states = el.states;
    boolean field = el.isEditable();
    if ((states & El.DISABLED) != 0 || field && el.onEdit() == null && !el.isReadOnly()) {
      out.append(" disabled");
    }
    if (el.isReadOnly()) {
      out.append(" readonly");
    }
    if (field && el.text().masked()) {
      out.append(" password");
    }
    if (field && el.text().revealed()) {
      out.append(" revealed");
    }
    if ((states & El.INVALID) != 0) {
      out.append(" invalid");
    }
    if ((states & El.BUSY) != 0) {
      out.append(" busy");
    }
    if ((states & El.SELECTED) != 0) {
      out.append(" selected");
    }
    if ((states & El.CURRENT) != 0) {
      out.append(" current");
    }
    if ((states & El.GRABBED) != 0) {
      out.append(" grabbed");
    }
    if ((states & El.MODIFIED) != 0) {
      out.append(" modified");
    }
    switch (states & (El.CHECKED | El.NOT_CHECKED)) {
      case El.CHECKED -> out.append(" checked");
      case El.NOT_CHECKED -> out.append(" checked=false");
      case El.CHECKED | El.NOT_CHECKED -> out.append(" checked=mixed");
      default -> {}
    }
    switch (states & (El.EXPANDED | El.COLLAPSED)) {
      case El.EXPANDED -> out.append(" expanded");
      case El.COLLAPSED -> out.append(" expanded=false");
      default -> {}
    }
    if (el.level != 0) {
      out.append(" level=").append(el.level);
    }
    if (el.position != 0) {
      out.append(" pos=").append(el.position).append('/').append(el.setSize);
    }
    switch (states & (El.ASCENDING | El.DESCENDING)) {
      case El.ASCENDING -> out.append(" sort=ascending");
      case El.DESCENDING -> out.append(" sort=descending");
      default -> {}
    }
  }

  private static void thumb(StringBuilder out, Scrollbars.Bar bar) {
    number(out, bar.thumbX()).append(',');
    number(out, bar.thumbY()).append(' ');
    number(out, bar.thumbWidth()).append('x');
    number(out, bar.thumbHeight());
  }

  /**
   * A text element's string as the dump writes it: {@link #quote quoted} whole up to {@link
   * #QUOTED} UTF-16 units; a longer one as its first 100 and its last 40, each quoted, with
   * {@code …} between them and its length after them, as in {@code "SELECT …" … "… 1;" length
   * 104857}, so that a dump stays readable whatever a field holds. No part splits a surrogate
   * pair.
   */
  static String quoteText(String text) {
    if (text.length() <= QUOTED) {
      return quote(text);
    }
    int head = 100;
    if (Character.isLowSurrogate(text.charAt(head))) {
      head--;
    }
    int tail = text.length() - 40;
    if (Character.isLowSurrogate(text.charAt(tail))) {
      tail++;
    }
    return quote(text.substring(0, head)) + " … " + quote(text.substring(tail)) + " length "
        + text.length();
  }

  /**
   * The text in double quotes, with a backslash before each quote and backslash in it, line feeds
   * as {@code \n}, carriage returns as {@code \r} and tabs as {@code \t}, so that it stays on one line and shows them, and a
   * character of a private use area, such as an
   * icon, as <code>&#92;u{E0EB}</code>, which would otherwise show as nothing or a box.
   */
  static String quote(String text) {
    StringBuilder out = new StringBuilder("\"");
    text.codePoints().forEach(c -> {
      if (c == '\\' || c == '"') {
        out.append('\\').append((char) c);
      } else if (c == '\n') {
        out.append("\\n");
      } else if (c == '\r') {
        out.append("\\r");
      } else if (c == '\t') {
        out.append("\\t");
      } else if (Character.getType(c) == Character.PRIVATE_USE) {
        out.append("\\u{")
            .append(Integer.toHexString(c).toUpperCase(Locale.ROOT))
            .append('}');
      } else {
        out.appendCodePoint(c);
      }
    });
    return out.append('"').toString();
  }

  // Not String.format: its decimal separator depends on the locale.
  private static StringBuilder number(StringBuilder out, double value) {
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
