package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Theme.over;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.Drag;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/**
 * A whole number between two ends, chosen by dragging a knob along a track. The part of the track
 * up to the knob is in the accent. Dragging puts the knob under the pointer, by steps; while the
 * slider has the focus, the arrow keys move it by a step, Page Up and Page Down by a tenth of the
 * range, and Home and End to the ends. A press on the track puts the knob there. The app
 * keeps the number. It is {@link Theme.Sizes#field} wide unless the view sets a width with {@code
 * .w(...)}, and as high as a control. In a tree dump it is {@code role=slider} with its number as {@code value}.
 */
public final class Slider {

  private Slider() {}

  /**
   * @param value the number, from the app's state, kept inside the range when drawn
   * @param step the distance between two numbers the slider stops at, from {@code min}
   * @param onChange told each new number; null disables the slider
   */
  public static El slider(Theme t, int value, int min, int max, int step, IntConsumer onChange) {
    if (min >= max || step < 1) {
      throw new IllegalArgumentException(
          "Needs min < max and a step of 1 or more, was " + min + ", " + max + ", " + step);
    }
    boolean enabled = onChange != null;
    int at = Math.clamp(value, min, max);
    float share = (float) (at - min) / (max - min);
    float knob = t.size().checkbox();
    float track = 2 * t.space().xs();
    // The rail runs between the knob's centre at either end, so that shares of it place the knob
    // at any width.
    El done = el().h(track)
        .radius(track / 2)
        .fill(enabled ? t.accent().color() : t.border().subtle())
        .motion(t.motion().move());
    done.style().width = Len.percent(share);
    int fill = t.surface().raised();
    El thumb = el().size(knob, knob)
        .radius(knob / 2)
        .fill(fill)
        .border(t.size().stroke(), enabled ? t.border().strong() : t.border().subtle())
        .motion(t.motion().move());
    Style place = thumb.style();
    place.position = Style.Position.ABSOLUTE;
    place.left = Len.percent(share);
    place.top = Len.px((track - knob) / 2);
    place.marginLeft = Len.px(-knob / 2);
    El rail = el().h(track)
        .grow(1)
        .radius(track / 2)
        .fill(enabled ? t.border().strong() : t.border().subtle())
        .children(done, thumb);
    El slider = row()
        .role("slider")
        .value(Integer.toString(value))
        .disabled(!enabled)
        .w(t.size().field())
        .h(t.size().control())
        .padding(0, knob / 2)
        .align(Style.Align.CENTER)
        .radius(t.size().control() / 2)
        .child(rail);
    if (!enabled) {
      return slider;
    }
    int page = Math.max(step, (max - min) / 10 / step * step);
    // Puts the knob at x of the slider as the frame the press or drag began in laid it out.
    DoubleConsumer put = x -> {
      double to = (x - knob / 2) / (slider.width() - knob);
      int next = snap(min + to * (max - min), min, max, step);
      if (next != value) {
        onChange.accept(next);
      }
    };
    return slider
        .focusable()
        .keepFocus()
        .focusRing(t.accent().edge())
        .cursor(Cursor.POINTER)
        .onPress((clicks, modifiers, x, y) -> put.accept(x))
        .onDrag(d -> {
          if (d.phase() != Drag.Phase.CANCEL && d.phase() != Drag.Phase.END) {
            put.accept(d.x());
          }
        })
        .onKey(k -> {
          if (k.modifiers() != 0) {
            return false;
          }
          int next = switch (k.name()) {
            case "ArrowLeft", "ArrowDown" -> at - step;
            case "ArrowRight", "ArrowUp" -> at + step;
            case "PageDown" -> at - page;
            case "PageUp" -> at + page;
            case "Home" -> min;
            case "End" -> max;
            case "Enter", "Space" -> at; // taken, or they would press the middle of the track
            default -> Integer.MIN_VALUE;
          };
          if (next == Integer.MIN_VALUE) {
            return false;
          }
          next = snap(next, min, max, step);
          if (next != value) {
            onChange.accept(next);
          }
          return true;
        })
        .whenHovered(e -> thumb.fill(over(fill, t.surface().hover())))
        .whenPressed(e -> thumb
            .fill(over(fill, t.surface().pressed()))
            .border(t.size().stroke(), t.accent().color()));
  }

  /** The stop nearest to {@code v}, inside the range. */
  private static int snap(double v, int min, int max, int step) {
    long stops = Math.round((v - min) / step);
    long snapped = min + stops * step;
    if (snapped > max) {
      snapped -= step; // the last stop before max when the range is no whole number of steps
    }
    return (int) Math.clamp(snapped, min, max);
  }
}
