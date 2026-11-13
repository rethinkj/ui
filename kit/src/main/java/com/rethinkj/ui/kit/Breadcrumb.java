package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Where something lies, as a path of parts that go there, separated by chevrons: {@code localhost
 * › shop › public › orders}. The last part is the current place, which takes no press. Where the
 * path does not fit, it is shortened at the start: an ellipsis stands for the parts left out and
 * opens a menu of them. The breadcrumb learns its width and the width of each part from the layout
 * ({@link El#onResize}) and keeps them in its {@link State}, so it settles a frame after its width
 * changes.
 *
 * <p>It is one stop for Tab, at the part the arrows last went to; Arrow Left and Right go between
 * the parts and the ellipsis, Home and End to the ends, and Enter presses the part. A press leaves
 * the focus where it was. In a tree dump it is {@code role=navigation label="Breadcrumb"}, each
 * part {@code role=link} with its text as the label, the last one {@code current}, and the
 * ellipsis {@code role=button label="Hidden parts"}.
 */
public final class Breadcrumb {

  private Breadcrumb() {}

  /**
   * What a breadcrumb keeps between builds, held by the app, one per breadcrumb: the widths, the
   * menu of the hidden parts and the part Tab reaches.
   */
  public static final class State {
    final Menu.State hidden = new Menu.State();
    float width = Float.NaN;
    final Map<String, Float> widths = new HashMap<>();
    final Roving keys = new Roving();

    /** The menu of the parts the ellipsis stands for. */
    public Menu.State hidden() {
      return hidden;
    }
  }

  /**
   * @param parts the path from its start to the current place, at least one
   * @param onSelect told the index of the part the user goes to, never the last; null for a
   *     path that only shows
   */
  public static El breadcrumb(Theme t, State state, List<String> parts, IntConsumer onSelect) {
    if (parts.isEmpty()) {
      throw new IllegalArgumentException("A breadcrumb needs at least the current place.");
    }
    float gap = t.space().xs();
    int first = first(t, state, parts, gap);
    // grow(1) fills a row's width; the most height keeps it a row high in a column with room.
    El bar = row()
        .role("navigation")
        .label("Breadcrumb")
        .h(t.size().control())
        .grow(1)
        .minSize(0, t.size().control())
        .maxSize(Float.POSITIVE_INFINITY, t.size().control())
        .gap(gap)
        .align(Style.Align.CENTER)
        .clip()
        .onResize((w, h) -> state.width = w);
    List<El> stops = new ArrayList<>();
    if (first > 0) {
      List<Menu.Item> items = new ArrayList<>();
      for (int i = 0; i < first; i++) {
        int at = i;
        items.add(Menu.item(parts.get(i), onSelect == null ? null : () -> onSelect.accept(at)));
      }
      El more = part(t, "…", null, state.hidden::toggle)
          .key("more")
          .role("button")
          .label("Hidden parts")
          .onResize((w, h) -> state.widths.put("more", w));
      stops.add(more);
      bar.child(Menu.dropdown(t, more, state.hidden, items));
    }
    for (int i = first; i < parts.size(); i++) {
      int at = i;
      boolean current = i == parts.size() - 1;
      El unit = row()
          .key("crumb-" + i)
          .gap(gap)
          .align(Style.Align.CENTER)
          .shrink(0)
          .onResize((w, h) -> state.widths.put(id(parts, at), w));
      if (i > 0) {
        unit.child(Icon.icon(
            t, Symbol.CHEVRON_RIGHT, Icon.Variant.MUTED, t.badge().text().size()));
      }
      if (current) {
        Theme.TextStyle style = t.type().strong();
        unit.child(row()
            .role("link")
            .label(parts.get(i))
            .current(true)
            .h(t.size().control())
            .padding(0, t.space().s())
            .align(Style.Align.CENTER)
            .child(El.text(parts.get(i))
                .font(style.font().get())
                .fontSize(style.size())
                .color(t.text().primary())));
      } else {
        El link = part(
                t, parts.get(i), parts.get(i), onSelect == null ? null : () -> onSelect.accept(at))
            .role("link");
        if (onSelect != null) {
          stops.add(link);
        }
        unit.child(link);
      }
      bar.child(unit);
    }
    state.keys.apply(bar, stops);
    return bar;
  }

  /** A pressable part: muted text, primary under the pointer, on the hover's fill. */
  private static El part(Theme t, String text, String label, Runnable onPress) {
    El el = row()
        .label(label)
        .h(t.size().control())
        .padding(0, t.space().s())
        .align(Style.Align.CENTER)
        .radius(t.radius().small())
        .shrink(0)
        .disabled(onPress == null)
        .motion(t.motion().feedback());
    Theme.TextStyle style = t.type().body();
    El words = El.text(text)
        .font(style.font().get())
        .fontSize(style.size())
        .color(onPress == null ? t.text().disabled() : t.text().muted());
    el.child(words);
    if (onPress == null) {
      return el;
    }
    return el.focusable()
        .keepFocus()
        .focusRing(t.accent().edge())
        .cursor(Cursor.POINTER)
        .onPress(onPress)
        .whenHovered(e -> {
          e.fill(t.surface().hover());
          words.color(t.text().primary());
        })
        .whenPressed(e -> e.fill(t.surface().pressed()));
  }

  private static String id(List<String> parts, int i) {
    return i + ":" + parts.get(i);
  }

  /**
   * The first part that fits with the ellipsis before it, the current place always among them,
   * as the last layout gave the widths; 0, the whole path, until every width is known.
   */
  private static int first(Theme t, State state, List<String> parts, float gap) {
    if (Float.isNaN(state.width)) {
      return 0;
    }
    float[] widths = new float[parts.size()];
    float total = 0;
    for (int i = 0; i < parts.size(); i++) {
      Float w = state.widths.get(id(parts, i));
      if (w == null) {
        return 0;
      }
      widths[i] = w + (i == 0 ? 0 : gap);
      total += widths[i];
    }
    if (total <= state.width) {
      return 0;
    }
    // The first part shown gets the separator before it, as each after the first does.
    float room = state.width - state.widths.getOrDefault("more", t.size().control()) - gap;
    int first = 0;
    while (first < parts.size() - 1 && total > room) {
      total -= widths[first];
      first++;
    }
    return first;
  }
}
