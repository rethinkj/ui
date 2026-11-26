package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.Background;
import com.rethinkj.ui.Colors;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Measure;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A horizontal bar for a measured amount in a known range, such as a quota, a budget or how storage
 * is split: ordered {@link Segment}s stacked from the left, each showing its name and value in
 * whole percent ({@link Progress#percent}) with tabular figures inside its fill, ellipsized when
 * narrow; an optional diagonal hatch over the empty rest of the track, one marker line across the
 * track at a reference fraction, and trailing badges at the bar's right end. It does not take focus
 * or input.
 *
 * <p>The track is {@link Theme.Sizes#controlLarge} high with {@link Theme.Radii#medium} corners in
 * {@link Theme.Borders#control}, fills its row ({@code .wFull()}) unless the view gives it a width,
 * and clips its parts. Segments take their fills from {@link Theme.Series} by index, wrapped modulo
 * the list's size, or a color of their own, laid over the plain track, on which the text keeps the series text color
 * while it reads at 4.5:1 and otherwise takes the primary text, white or black, whichever reads
 * ({@link Colors#contrast}). Segments and the marker
 * spring to new values with {@link Theme.Motion#move}. Trailing elements sit vertically centered at
 * the right end of the track, each on the window's color so that it reads on any fill, hide as a
 * whole when they do not fit beside the segment texts, and slide in from the right with {@link
 * Theme.Motion#move} as space opens.
 *
 * <p>In a tree dump the bar is {@code role=meter} with its {@link El#label label} (if set) and the
 * whole percent of all segments together as {@code value}. Each segment is a keyed child ({@code
 * #segment-0}, {@code #segment-1}, …) with its name as {@code label} and its share as {@code
 * value}; the marker, when present, is {@code #marker} with its label (defaulting to {@link
 * Texts#marker}) and fraction as {@code value}.
 *
 * <pre>{@code
 * Meter.bar(
 *         List.of(
 *             new Meter.Segment("Compute", 0.42, 0),
 *             new Meter.Segment("Storage", 0.24, 1)),
 *         Meter.options()
 *             .hatched()
 *             .marker(0.8, "Soft limit")
 *             .trailing(Badge.badge("66%")))
 *     .label("Monthly quota")
 * }</pre>
 */
public final class Meter {

  private static final float HATCH_ANGLE = -45f;

  private Meter() {}

  /**
   * One ordered slice of a {@link Meter}, measured as a share of the whole track.
   *
   * @param name the segment's name, shown inside its fill and used as its label in a tree dump
   * @param fraction share of the whole track, from 0 to 1, clamped; NaN is 0, and the sum of all
   *     segments is capped at 1
   * @param series index into {@link Theme.Series#colors}, wrapped modulo the list's size, ignored
   *     when {@code color} is set
   * @param color ARGB fill color, laid over the plain track, or null to use {@code series}
   */
  public record Segment(String name, double fraction, int series, Integer color) {

    public Segment {
      Objects.requireNonNull(name, "name");
    }

    /** A segment colored from {@link Theme.Series} at {@code series}. */
    public Segment(String name, double fraction, int series) {
      this(name, fraction, series, null);
    }

    /** This segment with an ARGB fill color instead of its series color. */
    public Segment color(int argb) {
      return new Segment(name, fraction, series, argb);
    }
  }

  /** Optional hatch, marker line and trailing badges of a {@link Meter}. */
  public static final class Options {
    private boolean hatched;
    private boolean hasMarker;
    private double markerFraction;
    private String markerLabel;
    private final List<El> trailing = new ArrayList<>();

    private Options() {}

    /** Draws diagonal stripes over the empty rest of the track. */
    public Options hatched() {
      this.hatched = true;
      return this;
    }

    /**
     * Places a marker line across the track at {@code fraction}, labeled with {@link Texts#marker}.
     *
     * @param fraction position along the track, from 0 to 1, clamped; NaN is 0
     */
    public Options marker(double fraction) {
      return marker(fraction, null);
    }

    /**
     * Places a marker line across the track at {@code fraction}.
     *
     * @param fraction position along the track, from 0 to 1, clamped; NaN is 0
     * @param label accessible label in a tree dump; null uses {@link Texts#marker}
     */
    public Options marker(double fraction, String label) {
      this.hasMarker = true;
      this.markerFraction = fraction;
      this.markerLabel = label;
      return this;
    }

    /**
     * Adds trailing elements, such as {@link Badge#badge}s, at the right end of the bar. They are
     * hidden as a whole when they do not fit beside the segment texts.
     */
    public Options trailing(El... badges) {
      Objects.requireNonNull(badges, "badges");
      for (El badge : badges) {
        if (badge != null) {
          this.trailing.add(badge);
        }
      }
      return this;
    }
  }

  /** Default options: plain track, no marker, no trailing badges. */
  public static Options options() {
    return new Options();
  }

  /** A meter with default {@link #options()}. */
  public static El bar(List<Segment> segments) {
    return bar(segments, options());
  }

  /**
   * A horizontal stacked meter bar.
   *
   * @param segments ordered slices stacked from the left; may be empty
   * @param options hatch, marker line and trailing elements
   */
  public static El bar(List<Segment> segments, Options options) {
    Objects.requireNonNull(segments, "segments");
    Objects.requireNonNull(options, "options");
    Theme theme = Theme.CURRENT.get();
    float height = theme.size().controlLarge();
    int trackFill = theme.border().control();
    float pad = theme.space().m();
    Theme.TextStyle textStyle = theme.badge().text();
    Theme.Series series = theme.series();

    El track = row()
        .role("meter")
        .wFull()
        .h(height)
        .minSize(0, height)
        .radius(theme.radius().medium())
        .clip()
        .fill(trackFill);
    if (options.hatched) {
      float stripe = theme.size().stroke();
      float gap = theme.space().s() + theme.size().stroke();
      track.background(Background.stripes(theme.border().track(), stripe, gap, HATCH_ANGLE));
    }

    double used = 0;
    List<Slice> slices = options.trailing.isEmpty() ? List.of() : new ArrayList<>(segments.size());
    for (int i = 0; i < segments.size(); i++) {
      Segment segment = Objects.requireNonNull(segments.get(i), "segment");
      double share = Math.clamp(clamp(segment.fraction()), 0.0, Math.max(0.0, 1.0 - used));
      double start = used;
      used += share;

      // a translucent color of its own lies over the plain track, not over the hatch
      int fill = segment.color() != null
          ? Colors.over(trackFill, segment.color())
          : series.color(segment.series());
      int ink = segment.color() != null ? inkOn(theme, fill) : series.text();
      String pct = Progress.percent(share);
      String display = segment.name().isEmpty() ? pct : segment.name() + " " + pct;

      El text = El.text(display)
          .font(textStyle.font().get())
          .fontSize(textStyle.size())
          .fontFeatures("+tnum")
          .color(ink)
          .ellipsis()
          .minSize(0, 0)
          .shrink(1)
          .margin(0, pad, 0, pad);

      El bar = row()
          .key("segment-" + i)
          .label(segment.name())
          .value(pct)
          .hFull()
          .shrink(0)
          .minSize(0, 0)
          .align(Style.Align.CENTER)
          .clip()
          .fill(fill)
          .motion(theme.motion().move())
          .child(text);
      bar.style().width = Len.percent((float) share);
      track.child(bar);

      if (!options.trailing.isEmpty() && share > 0) {
        slices.add(new Slice(start, share, text));
      }
    }

    // 0.6 + 0.3 + 0.1 adds up to just under 1, which would read as 99%
    track.value(Progress.percent(used > 1 - 1e-9 ? 1 : used));

    if (options.hasMarker) {
      double at = clamp(options.markerFraction);
      String label = options.markerLabel != null
          ? options.markerLabel
          : Texts.CURRENT.get().marker();
      float line = theme.size().indicator();
      float outline = theme.size().stroke();
      float width = line + 2 * outline;
      El marker = el().key("marker")
          .label(label)
          .value(Progress.percent(at))
          .w(width)
          .hFull()
          .fill(theme.text().primary())
          .border(0, outline, 0, outline, theme.surface().window())
          .motion(theme.motion().move());
      Style place = marker.style();
      place.position = Style.Position.ABSOLUTE;
      place.left = Len.percent((float) at);
      place.top = Len.ZERO;
      // centered on its fraction, but shifted inward at the ends so the track does not clip it
      place.marginLeft = Len.px((float) (-width * at));
      track.child(marker);
    }

    if (!options.trailing.isEmpty()) {
      addTrailing(track, theme, options.trailing, slices, pad);
    }

    return track;
  }

  /** One non-empty segment's span and text element, for sizing the room reserved ahead of badges. */
  private record Slice(double start, double share, El text) {}

  /**
   * Adds the trailing badges at the right end of the track, in an overlay clipped to the track: a
   * column that wraps, holding a row as high as the track that wraps too. When the badges do not
   * fit beside the rightmost segment text that actually draws glyphs, they wrap onto the row's
   * second line, and the row, now twice as high, wraps into a second column right of the track, so
   * no partial badge shows.
   *
   * <p>The row springs with {@link Theme.Motion#move}, as the segments do: when room opens, the
   * badges slide in from the right edge over the whole width of the track, farther than any label
   * travels, so they never pass over one; when room closes, they drop onto the hidden second line
   * at once.
   *
   * <p>A probe laid out over the track ahead of the overlay hands the track's pixel size to the
   * reserve element, since CSS flexbox measures auto-width items in a row against {@link
   * Measure#MAX_CONTENT}. Because the track lays out its in-flow segments before its absolute
   * children, each segment's {@link Text#lines()} already reflects whether any glyph fit inside the
   * segment.
   */
  private static void addTrailing(
      El track, Theme theme, List<El> badges, List<Slice> slices, float pad) {
    El trailing = row()
        .key("trailing")
        .shrink(0)
        .align(Style.Align.CENTER)
        .gap(theme.space().s())
        .margin(0, pad, 0, pad);
    for (El badge : badges) {
      // on the window's color, so that a quiet, translucent badge reads on any segment's fill
      trailing.child(el().shrink(0)
          .radius(theme.radius().small())
          .fill(theme.surface().window())
          .child(badge));
    }
    if (slices.isEmpty()) {
      El layer =
          row().key("trailing-layer").absolute(0, 0).wFull().hFull().wrap().clip();
      layer.style().alignContent = Style.Content.FLEX_START;
      track.child(layer.child(el().w(0).hFull().grow(1)).child(trailing));
      return;
    }

    float[] trackWidth = {Float.NaN};
    float[] trackHeight = {0};
    // laid out before the layer, like every earlier absolute child of the track
    El probe = el().absolute(0, 0).wFull().hFull().measure((knownW, knownH, availW, availH) -> {
      float width = Float.isNaN(knownW) ? availW : knownW;
      float height = Float.isNaN(knownH) ? availH : knownH;
      if (Float.isFinite(width)) {
        trackWidth[0] = width;
      }
      if (Float.isFinite(height)) {
        trackHeight[0] = height;
      }
      return new Measure.Size(0, 0);
    });
    // as high as the track, so that one line fills the first column and a second one wraps away
    El reserve = el().shrink(0).minSize(0, 0).measure((knownW, knownH, availW, availH) -> {
      if (availW == Measure.MIN_CONTENT) {
        return new Measure.Size(0, trackHeight[0]);
      }
      return new Measure.Size(reservedWidth(slices, trackWidth[0], pad), trackHeight[0]);
    });
    El row = row().wFull().shrink(0).minSize(0, 0).wrap().motion(theme.motion().move());
    row.style().alignContent = Style.Content.FLEX_START;
    row.child(reserve).child(el().w(0).grow(1)).child(trailing);

    El layer = col()
        .key("trailing-layer")
        .absolute(0, 0)
        .wFull()
        .hFull()
        .wrap()
        .clip()
        .child(el().wFull()) // fills the first column to the track's width
        .child(row);
    layer.style().alignContent = Style.Content.FLEX_START;
    track.child(probe).child(layer);
  }

  /**
   * Width to keep clear of trailing badges: up to the end of the rightmost segment label that
   * actually draws glyphs, or 0 when none does.
   */
  private static float reservedWidth(List<Slice> slices, float width, float pad) {
    for (int i = slices.size() - 1; i >= 0; i--) {
      Slice slice = slices.get(i);
      if (!draws(slice.text)) {
        continue;
      }
      double natural = TextSizing.measured(slice.text) + 2.0 * pad;
      if (!Float.isFinite(width)) {
        return (float) natural;
      }
      double reserved = slice.start * width + Math.min(slice.share * width, natural);
      return (float) Math.min(width, reserved);
    }
    return 0;
  }

  /** Whether a laid-out segment text drew at least one glyph; true while not yet laid out. */
  private static boolean draws(El text) {
    List<Text.Line> lines = text.text().lines();
    if (lines.isEmpty()) {
      return true;
    }
    for (Text.Line line : lines) {
      if (line.glyphs().count() > 0) {
        return true;
      }
    }
    return false;
  }

  /**
   * The series text on {@code fill} if it reads at 4.5:1, otherwise the first of the primary text,
   * white and black that reads better.
   */
  private static int inkOn(Theme theme, int fill) {
    int ink = theme.series().text();
    for (int other : new int[] {theme.text().primary(), Colors.WHITE, Colors.BLACK}) {
      if (contrast(ink, fill) < 4.5 && contrast(other, fill) > contrast(ink, fill)) {
        ink = other;
      }
    }
    return ink;
  }

  /** The contrast of {@code ink} as it shows on {@code fill}, translucent ink laid over it. */
  private static double contrast(int ink, int fill) {
    return Colors.contrast(Colors.over(fill, ink), fill);
  }

  private static double clamp(double fraction) {
    return Double.isNaN(fraction) ? 0 : Math.clamp(fraction, 0.0, 1.0);
  }
}
