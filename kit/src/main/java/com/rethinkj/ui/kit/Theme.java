package com.rethinkj.ui.kit;

import com.rethinkj.ui.motion.Spring;
import com.rethinkj.ui.text.Font;
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
 * or a constant. The focus ring is drawn by the {@link com.rethinkj.ui.App}, so an app also sets
 * its colour: {@code app.focusRing(theme.border().focus())}.
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
    Motion motion) {

  /**
   * Background layers, from the bottom up, and the overlays that a control lays over its
   * background while the pointer is over it or presses it.
   *
   * @param window behind everything
   * @param panel an area set apart on the window, such as a sidebar or a card
   * @param raised a control or a popup above both
   * @param hover translucent, over a control's background while the pointer is over it
   * @param pressed translucent, over a control's background while it is pressed
   */
  public record Surfaces(int window, int panel, int raised, int hover, int pressed) {}

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
   */
  public record Borders(int subtle, int strong, int focus) {}

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
   * @param body the text of the interface and of controls
   * @param mono code, identifiers and values that line up
   * @param heading titles of sections
   */
  public record Type(TextStyle body, TextStyle mono, TextStyle heading) {}

  /**
   * @param control the height of a button and a text field
   * @param checkbox the side of a checkbox's box
   * @param switchWidth the width of a toggle's track
   * @param switchHeight the height of a toggle's track
   * @param stroke the width of a border
   * @param field the width of a text field, unless the view gives it another
   */
  public record Sizes(
      float control,
      float checkbox,
      float switchWidth,
      float switchHeight,
      float stroke,
      float field) {

    /** Sizes with text fields 200 wide. */
    public Sizes(float control, float checkbox, float switchWidth, float switchHeight,
        float stroke) {
      this(control, checkbox, switchWidth, switchHeight, stroke, 200);
    }
  }

  /**
   * @param feedback colours that answer the pointer and a change of state
   * @param move parts that travel, such as a toggle's knob
   */
  public record Motion(Spring feedback, Spring move) {}

  private static final Spacing SPACE = new Spacing(2, 4, 8, 12, 16, 24);
  private static final Radii RADIUS = new Radii(3, 4, 8);
  private static final Type TYPE =
      new Type(
          new TextStyle(Font::sans, 13),
          new TextStyle(Font::mono, 12),
          new TextStyle(Font::sansBold, 15));
  private static final Sizes SIZE = new Sizes(24, 16, 28, 16, 1, 200);
  private static final Motion MOTION = new Motion(Spring.INSTANT, Spring.SNAP);

  /** Neutral greys with a blue accent, light text on dark surfaces. */
  public static final Theme DARK =
      new Theme(
          new Surfaces(0xFF1B1C1F, 0xFF232428, 0xFF2D2E33, 0x0FFFFFFF, 0x1AFFFFFF),
          new TextColors(0xFFE4E5E9, 0xFFA1A3AA, 0xFF6A6C73, 0xFFFFFFFF),
          new Borders(0xFF393A40, 0xFF7A7D86, 0xFFE4E5E9),
          new Accent(0xFF2E62D9, 0xFF3A6EE6, 0xFF2655C2, 0x732E62D9),
          new Status(0xFFF2797A, 0xFFE0B04C, 0xFF5BC489),
          SPACE,
          RADIUS,
          TYPE,
          SIZE,
          MOTION);

  /** Neutral greys with a blue accent, dark text on light surfaces. */
  public static final Theme LIGHT =
      new Theme(
          new Surfaces(0xFFF8F9FA, 0xFFEFF0F2, 0xFFFFFFFF, 0x0F000000, 0x1A000000),
          new TextColors(0xFF1D1E21, 0xFF5C5F66, 0xFFA3A5AB, 0xFFFFFFFF),
          new Borders(0xFFD8DADF, 0xFF878A93, 0xFF1D1E21),
          new Accent(0xFF2E62D9, 0xFF2755C0, 0xFF2048A8, 0x4D2E62D9),
          new Status(0xFFC4282B, 0xFF8F5F00, 0xFF1E7A3E),
          SPACE,
          RADIUS,
          TYPE,
          SIZE,
          MOTION);

  public Theme withSurface(Surfaces surface) {
    return new Theme(surface, text, border, accent, status, space, radius, type, size, motion);
  }

  public Theme withText(TextColors text) {
    return new Theme(surface, text, border, accent, status, space, radius, type, size, motion);
  }

  public Theme withBorder(Borders border) {
    return new Theme(surface, text, border, accent, status, space, radius, type, size, motion);
  }

  public Theme withAccent(Accent accent) {
    return new Theme(surface, text, border, accent, status, space, radius, type, size, motion);
  }

  public Theme withStatus(Status status) {
    return new Theme(surface, text, border, accent, status, space, radius, type, size, motion);
  }

  public Theme withSpace(Spacing space) {
    return new Theme(surface, text, border, accent, status, space, radius, type, size, motion);
  }

  public Theme withRadius(Radii radius) {
    return new Theme(surface, text, border, accent, status, space, radius, type, size, motion);
  }

  public Theme withType(Type type) {
    return new Theme(surface, text, border, accent, status, space, radius, type, size, motion);
  }

  public Theme withSize(Sizes size) {
    return new Theme(surface, text, border, accent, status, space, radius, type, size, motion);
  }

  public Theme withMotion(Motion motion) {
    return new Theme(surface, text, border, accent, status, space, radius, type, size, motion);
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
