package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * A bar of tabs over the content of the active one, as the open queries of a database tool. The
 * app keeps the tabs and which one is active, builds the content of that one only, and changes
 * them when the bar reports a choice or a close:
 *
 * <pre>{@code
 * tabs(theme, s.tabs.stream().map(q -> new Tab(q.title(), true, q.isModified())).toList(),
 *         s.active, i -> s.active = i, i -> s.close(i), queryView(s.tabs.get(s.active)))
 *     .key("queries").grow(1);
 * }</pre>
 *
 * <p>A press on a tab chooses it. A tab that can be closed has a cross after its title, which
 * shows on the active tab and under the pointer; a press on it closes the tab. A modified tab has
 * a dot before its title. Tabs keep the width of their titles; when they are wider than the bar
 * together, the bar scrolls sideways under the wheel, and whenever the active tab changes the bar
 * scrolls it into view.
 *
 * <p>The bar is one stop for Tab. While it has the focus, Left and Right choose the tab before or
 * after the active one, around at the ends, and Home and End the first and the last. From
 * anywhere inside, the bar or the content, Control+Tab chooses the next tab and Control+Shift+Tab
 * the one before, around at the ends, and Control+W and Control+F4 close the active tab if it can
 * be closed. Keys go up from the focused element, so these work while the focus is inside. When
 * the chosen tab's content has no element where the focused one was, the focus goes with it, and
 * keys reach only the view's root. An app that wants the shortcuts then, or while the focus is
 * elsewhere, gives its root the same handler, which the tabs reach first when they have the focus:
 *
 * <pre>{@code
 * El queries = tabs(theme, ...);
 * return col().wFull().hFull().onKey(queries.onKey()).children(toolbar, queries.grow(1));
 * }</pre>
 *
 * <p>The tabs are pressed with the one pointer button there is; closing a tab with the middle
 * button, as many tools do, waits for a second button in core. Tabs cannot be moved by dragging.
 *
 * <p>In a tree dump the bar is {@code #tablist} with {@code role=tablist}, the tabs {@code
 * role=tab} with the title as label and the key {@code tab-<index>}, their crosses {@code
 * role=button} with the label {@code Close} and the title and the key {@code close-<index>}, and
 * the content is in {@code #panel} with {@code role=tabpanel} and the active tab's title as label.
 * The component needs a size of its own or room to grow; its content fills the rest of it. It
 * takes no minimum size from its content, so that a list of rows there scrolls inside it.
 */
public final class Tabs {

  /**
   * A tab of the bar.
   *
   * @param title shown in the tab, also its label
   * @param closable whether it has a cross that closes it
   * @param modified whether a dot before the title says that its content has unsaved changes
   */
  public record Tab(String title, boolean closable, boolean modified) {

    public Tab {
      Objects.requireNonNull(title, "title");
    }

    /** A tab that can be closed and has no changes. */
    public Tab(String title) {
      this(title, true, false);
    }
  }

  private Tabs() {}

  /**
   * @param tabs the tabs, from the left
   * @param active the index of the active tab, or -1 for none
   * @param onSelect told the index of the tab the user chooses; null disables the bar
   * @param onClose told the index of a tab the user closes, which the app removes, choosing
   *     another active tab if it was the active one; null closes none
   * @param content the content of the active tab, which fills the rest; null for none
   */
  public static El tabs(
      Theme t, List<Tab> tabs, int active, IntConsumer onSelect, IntConsumer onClose,
      El content) {
    boolean enabled = onSelect != null;
    // Several presses and keys may reach the handlers of this view before the next one is built:
    // each goes on from the tab the last one chose, and after a close, which leaves the indexes
    // of this view behind, they do nothing.
    int[] at = {active};
    boolean[] closed = {false};
    IntConsumer select = !enabled ? null : i -> {
      if (!closed[0] && i != at[0]) {
        at[0] = i;
        onSelect.accept(i);
      }
    };
    IntConsumer close = onClose == null ? null : i -> {
      if (!closed[0]) {
        closed[0] = true;
        onClose.accept(i);
      }
    };
    El list = row().key("tablist").role("tablist")
        .gap(t.space().xs())
        .radius(t.radius().medium())
        .scrollBoth()
        .revealRow(active);
    // Sideways only: the wheel moves the tabs left and right.
    list.style().overflowY = Style.Overflow.HIDDEN;
    for (int i = 0; i < tabs.size(); i++) {
      list.child(tab(t, tabs.get(i), i, i == active, select, close));
    }
    El bar = row().key("bar").fill(t.surface().panel()).padding(t.space().xs()).shrink(0)
        .child(list);
    El under = el().h(t.size().stroke()).shrink(0).fill(t.border().subtle());
    El panel = col().key("panel").role("tabpanel")
        .label(active >= 0 && active < tabs.size() ? tabs.get(active).title() : null)
        .grow(1);
    panel.style().minHeight = Len.ZERO;
    if (content != null) {
      panel.child(content.grow(1));
    }
    El root = col().children(bar, under, panel);
    // It fills the room it is given: a list of rows in the content is no minimum.
    root.style().minWidth = Len.ZERO;
    root.style().minHeight = Len.ZERO;
    if (!enabled || tabs.isEmpty()) {
      return root;
    }

    int last = tabs.size() - 1;
    list.focusable().focusRing(t.border().focus()).onKey(k -> {
      if (k.modifiers() != 0) {
        return false;
      }
      int from = at[0];
      int next = switch (k.name()) {
        case "ArrowLeft" -> from <= 0 ? last : from - 1;
        case "ArrowRight" -> from >= last || from < 0 ? 0 : from + 1;
        case "Home" -> 0;
        case "End" -> last;
        default -> -1;
      };
      if (next < 0) {
        return false;
      }
      select.accept(next);
      return true;
    });
    return root.onKey(k -> {
      String name = k.name();
      int from = at[0];
      if (name.equals("Tab") && (k.modifiers() & ~Key.SHIFT) == Key.CONTROL) {
        boolean back = (k.modifiers() & Key.SHIFT) != 0;
        select.accept(from < 0 ? (back ? last : 0) : Math.floorMod(from + (back ? -1 : 1),
            tabs.size()));
        return true;
      }
      if (k.modifiers() == Key.CONTROL && (name.equals("W") || name.equals("F4"))
          && close != null && from >= 0 && from <= last && tabs.get(from).closable()) {
        close.accept(from);
        return true;
      }
      return false;
    });
  }

  private static El tab(
      Theme t, Tab tab, int index, boolean active, IntConsumer onSelect, IntConsumer onClose) {
    boolean enabled = onSelect != null;
    Theme.Surfaces s = t.surface();
    int ink = !enabled ? t.text().disabled() : active ? t.text().primary() : t.text().muted();
    // The active tab is raised; the others show the bar, with the translucent overlays on it.
    El el = row().key("tab-" + index).role("tab").label(tab.title())
        .h(t.size().control())
        .padding(0, t.space().m())
        .gap(t.space().s())
        .align(Style.Align.CENTER)
        .shrink(0)
        .radius(t.radius().medium())
        .border(t.size().stroke(), active ? t.border().subtle() : 0)
        .fill(active ? s.raised() : 0);
    if (tab.modified()) {
      el.child(label(t, "●").color(ink));
    }
    el.child(label(t, tab.title()).color(ink));
    if (!enabled) {
      return el;
    }
    El close = null;
    if (tab.closable() && onClose != null) {
      float side = t.size().checkbox();
      close = el().key("close-" + index).role("button").label("Close " + tab.title())
          .size(side, side)
          .shrink(0)
          .center()
          .radius(t.radius().small())
          .cursor(Cursor.POINTER)
          .opacity(active ? 1 : 0)
          .onPress(() -> onClose.accept(index))
          .whenHovered(e -> e.fill(s.hover()))
          .whenPressed(e -> e.fill(s.pressed()))
          .child(Icon.icon(t, Symbol.DISMISS, Icon.Variant.NORMAL, side).color(ink));
      el.child(close);
    }
    el.cursor(Cursor.POINTER).onPress(() -> onSelect.accept(index));
    if (active) {
      return el;
    }
    // Under the pointer the text of another tab is primary, and its cross shows.
    El cross = close;
    List<El> parts = List.copyOf(el.children());
    Consumer<El> lit = e -> {
      for (El part : parts) {
        (part == cross ? part.children().getFirst() : part).color(t.text().primary());
      }
      if (cross != null) {
        cross.opacity(1);
      }
    };
    return el
        .whenHovered(lit.andThen(e -> e.fill(s.hover())))
        .whenPressed(lit.andThen(e -> e.fill(s.pressed())));
  }
}
