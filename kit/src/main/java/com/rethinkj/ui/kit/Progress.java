package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;

/**
 * How far work has come, or that it runs: a bar with a value, a bar that runs while the length of
 * the work is not known, a thin line along a card's edge, a bar with its name and a text, and a
 * spinner for small places such as a button, a tab or a row of a tree. None takes the focus or
 * input.
 *
 * <p>A bar is a track of {@link Theme.Sizes#progress} with round ends in {@link
 * Theme.Borders#control}, {@link Theme.Sizes#field} wide unless the view sets a width ({@code
 * .wFull()} or {@code .grow(1)} fills its row), and the part done in the accent's edge, the
 * colour of the lines the kit draws in the accent, which stands out 3:1 from the track in both
 * themes; it follows the value with {@link Theme.Motion#move}. A {@link State} gives the part done
 * the danger, success or muted colour. A running bar ({@link #indeterminate}) has a piece that
 * grows out of the track's start, sweeps along it and shrinks into its end, its front ahead of
 * its back, easing in and out, once per {@link Theme.Motion#progress} seconds ({@link El#cycle}):
 * a calm sweep, as Fluent's single sliding piece, with the stretch of Material's line. A bar and
 * a running bar with the same key are the same element: when the app learns the size of the
 * work, the piece springs from where it ran to the value instead of jumping.
 *
 * <p>In a tree dump a bar is {@code role=progressbar} with the value in whole percent, as {@code
 * value="42%"} (100% only once it is done), a running bar without a value, and one in {@link
 * State#ERROR} is {@code invalid}. The part done is the child {@code #fill}.
 *
 * <pre>{@code
 * labeled(t, "orders.csv", s.done / s.size, mb(s.done) + " of " + mb(s.size) + " · " + left)
 *     .key("export")
 * }</pre>
 */
public final class Progress {

  /** What became of the work a bar shows. */
  public enum State {
    /** It runs: the accent. */
    NORMAL,
    /** It failed: the danger colour, and {@code invalid} in a dump. */
    ERROR,
    /** It is done: the success colour. */
    DONE,
    /** It waits for the user or was paused: muted. */
    PAUSED
  }

  private Progress() {}

  /**
   * A bar with a value.
   *
   * @param fraction how much is done, from 0 to 1, clamped; NaN is 0
   */
  public static El bar(Theme t, double fraction) {
    return bar(t, fraction, State.NORMAL);
  }

  /** A bar with a value, coloured by what became of the work. */
  public static El bar(Theme t, double fraction, State state) {
    return states(track(t, t.size().progress(), true, fraction, state), fraction, state)
        .w(t.size().field());
  }

  /** A bar that runs while the length of the work is not known. */
  public static El indeterminate(Theme t) {
    return track(t, t.size().progress(), true, Double.NaN, State.NORMAL)
        .role("progressbar").w(t.size().field());
  }

  /**
   * A line of {@link Theme.Sizes#indicator} along the whole width, with square ends and no track:
   * it lies flush along a card's edge, under a tab bar or a card's header, where it shows that
   * something loads without taking room from what is around it.
   */
  public static El line(Theme t, double fraction) {
    return states(track(t, t.size().indicator(), false, fraction, State.NORMAL), fraction,
        State.NORMAL).wFull();
  }

  /** A {@link #line} that runs while the length of the work is not known. */
  public static El line(Theme t) {
    return track(t, t.size().indicator(), false, Double.NaN, State.NORMAL).role("progressbar")
        .wFull();
  }

  /**
   * A bar under its name, with a text at the right beside the name, in muted text with tabular
   * figures, so that numbers that change do not shake. The whole is the progress bar of a dump,
   * labelled by the name.
   *
   * @param detail such as "12.4 MB of 80 MB · 20 s left"; null for the percentage
   */
  public static El labeled(Theme t, String label, double fraction, String detail) {
    return labeled(t, label, fraction, detail, State.NORMAL);
  }

  /** As {@link #labeled(Theme, String, double, String)}, coloured by what became of the work. */
  public static El labeled(Theme t, String label, double fraction, String detail, State state) {
    Theme.TextStyle body = t.type().body();
    El name = El.text(label).font(body.font().get()).fontSize(body.size())
        .color(t.text().primary()).ellipsis().minSize(0, 0).shrink(1);
    El text = El.text(detail == null ? percent(fraction) : detail).font(body.font().get())
        .fontSize(body.size()).fontFeatures("+tnum")
        .color(state == State.ERROR ? t.status().danger() : t.text().muted()).shrink(0);
    El top = row().gap(t.space().m()).align(Style.Align.CENTER).children(name, el().grow(1),
        text);
    El bar = track(t, t.size().progress(), true, fraction, state).wFull();
    return states(col().gap(t.space().xs()).w(t.size().field()).children(top, bar), fraction,
        state).label(label);
  }

