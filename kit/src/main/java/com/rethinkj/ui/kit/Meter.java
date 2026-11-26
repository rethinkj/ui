package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.Background;
import com.rethinkj.ui.Colors;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;
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
 * the right end of the track, each on the window's color so that it reads on any fill, and are
 * hidden as a whole when they do not fit beside the segment texts.
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
    double lastStart = 0;
    double lastShare = 0;
    El lastText = null;
    for (int i = 0; i < segments.size(); i++) {
      Segment segment = Objects.requireNonNull(segments.get(i), "segment");
      double share = Math.min(clamp(segment.fraction()), Math.max(0.0, 1.0 - used));
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

      if (share > 0) {
        lastStart = start;
        lastShare = share;
        lastText = text;
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
      track.child(trailingLayer(theme, options.trailing, lastStart, lastShare, lastText, pad));
    }

    return track;
  }

  /**
   * Places trailing badges at the right end of the track in a wrapping overlay clipped to the track
   * height: when the badges do not fit beside the last visible segment's text, Flexbox wraps the
   * entire badge row onto the second line below the clip box so no partial badge shows.
   */
  private static El trailingLayer(
      Theme theme, List<El> badges, double lastStart, double lastShare, El lastText, float pad) {
    El layer = row().key("trailing-layer").absolute(0, 0).wFull().hFull().wrap().clip();
    layer.style().alignContent = Style.Content.FLEX_START;
    boolean hasSegment = lastText != null;
    if (hasSegment) {
      if (lastStart > 0) {
        El lead = el().hFull().shrink(0).minSize(0, 0);
        lead.style().width = Len.percent((float) lastStart);
        layer.child(lead);
      }
      El textRoom = el().hFull()
          .shrink(0)
          .minSize(0, 0)
          .maxSize(TextSizing.natural(lastText) + 2 * pad, Float.POSITIVE_INFINITY);
      textRoom.style().width = Len.percent((float) lastShare);
      layer.child(textRoom);
    }
    layer.child(el().w(0).hFull().grow(1));
    El trailing = row()
        .key("trailing")
        .hFull()
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
    layer.child(trailing);
    return layer;
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
