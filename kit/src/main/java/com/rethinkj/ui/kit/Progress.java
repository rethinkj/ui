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
 * ring and a spinner for small places such as a button, a tab or a row of a tree. None takes the
 * focus or input.
 *
 * <p>A bar is a track of {@link Theme.Sizes#progress} with round ends in {@link
 * Theme.Borders#control}, {@link Theme.Sizes#field} wide unless the view sets a width ({@code
 * .wFull()} or {@code .grow(1)} fills its row), and the part done in the accent's edge, the
 * color of the lines the kit draws in the accent, which stands out 3:1 from the track in both
 * themes; it follows the value with {@link Theme.Motion#move}. A {@link State} gives the part done
 * the danger, success or muted color. A running bar ({@link #indeterminate}) has a piece that
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
 * labeled("orders.csv", state.done / state.size,
 *         mb(state.done) + " of " + mb(state.size) + " · " + left)
 *     .key("export")
 * }</pre>
 */
public final class Progress {

  /** What became of the work a bar shows. */
  public enum State {
    /** It runs: the accent. */
    NORMAL,
    /** It failed: the danger color, and {@code invalid} in a dump. */
    ERROR,
    /** It is done: the success color. */
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
  public static El bar(double fraction) {
    return bar(fraction, State.NORMAL);
  }

  /** A bar with a value, colored by what became of the work. */
  public static El bar(double fraction, State state) {
    Theme theme = Theme.CURRENT.get();
    return states(
            track(theme, theme.size().progress(), true, clamp(fraction), state), fraction, state)
        .w(theme.size().field());
  }

  /** A bar that runs while the length of the work is not known. */
  public static El indeterminate() {
    Theme theme = Theme.CURRENT.get();
    return track(theme, theme.size().progress(), true, Double.NaN, State.NORMAL)
        .role("progressbar")
        .w(theme.size().field());
  }

  /**
   * A line of {@link Theme.Sizes#indicator} along the whole width, with square ends and no track:
   * it lies flush along a card's edge, under a tab bar or a card's header, where it shows that
   * something loads without taking room from what is around it.
   */
  public static El line(double fraction) {
    Theme theme = Theme.CURRENT.get();
    return states(
            track(theme, theme.size().indicator(), false, clamp(fraction), State.NORMAL),
            fraction,
            State.NORMAL)
        .wFull();
  }

  /** A {@link #line} that runs while the length of the work is not known. */
  public static El line() {
    Theme theme = Theme.CURRENT.get();
    return track(theme, theme.size().indicator(), false, Double.NaN, State.NORMAL)
        .role("progressbar")
        .wFull();
  }

  /**
   * A bar under its name, with a text at the right beside the name, in muted text with tabular
   * figures, so that numbers that change do not shake. The whole is the progress bar of a dump,
   * labeled by the name.
   *
   * @param detail such as "12.4 MB of 80 MB · 20 s left"; null for the percentage
   */
  public static El labeled(String label, double fraction, String detail) {
    return labeled(label, fraction, detail, State.NORMAL);
  }

  /** As {@link #labeled(String, double, String)}, colored by what became of the work. */
  public static El labeled(String label, double fraction, String detail, State state) {
    Theme theme = Theme.CURRENT.get();
    Theme.TextStyle body = theme.type().body();
    El name = El.text(label)
        .font(body.font().get())
        .fontSize(body.size())
        .color(theme.text().primary())
        .ellipsis()
        .minSize(0, 0)
        .shrink(1);
    El text = El.text(detail == null ? percent(fraction) : detail)
        .font(body.font().get())
        .fontSize(body.size())
        .fontFeatures("+tnum")
        .color(state == State.ERROR ? theme.status().danger() : theme.text().muted())
        .shrink(0);
    El top =
        row().gap(theme.space().m()).align(Style.Align.CENTER).children(name, el().grow(1), text);
    El bar = track(theme, theme.size().progress(), true, clamp(fraction), state).wFull();
    return states(
            col().gap(theme.space().xs()).w(theme.size().field()).children(top, bar),
            fraction,
            state)
        .label(label);
  }

  /**
   * A ring of {@link Theme.Sizes#icon} that fills clockwise from the top as the work goes on, for
   * a place too small for a bar, such as a button: the faint ring of a {@link #spinner}, and the
   * part done over it in the accent's edge, which follows the value with {@link
   * Theme.Motion#move}. In a tree dump it is a bar, {@code role=progressbar} with its value, and
   * the part done is {@code #fill}.
   *
   * @param fraction how much is done, from 0 to 1, clamped; NaN is 0
   */
  public static El ring(double fraction) {
    Theme theme = Theme.CURRENT.get();
    return ring(
        theme,
        theme.size().icon(),
        fraction,
        theme.accent().edge(),
        theme.border().track());
  }

  /** A ring in other colors, as on a primary button. */
  static El ring(Theme theme, float size, double fraction, int arc, int ring) {
    float width = size / 8; // as a spinner's
    El fill = el().key("fill")
        .absolute(0, 0)
        .size(size, size)
        .radius(size / 2)
        .border(width, arc)
        .arc(0, (float) clamp(fraction))
        .motion(theme.motion().move());
    return states(
        el().size(size, size)
            .shrink(0)
            .child(el().absolute(0, 0).size(size, size).radius(size / 2).border(width, ring))
            .child(fill),
        fraction,
        State.NORMAL);
  }

  /** A spinner of {@link Theme.Sizes#icon}, labeled "Loading". */
  public static El spinner() {
    Theme theme = Theme.CURRENT.get();
    return spinner(theme.size().icon());
  }

  /**
   * A spinner: a faint ring in {@link Theme.Borders#track}, which shows on every surface and
   * selection, round which a light arc in the accent's
   * edge runs once per {@link Theme.Motion#spin} seconds. The arc is four crescents, each the
   * ring's box with a border on one side alone, which tapers to nothing a quarter of a turn either
   * way; their opacities follow the light round, each fading into the next, so it moves smoothly
   * rather than from one spoke to the next. Labeled "Loading" unless the view sets another label.
   *
   * @param size the side, such as {@code size().icon()} for a button or a row
   */
  public static El spinner(float size) {
    Theme theme = Theme.CURRENT.get();
    return spinner(theme, size, theme.accent().edge(), theme.border().track());
  }

  /** A spinner in other colors, as on a primary button. */
  static El spinner(Theme theme, float size, int arc, int ring) {
    float width = size / 8; // 2 at 16, the stroke of the icons drawn for 16
    double period = theme.motion().spin();
    El spinner = el().role("progressbar")
        .label(Texts.CURRENT.get().busy())
        .size(size, size)
        .shrink(0)
        .child(el().absolute(0, 0).size(size, size).radius(size / 2).border(width, ring));
    // top, right, bottom, left: each a border on one side of the ring's box, which tapers to
    // nothing a quarter of a turn either way; a box with two sides would light three quarters
    for (int i = 0; i < 4; i++) {
      double at = i / 4.0;
      spinner.child(el().absolute(0, 0)
          .size(size, size)
          .radius(size / 2)
          .border(
              i == 0 ? width : 0, i == 1 ? width : 0, i == 2 ? width : 0, i == 3 ? width : 0, arc)
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
   * The track with its part done, or with a running piece when {@code fraction} is NaN, which the
   * forms with a value therefore never pass; the piece is {@code #fill} in both, so that the one
   * becomes the other.
   */
  private static El track(
      Theme theme, float thickness, boolean round, double fraction, State state) {
    float radius = round ? thickness / 2 : 0;
    int color = switch (state) {
      case NORMAL -> theme.accent().edge();
      case ERROR -> theme.status().danger();
      case DONE -> theme.status().success();
      case PAUSED -> theme.text().muted();
    };
    El fill = el().key("fill")
        .absolute(0, 0)
        .hFull()
        .radius(radius)
        .fill(color)
        .motion(theme.motion().move());
    if (Double.isNaN(fraction)) {
      fill.wFull().cycle(theme.motion().progress(), (e, phase) -> {
        // The front leads the back by a fifth of the pass, both easing in and out.
        double front = ease(Math.min(1, phase / 0.8));
        double back = ease(Math.max(0, (phase - 0.2) / 0.8));
        float w = e.width();
        e.place(e.x() + (float) (back * w), e.y(), (float) ((front - back) * w), e.height());
      });
    } else {
      fill.style().width = Len.percent((float) fraction);
    }
    return el().h(thickness)
        .shrink(0)
        .radius(radius)
        .clip()
        .fill(round ? theme.border().control() : 0)
        .child(fill);
  }

  /** Cubic easing in and out, from 0 to 1. */
  private static double ease(double x) {
    return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
  }
}