  /** A spinner of {@link Theme.Sizes#icon}, labelled "Loading". */
  public static El spinner(Theme t) {
    return spinner(t, t.size().icon());
  }

  /**
   * A spinner: a faint ring in {@link Theme.Borders#track}, which shows on every surface and
   * selection, round which a light arc in the accent's
   * edge runs once per {@link Theme.Motion#spin} seconds. The core has no rotation and no arcs, so
   * the arc is four crescents, each the ring's box with a border on one side alone, which tapers
   * to nothing a quarter of a turn either way; their opacities follow the light round, each fading
   * into the next, so it moves smoothly rather than from one spoke to the next. Labelled "Loading" unless the view sets
   * another label.
   *
   * @param size the side, such as {@code size().icon()} for a button or a row
   */
  public static El spinner(Theme t, float size) {
    return spinner(t, size, t.accent().edge(), t.border().track());
  }

  /** A spinner in other colours, as on a primary button. */
  static El spinner(Theme t, float size, int arc, int ring) {
    float width = size / 8; // 2 at 16, the stroke of the icons drawn for 16
    double period = t.motion().spin();
    El spinner = el().role("progressbar").label("Loading").size(size, size).shrink(0)
        .child(el().absolute(0, 0).size(size, size).radius(size / 2).border(width, ring));
    // top, right, bottom, left: each a border on one side of the ring's box, which tapers to
    // nothing a quarter of a turn either way; a box with two sides would light three quarters
    for (int i = 0; i < 4; i++) {
      double at = i / 4.0;
      spinner.child(el().absolute(0, 0).size(size, size).radius(size / 2)
          .border(i == 0 ? width : 0, i == 1 ? width : 0, i == 2 ? width : 0, i == 3 ? width : 0,
              arc)
          .cycle(period, (e, phase) -> {
            double away = Math.abs(((phase - at) % 1 + 1.5) % 1 - 0.5); // 0 to 1/2 of a turn
            e.opacity((float) Math.max(0, Math.cos(Math.PI * away * 2)));
          }));
    }
    return spinner;
  }

  /**
   * The value in whole percent, as a dump has it: 100% only at 1, so that a bar shows that it is
   * not quite done.
   */
  public static String percent(double fraction) {
    double f = clamp(fraction);
    return (f >= 1 ? 100 : Math.min(99, Math.round(f * 100))) + "%";
  }

  private static double clamp(double fraction) {
    return Double.isNaN(fraction) ? 0 : Math.clamp(fraction, 0.0, 1.0);
  }

  private static El states(El el, double fraction, State state) {
    return el.role("progressbar").value(percent(fraction)).invalid(state == State.ERROR);
  }

  /**
   * The track with its part done, or with a running piece when {@code fraction} is NaN; the
   * piece is {@code #fill} in both, so that the one becomes the other.
   */
  private static El track(Theme t, float thickness, boolean round, double fraction, State state) {
    float radius = round ? thickness / 2 : 0;
    int colour = switch (state) {
      case NORMAL -> t.accent().edge();
      case ERROR -> t.status().danger();
      case DONE -> t.status().success();
      case PAUSED -> t.text().muted();
    };
    El fill = el().key("fill").absolute(0, 0).hFull().radius(radius).fill(colour)
        .motion(t.motion().move());
    if (Double.isNaN(fraction)) {
      fill.wFull().cycle(t.motion().progress(), (e, phase) -> {
        // The front leads the back by a fifth of the pass, both easing in and out.
        double front = ease(Math.min(1, phase / 0.8));
        double back = ease(Math.max(0, (phase - 0.2) / 0.8));
        float w = e.width();
        e.place(e.x() + (float) (back * w), e.y(), (float) ((front - back) * w), e.height());
      });
    } else {
      fill.style().width = Len.percent((float) clamp(fraction));
    }
    return el().h(thickness).shrink(0).radius(radius).clip()
        .fill(round ? t.border().control() : 0).child(fill);
  }

  /** Cubic easing in and out, from 0 to 1. */
  private static double ease(double x) {
    return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
  }
}
