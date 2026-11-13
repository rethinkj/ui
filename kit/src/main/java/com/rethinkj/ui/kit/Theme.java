package com.rethinkj.ui.kit;

import static com.rethinkj.ui.Colors.BLACK;
import static com.rethinkj.ui.Colors.WHITE;
import static com.rethinkj.ui.Colors.alpha;
import static com.rethinkj.ui.kit.Palette.*;

import com.rethinkj.ui.Colors;
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
 * Theme brand = Theme.LIGHT.withAccent(new Theme.Accent(brand, brandHover, brandPressed));
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
 * <p>Colours are 0xAARRGGBB ({@link Colors}), sizes logical pixels. The default themes are written
 * with the colours of {@link Palette}.
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
    Code code,
    Caption caption,
    Prose prose,
    Badges badge) {

  /**
   * A theme whose colours for code are {@link Code#DARK} on a dark raised surface and {@link
   * Code#LIGHT} on a light one, with the default {@link Caption}, and whose documents look as
   * {@link Prose#DARK} or {@link Prose#LIGHT} by the same rule.
   */
  public Theme(Surfaces surface, TextColors text, Borders border, Accent accent, Status status,
      Spacing space, Radii radius, Type type, Sizes size, Motion motion, Grid grid,
      Scrollbars scrollbars, Elevation elevation) {
    this(surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, lightness(surface.raised()) < 0.5 ? Code.DARK : Code.LIGHT,
        CAPTION);
  }

  /** A theme with the default {@link Caption} and the {@link Prose} of its lightness, as above. */
  public Theme(Surfaces surface, TextColors text, Borders border, Accent accent, Status status,
      Spacing space, Radii radius, Type type, Sizes size, Motion motion, Grid grid,
      Scrollbars scrollbars, Elevation elevation, Code code) {
    this(surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code, CAPTION);
  }

  /** A theme whose documents look as {@link Prose#DARK} or {@link Prose#LIGHT}, as above. */
  public Theme(Surfaces surface, TextColors text, Borders border, Accent accent, Status status,
      Spacing space, Radii radius, Type type, Sizes size, Motion motion, Grid grid,
      Scrollbars scrollbars, Elevation elevation, Code code, Caption caption) {
    this(surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code, caption,
        lightness(surface.raised()) < 0.5 ? Prose.DARK : Prose.LIGHT);
  }

  /** A theme whose badges look as {@link Badges#DARK} or {@link Badges#LIGHT}, as above. */
  public Theme(Surfaces surface, TextColors text, Borders border, Accent accent, Status status,
      Spacing space, Radii radius, Type type, Sizes size, Motion motion, Grid grid,
      Scrollbars scrollbars, Elevation elevation, Code code, Caption caption, Prose prose) {
    this(surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code, caption, prose,
        lightness(surface.raised()) < 0.5 ? Badges.DARK : Badges.LIGHT);
  }

  /**
   * The colours of a {@link Badge} of one kind: a quiet one is {@code softText} on {@code soft},
   * a translucent tint laid over whatever the badge lies on, a strong one {@code strongText} on
   * {@code strong}.
   *
   * @param soft translucent, the quiet badge's fill
   * @param softText the quiet badge's text and icon, 4.5:1 on its fill over the surfaces, the
   *     grid's rows and its selection
   * @param strong opaque, the strong badge's fill
   * @param strongText the strong badge's text and icon, 4.5:1 on {@code strong}
   */
  public record Tone(int soft, int softText, int strong, int strongText) {}

  /**
   * How {@link Badge}s look: a {@link Tone} per kind and two heights.
   *
   * @param neutral types and properties, such as {@code bigint} or {@code NOT NULL}
   * @param accent what is chosen or stands out, such as a primary key
   * @param success what is well
   * @param warning what needs care
   * @param danger what failed or destroys
   * @param row the height of a badge in a row of a grid, a tree or a list, which it fits without
   *     changing the row's height
   * @param toolbar the height of a badge in a toolbar or a header, beside controls
   * @param text the badge's type, smaller than the body's
   * @param avatars the fills an {@link Avatar} picks from by its name, each 3:1 on the surfaces
   *     of both default themes, so that one palette serves both
   * @param onAvatar the initials and icon on those fills, 4.5:1 on each
   */
  public record Badges(Tone neutral, Tone accent, Tone success, Tone warning, Tone danger,
      float row, float toolbar, TextStyle text, List<Integer> avatars, int onAvatar) {

    public Badges {
      if (avatars.isEmpty()) {
        throw new IllegalArgumentException("at least one colour for avatars");
      }
      avatars = List.copyOf(avatars);
    }

    private static final TextStyle TEXT = new TextStyle(Font::sansMedium, 12);
    private static final List<Integer> AVATARS = List.of(BLUE_490, VIOLET_478, PURPLE_490,
        PINK_491, RED_493, ORANGE_488, AMBER_491, GREEN_492, TEAL_490);

    /** For dark surfaces. */
    public static final Badges DARK = new Badges(
        new Tone(alpha(WHITE, 0.05f), GREY_853, GREY_395, WHITE),
        new Tone(alpha(BLUE_496, 0.18f), BLUE_782, BLUE_496, WHITE),
        new Tone(alpha(GREEN_735, 0.1f), GREEN_826, GREEN_452, WHITE),
        new Tone(alpha(AMBER_745, 0.1f), AMBER_835, AMBER_745, GREY_108),
        new Tone(alpha(RED_649, 0.1f), RED_803, RED_457, WHITE),
        18, 22, TEXT, AVATARS, WHITE);

    /** For light surfaces. */
    public static final Badges LIGHT = new Badges(
        new Tone(alpha(GREY_407, 0.1f), GREY_314, GREY_407, WHITE),
        new Tone(alpha(BLUE_447, 0.12f), BLUE_366, BLUE_447, WHITE),
        new Tone(alpha(GREEN_389, 0.13f), GREEN_333, GREEN_389, WHITE),
        new Tone(alpha(AMBER_622, 0.17f), AMBER_327, AMBER_735, GREY_108),
        new Tone(alpha(RED_395, 0.12f), RED_345, RED_395, WHITE),
        18, 22, TEXT, AVATARS, WHITE);
  }

  /**
   * How a document looks that {@link Markdown} shows, on a card ({@link Surfaces#panel}): its
   * type, the colours of links, inline code and quotes, and how wide its text runs. Spaces between
   * blocks come from {@link Spacing}, code blocks from the raised surface and the subtle border.
   *
   * @param body paragraphs, lists, quotes and tables
   * @param headings the headings of levels 1 to 6, in this order
   * @param link the text of links, 4.5:1 on the panel and on {@code code}
   * @param code translucent, behind inline code, laid over the panel
   * @param quote the line along the left of a quote
   * @param measure the widest the column of blocks runs, for lines that stay easy to read
   */
  public record Prose(TextStyle body, List<TextStyle> headings, int link, int code, int quote,
      float measure) {

    public Prose {
      if (headings.size() != 6) {
        throw new IllegalArgumentException("a style for each of the six levels of headings");
      }
      headings = List.copyOf(headings);
    }

    private static final TextStyle BODY = new TextStyle(Font::sans, 14);
    private static final List<TextStyle> HEADINGS = List.of(
        new TextStyle(Font::sansSemibold, 26), new TextStyle(Font::sansSemibold, 20),
        new TextStyle(Font::sansSemibold, 17), new TextStyle(Font::sansSemibold, 15),
        new TextStyle(Font::sansSemibold, 14), new TextStyle(Font::sansSemibold, 13));

    /** For a dark panel. */
    public static final Prose DARK = new Prose(BODY, HEADINGS, BLUE_694, alpha(WHITE, 0.024f),
        GREY_311, 720);

    /** For a light panel. */
    public static final Prose LIGHT = new Prose(BODY, HEADINGS, BLUE_403, alpha(BLACK, 0.08f),
        GREY_805, 720);
  }


  /**
   * Whether a colour is light, from 0 to 1: the luma of its sRGB values as they are, not in linear
   * light, which is all the shorter constructors need to tell a dark theme's colour from a light
   * one's.
   */
  private static double lightness(int argb) {
    return (0.2126 * Colors.red(argb) + 0.7152 * Colors.green(argb) + 0.0722 * Colors.blue(argb))
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
   * @param line translucent, behind the line of the caret, and the selection a find keeps to
   * @param bracket translucent, behind the bracket at the caret and the one that matches it
   * @param match translucent, behind each match of a find ({@link Find})
   * @param current translucent, behind the current match of a find, stronger than {@code match}
   * @param occurrence translucent, behind the other occurrences of the word at the caret
   */
  public record Code(int keyword, int string, int number, int comment, int operator, int line,
      int bracket, int match, int current, int occurrence) {

    /** For dark surfaces. */
    public static final Code DARK = new Code(VIOLET_782, GREEN_823, ORANGE_806, GREY_747,
        CYAN_843, alpha(WHITE, 0.016f), alpha(WHITE, 0.047f), alpha(AMBER_323, 0.376f),
        alpha(AMBER_467, 0.267f), alpha(WHITE, 0.03f));

    /** For light surfaces. */
    public static final Code LIGHT = new Code(VIOLET_362, GREEN_378, ORANGE_380, GREY_386,
        CYAN_363, alpha(BLACK, 0.063f), alpha(BLACK, 0.15f), alpha(AMBER_768, 0.25f),
        alpha(AMBER_720, 0.5f), alpha(BLACK, 0.11f));

    /**
     * The colours of code with the backgrounds of a find and of a word's occurrences of {@link
     * #DARK} where the caret's line is light on dark, else of {@link #LIGHT}.
     */
    public Code(int keyword, int string, int number, int comment, int operator, int line,
        int bracket) {
      this(keyword, string, number, comment, operator, line, bracket,
          alpha(line, 1) == WHITE ? DARK.match : LIGHT.match,
          alpha(line, 1) == WHITE ? DARK.current : LIGHT.current,
          alpha(line, 1) == WHITE ? DARK.occurrence : LIGHT.occurrence);
    }
  }

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
   * @param track translucent, a faint line that keeps one contrast on every surface, band and
   *     selection it lies on: a {@link Progress#spinner}'s ring
   */
  public record Borders(int subtle, int strong, int control, int track) {

    /** Borders whose track is {@code strong} at 35 % in a dark theme, 40 % in a light one. */
    public Borders(int subtle, int strong, int control) {
      this(subtle, strong, control, alpha(strong, lightness(subtle) < 0.5 ? 0.35f : 0.4f));
    }
  }

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
      this(color, hover, pressed, alpha(color, 0.4f), color);
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
   * @param progress the thickness of a {@link Progress} bar in a row; the line along a card's edge
   *     is {@code indicator} thick
   * @param palette the width of a {@link CommandPalette}
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
      float ringGap,
      float progress,
      float palette) {

    /** Sizes with a command palette 560 wide. */
    public Sizes(float control, float checkbox, float switchWidth, float switchHeight,
        float stroke, float field, float handle, float step, float icon, float indicator,
        float ringGap, float progress) {
      this(control, checkbox, switchWidth, switchHeight, stroke, field, handle, step, icon,
          indicator, ringGap, progress, 560);
    }

    /** Sizes with progress bars 4 thick. */
    public Sizes(float control, float checkbox, float switchWidth, float switchHeight,
        float stroke, float field, float handle, float step, float icon, float indicator,
        float ringGap) {
      this(control, checkbox, switchWidth, switchHeight, stroke, field, handle, step, icon,
          indicator, ringGap, 4);
    }

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
   * @param progress seconds a {@link Progress#indeterminate} bar takes for one pass along its track
   * @param spin seconds a {@link Progress#spinner} takes to go round once
   * @param confirm seconds a {@link Button#copy} button shows that it copied
   */
  public record Motion(Spring feedback, Spring move, double submenuDelay, double tooltipDelay,
      Spring fade, double toast, double progress, double spin, double confirm) {

    /** Motion whose copy buttons confirm for 1.5 seconds. */
    public Motion(Spring feedback, Spring move, double submenuDelay, double tooltipDelay,
        Spring fade, double toast, double progress, double spin) {
      this(feedback, move, submenuDelay, tooltipDelay, fade, toast, progress, spin, 1.5);
    }

    /** Motion with a running bar's pass of 2 seconds and a spinner's turn of 1.2 seconds. */
    public Motion(Spring feedback, Spring move, double submenuDelay, double tooltipDelay,
        Spring fade, double toast) {
      this(feedback, move, submenuDelay, tooltipDelay, fade, toast, 2, 1.2);
    }

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
   * @param changed laid over a cell whose value was changed and is not yet written, translucent;
   *     the mark at the start of its row is {@link Accent#edge}. These four tints lie on the rows
   *     and stripes, not on the selection, which shows alone where it is; muted text keeps 4.5:1
   *     on them, which in linear light leaves a dark theme little alpha
   * @param added laid over a new row, translucent; its mark is {@link Status#success}
   * @param deleted laid over a row marked to be deleted, translucent; its mark is {@link
   *     Status#danger}, and its text is muted and struck through
   * @param error laid over a cell whose change failed, translucent, inside a line of {@link
   *     Status#danger}
   */
  public record Grid(
      float rowHeight, float minColumn, float maxColumn, float handle, float mark, int header,
      int row, int stripe, int selected, int selectedInactive, int cursor, float cursorWidth,
      int changed, int added, int deleted, int error) {

    /**
     * A grid whose changed, new, deleted and failed rows and cells are tinted as the default
     * theme of its lightness tints them.
     */
    public Grid(float rowHeight, float minColumn, float maxColumn, float handle, float mark,
        int header, int row, int stripe, int selected, int selectedInactive, int cursor,
        float cursorWidth) {
      this(rowHeight, minColumn, maxColumn, handle, mark, header, row, stripe, selected,
          selectedInactive, cursor, cursorWidth,
          lightness(row) < 0.5 ? alpha(BLUE_694, 0.03f) : alpha(BLUE_447, 0.11f),
          lightness(row) < 0.5 ? alpha(GREEN_735, 0.03f) : alpha(GREEN_389, 0.11f),
          lightness(row) < 0.5 ? alpha(RED_649, 0.03f) : alpha(RED_395, 0.094f),
          lightness(row) < 0.5 ? alpha(RED_649, 0.04f) : alpha(RED_395, 0.14f));
    }
  }

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

  /**
   * The buttons at the right of a frameless window's own title bar, which draw no fill at rest.
   *
   * @param closeHover the close button's fill under the pointer, the red of the system's own
   * @param onClose the close button's cross on that fill and on {@code closePressed}
   * @param closePressed the close button's fill while it is pressed, a lighter red, as the
   *     system's
   */
  public record Caption(int closeHover, int onClose, int closePressed) {

    /** A caption whose close button is pressed in a lighter tone of its red. */
    public Caption(int closeHover, int onClose) {
      this(closeHover, onClose, Theme.over(closeHover, alpha(WHITE, 0.12f)));
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
  private static final Sizes SIZE = new Sizes(24, 16, 28, 16, 1, 200, 7, 16, 16, 2, 2, 4, 560);
  private static final Motion MOTION =
      new Motion(Spring.INSTANT, Spring.SNAP, 0.3, 0.5, Spring.INSTANT, 5, 2, 1.2, 1.5);
  private static final Caption CAPTION = new Caption(RED_436, WHITE, RED_485);

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
   * light one) at 25 % and 45 % (dark) or 30 % and 50 % (light), mixed in linear light both by
   * Theme.over and by the renderer, so every component gives the same colour: hover 4 L* or more
   * from the surface, pressed 3 L* or more beyond hover.
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
   * Caption. The close button of a window's own title bar turns the red of Windows 11's under the
   * pointer, in both themes, with a white cross at 4.5:1 or more.
   *
   * Sizes and type. Controls 24, rows 24, text 13; radii 4 for small parts, 6 for controls, 10 for
   * cards; headings semibold, strong text, column titles and buttons medium.
   *
   * Palette. Every colour is one of Palette's, named by its hue and its L* times ten, or one of
   * them at an alpha: alpha(GREY_298, 0.25f) is the dark theme's hover. Tokens that share a colour
   * name the same entry (in the dark theme the band and the grid's header are GREY_099, and the
   * accent's edge, the grid's cursor, a link and the tint of a changed cell BLUE_694); a value
   * that no rule makes from another is an entry of its own, also where it is close to another.
   */

  /** Neutral greys with a blue accent, light text on dark surfaces. */
  public static final Theme DARK =
      new Theme(
          new Surfaces(GREY_047, GREY_083, GREY_122, GREY_099,
              alpha(GREY_298, 0.25f), alpha(GREY_298, 0.45f), alpha(BLACK, 0.6f)),
          new TextColors(GREY_916, GREY_648, GREY_425, WHITE),
          new Borders(GREY_157, GREY_477, GREY_226, alpha(GREY_477, 0.35f)),
          new Accent(BLUE_496, BLUE_469, BLUE_430, alpha(BLUE_496, 0.4f), BLUE_694),
          new Status(RED_649, AMBER_745, GREEN_735),
          SPACE,
          RADIUS,
          TYPE,
          SIZE,
          MOTION,
          new Grid(24, 32, 480, 6, 12, GREY_099, GREY_083, GREY_083, BLUE_180,
              GREY_208, BLUE_694, 1),
          new Scrollbars(6, 2, 3, 24, GREY_568, GREY_655, GREY_759),
          new Elevation(
              List.of(new Shadow(0, 1, 2, 0, alpha(BLACK, 0.8f)),
                  new Shadow(0, 6, 16, 0, alpha(BLACK, 0.55f))),
              List.of(new Shadow(0, 1, 2, 0, alpha(BLACK, 0.6f)),
                  new Shadow(0, 4, 12, 0, alpha(BLACK, 0.5f))),
              List.of(new Shadow(0, 2, 4, 0, alpha(BLACK, 0.6f)),
                  new Shadow(0, 12, 32, 0, alpha(BLACK, 0.6f)))),
          Code.DARK,
          CAPTION);

  /** Neutral greys with a blue accent, dark text on light surfaces. */
  public static final Theme LIGHT =
      new Theme(
          new Surfaces(GREY_941, WHITE, WHITE, GREY_976,
              alpha(GREY_773, 0.3f), alpha(GREY_773, 0.5f), alpha(BLACK, 0.35f)),
          new TextColors(GREY_108, GREY_407, GREY_605, WHITE),
          new Borders(GREY_919, GREY_551, GREY_844, alpha(GREY_551, 0.4f)),
          new Accent(BLUE_447, BLUE_391, BLUE_335, alpha(BLUE_447, 0.35f), BLUE_447),
          new Status(RED_395, AMBER_384, GREEN_389),
          SPACE,
          RADIUS,
          TYPE,
          SIZE,
          MOTION,
          new Grid(24, 32, 480, 6, 12, GREY_976, WHITE, WHITE, BLUE_918,
              GREY_890, BLUE_447, 1),
          new Scrollbars(6, 2, 3, 24, GREY_516, GREY_423, GREY_327),
          new Elevation(
              List.of(new Shadow(0, 1, 2, 0, alpha(GREY_084, 0.16f)),
                  new Shadow(0, 6, 18, 0, alpha(GREY_084, 0.145f))),
              List.of(new Shadow(0, 1, 3, 0, alpha(BLACK, 0.25f)),
                  new Shadow(0, 6, 16, 0, alpha(BLACK, 0.22f))),
              List.of(new Shadow(0, 2, 6, 0, alpha(BLACK, 0.25f)),
                  new Shadow(0, 16, 40, 0, alpha(BLACK, 0.3f)))),
          Code.LIGHT,
          CAPTION);

  public Theme withSurface(Surfaces surface) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code, caption, prose, badge);
  }

  public Theme withText(TextColors text) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code, caption, prose, badge);
  }

  public Theme withBorder(Borders border) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code, caption, prose, badge);
  }

  public Theme withAccent(Accent accent) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code, caption, prose, badge);
  }

  public Theme withStatus(Status status) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code, caption, prose, badge);
  }

  public Theme withSpace(Spacing space) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code, caption, prose, badge);
  }

  public Theme withRadius(Radii radius) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code, caption, prose, badge);
  }

  public Theme withType(Type type) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code, caption, prose, badge);
  }

  public Theme withSize(Sizes size) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code, caption, prose, badge);
  }

  public Theme withMotion(Motion motion) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code, caption, prose, badge);
  }

  public Theme withGrid(Grid grid) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code, caption, prose, badge);
  }

  public Theme withScrollbars(Scrollbars scrollbars) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code, caption, prose, badge);
  }

  public Theme withCode(Code code) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code, caption, prose, badge);
  }

  public Theme withElevation(Elevation elevation) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code, caption, prose, badge);
  }

  public Theme withProse(Prose prose) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code, caption, prose, badge);
  }

  public Theme withBadge(Badges badge) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code, caption, prose, badge);
  }

  public Theme withCaption(Caption caption) {
    return new Theme(
        surface, text, border, accent, status, space, radius, type, size, motion, grid,
        scrollbars, elevation, code, caption, prose, badge);
  }

  /**
   * The colour {@code overlay} gives when laid over the opaque colour {@code base}, such as a
   * control's background with the {@link Surfaces#hover} overlay on it: {@link Colors#over}. It
   * mixes in linear light, as the renderer blends a translucent fill, so a component that draws
   * the overlay translucent and one that fills with this colour look the same.
   */
  public static int over(int base, int overlay) {
    return Colors.over(base, overlay);
  }
}
