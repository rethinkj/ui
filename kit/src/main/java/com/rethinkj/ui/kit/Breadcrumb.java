package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import java.util.ArrayList;
import java.util.List;
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
    final Fitting fitting = new Fitting();
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
  public static El breadcrumb(State state, List<String> parts, IntConsumer onSelect) {
    Theme theme = Theme.CURRENT.get();
    if (parts.isEmpty()) {
      throw new IllegalArgumentException("A breadcrumb needs at least the current place.");
    }
    float gap = theme.space().xs();
    int first = parts.size()
        - state.fitting.fit(
            theme,
            parts.size(),
            Fitting.Direction.SUFFIX,
            1,
            i -> state.fitting.width(id(parts, i)));
    El bar = state.fitting.row(theme).role("navigation").label("Breadcrumb");
    List<El> stops = new ArrayList<>();
    if (first > 0) {
      List<Menu.Item> items = new ArrayList<>();
      for (int i = 0; i < first; i++) {
        int at = i;
        items.add(Menu.item(parts.get(i), onSelect == null ? null : () -> onSelect.accept(at)));
      }
      El more = part(theme, "…", null, state.hidden::toggle)
          .key("more")
          .role("button")
          .label("Hidden parts")
          .onResize(state.fitting.measure("more"));
      stops.add(more);
      bar.child(Menu.dropdown(more, state.hidden, items));
    }
    for (int i = first; i < parts.size(); i++) {
      int at = i;
      boolean current = i == parts.size() - 1;
      El unit = row()
          .key("crumb-" + i)
          .gap(gap)
          .align(Style.Align.CENTER)
          .shrink(0)
          .onResize(state.fitting.measure(id(parts, at)));
      if (i > 0) {
        unit.child(Icon.icon(
            Symbol.CHEVRON_RIGHT, Icon.Variant.MUTED, theme.badge().text().size()));
      }
      if (current) {
        Theme.TextStyle style = theme.type().strong();
        unit.child(row()
            .role("link")
            .label(parts.get(i))
            .current(true)
            .h(theme.size().control())
            .padding(0, theme.space().s())
            .align(Style.Align.CENTER)
            .child(El.text(parts.get(i))
                .font(style.font().get())
                .fontSize(style.size())
                .color(theme.text().primary())));
      } else {
        El link = part(
                theme,
                parts.get(i),
                parts.get(i),
                onSelect == null ? null : () -> onSelect.accept(at))
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
  private static El part(Theme theme, String text, String label, Runnable onPress) {
    El el = row()
        .label(label)
        .h(theme.size().control())
        .padding(0, theme.space().s())
        .align(Style.Align.CENTER)
        .radius(theme.radius().small())
        .shrink(0)
        .disabled(onPress == null)
        .motion(theme.motion().feedback());
    Theme.TextStyle style = theme.type().body();
    El words = El.text(text)
        .font(style.font().get())
        .fontSize(style.size())
        .color(onPress == null ? theme.text().disabled() : theme.text().muted());
    el.child(words);
    if (onPress == null) {
      return el;
    }
    return el.focusable()
        .keepFocus()
        .focusRing(theme.accent().edge())
        .cursor(Cursor.POINTER)
        .onPress(onPress)
        .whenHovered(e -> {
          e.fill(theme.surface().hover());
          words.color(theme.text().primary());
        })
        .whenPressed(e -> e.fill(theme.surface().pressed()));
  }

  private static String id(List<String> parts, int i) {
    return i + ":" + parts.get(i);
  }
}
