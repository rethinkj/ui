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
 * <p>The look is made of layers: the window's background ({@link Surfaces#window}), cards on it
 * ({@link Surfaces#panel}) lifted by {@link Elevation#card}, quiet bands inside a card such as a
 * tab bar, a grid's header and a footer ({@link Surfaces#band}), and controls and popups ({@link
 * Surfaces#raised}). Hairlines of low contrast ({@link Borders#subtle}) separate them. Radii go by
 * rank: cards {@link Radii#large}, controls {@link Radii#medium}, small parts {@link Radii#small}.
 * The full accent fills primary buttons and what is on; its lighter {@link Accent#edge} draws
 * outlines, the focus ring and the active tab's line.
 *
 * <p>Components get the theme as their first parameter, and the view gets it from the app's state
 * or a constant. Focusable components draw their focus ring in {@code accent().edge()} of their
 * theme ({@link com.rethinkj.ui.El#focusRing(int)}); a primary button, whose fill is the accent,
 * draws it {@link Sizes#ringGap} away ({@link com.rethinkj.ui.El#focusRingGap}), so that the
 * surface behind the button lies between the two.
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
    Elevation elevation) {

  /**
   * Background layers, from the bottom up, and the overlays that a control lays over its
   * background while the pointer is over it or presses it.
   *
   * @param window behind everything: the page the cards lie on, and the title bar
   * @param panel a card, a sidebar or a dialog on the window
   * @param raised a control or a popup above both
   * @param band a quiet band inside a card: a tab bar, a footer, the header of a grid
   * @param hover translucent, over a control's background while the pointer is over it
   * @param pressed translucent, over a control's background while it is pressed
   * @param scrim translucent, over the whole window under a modal dialog
   */
  public record Surfaces(
      int window, int panel, int raised, int band, int hover, int pressed, int scrim) {}

  /**
   * @param primary most text
   * @param muted secondary text: hints, captions, values beside their names
   * @param disabled the text of a control that cannot be used now
   * @param onAccent text and marks on the accent colour
   */
  public record TextColors(int primary, int muted, int disabled, int onAccent) {}

  /**
   * @param subtle hairlines between surfaces and rows, and the edges of cards and popups
   * @param strong the edges a control is recognised by, such as a text field's frame or a
   *     checkbox's box, which stand out 3:1 from the surfaces
   * @param control the edge of a button and of other controls that their text names, quieter
   *     than {@code strong}, and of popups and dialogs
   */
  public record Borders(int subtle, int strong, int control) {}

  /**
   * The colour of the main action and of what is on, with its hover and pressed variants.
   *
   * @param selection translucent, behind selected text
   * @param edge the accent's lighter tone for lines and outlines: the focus ring, the active tab's
   *     line, the edge of a selected card, the mark of a navigation list's selected item; it
   *     stands out 3:1 from the surfaces and the grid's selection, which the full accent does not
   *     on a dark surface
   */
  public record Accent(int color, int hover, int pressed, int selection, int edge) {

    /** An accent whose selection is its colour at 40 % opacity and whose edge is its colour. */
    public Accent(int color, int hover, int pressed) {
      this(color, hover, pressed, 0x66000000 | color & 0xFFFFFF, color);
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
   * title} over a page, {@code heading} over a section or a card's toolbar, {@code strong} over a
   * card. Titles and headings are semibold, strong text medium.
   *
   * @param body the text of the interface and of controls
   * @param mono code, identifiers and values that line up
   * @param heading titles of sections and of a card's toolbar
   * @param title the title of a page, such as a page of settings
   * @param strong the body's size in a heavier weight: a card's title, the titles of a grid's
   *     columns and the text of buttons
   */
  public record Type(
      TextStyle body, TextStyle mono, TextStyle heading, TextStyle title, TextStyle strong) {}

  /**
   * @param control the height of a button and a text field
   * @param checkbox the side of a checkbox's box and of a tab's close button
   * @param switchWidth the width of a toggle's track
   * @param switchHeight the height of a toggle's track
   * @param stroke the width of a border, and of the line between the parts of a split
   * @param field the width of a text field, unless the view gives it another
   * @param handle the width of the strip along a split's line that takes the pointer
   * @param step how far an arrow key moves a split's line
   * @param icon the side of an {@link Icon}, unless the view gives it another; the icons are
   *     sharpest at a size they are drawn for (see {@link Symbol})
   * @param indicator the thickness of the line that marks the active tab
   * @param ringGap the gap between a primary button and its focus ring, in which what lies behind
   *     the button shows, so that the ring in the accent's edge stands out from the accent's fill
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
      float icon,
      float indicator,
      float ringGap) {

    /** Sizes with a gap of 2 between a primary button and its ring. */
    public Sizes(float control, float checkbox, float switchWidth, float switchHeight,
        float stroke, float field, float handle, float step, float icon, float indicator) {
      this(control, checkbox, switchWidth, switchHeight, stroke, field, handle, step, icon,
          indicator, 2);
    }

    /**
     * Sizes with text fields 200 wide, a split's handle 7 wide, steps of 16, icons 16 and a tab's
     * line 2 thick.
     */
    public Sizes(float control, float checkbox, float switchWidth, float switchHeight,
        float stroke) {
      this(control, checkbox, switchWidth, switchHeight, stroke, 200);
    }

    /** Sizes with a split's handle 7 wide, steps of 16, icons 16 and a tab's line 2 thick. */
    public Sizes(float control, float checkbox, float switchWidth, float switchHeight,
        float stroke, float field) {
      this(control, checkbox, switchWidth, switchHeight, stroke, field, 7, 16, 16, 2);
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
   * The data grid's sizes and backgrounds. Its lines are {@link Borders#subtle}, its selected
   * rows and cells are a tint without an edge, and its hovered
   * rows and header cells lay {@link Surfaces#hover} and {@link Surfaces#pressed} over these. The
   * {@link Tree} takes its row height and the colours of its selected rows from here too.
   *
   * @param rowHeight the height of a row and of the header
   * @param minColumn the narrowest a column can be made by dragging its edge or fitting it
   * @param maxColumn the widest a double press on a column's edge fits it to its content
   * @param handle the width of the strip around a header cell's right edge that resizes it
   * @param mark the size of the sort arrows in the header
   * @param header the background of the header, a quiet band
   * @param row the background of the rows, and of the grid below the last one
   * @param stripe the background of every other row; the row's colour for none
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
   * a wide one only greys it. The renderer mixes them in linear light, where a shadow shows about
   * half as much as the same alpha in CSS.
   *
   * @param card cards on the window, lifted a little
   * @param popup menus, a select's list and tooltips
   * @param dialog dialogs, above the scrim
   */
  public record Elevation(List<Shadow> card, List<Shadow> popup, List<Shadow> dialog) {

    public Elevation {
      card = List.copyOf(card);
      popup = List.copyOf(popup);
      dialog = List.copyOf(dialog);
    }
  }

  private static final Spacing SPACE = new Spacing(2, 4, 8, 12, 16, 24);
  private static final Radii RADIUS = new Radii(4, 6, 10);
  private static final Type TYPE =
      new Type(
          new TextStyle(Font::sans, 13),
          new TextStyle(Font::mono, 12),
          new TextStyle(Font::sansSemibold, 14),
          new TextStyle(Font::sansSemibold, 20),
          new TextStyle(Font::sansMedium, 13));
  private static final Sizes SIZE = new Sizes(24, 16, 28, 16, 1, 200, 7, 16, 16, 2, 2);
  private static final Motion MOTION =
      new Motion(Spring.INSTANT, Spring.SNAP, 0.3, 0.5, Spring.INSTANT, 5);

  /*
   * The rules the default themes follow; ThemeTest holds them to these.
   *
   * Layers. The window is the page, darker than the cards in the dark theme and a light grey in the
   * light one; cards (panel) lie on it, bands (a tab bar, a grid's header, a footer) are a quiet
   * step inside a card, and controls and popups (raised) a step above it in the dark theme and
   * white in the light one, where a control is told apart by its edge and a popup by its edge and
   * shadow. Hairlines (subtle) are a low-contrast step from the card; a button's edge (control) a
   * little more.
   *
   * Hover and pressed. A neutral grey near the surfaces (lighter in the dark theme, darker in the
   * light one) at 25 % and 45 % (dark) or 30 % and 50 % (light), mixed in linear light both by Theme.over and by the renderer, so
   * every component gives the same colour: hover 4 L* or more from the surface, pressed 3 L* or
   * more beyond hover.
   *
   * Accent. One blue: white text on it at 4.5:1. Its edge, the tone of lines, outlines and the
   * focus ring, 3:1 on every surface and on the grid's selection: a lighter blue in the dark
   * theme, the accent itself in the light one. A primary button's ring is the primary text, 3:1 on
   * the accent. Text selection: the accent at 40 % (dark) and 35 % (light).
   *
   * Text. Primary and muted at least 4.5:1 on every surface, band, hover, pressed and selection
   * they meet; disabled from 2.5:1 to 3.5:1 on window, panel and raised, legible but clearly below
   * muted. Status colours 4.5:1 on the surfaces and on the grid's selection.
   *
   * Edges and marks. The strong border, the accent as a fill of what is on, the grid's cursor and
   * the scrollbar's thumb at rest at least 3:1 on the surfaces they lie on.
   *
   * Selection in lists. Focused: a calm tint of the accent, primary text at 9:1 or more. Without
   * the focus: the same lightness with little colour, apart from the hover by 4 L* or more.
   *
   * Shadows. Cards a little, popups more, dialogs most; the light theme with about twice the alpha
   * a CSS shadow has, as the renderer mixes in linear light.
   *
   * Sizes and type. Controls 24, rows 24, text 13; radii 4 for small parts, 6 for controls, 10 for
   * cards; headings semibold, strong text, column titles and buttons medium.
   */

  /** Neutral greys with a blue accent, light text on dark surfaces. */
  public static final Theme DARK =
      new Theme(
          new Surfaces(0xFF0F1013, 0xFF17181C, 0xFF1E2025, 0xFF1A1B20, 0x4045464C, 0x7345464C,
              0x99000000),
          new TextColors(0xFFE6E7EA, 0xFF9A9DA9, 0xFF62646D, 0xFFFFFFFF),
          new Borders(0xFF25272D, 0xFF6E717B, 0xFF33363D),
          new Accent(0xFF3D6FE6, 0xFF3768DD, 0xFF2F5ED0, 0x663D6FE6, 0xFF86A8F8),
          new Status(0xFFF2797A, 0xFFE0B04C, 0xFF5CC98A),
          SPACE,
          RADIUS,
          TYPE,
          SIZE,
          MOTION,
          new Grid(24, 32, 480, 6, 12, 0xFF1A1B20, 0xFF17181C, 0xFF17181C, 0xFF1C2B4D,
              0xFF2E323D, 0xFF86A8F8, 1),
          new Scrollbars(6, 2, 3, 24, 0xFF858892, 0xFF9C9FA8, 0xFFB9BBC1),
          new Elevation(
              List.of(new Shadow(0, 1, 2, 0, 0xCC000000), new Shadow(0, 6, 16, 0, 0x8C000000)),
              List.of(new Shadow(0, 1, 2, 0, 0x99000000), new Shadow(0, 4, 12, 0, 0x80000000)),
              List.of(new Shadow(0, 2, 4, 0, 0x99000000), new Shadow(0, 12, 32, 0, 0x99000000))));

  /** Neutral greys with a blue accent, dark text on light surfaces. */
  public static final Theme LIGHT =
      new Theme(
          new Surfaces(0xFFECEEF2, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFF7F8FA, 0x4DBDBFC5, 0x80BDBFC5,
              0x59000000),
          new TextColors(0xFF1B1D22, 0xFF5C606B, 0xFF8F929A, 0xFFFFFFFF),
          new Borders(0xFFE6E8EC, 0xFF80848D, 0xFFCFD3DA),
          new Accent(0xFF2E62D9, 0xFF2755C0, 0xFF2048A8, 0x592E62D9, 0xFF2E62D9),
          new Status(0xFFB42318, 0xFF7A5300, 0xFF17693A),
          SPACE,
          RADIUS,
          TYPE,
          SIZE,
          MOTION,
          new Grid(24, 32, 480, 6, 12, 0xFFF7F8FA, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFDFE8FB,
              0xFFDCE0E6, 0xFF2E62D9, 1),
          new Scrollbars(6, 2, 3, 24, 0xFF777B84, 0xFF60646E, 0xFF4A4D54),
          new Elevation(
              List.of(new Shadow(0, 1, 2, 0, 0x29141824), new Shadow(0, 6, 18, 0, 0x25141824)),
              List.of(new Shadow(0, 1, 3, 0, 0x40000000), new Shadow(0, 6, 16, 0, 0x38000000)),
              List.of(new Shadow(0, 2, 6, 0, 0x40000000), new Shadow(0, 16, 40, 0, 0x4D000000))));

  public Theme withSurface(Surfaces surface) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation);
  }

  public Theme withText(TextColors text) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation);
  }

  public Theme withBorder(Borders border) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation);
  }

  public Theme withAccent(Accent accent) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation);
  }

  public Theme withStatus(Status status) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation);
  }

  public Theme withSpace(Spacing space) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation);
  }

  public Theme withRadius(Radii radius) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation);
  }

  public Theme withType(Type type) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation);
  }

  public Theme withSize(Sizes size) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation);
  }

  public Theme withMotion(Motion motion) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation);
  }

  public Theme withGrid(Grid grid) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation);
  }

  public Theme withScrollbars(Scrollbars scrollbars) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation);
  }

  public Theme withElevation(Elevation elevation) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation);
  }

  /**
   * The colour {@code overlay} gives when laid over the opaque colour {@code base}, such as a
   * control's background with the {@link Surfaces#hover} overlay on it. It mixes in linear light,
   * as the renderer blends a translucent fill, so a component that draws the overlay translucent
   * and one that fills with this colour look the same.
   */
  public static int over(int base, int overlay) {
    double alpha = (overlay >>> 24) / 255.0;
    int argb = 0xFF;
    for (int shift = 16; shift >= 0; shift -= 8) {
      double mixed = linear(overlay >>> shift & 0xFF) * alpha
          + linear(base >>> shift & 0xFF) * (1 - alpha);
      double srgb =
          mixed <= 0.0031308 ? mixed * 12.92 : 1.055 * StrictMath.pow(mixed, 1 / 2.4) - 0.055;
      argb = argb << 8 | (int) Math.round(srgb * 255);
    }
    return argb;
  }

  /** An 8-bit sRGB channel in linear light, from 0 to 1. */
  private static double linear(int channel) {
    double c = channel / 255.0;
    return c <= 0.04045 ? c / 12.92 : StrictMath.pow((c + 0.055) / 1.055, 2.4);
  }
}
