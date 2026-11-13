package com.rethinkj.ui.kit;

import com.rethinkj.ui.Scrollbars;
import com.rethinkj.ui.Shadow;
import com.rethinkj.ui.motion.Spring;
import com.rethinkj.ui.text.Font;
import java.util.List;
import java.util.function.Supplier;

/**
 * The values the kit's components read, named for what they are for rather than for how they look:
 * {@code text().muted()}, not "grey 500". Components take no colour, size or spring of their own,
 * so a theme decides all of them.
 *
 * <p>A theme is immutable. {@link #DARK} and {@link #LIGHT} are a plain neutral default; an app
 * copies one and changes what it needs with the {@code with} methods:
 *
 * <pre>{@code
 * Theme brand = Theme.DARK.withAccent(new Theme.Accent(0xFF7C3AED, 0xFF8B5CF6, 0xFF6D28D9));
 * }</pre>
 *
 * <p>Components get the theme as their first parameter, and the view gets it from the app's state
 * or a constant. Focusable components draw their focus ring in {@code border().focus()} of their
 * theme ({@link com.rethinkj.ui.El#focusRing(int)}).
 *
 * <p>The scrollbars are core's {@link Scrollbars}, which any scroll area or list takes as they are:
 * {@code col().scroll().scrollbars(theme.scrollbars())}. Their thumbs stand out 3:1 from the
 * surfaces and the grid's rows they lie on.
 *
 * <p>Menus, a select's list, tooltips and dialogs lie above the rest with the shadows of {@link
 * #elevation} and keep the subtle border around them.
 *
 * <p>Colours are 0xAARRGGBB, sizes logical pixels.
 */
