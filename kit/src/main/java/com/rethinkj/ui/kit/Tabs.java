package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.Drag;
import com.rethinkj.ui.Drop;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.window.Windows;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * A card whose first row is a bar of tabs over the content of the active one, as the open queries
 * of a database tool. The bar is a quiet band ({@link Theme.Surfaces#band}) with a hairline under
 * it; the active tab has primary text and a line in the accent's edge at its bottom, the others
 * muted text. The content fills the rest of the card, edge to edge, as in a {@link Card#flush}
 * card, so a toolbar, a data grid and a footer go there as they go into that. The app keeps the
 * tabs and which one is active, builds the content of that one only, and changes them when the bar
 * reports a choice or a close:
 *
 * <pre>{@code
 * tabs(theme, s.tabs.stream().map(q -> new Tab(q.title(), true, q.isModified())).toList(),
 *         s.active, i -> s.active = i, i -> s.close(i), queryView(s.tabs.get(s.active)))
 *     .key("queries").grow(1);
 * }</pre>
 *
 * <p>A press on a tab chooses it and leaves the focus where it was: in the content it goes on into
 * the new content, as below. A tab that can be closed has a cross after its title, which
 * shows on the active tab and under the pointer; a press on it closes the tab. A modified tab has
 * a dot before its title. Tabs keep the width of their titles; when they are wider than the bar
 * together, the bar scrolls sideways under the wheel, and whenever the active tab changes the bar
 * scrolls it into view.
 *
 * <p>The bar is one stop for Tab. While it has the focus, Left and Right choose the tab before or
 * after the active one, around at the ends, and Home and End the first and the last; the focus
 * stays on the bar, as in WAI-ARIA's tabs, so that the user can go on choosing. From anywhere
 * inside, the bar or the content, Control+Tab chooses the next tab and Control+Shift+Tab the one
 * before, around at the ends, and Control+W and Control+F4 close the active tab if it can be
 * closed. Keys go up from the focused element, so these work while the focus is inside. From the
 * content the focus goes into the content of the tab that takes over: to the element where it
 * was if the new content has one, else to its {@link El#autofocus} element or its first focusable
 * one, else to the bar (see {@link com.rethinkj.ui.App#focused}). An app that wants the shortcuts
 * while the focus is elsewhere, as in a tree beside the tabs, gives its root the same handler,
 * which the tabs reach first when they have the focus:
 *
 * <pre>{@code
 * El queries = tabs(theme, ...);
 * return col().wFull().hFull().onKey(queries.onKey()).children(toolbar, queries.grow(1));
 * }</pre>
 *
 * <p>The tabs are pressed with the one pointer button there is; closing a tab with the middle
 * button, as many tools do, waits for a second button in core.
 *
 * <p>With {@link Options#onMove} the user moves tabs, and the app changes the order: a tab dragged
 * along its bar leaves a muted tab in its place and shows a line in the accent's edge where it
 * would go, the bar scrolls along when the pointer nears its ends, and the release moves it there;
 * Escape or a lost release gives the drag up and leaves the order as it was. A press without a
 * drag ({@link Drag#THRESHOLD}) chooses the tab as before. Control+Shift+Page Up and Page Down move
 * the active tab one place towards the start or the end, as in Chrome, Firefox and VS Code on
 * Windows and Linux. With {@link Options#onTearOff} a tab dragged out of its bar, more than {@link
 * Theme.Sizes#control} above or below it or out of the window, is the app's to open in a window of
 * its own, and with {@link Options#onDock} a bar takes the tabs dragged from another bar that
 * shares its {@link Dragging} state, in another window too. {@link TabGroups} puts all of it
 * together for tabs in several windows.
 *
 * <p>In a tree dump the bar is {@code #tablist} with {@code role=tablist}, the tabs {@code
 * role=tab} with the title as label and the key {@code tab-<index>}, their crosses {@code
 * role=button} with the label {@code Close} and the title and the key {@code close-<index>}, and
 * the content is in {@code #panel} with {@code role=tabpanel} and the active tab's title as label.
 * The tab being dragged is {@code grabbed}, and the line where it would go is {@code #marker}.
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
   * @param icon before the title, in the title's colour, such as a table's; null for none
   */
  public record Tab(String title, boolean closable, boolean modified, Symbol icon) {

    public Tab {
      Objects.requireNonNull(title, "title");
    }

    /** A tab without an icon. */
    public Tab(String title, boolean closable, boolean modified) {
      this(title, closable, modified, null);
    }

    /** A tab that can be closed and has no changes. */
    public Tab(String title) {
      this(title, true, false);
    }

    /** This tab with an icon before its title. */
    public Tab icon(Symbol icon) {
      return new Tab(title, closable, modified, icon);
    }
  }

  private Tabs() {}

  /** Told that the user moved the tab at {@code from} so that it is now at {@code to}. */
  @FunctionalInterface
  public interface Move {
    void move(int from, int to);
  }

  /**
   * Told that the user dropped a tab of another bar into this one.
   *
   * @param fromBar the {@link Options#id} of the bar the tab comes from
   * @param from its index there
   * @param at the index in this bar before which it goes; the number of tabs for the end
   */
  @FunctionalInterface
  public interface Dock {
    void dock(Object fromBar, int from, int at);
  }

  /**
   * A step of the drag of a tab that has left its bar, which the app follows with a window of the
   * tab's own: the step on which it left, and every step after it up to {@link Drag.Phase#END} or
   * {@link Drag.Phase#CANCEL}.
   *
   * @param bar the {@link Options#id} of the bar the tab is in now: its own until the app moves it
   *     and says so with {@link Dragging#moved}
   * @param index the tab's index there
   * @param drag the step, with the pointer on the screen
   * @param grabX where the pointer holds the tab, from its top left, in logical pixels
   * @param grabY the same downwards
   * @param firstX where the first tab of the bar lay in its window when the drag began, in logical
   *     pixels; NaN where no runner says where the window is
   * @param firstY the same downwards
   * @param window the window the drag began in, or null without a runner
   */
  public record TearOff(Object bar, int index, Drag drag, float grabX, float grabY, float firstX,
      float firstY, Windows.Handle window) {}

  /**
   * What the app keeps of the drag of a tab: which tab, whether it left its bar, and the bar that
   * shows where it would go. Bars that share one state take each other's tabs ({@link
   * Options#onDock}), in any window of the runner; it is also what the drag carries ({@link
   * El#dragPayload}). The tabs change it; the app only creates it, and tells it with {@link
   * #moved} where it put a tab that it moved during a drag.
   */
  public static final class Dragging {
    Object bar; // the bar of the tab being dragged, by Options.id
    int index = -1; // and its index there; -1 while nothing is dragged
    boolean torn; // it left its bar
    float grabX;
    float grabY;
    float firstX;
    float firstY;
    Windows.Handle window;
    Object overBar; // the bar that shows the marker
    int slot = -1; // before which tab of it, -1 for none
    float markerX; // the marker's left in that bar
    Object reveal; // new for each step that scrolls that bar along
    int steps; // the steps of the drag so far
    int marked = -1; // the step that showed the marker: a bar's drop handler runs before it
    float revealX;

    /** Whether a tab is being dragged. */
    public boolean isActive() {
      return index >= 0;
    }

    /** The {@link Options#id} of the bar of the tab being dragged. */
    public Object bar() {
      return bar;
    }

    /** The index of the tab being dragged, -1 for none. */
    public int index() {
      return index;
    }

    /** Whether the tab being dragged has left its bar ({@link Options#onTearOff}). */
    public boolean isTorn() {
      return torn;
    }

    /** The app moved the tab being dragged: it is now at {@code index} in {@code bar}. */
    public void moved(Object bar, int index) {
      this.bar = bar;
      this.index = index;
    }

    boolean on(Object bar, int index) {
      return this.index == index && Objects.equals(this.bar, bar);
    }

    void clear() {
      bar = null;
      index = -1;
      torn = false;
      window = null;
      overBar = null;
      slot = -1;
      reveal = null;
      steps = 0;
      marked = -1;
    }
  }

  /**
   * What the tabs do besides choosing and closing: moving, tearing off, docking and a menu. Each
   * is off until a call turns it on; the view makes the options anew with the tabs.
   */
  public static final class Options {
    private Dragging dragging;
    private Object id;
    private Move onMove;
    private Consumer<TearOff> onTearOff;
    private boolean tearOffAtStart;
    private Dock onDock;
    private Menu.State menu;
    private List<Menu.Item> menuItems = List.of();

    private Options() {}

    /**
     * The state of the drags of tabs, which the app keeps, one for all bars that take each other's
     * tabs. The tabs are dragged only with it and {@link #onMove} or {@link #onTearOff}.
     */
    public Options drag(Dragging state) {
      this.dragging = Objects.requireNonNull(state, "state");
      return this;
    }

    /**
     * Which bar this is among those that share a {@link Dragging} state, compared with {@link
     * Object#equals}, as a group of tabs; null, the default, for a bar alone.
     */
    public Options id(Object id) {
      this.id = id;
      return this;
    }

    /**
     * Lets the user move tabs within the bar, by dragging and with Control+Shift+Page Up and Page
     * Down; the handler changes the order, as {@link Tabs#move} does.
     */
    public Options onMove(Move handler) {
      this.onMove = handler;
      return this;
    }

    /**
     * Lets the user drag a tab out of its bar: the handler is told the step on which the pointer
     * left it, by more than {@link Theme.Sizes#control} above or below or out of the window, and
     * every step after it, and opens a window for the tab and moves it along; see {@link
     * TabGroups}. Without it a tab stays in its bar.
     */
    public Options onTearOff(Consumer<TearOff> handler) {
      this.onTearOff = handler;
      return this;
    }

    /**
     * Whether a drag tears its tab off from its first step, as the only tab of a window, whose
     * drag moves the window; false by default.
     */
    public Options tearOffAtStart(boolean on) {
      this.tearOffAtStart = on;
      return this;
    }

    /** Lets the bar take a tab dragged from another bar that shares its {@link Dragging} state. */
    public Options onDock(Dock handler) {
      this.onDock = handler;
      return this;
    }

    /**
     * A menu that a secondary press on a tab opens after choosing it, as do the Menu key and
     * Shift+F10 on the bar and a secondary press beside the tabs; the items act on the active tab.
     */
    public Options contextMenu(Menu.State state, List<Menu.Item> items) {
      this.menu = Objects.requireNonNull(state, "state");
      this.menuItems = List.copyOf(items);
      return this;
    }
  }

  /** Options that turn nothing on; see {@link Options}. */
  public static Options options() {
    return new Options();
  }

  /**
   * Moves the element at {@code from} to {@code to} and returns where the element at {@code
   * active} is afterwards, for an {@link Options#onMove} handler: {@code s.active = move(s.tabs,
   * s.active, from, to)}.
   */
  public static <T> int move(List<T> list, int active, int from, int to) {
    list.add(to, list.remove(from));
    if (active == from) {
      return to;
    }
    if (from < active && to >= active) {
      return active - 1;
    }
    if (from > active && to <= active) {
      return active + 1;
    }
    return active;
  }

  /** A step of the drag of the tab at {@code index} of the bar whose tabs are {@code built}. */
  private static void drag(Theme t, Options o, int index, El list, List<El> built, Drag d) {
    Dragging g = o.dragging;
    Drag.Phase phase = d.phase();
    if (phase == Drag.Phase.START) {
      g.clear();
      g.bar = o.id;
      g.index = index;
      g.grabX = (float) d.x() - d.dx();
      g.grabY = (float) d.y() - d.dy();
      // The tabs lie side by side in the list, which is where the first one lies in the window.
      g.firstX = d.windowX() - (float) d.x() - (built.get(index).x() - built.getFirst().x());
      g.firstY = d.windowY() - (float) d.y();
      g.window = d.window();
      g.torn = o.onTearOff != null && o.tearOffAtStart;
      if (!g.torn && o.onMove != null) { // the bar's drop handler ran before this step
        mark(t, o, list, built, built.get(index).x() + (float) d.x());
      }
    }
    if (!g.isActive()) {
      return;
    }
    if (g.marked != g.steps) { // no bar showed the marker on this step: it is over none
      g.overBar = null;
      g.slot = -1;
    }
    g.steps++;
    boolean moving = phase == Drag.Phase.START || phase == Drag.Phase.MOVE;
    float reach = t.size().control();
    if (moving && !g.torn && o.onTearOff != null && (d.outside()
        || d.y() < -reach || d.y() > t.size().control() + t.space().m() + reach)) {
      g.torn = true;
    }
    if (g.torn) {
      o.onTearOff.accept(new TearOff(g.bar, g.index, d, g.grabX, g.grabY, g.firstX, g.firstY,
          g.window));
    }
    if (!moving) {
      g.clear();
    }
  }

  /** What a drag of a tab does over a bar: the marker, scrolling the list along, the drop. */
  private static void over(Theme t, Options o, El list, List<El> built, Drop d) {
    Dragging g = o.dragging;
    if (d.payload() != g || !g.isActive()) {
      return;
    }
    boolean own = Objects.equals(g.bar, o.id);
    boolean takes = own && !g.torn ? o.onMove != null : o.onDock != null;
    if (d.phase() == Drop.Phase.LEAVE || !takes
        || own && o.tearOffAtStart && o.onTearOff != null) { // its window moves along
      if (Objects.equals(g.overBar, o.id)) {
        g.overBar = null;
        g.slot = -1;
      }
      return;
    }
    float x = (float) d.x() - list.x(); // the pointer in the list as shown
    if (d.phase() == Drop.Phase.OVER) {
      mark(t, o, list, built, x);
      return;
    }
    int slot = slot(built, x); // the drop
    g.overBar = null;
    g.slot = -1;
    if (own && !g.torn) {
      int to = slot > g.index ? slot - 1 : slot;
      if (to != g.index) {
        o.onMove.move(g.index, to);
      }
    } else {
      o.onDock.dock(g.bar, g.index, slot);
    }
  }

  /** Before which of the tabs a tab dropped at {@code x} in the list as shown would go. */
  private static int slot(List<El> built, float x) {
    int slot = 0;
    for (El tab : built) {
      if (x > tab.x() + tab.width() / 2) {
        slot++;
      }
    }
    return slot;
  }

  /**
   * Shows the marker where a tab dropped at {@code x} in the list as shown would go, and scrolls
   * the list along near its ends.
   */
  private static void mark(Theme t, Options o, El list, List<El> built, float x) {
    Dragging g = o.dragging;
    int slot = slot(built, x);
    float gap = t.space().xs();
    float line = t.size().indicator();
    float edge = built.isEmpty() ? 0
        : slot < built.size() ? built.get(slot).x() - gap / 2
        : built.getLast().x() + built.getLast().width() + gap / 2;
    g.overBar = o.id;
    g.slot = slot;
    g.marked = g.steps;
    g.markerX = list.x() + Math.clamp(edge - line / 2, 0, Math.max(0, list.width() - line));
    // Near an end of the list, each step scrolls it a little further that way.
    float near = t.size().control();
    float step = t.space().m();
    if (x < near && list.scrollX() > 0) {
      g.reveal = new Object();
      g.revealX = (float) list.scrollX() - step;
    } else if (x > list.width() - near) {
      g.reveal = new Object();
      g.revealX = (float) list.scrollX() + list.width() + step - 1;
    }
  }

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
    return tabs(t, tabs, active, onSelect, onClose, content, options());
  }

  /** The tabs with what {@code options} turns on: moving, tearing off, docking, a menu. */
  public static El tabs(
      Theme t, List<Tab> tabs, int active, IntConsumer onSelect, IntConsumer onClose,
      El content, Options o) {
    boolean enabled = onSelect != null;
    Dragging dragging = o.dragging;
    boolean drags = enabled && dragging != null && (o.onMove != null || o.onTearOff != null);
    // Each input reaches the handlers of a view built after the one before it, so this view's
    // indexes are those of the state.
    IntConsumer select = !enabled ? null : i -> {
      if (i != active) {
        onSelect.accept(i);
      }
    };
    IntConsumer close = onClose;
    // Its top corners follow the card's, inside its border and the bar's padding, so that the
    // focus ring, which the card's clip puts inside the bar, follows them too.
    float corner = t.radius().large() - t.size().stroke() - t.space().xs();
    El list = row().key("tablist").role("tablist")
        .radius(corner, corner, 0, 0)
        .gap(t.space().xs())
        .scrollBoth()
        .revealRow(active);
    // Sideways only: the wheel moves the tabs left and right.
    list.style().overflowY = Style.Overflow.HIDDEN;
    List<El> built = new ArrayList<>();
    for (int i = 0; i < tabs.size(); i++) {
      boolean grabbed = dragging != null && dragging.on(o.id, i);
      // While a tab is dragged the others do not light up: the drag's element is the one under
      // the pointer, by its key, which another tab has once the dragged one left.
      El tab = tab(t, tabs.get(i), i, i == active, grabbed && !dragging.torn,
          dragging != null && dragging.isActive(), select, close)
          .grabbed(grabbed);
      if (drags) {
        int index = i;
        tab.dragPayload(dragging).onDrag(d -> drag(t, o, index, list, built, d));
      }
      if (enabled && o.menu != null) {
        int index = i;
        tab.onSecondaryPress((x, y) -> {
          select.accept(index);
          o.menu.openAt(x, y);
        });
      }
      built.add(tab);
      list.child(tab);
    }
    El bar = row().key("bar").fill(t.surface().band()).padding(0, t.space().xs()).shrink(0)
        .border(0, 0, t.size().stroke(), 0, t.border().subtle()).child(list);
    if (enabled && dragging != null) {
      bar.onDrop(d -> over(t, o, list, built, d));
      if (dragging.slot >= 0 && Objects.equals(dragging.overBar, o.id)) {
        float side = t.size().control();
        bar.child(el().key("marker").absolute(dragging.markerX, (t.space().m()) / 2)
            .size(t.size().indicator(), side).fill(t.accent().edge()));
        if (dragging.reveal != null) {
          list.reveal(dragging.reveal, dragging.revealX, 0, 1, 1);
        }
      }
    }
    if (enabled && o.menu != null) { // a press beside the tabs opens the active one's menu
      Menu.contextMenu(t, bar, o.menu, o.menuItems);
    }
    El panel = col().key("panel").role("tabpanel")
        .label(active >= 0 && active < tabs.size() ? tabs.get(active).title() : null)
        .grow(1);
    panel.style().minHeight = Len.ZERO;
    if (content != null) {
      // No minimum from the content either, so that a list of rows deep inside it scrolls.
      content.style().minWidth = Len.ZERO;
      content.style().minHeight = Len.ZERO;
      panel.child(content.grow(1));
    }
    El root = Card.surface(t, Card.Variant.OUTLINED).children(bar, panel);
    // It fills the room it is given: a list of rows in the content is no minimum.
    root.style().minWidth = Len.ZERO;
    root.style().minHeight = Len.ZERO;
    if (!enabled || tabs.isEmpty()) {
      return root;
    }

    int last = tabs.size() - 1;
    // Tab reaches the bar; a press on a tab leaves the focus where it is, so that from the content
    // it goes on into the new content.
    if (o.menu != null) { // the Menu key and Shift+F10 on the bar: the active tab's menu
      list.onSecondaryPress(o.menu::openAt);
    }
    list.focusable().keepFocus().focusRing(t.accent().edge()).onKey(k -> {
      if (k.modifiers() != 0) {
        return false;
      }
      int from = active;
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
      int from = active;
      if (name.equals("Tab") && (k.modifiers() & ~Key.SHIFT) == Key.CONTROL) {
        boolean back = (k.modifiers() & Key.SHIFT) != 0;
        select.accept(from < 0 ? (back ? last : 0) : Math.floorMod(from + (back ? -1 : 1),
            tabs.size()));
        return true;
      }
      if (k.modifiers() == (Key.CONTROL | Key.SHIFT) && o.onMove != null && from >= 0
          && (name.equals("PageUp") && from > 0 || name.equals("PageDown") && from < last)) {
        o.onMove.move(from, name.equals("PageUp") ? from - 1 : from + 1);
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
      Theme t, Tab tab, int index, boolean active, boolean muted, boolean quiet,
      IntConsumer onSelect, IntConsumer onClose) {
    boolean enabled = onSelect != null;
    Theme.Surfaces s = t.surface();
    int ink = !enabled || muted ? t.text().disabled()
        : active ? t.text().primary() : t.text().muted();
    // The tabs fill the bar's height; the active one has a line at its bottom, the others show
    // the band, with the translucent overlays on it under the pointer.
    float height = t.size().control() + t.space().m();
    El el = row().key("tab-" + index).role("tab").label(tab.title())
        .selected(active)
        .disabled(!enabled)
        .h(height)
        .padding(0, t.space().m())
        .gap(t.space().s())
        .align(Style.Align.CENTER)
        .shrink(0);
    if (active) {
      float line = t.size().indicator();
      el.child(el().absolute(0, height - line).h(line).wFull().fill(t.accent().edge()));
    }
    if (tab.modified()) {
      el.child(label(t, "●").color(ink));
    }
    if (tab.icon() != null) {
      el.child(Icon.icon(t, tab.icon()).color(ink));
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
    if (active || quiet) {
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
