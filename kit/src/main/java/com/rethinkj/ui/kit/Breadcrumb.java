package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.WindowState;
import com.rethinkj.ui.text.Font;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Where something lies, as a path of crumbs from its start to the current place, each with an
 * icon if it has one: {@code localhost › shop › public › orders}. A crumb with a press goes there;
 * the last is the current place, which takes none. The chevron after a crumb with {@link
 * Crumb#children} is a button that opens a menu of the places inside it, as a file manager's
 * address bar has it; the other chevrons only separate. Where the path does not fit, the crumbs
 * after the first go into a menu behind an ellipsis, from the start on; where that is not enough,
 * the first one too, and only then is the current place shortened, with an ellipsis and its whole
 * name in a tooltip. The breadcrumb learns its width from the layout ({@link El#onResize}) and
 * keeps it in its {@link State}, and the frame that brings a new width already shows what fits it;
 * the width of each
 * crumb it works out from the theme and the width of its text, as layout shaped that text at the
 * window's scale once it has.
 *
 * <p>It is one stop for Tab, at the crumb or button the arrows last went to, also where the
 * ellipsis comes or goes; where that one goes into the ellipsis, at the one they went to before it
 * that still shows, else at the first. Arrow Left and Right go between the crumbs, the chevrons
 * with a menu and the ellipsis, Home and End to the ends, Enter presses, and Arrow Down opens a
 * menu. A press on a crumb leaves the focus where it was. In a tree dump it is {@code
 * role=navigation label="Breadcrumb"}, each crumb {@code role=link} with its text as the label, the
 * last one {@code current}, a chevron with a menu {@code role=button label="Inside shop"} and the
 * ellipsis {@code role=button label="Hidden parts"}, each {@code expanded} while its menu is open.
 */
public final class Breadcrumb {

  private Breadcrumb() {}

  /**
   * A place on the path.
   *
   * @param text its name, which a tree dump gives as its label
   * @param onPress goes there; null for a crumb that only shows. The last crumb is the current
   *     place, which takes no press
   * @param icon before the text, such as a table's; null for none
   * @param children the places inside it, for the menu of the chevron after it, asked for only
   *     while that menu is open; null for a chevron that only separates, and none after the last
   */
  public record Crumb(
      String text, Runnable onPress, Icon icon, Supplier<List<Menu.Item>> children) {

    public Crumb {
      Objects.requireNonNull(text, "text");
    }

    /** This crumb with an icon before its text. */
    public Crumb icon(Icon icon) {
      return new Crumb(text, onPress, icon, children);
    }

    /**
     * This crumb with a chevron after it that opens a menu of the places inside it, as {@code
     * state::tables}.
     */
    public Crumb children(Supplier<List<Menu.Item>> children) {
      return new Crumb(text, onPress, icon, children);
    }
  }

  /**
   * A crumb without an icon or a menu.
   *
   * @param onPress goes there; null for a crumb that only shows
   */
  public static Crumb crumb(String text, Runnable onPress) {
    return new Crumb(text, onPress, null, null);
  }

  /**
   * What a breadcrumb keeps between builds, held by the app, one per breadcrumb: its width, the
   * menus and the stop Tab reaches.
   */
  public static final class State {
    final Menu.State hidden = new Menu.State();
    private final List<Menu.State> children = new ArrayList<>(); // by the crumb's index
    private List<String> path = List.of(); // the texts of the crumbs the last build had
    // The width of a crumb's text as layout shaped it, at the window's scale.
    private final Map<Shaped, Float> texts = new HashMap<>();
    final Fitting fitting = new Fitting();
    final Roving keys = new Roving();

    /** The menu of the crumbs the ellipsis stands for. */
    public Menu.State hidden() {
      return hidden;
    }

    /** The menu of the chevron after the crumb at {@code index}. */
    Menu.State children(int index) {
      while (children.size() <= index) {
        children.add(new Menu.State());
      }
      return children.get(index);
    }

    /**
     * Closes the menus from the first crumb that differs from the last build's on, and the menu of
     * the ellipsis where the path changed, so that no menu shows another place's items.
     */
    void follow(Theme theme, List<Crumb> crumbs) {
      int same = 0;
      while (same < Math.min(path.size(), crumbs.size())
          && path.get(same).equals(crumbs.get(same).text())) {
        same++;
      }
      for (int i = same; i < children.size(); i++) {
        children.get(i).close();
      }
      if (same < Math.max(path.size(), crumbs.size())) {
        hidden.close();
      }
      path = crumbs.stream().map(Crumb::text).toList();
      texts
          .keySet()
          .retainAll(crumbs.stream()
              .flatMap(c -> List.of(key(theme, c, false), key(theme, c, true)).stream())
              .toList());
    }

    /**
     * The width of a crumb's text: as layout shaped it where it has, with the system's fonts where
     * the app falls back to them, else as the build measures it at the window's scale.
     */
    float text(Theme theme, Crumb crumb, boolean current) {
      Float width = texts.get(key(theme, crumb, current));
      return width != null ? width : TextSizing.natural(words(theme, crumb, current));
    }

    /** Learns the width of a crumb's text from layout, at the window's scale. */
    El.SizeHandler learn(Theme theme, Crumb crumb, boolean current, El words) {
      Shaped key = key(theme, crumb, current);
      return (w, h) -> texts.put(key, TextSizing.measured(words)); // after the build: as laid out
    }

    /** What the width of a crumb's text depends on. */
    private record Shaped(Font font, float size, String text, float scale) {}

    private static Shaped key(Theme theme, Crumb crumb, boolean current) {
      Theme.TextStyle style = style(theme, current);
      return new Shaped(
          style.font().get(),
          style.size(),
          crumb.text(),
          WindowState.CURRENT.get().scale());
    }

    /** Closes the menus of the chevrons this build does not show, so none opens by itself. */
    void closeChildren(boolean[] shown) {
      for (int i = 0; i < children.size(); i++) {
        if (i >= shown.length || !shown[i]) {
          children.get(i).close();
        }
      }
    }
  }

  /**
   * Which crumbs show: those before {@code start} and those from {@code from} on, with the
   * ellipsis between them where {@code start < from}. With {@code squeeze} the current place is
   * shortened to the width that is left.
   */
  private record Plan(int start, int from, boolean squeeze) {}

  private static final Plan ALL = new Plan(0, 0, false);

  /**
   * A stop for Tab by the crumb it belongs to, not by its place in the bar.
   *
   * @param crumb its index in the path; -1 for the ellipsis
   * @param chevron the chevron after the crumb rather than the crumb
   */
  private record Stop(int crumb, boolean chevron) {}

  /** @param crumbs the path from its start to the current place, at least one */
  public static El breadcrumb(State state, List<Crumb> crumbs) {
    Theme theme = Theme.CURRENT.get();
    if (crumbs.isEmpty()) {
      throw new IllegalArgumentException("A breadcrumb needs at least the current place.");
    }
    state.follow(theme, crumbs);
    int last = crumbs.size() - 1;
    float[] widths = new float[crumbs.size()];
    for (int i = 0; i <= last; i++) {
      widths[i] =
          width(theme, crumbs.get(i), i == last, state.text(theme, crumbs.get(i), i == last));
    }
    Plan plan = plan(theme, state.fitting.width(), widths);
    El bar =
        state.fitting.row(theme).role("navigation").label(Texts.CURRENT.get().breadcrumb());
    List<El> stops = new ArrayList<>();
    List<Stop> ids = new ArrayList<>();
    boolean[] menus = new boolean[crumbs.size()];
    for (int i = 0; i <= last; i++) {
      if (i == plan.start() && plan.start() < plan.from()) {
        bar.child(more(theme, state, crumbs, plan, stops));
        ids.add(new Stop(-1, false));
        i = plan.from();
      }
      Crumb crumb = crumbs.get(i);
      El unit = row().key("crumb-" + i).gap(theme.space().xs()).align(Style.Align.CENTER);
      El part = part(theme, state, crumb, i == last);
      unit.child(part);
      if (part.isFocusable()) {
        stops.add(part);
        ids.add(new Stop(i, false));
      }
      El chevron = chevron(theme, state, crumb, i, i == last);
      if (chevron != null) {
        unit.child(chevron);
        if (crumb.children() != null) {
          stops.add(chevron);
          ids.add(new Stop(i, true));
          menus[i] = true;
        }
      }
      bar.child(i == last && plan.squeeze() ? unit.shrink(1).minSize(0, 0) : unit.shrink(0));
    }
    state.closeChildren(menus);
    if (plan.start() == plan.from()) {
      state.hidden.close();
    }
    // As the focus does (App#focused), the stop stays in its crumb where it can: on the chevron
    // of a crumb that became the current place.
    if (state.keys.active() instanceof Stop stop && !ids.contains(stop)) {
      Stop other = new Stop(stop.crumb(), !stop.chevron());
      if (ids.contains(other)) {
        state.keys.at(other);
      }
    }
    state.keys.apply(bar, stops, ids);
    return bar;
  }

  /**
   * The first crumb, the ellipsis and as many at the end as fit; where the current place does not
   * fit beside them, the ellipsis and the end, shortening the current place where it alone does
   * not fit. Everything until the bar has been measured.
   */
  private static Plan plan(Theme theme, float width, float[] widths) {
    int count = widths.length;
    float gap = theme.space().xs();
    float total = -gap;
    for (float w : widths) {
      total += gap + w;
    }
    if (Float.isNaN(width) || total <= width) {
      return ALL;
    }
    if (count == 1) {
      return new Plan(0, 0, true);
    }
    float more = moreWidth(theme);
    if (count > 2) {
      int from = end(widths, gap, width - widths[0] - gap - more, 2);
      if (from < count) {
        return new Plan(1, from, false);
      }
    }
    int from = end(widths, gap, width - more, 1);
    return from < count ? new Plan(0, from, false) : new Plan(0, count - 1, true);
  }

  /**
   * The first of the crumbs at the end that fit into {@code room}, each after a gap, and not
   * before {@code min}; the count of crumbs where not even the last fits.
   */
  private static int end(float[] widths, float gap, float room, int min) {
    int from = widths.length;
    float used = 0;
    while (from > min && used + gap + widths[from - 1] <= room) {
      used += gap + widths[--from];
    }
    return from;
  }

  /**
   * The width of a crumb with the chevron after it, from the theme and the width of its text
   * alone, so that it does not depend on where layout puts it or on what the last build showed.
   */
  static float width(Theme theme, Crumb crumb, boolean current, float text) {
    float width = 2 * theme.space().s() + text;
    if (crumb.icon() != null) {
      width += theme.size().icon() + theme.space().s();
    }
    if (!current || crumb.children() != null) {
      width += theme.space().xs() + theme.size().icon();
    }
    return width;
  }

  /** The width of the ellipsis with the chevron after it. */
  static float moreWidth(Theme theme) {
    return theme.size().control() + theme.space().xs() + theme.size().icon();
  }

  /** The ellipsis, with its menu of the crumbs it stands for, and the chevron after it. */
  private static El more(Theme theme, State state, List<Crumb> crumbs, Plan plan, List<El> stops) {
    List<Menu.Item> items = new ArrayList<>();
    for (int i = plan.start(); i < plan.from(); i++) {
      Crumb crumb = crumbs.get(i);
      items.add(Menu.item(crumb.text(), crumb.onPress()).icon(crumb.icon()));
    }
    El button = Button.icon(
            Button.Variant.GHOST,
            Symbol.MORE_HORIZONTAL,
            Texts.CURRENT.get().hiddenParts(),
            state.hidden::toggle)
        .expanded(state.hidden.isOpen());
    stops.add(button);
    return row()
        .key("more")
        .gap(theme.space().xs())
        .align(Style.Align.CENTER)
        .shrink(0)
        .child(Menu.dropdown(button, state.hidden, items))
        .child(mark(theme));
  }

  /** A crumb: muted, primary under the pointer where it takes a press; the current place strong. */
  private static El part(Theme theme, State state, Crumb crumb, boolean current) {
    Runnable onPress = current ? null : crumb.onPress();
    int color = current ? theme.text().primary() : theme.text().muted();
    El words = words(theme, crumb, current).color(color).ellipsis().minSize(0, 0);
    words.onResize(state.learn(theme, crumb, current, words));
    El icon = crumb.icon() == null
        ? null
        : Icon.Picture.color(Icon.icon(crumb.icon()), color).shrink(0);
    El el = row()
        .role("link")
        .label(crumb.text())
        .h(theme.size().control())
        .minSize(0, 0)
        .padding(0, theme.space().s())
        .gap(theme.space().s())
        .align(Style.Align.CENTER)
        .radius(theme.radius().small());
    if (icon != null) {
      el.child(icon);
    }
    el.child(words);
    if (current) {
      return Tooltip.whenShortened(el.current(true), crumb.text());
    }
    if (onPress == null) {
      return el;
    }
    return el.focusable()
        .keepFocus()
        .focusRing(theme.accent().edge())
        .cursor(Cursor.POINTER)
        .motion(theme.motion().feedback())
        .onPress(onPress)
        .whenHovered(e -> {
          e.fill(theme.surface().hover());
          words.color(theme.text().primary());
          if (icon != null) {
            Icon.Picture.color(icon, theme.text().primary());
          }
        })
        .whenPressed(e -> e.fill(theme.surface().pressed()));
  }

  /** A crumb's text, in strong type for the current place. */
  private static El words(Theme theme, Crumb crumb, boolean current) {
    Theme.TextStyle style = style(theme, current);
    return El.text(crumb.text()).font(style.font().get()).fontSize(style.size());
  }

  private static Theme.TextStyle style(Theme theme, boolean current) {
    return current ? theme.type().strong() : theme.type().body();
  }

  /**
   * The chevron after a crumb: a button that opens the menu of its children, pointing down while
   * it is open, or a mark between two crumbs; null after the last crumb without children.
   */
  private static El chevron(Theme theme, State state, Crumb crumb, int index, boolean last) {
    if (crumb.children() == null) {
      return last ? null : mark(theme);
    }
    Menu.State menu = state.children(index);
    boolean open = menu.isOpen();
    El glyph = glyph(theme, open ? Symbol.CHEVRON_DOWN : Symbol.CHEVRON_RIGHT)
        .color(open ? theme.text().primary() : theme.border().strong());
    El button = box(theme)
        .child(glyph)
        .role("button")
        .label(Texts.CURRENT.get().inside(crumb.text()))
        .expanded(open)
        .radius(theme.radius().small())
        .focusable()
        .keepFocus()
        .focusRing(theme.accent().edge())
        .cursor(Cursor.POINTER)
        .motion(theme.motion().feedback())
        .onPress(menu::toggle)
        .whenPressed(e -> e.fill(theme.surface().pressed()));
    if (open) {
      button.fill(theme.surface().pressed());
    } else {
      button.whenHovered(e -> {
        e.fill(theme.surface().hover());
        glyph.color(theme.text().primary());
      });
    }
    return Menu.dropdown(button, menu, open ? crumb.children().get() : List.of());
  }

  /** A chevron that only separates, in the color of the edges controls are known by. */
  private static El mark(Theme theme) {
    return box(theme)
        .child(glyph(theme, Symbol.CHEVRON_RIGHT).color(theme.border().strong()));
  }

  /** A chevron at the size of small text, as a status bar's menu has it. */
  private static El glyph(Theme theme, Symbol symbol) {
    return Icon.icon(symbol, Icon.Variant.MUTED, theme.type().small().size());
  }

  private static El box(Theme theme) {
    return El.el().size(theme.size().icon(), theme.size().control()).center().shrink(0);
  }
}