public record Theme(
    Surfaces surface,
    TextColors text,
    Borders border,
    Accent accent,
    Status status,
    Spacing space,
    Radii radius,
    Type type,
    Sizes size,
    Motion motion,
    Grid grid,
    Scrollbars scrollbars,
    Elevation elevation,
    Code code) {

  /**
   * A theme whose colours for code are {@link Code#DARK} on a dark raised surface and {@link
   * Code#LIGHT} on a light one.
   */
  public Theme(Surfaces surface, TextColors text, Borders border, Accent accent, Status status,
      Spacing space, Radii radius, Type type, Sizes size, Motion motion, Grid grid,
      Scrollbars scrollbars, Elevation elevation) {
    this(surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, lightness(surface.raised()) < 0.5 ? Code.DARK : Code.LIGHT);
  }

  private static double lightness(int argb) {
    return (0.2126 * (argb >> 16 & 0xFF) + 0.7152 * (argb >> 8 & 0xFF) + 0.0722 * (argb & 0xFF))
        / 255;
  }

  /**
   * The colours of code in an editor ({@link CodeEditor}, {@link Sql}): text colours for the kinds
   * of token, each 4.5:1 on the field's raised surface, also hovered and on the caret's line, and
   * translucent backgrounds laid over that surface.
   *
   * @param keyword words of the language, such as {@code SELECT}
   * @param string literal strings
   * @param number literal numbers
   * @param comment comments
   * @param operator operators, brackets and punctuation
   * @param line translucent, behind the line of the caret
   * @param bracket translucent, behind the bracket at the caret and the one that matches it
   */
  public record Code(int keyword, int string, int number, int comment, int operator, int line,
      int bracket) {

    /** For dark surfaces. */
    public static final Code DARK = new Code(0xFFD2B4FF, 0xFFA6DB94, 0xFFF5BD8C, 0xFFAEB2BA,
        0xFF9ADCF5, 0x0DFFFFFF, 0x1FFFFFFF);

    /** For light surfaces. */
    public static final Code LIGHT = new Code(0xFF6A28CF, 0xFF1A6633, 0xFF95400C, 0xFF575B63,
        0xFF0A5D75, 0x0A000000, 0x1A000000);
  }

  /**
   * Background layers, from the bottom up, and the overlays that a control lays over its
   * background while the pointer is over it or presses it.
   *
   * @param window behind everything
   * @param panel an area set apart on the window, such as a sidebar or a card
   * @param raised a control or a popup above both
   * @param hover translucent, over a control's background while the pointer is over it
   * @param pressed translucent, over a control's background while it is pressed
   * @param scrim translucent, over the whole window under a modal dialog
   */
  public record Surfaces(int window, int panel, int raised, int hover, int pressed, int scrim) {

    /** Surfaces with a scrim of black at 50 %. */
    public Surfaces(int window, int panel, int raised, int hover, int pressed) {
      this(window, panel, raised, hover, pressed, 0x80000000);
    }
  }

  /**
   * @param primary most text
   * @param muted secondary text: hints, captions, values beside their names
   * @param disabled the text of a control that cannot be used now
   * @param onAccent text and marks on the accent colour
   */
  public record TextColors(int primary, int muted, int disabled, int onAccent) {}

  /**
   * @param subtle dividers and the edges of surfaces
   * @param strong the edges a control is recognised by, such as a checkbox's box
   * @param focus the focus ring, which has to stand out against the backgrounds and the accent
   * @param selected the edge of a selected card and the mark of the selected item of a navigation
   *     list, in the accent's hue but standing out 3:1 from the surfaces, which the accent itself
   *     does not on a dark raised surface
   */
  public record Borders(int subtle, int strong, int focus, int selected) {

    /** Borders whose selected edge is the focus ring's colour. */
    public Borders(int subtle, int strong, int focus) {
      this(subtle, strong, focus, focus);
    }
  }

  /**
   * The colour of the main action and of what is on, with its hover and pressed variants.
   *
   * @param selection translucent, behind selected text
   */
  public record Accent(int color, int hover, int pressed, int selection) {

    /** An accent whose selection is its colour at 40 % opacity. */
    public Accent(int color, int hover, int pressed) {
      this(color, hover, pressed, 0x66000000 | color & 0xFFFFFF);
    }
  }

  /** Colours of text and marks that report a state. */
  public record Status(int danger, int warning, int success) {}

  /** Steps of space between and inside elements. */
  public record Spacing(float xs, float s, float m, float l, float xl, float xxl) {}

  /**
   * @param small small controls, such as a checkbox
   * @param medium buttons
   * @param large panels and cards
   */
  public record Radii(float small, float medium, float large) {}

  /**
   * A font and its size. The font is supplied when text is built, so that a theme can be made
   * and read without loading FreeType and HarfBuzz.
   */
  public record TextStyle(Supplier<Font> font, float size) {}

  /**
   * The type scale, from a page's title down to code. Headings come in three levels: {@code
   * title} over a page, {@code heading} over a section, {@code strong} over a card.
   *
   * @param body the text of the interface and of controls
   * @param mono code, identifiers and values that line up
   * @param heading titles of sections
   * @param title the title of a page, such as a page of settings
   * @param strong titles inside a section, such as a card's: the body's size in the heading's font
   */
  public record Type(
      TextStyle body, TextStyle mono, TextStyle heading, TextStyle title, TextStyle strong) {

    /**
     * A scale whose title is a third larger than the heading, and whose strong text is the body's
     * size in the heading's font.
     */
    public Type(TextStyle body, TextStyle mono, TextStyle heading) {
      this(body, mono, heading, new TextStyle(heading.font(), Math.round(heading.size() * 4 / 3f)),
          new TextStyle(heading.font(), body.size()));
    }
  }

  /**
   * @param control the height of a button, a text field and a tab
   * @param checkbox the side of a checkbox's box and of a tab's close button
   * @param switchWidth the width of a toggle's track
   * @param switchHeight the height of a toggle's track
   * @param stroke the width of a border, and of the line between the parts of a split
   * @param field the width of a text field, unless the view gives it another
   * @param handle the width of the strip along a split's line that takes the pointer
   * @param step how far an arrow key moves a split's line
   * @param icon the side of an {@link Icon}, unless the view gives it another; the icons are
   *     sharpest at a size they are drawn for (see {@link Symbol})
   */
  public record Sizes(
      float control,
      float checkbox,
      float switchWidth,
      float switchHeight,
      float stroke,
      float field,
      float handle,
      float step,
      float icon) {

    /** Sizes with text fields 200 wide, a split's handle 7 wide, steps of 16 and icons 16. */
    public Sizes(float control, float checkbox, float switchWidth, float switchHeight,
        float stroke) {
      this(control, checkbox, switchWidth, switchHeight, stroke, 200);
    }

    /** Sizes with a split's handle 7 wide, steps of 16 and icons 16. */
    public Sizes(float control, float checkbox, float switchWidth, float switchHeight,
        float stroke, float field) {
      this(control, checkbox, switchWidth, switchHeight, stroke, field, 7, 16, 16);
    }
  }

  /**
   * @param feedback colours that answer the pointer and a change of state
   * @param move parts that travel, such as a toggle's knob
   * @param submenuDelay seconds the pointer rests on a menu's item before its submenu opens, or
   *     the open one closes
   * @param tooltipDelay seconds the pointer rests on an element, or the keyboard's focus stays on
   *     it, before its tooltip shows
   * @param fade how menus, tooltips, dialogs and toasts fade in as they open and out as they close
   * @param toast seconds a {@link Toast} stays before it closes itself
   */
  public record Motion(Spring feedback, Spring move, double submenuDelay, double tooltipDelay,
      Spring fade, double toast) {

    /** Motion with submenus after 0.3 seconds, tooltips after half a second, quick fades. */
    public Motion(Spring feedback, Spring move) {
      this(feedback, move, 0.3, 0.5);
    }

    /** Motion with quick fades and toasts that stay five seconds. */
    public Motion(Spring feedback, Spring move, double submenuDelay, double tooltipDelay) {
      this(feedback, move, submenuDelay, tooltipDelay, Spring.INSTANT, 5);
    }
  }

  /**
   * The data grid's sizes and backgrounds. Its lines are {@link Borders#subtle}, and its hovered
   * rows and header cells lay {@link Surfaces#hover} and {@link Surfaces#pressed} over these. The
   * {@link Tree} takes its row height and the colours of its selected rows from here too.
   *
   * @param rowHeight the height of a row and of the header
   * @param minColumn the narrowest a column can be made by dragging its edge or fitting it
   * @param maxColumn the widest a double press on a column's edge fits it to its content
   * @param handle the width of the strip around a header cell's right edge that resizes it
   * @param mark the size of the sort arrows in the header
   * @param header the background of the header and of the row numbers
   * @param row the background of the rows, and of the grid below the last one
   * @param stripe the background of every other row
   * @param selected the selected rows or cells while the grid has the keyboard focus
   * @param selectedInactive the selected rows or cells while the focus is elsewhere
   * @param cursor the outline of the cell the cursor is on while the grid has the focus, which
   *     stands out 3:1 from the rows and the selection
   * @param cursorWidth the width of that outline
   */
  public record Grid(
      float rowHeight, float minColumn, float maxColumn, float handle, float mark, int header,
      int row, int stripe, int selected, int selectedInactive, int cursor, float cursorWidth) {}

  /**
   * The shadows under what lies above the rest ({@link com.rethinkj.ui.El#shadow}), each a tight
   * one that darkens the edge and a wide one that shows the height, the first on top. A dark theme
   * needs them stronger and tighter than a light one: a shadow shows little on a dark surface, and
   * a wide one only greys it.
   *
   * @param popup menus, a select's list and tooltips
   * @param dialog dialogs, above the scrim
   */
  public record Elevation(List<Shadow> popup, List<Shadow> dialog) {

    public Elevation {
      popup = List.copyOf(popup);
      dialog = List.copyOf(dialog);
    }
  }

  private static final Spacing SPACE = new Spacing(2, 4, 8, 12, 16, 24);
  private static final Radii RADIUS = new Radii(3, 4, 8);
  private static final Type TYPE =
      new Type(
          new TextStyle(Font::sans, 13),
          new TextStyle(Font::mono, 12),
          new TextStyle(Font::sansBold, 15),
          new TextStyle(Font::sansBold, 20),
          new TextStyle(Font::sansBold, 13));
  private static final Sizes SIZE = new Sizes(24, 16, 28, 16, 1, 200, 7, 16, 16);
  private static final Motion MOTION =
      new Motion(Spring.INSTANT, Spring.SNAP, 0.3, 0.5, Spring.INSTANT, 5);

  /** Neutral greys with a blue accent, light text on dark surfaces. */
  public static final Theme DARK =
      new Theme(
          new Surfaces(0xFF1B1C1F, 0xFF232428, 0xFF2D2E33, 0x0FFFFFFF, 0x1AFFFFFF, 0x99000000),
          new TextColors(0xFFE4E5E9, 0xFFA1A3AA, 0xFF6A6C73, 0xFFFFFFFF),
          new Borders(0xFF393A40, 0xFF7A7D86, 0xFFE4E5E9, 0xFF7AA2F7),
          new Accent(0xFF2E62D9, 0xFF3A6EE6, 0xFF2655C2, 0x732E62D9),
          new Status(0xFFF2797A, 0xFFE0B04C, 0xFF5BC489),
          SPACE,
          RADIUS,
          TYPE,
          SIZE,
          MOTION,
          new Grid(22, 32, 480, 6, 12, 0xFF232428, 0xFF1B1C1F, 0xFF1F2023, 0xFF1C3666,
              0xFF2B3240, 0xFF7AA2F7, 2),
          new Scrollbars(6, 2, 3, 24, 0xFF83868F, 0xFFA1A3AA, 0xFFB9BBC1),
          new Elevation(
              List.of(new Shadow(0, 1, 2, 0, 0x73000000), new Shadow(0, 4, 10, 0, 0x66000000)),
              List.of(new Shadow(0, 2, 4, 0, 0x73000000), new Shadow(0, 8, 24, 0, 0x80000000))));

  /** Neutral greys with a blue accent, dark text on light surfaces. */
  public static final Theme LIGHT =
      new Theme(
          new Surfaces(0xFFF8F9FA, 0xFFEFF0F2, 0xFFFFFFFF, 0x0F000000, 0x1A000000, 0x59000000),
          new TextColors(0xFF1D1E21, 0xFF5C5F66, 0xFFA3A5AB, 0xFFFFFFFF),
          new Borders(0xFFD8DADF, 0xFF878A93, 0xFF1D1E21, 0xFF1D4ED8),
          new Accent(0xFF2E62D9, 0xFF2755C0, 0xFF2048A8, 0x4D2E62D9),
          new Status(0xFFC4282B, 0xFF8F5F00, 0xFF1E7A3E),
          SPACE,
          RADIUS,
          TYPE,
          SIZE,
          MOTION,
          new Grid(22, 32, 480, 6, 12, 0xFFF1F2F4, 0xFFFFFFFF, 0xFFF5F6F8, 0xFFCFE0FC,
              0xFFE3E6EC, 0xFF1D4ED8, 2),
          new Scrollbars(6, 2, 3, 24, 0xFF6E717A, 0xFF5C5F66, 0xFF4A4D54),
          new Elevation(
              List.of(new Shadow(0, 1, 3, 0, 0x1F000000), new Shadow(0, 6, 16, 0, 0x24000000)),
              List.of(new Shadow(0, 2, 6, 0, 0x1F000000), new Shadow(0, 16, 40, 0, 0x33000000))));

  public Theme withSurface(Surfaces surface) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code);
  }

  public Theme withText(TextColors text) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code);
  }

  public Theme withBorder(Borders border) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code);
  }

  public Theme withAccent(Accent accent) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code);
  }

  public Theme withStatus(Status status) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code);
  }

  public Theme withSpace(Spacing space) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code);
  }

  public Theme withRadius(Radii radius) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code);
  }

  public Theme withType(Type type) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code);
  }

  public Theme withSize(Sizes size) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code);
  }

  public Theme withMotion(Motion motion) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code);
  }

  public Theme withGrid(Grid grid) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code);
  }

  public Theme withScrollbars(Scrollbars scrollbars) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code);
  }

  public Theme withCode(Code code) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code);
  }

  public Theme withElevation(Elevation elevation) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code);
  }

  /**
   * The colour {@code overlay} gives when laid over the opaque colour {@code base}, such as a
   * control's background with the {@link Surfaces#hover} overlay on it.
   */
  public static int over(int base, int overlay) {
    int alpha = overlay >>> 24;
    int argb = 0xFF;
    for (int shift = 16; shift >= 0; shift -= 8) {
      int b = base >>> shift & 0xFF;
      int o = overlay >>> shift & 0xFF;
      argb = argb << 8 | Math.round((o * alpha + b * (255 - alpha)) / 255f);
    }
    return argb;
  }
}
