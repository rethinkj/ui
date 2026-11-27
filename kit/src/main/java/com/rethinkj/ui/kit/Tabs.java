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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;

/**
 * A card whose first row is a bar of tabs over the content of the active one, as the open queries
 * of a database tool. The bar sits on the window's surface ({@link Theme.Surfaces#window}) with a
 * divider under it; the active tab joins the content below in {@link Theme.Surfaces#panel} with a
 * divider rim, primary text and an accent mark along its top in the active group, while inactive
 * tabs have muted text and take a hover fill under the pointer. The content fills the rest of the
 * card, edge to edge, as in a {@link Card#flush} card, so a toolbar, a data grid and a footer go
 * there as they go into that. The app keeps the tabs and which one is active, builds the content
 * of that one only, and changes them when the bar reports a choice or a close:
 *
 * <pre>{@code
 * tabs(
 *         state.tabs.stream()
 *             .map(query -> new Tab(query.title(), true, query.isModified()))
 *             .toList(),
 *         state.active, index -> state.active = index, index -> state.close(index),
 *         queryView(state.tabs.get(state.active)))
 *     .key("queries").grow(1);
 * }</pre>
 *
 * <p>A busy tab ({@link Tab#busy}) has a spinner in place of its icon, so that work in the
 * content of a tab that is not active, such as a query that runs, shows in the bar.
 *
 * <p>A press on a tab chooses it and leaves the focus where it was: in the content it goes on into
 * the new content, as below. A tab that can be closed reserves a close slot after its title; the
 * cross shows on an active unmodified tab, and on an inactive tab while the pointer is over the
 * tab's right end; a press on it closes the tab. A modified tab shows a dot in that slot (or after
 * its title when it cannot be closed) that turns into the cross while the pointer is over the
 * tab's right end. Tabs keep the width of their titles; when they are wider than the bar
 * together, the bar scrolls sideways under the wheel, and whenever the active tab changes the bar
 * scrolls it into view.
 *
 * <p>The bar is one stop for Tab. While it has the focus, Left and Right choose the tab before or
 * after the active one, around at the ends, and Home and End the first and the last; the focus
 * stays on the bar, as in WAI-ARIA's tabs, so that the user can go on choosing. From anywhere
 * inside, the bar or the content, Control+Tab and Control+Page Down choose the next tab and
 * Control+Shift+Tab and Control+Page Up the one before, around at the ends, Control+W and
 * Control+F4 close the active tab if it can be closed, and with {@link Options#onAdd} Control+T
 * adds a tab, also in an empty bar. Keys go up from the focused element, so these work while the
 * focus is inside; tabs in the content of other tabs leave the four that choose to the outer ones
 * with {@link Options#switchKeys}. From the
 * content the focus goes into the content of the tab that takes over: to the element where it
 * was last in that content, else to its {@link El#defaultFocus} or {@link El#autofocus} element or
 * its first focusable one, else to the bar (see {@link com.rethinkj.ui.App#focused}). The focus is
 * remembered by identity, so key each tab's content of its own, as {@link TabGroups} does; contents
 * of the same key share it. An app that wants the shortcuts
 * while the focus is elsewhere, as in a tree beside the tabs, gives its root the same handler,
 * which the tabs reach first when they have the focus:
 *
 * <pre>{@code
 * El queries = tabs(...);
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
 * role=tab} with the title as label and the key {@code tab-<index>}, a busy one {@code busy}, a
 * modified one {@code modified}, their crosses {@code role=button} with the label {@code Close}
 * and the title and the key {@code close-<index>}, the button of {@link Options#onAdd} {@code
 * #add} with {@code role=button} and the label {@code New tab}, the button of {@link
 * Options#overflow} {@code #overflow} with {@code role=button} and the label {@code All tabs}
 * while the tabs overflow and its empty room {@code #room} otherwise, and the content is in {@code
 * #panel} with {@code role=tabpanel} and the active tab's title as label. The tab being dragged is
 * {@code grabbed}, and the line where it would go is {@code #marker}.
 * The component needs a size of its own or room to grow; its content fills the rest of it. It
 * takes no minimum size from its content, so that a list of rows there scrolls inside it.
 *
 * <p>Inside a card or a pane, such as the result and the messages of a query, {@link
 * Variant#LINE} leaves the card out: a compact bar with a hairline under it, whose line under the
 * active tab ({@code #indicator}) slides to the tab chosen next.
 */
public final class Tabs {

  /** How the tabs look. */
  public enum Variant {
    /**
     * A card whose first row is the bar on {@link Theme.Surfaces#window}, whose active tab joins
     * the content in {@link Theme.Surfaces#panel} with an accent mark at its top: the default.
     */
    CARD,
    /**
     * The bar and the content without a card, for tabs inside one: a flush bar {@link
     * Theme.Sizes#control} high with a divider under it, a sliding inset underline ({@link
     * Theme.Motion#move()}) under the active tab, and a rounded hover fill on inactive tabs.
     */
    LINE
  }

  /**
   * A tab of the bar.
   *
   * @param title shown in the tab, also its label
   * @param closable whether it has a cross that closes it
   * @param modified whether a dot in the close slot (or after the title when not closable) says
   *     that its content has unsaved changes, turning into the cross while the pointer is over the
   *     tab's right end; a tree dump says {@code modified}
   * @param icon before the title, in the title's color, such as a table's; null for none
   * @param hue a color that marks the tab as one of a group, as the tabs of a connection to
   *     production: a vertical tick in the leading padding in the hue's edge color of the theme;
   *     null for none
   * @param busy whether its content works, as a query that runs: a {@link Progress#spinner} in
   *     place of the icon, or before the title without one, so that it shows while another tab is
   *     active; a tree dump says {@code busy}
   * @param tooltip shown while the pointer rests on the tab, such as a file's whole path; null for
   *     none
   */
  public record Tab(
      String title,
      boolean closable,
      boolean modified,
      Icon icon,
      Theme.Hue hue,
      boolean busy,
      String tooltip) {

    public Tab {
      Objects.requireNonNull(title, "title");
    }

    /** A tab without a tooltip. */
    public Tab(
        String title, boolean closable, boolean modified, Icon icon, Theme.Hue hue, boolean busy) {
      this(title, closable, modified, icon, hue, busy, null);
    }

    /** A tab that is not busy. */
    public Tab(String title, boolean closable, boolean modified, Icon icon, Theme.Hue hue) {
      this(title, closable, modified, icon, hue, false);
    }

    /** A tab without a color mark. */
    public Tab(String title, boolean closable, boolean modified, Icon icon) {
      this(title, closable, modified, icon, null);
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
    public Tab icon(Icon icon) {
      return new Tab(title, closable, modified, icon, hue, busy, tooltip);
    }

    /**
     * This tab with a color mark, which a tree dump says as its value, the hue's name in lower
     * case ({@code value="red"}).
     */
    public Tab hue(Theme.Hue hue) {
      return new Tab(title, closable, modified, icon, hue, busy, tooltip);
    }

    /** This tab busy or not, as {@link #busy()} says. */
    public Tab busy(boolean busy) {
      return new Tab(title, closable, modified, icon, hue, busy, tooltip);
    }

    /** This tab with a tooltip, such as a file's whole path; null for none. */
    public Tab tooltip(String tooltip) {
      return new Tab(title, closable, modified, icon, hue, busy, tooltip);
    }
  }

  private Tabs() {}

  /** Told that the user moved the tab at {@code from} so that it is now at {@code to}. */
  @FunctionalInterface
  public interface Move {
    void move(int from, int to);
  }

  /** Told that the user dropped a tab of another bar into this one. */
  @FunctionalInterface
  public interface Dock {
    /**
     * @param fromBar the {@link Options#id} of the bar the tab comes from
     * @param from its index there
     * @param at the index in this bar before which it goes; the number of tabs for the end
     */
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
  public record TearOff(
      Object bar,
      int index,
      Drag drag,
      float grabX,
      float grabY,
      float firstX,
      float firstY,
      Windows.Handle window) {}

  /**
   * What the app keeps of the drag of a tab: which tab, whether it left its bar, and the bar that
   * shows where it would go. Bars that share one state take each other's tabs ({@link
   * Options#onDock}), in any window of the runner; it is also what the drag carries ({@link
   * El#dragPayload}). The tabs change it; the app only creates it, and tells it with {@link
   * #moved} where it put a tab that it moved during a drag.
   */
  public static final class Dragging {
    Object bar; // the bar of the tab being dragged, by Options.id
    Object startBar; // the bar the drag began in, whose window holds the press
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
      startBar = null;
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
   * What the app keeps of a tab bar's overflow menu ({@link Options#overflow}): the menu state and
   * the widths layout gave the bar and its tabs. The tabs change it; the app only creates it and
   * keeps one per bar.
   */
  public static final class Overflow {
    final Menu.State menu = new Menu.State();
    float barWidth = Float.NaN;
    float addWidth = Float.NaN;
    float[] tabWidths = new float[0];
    Object revealKey;
    float revealX;
    float revealWidth;

    /** The menu of all tabs. */
    public Menu.State menu() {
      return menu;
    }

    /** Grows only: a tab that comes back at an index tells no size again if its size is kept. */
    void resize(int count) {
      if (tabWidths.length < count) {
        int prev = tabWidths.length;
        tabWidths = Arrays.copyOf(tabWidths, count);
        Arrays.fill(tabWidths, prev, count, Float.NaN);
      }
    }

    void tabWidth(int index, float width) {
      if (index < tabWidths.length) {
        tabWidths[index] = width;
      }
    }

    boolean overflows(int count, float available, float gap) {
      if (count == 0 || Float.isNaN(barWidth) || Float.isNaN(available)) {
        return false;
      }
      float total = -gap;
      boolean any = false;
      for (int i = 0; i < count; i++) {
        float w = tabWidths[i];
        if (Float.isNaN(w)) {
          return any && total > available;
        }
        total += gap + w;
        any = true;
      }
      return total > available;
    }

    /** Scrolls the list to show this tab of the view the user chose it in, once. */
    void reveal(El tab, El list) {
      revealKey = new Object();
      revealX = tab.x() + (float) list.scrollX();
      revealWidth = tab.width();
    }
  }

  /**
   * What the tabs do besides choosing and closing: adding, an overflow menu, moving, tearing off,
   * docking and a context menu, and how they look. Each is off until a call turns it on; the view
   * makes the options anew with the tabs.
   */
  public static final class Options {
    private Dragging dragging;
    private Object id;
    private Runnable onAdd;
    private Overflow overflow;
    private Move onMove;
    private Consumer<TearOff> onTearOff;
    private boolean tearOffAtStart;
    private boolean tearOffOutside;
    private boolean switchKeys = true;
    private boolean active = true;
    private Object focusBar;
    private El panelOverlay;
    private Consumer<El> onTablist;
    private Predicate<Object> acceptsExternal;
    private ObjIntConsumer<Object> onExternalDrop;
    private BiConsumer<El, Drop> onPanelDrop;
    private Dock onDock;
    private Menu.State menu;
    private List<Menu.Item> menuItems = List.of();
    private Variant variant = Variant.CARD;

    private Options() {}

    /** How the tabs look; {@link Variant#CARD} by default. */
    public Options variant(Variant variant) {
      this.variant = Objects.requireNonNull(variant, "variant");
      return this;
    }

    /**
     * Lets the user add a tab, by a plus button after the last tab (pinned at the bar's end once
     * the tabs overflow, and shown in an empty bar too) and with Control+T from inside the tabs;
     * null, the default, shows no button.
     */
    public Options onAdd(Runnable handler) {
      this.onAdd = handler;
      return this;
    }

    /**
     * Shows a button at the bar's end, after any {@link #onAdd} button, while the tabs do not
     * fit. It opens a dropdown that lists every tab in bar order; choosing a tab selects it and
     * scrolls it into view, also when it is already active. The app keeps one {@link Overflow}
     * state per bar.
     */
    public Options overflow(Overflow state) {
      this.overflow = Objects.requireNonNull(state, "state");
      return this;
    }

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

    /**
     * Whether a drag tears its tab off only once the pointer leaves every window of the app
     * ({@link Drag#outsideAll}) instead of on leaving the bar by more than {@link
     * Theme.Sizes#control}; false by default. With this on, dragging over the content of the
     * window stays inside, as for the drop zones of {@link TabGroups#splits}, and without a window
     * runner a drag never tears off. On Wayland, where the pointer is not reported outside a
     * window, a tab's menu is the way to move it to a new window.
     */
    public Options tearOffOutside(boolean on) {
      this.tearOffOutside = on;
      return this;
    }

    /**
     * Whether Control+Tab, Control+Shift+Tab, Control+Page Down and Control+Page Up choose this
     * bar's tabs; true by default. Off, the bar leaves these four to what encloses it, so that in
     * tabs nested in the content of other tabs they choose the outer tabs while the focus is in the
     * inner ones. Left, Right, Home and End on the bar, and the keys that close and move a tab,
     * work as before.
     */
    public Options switchKeys(boolean on) {
      this.switchKeys = on;
      return this;
    }

    /**
     * Whether this bar is the active group's: in {@link Variant#CARD}, only the active group shows
     * the accent mark along the top of its active tab; in {@link Variant#LINE}, it draws the
     * underline in {@link Theme.Accent#edge} instead of {@link Theme.Accent#edgeMuted}; true by
     * default.
     */
    public Options active(boolean on) {
      this.active = on;
      return this;
    }

    /** Moves the focus to this bar's tablist when {@code key} is new; used by {@link TabGroups}. */
    Options focusBar(Object key) {
      this.focusBar = key;
      return this;
    }

    /** Places {@code overlay} last inside the panel, over the tab's content; used by {@link TabGroups}. */
    Options panelOverlay(El overlay) {
      this.panelOverlay = overlay;
      return this;
    }

    /** Reports this bar's {@code #tablist} element when built; used by {@link TabGroups}. */
    Options onTablist(Consumer<El> listener) {
      this.onTablist = listener;
      return this;
    }

    /**
     * Takes non-tab drags dropped on the bar: {@code accepts} decides on {@link Drop.Phase#OVER}
     * whether to show the insertion marker, and {@code drop} receives the payload and slot on
     * {@link Drop.Phase#DROP}; used by {@link TabGroups}.
     */
    Options onExternalDrop(Predicate<Object> accepts, ObjIntConsumer<Object> drop) {
      this.acceptsExternal = accepts;
      this.onExternalDrop = drop;
      return this;
    }

    /**
     * Handles drops that reach the panel itself (not taken by its content); used by {@link
     * TabGroups}.
     */
    Options onPanelDrop(BiConsumer<El, Drop> handler) {
      this.onPanelDrop = handler;
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
   * active} is afterwards, for an {@link Options#onMove} handler: {@code
   * state.active = move(state.tabs, state.active, from, to)}.
   */
  public static <T> int move(List<T> list, int active, int from, int to) {
    list.add(to, list.remove(from));
    return moved(active, from, to);
  }

  /** Where the element at {@code index} is after the one at {@code from} moved to {@code to}. */
  private static int moved(int active, int from, int to) {
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

  /** The height of the list and its tabs. */
  private static float height(Theme theme, Options o) {
    if (o.variant == Variant.LINE) {
      return theme.size().control();
    }
    return theme.size().control() + theme.space().s() + theme.size().stroke();
  }

  /** A step of the drag of the tab at {@code index} of the bar whose tabs are {@code built}. */
  private static void drag(Theme theme, Options o, int index, El list, List<El> built, Drag d) {
    Dragging g = o.dragging;
    Drag.Phase phase = d.phase();
    if (phase == Drag.Phase.START) {
      g.clear();
      g.bar = o.id;
      g.startBar = o.id;
      g.index = index;
      g.grabX = (float) d.x() - d.dx();
      g.grabY = (float) d.y() - d.dy();
      // The tabs lie side by side in the list, which is where the first one lies in the window.
      g.firstX =
          d.windowX() - (float) d.x() - (built.get(index).x() - built.getFirst().x());
      g.firstY = d.windowY() - (float) d.y();
      g.window = d.window();
      g.torn = o.onTearOff != null && o.tearOffAtStart;
      // The bar's drop handler ran before this step, if the pointer is still over the bar.
      if (!g.torn && o.onMove != null && d.y() >= 0 && d.y() < height(theme, o)) {
        mark(theme, o, list, built, built.get(index).x() + (float) d.x());
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
    float reach = theme.size().control();
    boolean leave = o.tearOffOutside
        ? d.outsideAll()
        : d.outside() || d.y() < -reach || d.y() > height(theme, o) + reach;
    if (moving && !g.torn && o.onTearOff != null && leave) {
      g.torn = true;
    }
    if (g.torn) {
      o.onTearOff.accept(
          new TearOff(g.bar, g.index, d, g.grabX, g.grabY, g.firstX, g.firstY, g.window));
    }
    if (!moving) {
      g.clear();
    }
  }

  /** What a drag of a tab does over a bar: the marker, scrolling the list along, the drop. */
  private static void over(Theme theme, Options o, El list, List<El> built, Drop d) {
    Dragging g = o.dragging;
    if (d.payload() != g) {
      if (d.phase() == Drop.Phase.LEAVE || o.acceptsExternal == null || o.onExternalDrop == null) {
        if (Objects.equals(g.overBar, o.id)) {
          g.overBar = null;
          g.slot = -1;
          g.reveal = null;
        }
        return;
      }
      float x = (float) d.x() - list.x(); // the pointer in the list as shown
      if (d.phase() == Drop.Phase.OVER) {
        if (o.acceptsExternal.test(d.payload())) {
          mark(theme, o, list, built, x);
        } else if (Objects.equals(g.overBar, o.id)) {
          g.overBar = null;
          g.slot = -1;
          g.reveal = null;
        }
        return;
      }
      int slot = slot(built, x); // the drop
      if (Objects.equals(g.overBar, o.id)) {
        g.overBar = null;
        g.slot = -1;
        g.reveal = null;
      }
      o.onExternalDrop.accept(d.payload(), slot);
      return;
    }
    if (!g.isActive()) {
      return;
    }
    boolean own = Objects.equals(g.bar, o.id);
    boolean takes = own && !g.torn ? o.onMove != null : o.onDock != null;
    if (d.phase() == Drop.Phase.LEAVE
        || !takes
        || own && o.tearOffAtStart && o.onTearOff != null) { // its window moves along
      if (Objects.equals(g.overBar, o.id)) {
        g.overBar = null;
        g.slot = -1;
      }
      return;
    }
    float x = (float) d.x() - list.x(); // the pointer in the list as shown
    if (d.phase() == Drop.Phase.OVER) {
      mark(theme, o, list, built, x);
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
  private static void mark(Theme theme, Options o, El list, List<El> built, float x) {
    Dragging g = o.dragging;
    int slot = slot(built, x);
    float gap = theme.space().xs();
    float line = theme.size().indicator();
    float edge = built.isEmpty()
        ? 0
        : slot < built.size()
            ? built.get(slot).x() - gap / 2
            : built.getLast().x() + built.getLast().width() + gap / 2;
    g.overBar = o.id;
    g.slot = slot;
    g.marked = g.steps;
    g.markerX = list.x() + Math.clamp(edge - line / 2, 0, Math.max(0, list.width() - line));
    // Near an end of the list, each step scrolls it a little further that way.
    float near = theme.size().control();
    float step = theme.space().m();
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
      List<Tab> tabs, int active, IntConsumer onSelect, IntConsumer onClose, El content) {
    return tabs(tabs, active, onSelect, onClose, content, options());
  }

  /**
   * The tabs with what {@code options} turns on: adding, moving, tearing off, docking, a menu, a
   * look.
   */
  public static El tabs(
      List<Tab> tabs,
      int active,
      IntConsumer onSelect,
      IntConsumer onClose,
      El content,
      Options options) {
    Theme theme = Theme.CURRENT.get();
    boolean enabled = onSelect != null;
    Dragging dragging = options.dragging;
    Overflow overflow = options.overflow;
    if (overflow != null) {
      overflow.resize(tabs.size());
      if (!enabled) {
        overflow.menu.close();
      }
    }
    boolean drags =
        enabled && dragging != null && (options.onMove != null || options.onTearOff != null);
    // Each input reaches the handlers of a view built after the one before it, so this view's
    // indexes are those of the state.
    IntConsumer select = !enabled
        ? null
        : i -> {
          if (i != active) {
            onSelect.accept(i);
          }
        };
    IntConsumer close = onClose;
    boolean line = options.variant == Variant.LINE;
    float height = height(theme, options);
    float barPadX = theme.space().s();
    // Its top corners follow the card's, inside its border and the bar's padding, so that the
    // focus ring, which the card's clip puts inside the bar, follows them too.
    float corner =
        line ? theme.radius().small() : theme.radius().large() - theme.size().stroke() - barPadX;
    El list = row()
        .key("tablist")
        .role("tablist")
        .radius(corner, corner, 0, 0)
        .gap(theme.space().xs())
        .scrollBoth();
    // Sideways only: the wheel moves the tabs left and right.
    list.style().overflowY = Style.Overflow.HIDDEN;
    // Shrinks below its tabs so trailing buttons stay at the bar's end once the tabs overflow.
    list.style().minWidth = Len.ZERO;
    // As high without tabs as with them, so that an empty bar still takes a tab dropped on it.
    list.style().minHeight = Len.px(height);
    boolean indicator = active >= 0 && active < tabs.size() && line;
    list.revealRow(indicator ? active + 1 : active);
    int indicatorColor = options.active ? theme.accent().edge() : theme.accent().edgeMuted();
    if (indicator) {
      // One element under whichever tab is active, so that it slides there; the first child, so
      // that the tabs lie over it and take the presses, also where it slides across them.
      float stroke = theme.size().indicator();
      float inset = theme.space().m();
      El under = el().key("indicator")
          .inert()
          .follow("tab-" + active)
          .absolute(inset, height - stroke)
          .h(stroke)
          .radius(stroke, stroke, 0, 0)
          .fill(indicatorColor)
          .motion(theme.motion().move());
      under.style().right = Len.px(inset);
      list.child(under);
    }
    List<El> built = new ArrayList<>();
    for (int i = 0; i < tabs.size(); i++) {
      boolean grabbed = dragging != null && dragging.on(options.id, i);
      // While a tab is dragged the others do not light up: the drag's element is the one under
      // the pointer, by its key, which another tab has once the dragged one left.
      El tab = tab(
              theme,
              tabs.get(i),
              i,
              i == active,
              line,
              height,
              grabbed && !dragging.torn,
              dragging != null && dragging.isActive(),
              select,
              close)
          .grabbed(grabbed);
      // Not while a tab is dragged: over the bars a tooltip would cover the drop marker.
      if (tabs.get(i).tooltip() != null && (dragging == null || !dragging.isActive())) {
        Tooltip.tooltip(tab, tabs.get(i).tooltip());
      }
      Theme.Hue hue = tabs.get(i).hue();
      if (hue != null) {
        float indicatorWidth = theme.size().indicator();
        float tickH = theme.size().icon() - theme.space().s();
        float innerH = line ? height : height - theme.size().stroke();
        float tickX = (theme.space().m() - indicatorWidth) / 2;
        float tickY = (innerH - tickH) / 2;
        El tick = el().key("hue-" + i)
            .inert()
            .absolute(tickX, tickY)
            .size(indicatorWidth, tickH)
            .radius(indicatorWidth / 2)
            .fill(theme.withAccent(hue).accent().edge());
        tab.value(hue.name().toLowerCase(java.util.Locale.ROOT)).child(tick);
      }
      if (!line && i == active && options.active) {
        float indicatorH = theme.size().indicator();
        float stroke = theme.size().stroke();
        float inset = theme.radius().medium() - stroke;
        El mark = el().key("indicator")
            .inert()
            .absolute(inset, -stroke)
            .h(indicatorH)
            .radius(0, 0, indicatorH, indicatorH)
            .fill(theme.accent().edge());
        mark.style().right = Len.px(inset);
        tab.child(mark);
      }
      if (drags) {
        int index = i;
        tab.dragPayload(dragging).onDrag(d -> drag(theme, options, index, list, built, d));
      }
      if (enabled && options.menu != null) {
        int index = i;
        tab.onSecondaryPress((x, y) -> {
          select.accept(index);
          options.menu.openAt(x, y);
        });
      }
      if (enabled && overflow != null) {
        int index = i;
        tab.onResize((w, h) -> overflow.tabWidth(index, w));
      }
      built.add(tab);
      list.child(tab);
    }
    if (options.onTablist != null) {
      options.onTablist.accept(list);
    }
    float tabGap = theme.space().xs();
    float barGap = line ? theme.space().xs() : theme.space().s();
    El bar = row()
        .key("bar")
        .fill(line ? 0 : theme.surface().window())
        .gap(barGap)
        .align(Style.Align.CENTER)
        .shrink(0);
    if (!line) {
      bar.padding(theme.space().s(), barPadX, 0, barPadX);
      El divider = el().inert()
          .absolute(0, 0)
          .wFull()
          .hFull()
          .border(0, 0, theme.size().stroke(), 0, theme.border().divider());
      bar.child(divider);
    } else {
      bar.padding(0, barPadX)
          .border(0, 0, theme.size().stroke(), 0, theme.border().divider());
    }
    bar.child(list);
    if (enabled && overflow != null) {
      bar.onResize((w, h) -> overflow.barWidth = w);
    }
    if (enabled && options.onAdd != null) {
      // In a row without motion of its own, so that the button's spring leaves its place alone
      // and it follows the bar at once when the tabs or the width change.
      El add = row()
          .shrink(0)
          .child(Button.icon(
                  Button.Variant.GHOST,
                  Symbol.ADD,
                  Texts.CURRENT.get().newTab(),
                  options.onAdd,
                  Button.options().size(Button.Size.SMALL).shortcut(Key.CONTROL, "T"))
              .key("add"));
      if (overflow != null) {
        add.onResize((w, h) -> overflow.addWidth = w);
      }
      bar.child(add);
    }
    if (enabled && overflow != null) {
      // While the tabs fit, an empty room as wide as the button and its gap stands in their place
      // and gives way first, so that the bar asks for the same width with the button and without.
      float side = theme.size().controlSmall();
      float available = overflow.barWidth - 2 * barPadX;
      if (options.onAdd != null) {
        available -= overflow.addWidth + barGap;
      }
      if (overflow.overflows(tabs.size(), available, tabGap)) {
        List<Menu.Item> items = new ArrayList<>(tabs.size());
        for (int i = 0; i < tabs.size(); i++) {
          int index = i;
          items.add(Menu.item(tabs.get(i).title(), () -> {
                if (index == active) { // another tab comes into view as the active one
                  overflow.reveal(built.get(index), list);
                }
                select.accept(index);
              })
              .icon(tabs.get(i).icon())
              .checked(i == active));
        }
        El button = Button.icon(
                Button.Variant.GHOST,
                Symbol.CHEVRON_DOWN,
                Texts.CURRENT.get().allTabs(),
                overflow.menu::toggle,
                Button.options().size(Button.Size.SMALL))
            .key("overflow")
            .expanded(overflow.menu.isOpen());
        bar.child(row().shrink(0).child(Menu.dropdown(button, overflow.menu, items)));
      } else {
        overflow.menu.close();
        list.shrink(0);
        El room = el().key("room").w(side + barGap);
        room.style().minWidth = Len.ZERO;
        room.style().marginLeft = Len.px(-barGap); // the bar's gap before it is part of its width
        bar.child(room);
      }
      if (overflow.revealKey != null) {
        list.reveal(overflow.revealKey, overflow.revealX, 0, overflow.revealWidth, height);
        overflow.revealKey = null; // once: a later build must not bring the old choice back
      }
    }
    if (enabled && dragging != null) {
      bar.onDrop(d -> over(theme, options, list, built, d));
      if (dragging.slot >= 0 && Objects.equals(dragging.overBar, options.id)) {
        float side = theme.size().control();
        float markerTop = (line ? 0 : theme.space().s()) + (height - side) / 2;
        bar.child(el().key("marker")
            .absolute(dragging.markerX, markerTop)
            .size(theme.size().indicator(), side)
            .fill(theme.accent().edge()));
        if (dragging.reveal != null) {
          list.reveal(dragging.reveal, dragging.revealX, 0, 1, 1);
        }
      }
    }
    if (enabled && options.menu != null) { // a press beside the tabs opens the active one's menu
      Menu.contextMenu(bar, options.menu, options.menuItems);
    }
    El panel = col()
        .key("panel")
        .role("tabpanel")
        .label(active >= 0 && active < tabs.size() ? tabs.get(active).title() : null)
        .grow(1);
    panel.style().minHeight = Len.ZERO;
    if (content != null) {
      // No minimum from the content either, so that a list of rows deep inside it scrolls.
      content.style().minWidth = Len.ZERO;
      content.style().minHeight = Len.ZERO;
      panel.child(content.grow(1));
    }
    if (options.panelOverlay != null) {
      panel.child(options.panelOverlay);
    }
    if (enabled && options.onPanelDrop != null) {
      panel.onDrop(d -> options.onPanelDrop.accept(panel, d));
    }
    El root = (line ? col() : Card.surface(theme, Card.Variant.OUTLINED)).children(bar, panel);
    // It fills the room it is given: a list of rows in the content is no minimum.
    root.style().minWidth = Len.ZERO;
    root.style().minHeight = Len.ZERO;
    if (!enabled) {
      return root;
    }
    if (!tabs.isEmpty()) {
      int last = tabs.size() - 1;
      // Tab reaches the bar; a press on a tab leaves the focus where it is, so that from the
      // content it goes on into the new content.
      if (options.menu != null) { // the Menu key and Shift+F10 on the bar: the active tab's menu
        list.onSecondaryPress(options.menu::openAt);
      }
      if (options.focusBar != null) {
        list.takeFocus(options.focusBar);
      }
      list.focusable().keepFocus().focusRing(theme.accent().edge()).onKey(k -> {
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
    } else if (options.onAdd == null) {
      return root;
    }
    return root.onKey(k -> {
      String name = k.name();
      if (k.modifiers() == Key.CONTROL && name.equals("T") && options.onAdd != null) {
        if (options.menu != null) {
          options.menu.close();
        }
        options.onAdd.run();
        return true;
      }
      if (tabs.isEmpty()) {
        return false;
      }
      int last = tabs.size() - 1;
      int from = active;
      boolean tab =
          options.switchKeys && name.equals("Tab") && (k.modifiers() & ~Key.SHIFT) == Key.CONTROL;
      boolean page = options.switchKeys
          && k.modifiers() == Key.CONTROL
          && (name.equals("PageDown") || name.equals("PageUp"));
      if (tab || page) {
        boolean back = tab ? (k.modifiers() & Key.SHIFT) != 0 : name.equals("PageUp");
        if (options.menu != null) { // its items act on the active tab, which changes
          options.menu.close();
        }
        select.accept(
            from < 0 ? (back ? last : 0) : Math.floorMod(from + (back ? -1 : 1), tabs.size()));
        return true;
      }
      if (k.modifiers() == (Key.CONTROL | Key.SHIFT)
          && options.onMove != null
          && from >= 0
          && (name.equals("PageUp") && from > 0 || name.equals("PageDown") && from < last)) {
        int to = name.equals("PageUp") ? from - 1 : from + 1;
        // A tab being dragged in this bar stays the one dragged, wherever the move puts it.
        boolean follows =
            dragging != null && dragging.isActive() && Objects.equals(dragging.bar, options.id);
        int dragged = follows ? moved(dragging.index, from, to) : -1;
        options.onMove.move(from, to);
        if (follows) {
          dragging.index = dragged;
        }
        return true;
      }
      if (k.modifiers() == Key.CONTROL
          && (name.equals("W") || name.equals("F4"))
          && close != null
          && from >= 0
          && from <= last
          && tabs.get(from).closable()) {
        if (options.menu != null) { // the active tab's menu ends with it
          options.menu.close();
        }
        close.accept(from);
        return true;
      }
      return false;
    });
  }

  private static El tab(
      Theme theme,
      Tab tab,
      int index,
      boolean active,
      boolean line,
      float height,
      boolean muted,
      boolean quiet,
      IntConsumer onSelect,
      IntConsumer onClose) {
    boolean enabled = onSelect != null;
    boolean hasClose = enabled && tab.closable() && onClose != null;
    Theme.Surfaces s = theme.surface();
    int ink = !enabled || muted
        ? theme.text().disabled()
        : active ? theme.text().primary() : theme.text().muted();
    float leadPad = theme.space().m();
    float trailPad = hasClose ? 0 : theme.space().m();
    float gap = theme.space().s();
    El el = row()
        .key("tab-" + index)
        .role("tab")
        .label(tab.title())
        .selected(active)
        .busy(tab.busy())
        .modified(tab.modified())
        .disabled(!enabled)
        .h(height)
        .padding(0, trailPad, 0, leadPad)
        .gap(gap)
        .align(Style.Align.CENTER)
        .shrink(0);
    if (!line) {
      float stroke = theme.size().stroke();
      el.radius(theme.radius().medium(), theme.radius().medium(), 0, 0)
          .border(stroke, stroke, 0, stroke, active ? theme.border().divider() : 0);
      if (active) {
        el.fill(s.panel());
      }
    }
    El hoverPill = null;
    if (enabled && !active && !quiet) {
      if (!line) {
        float stroke = theme.size().stroke();
        hoverPill = el().inert()
            .absolute(-stroke, -stroke)
            .radius(theme.radius().medium(), theme.radius().medium(), 0, 0);
        hoverPill.style().right = Len.px(-stroke);
        hoverPill.style().bottom = Len.px(stroke);
      } else {
        float pillH = theme.size().control();
        hoverPill = el().inert()
            .absolute(0, (height - pillH) / 2)
            .wFull()
            .h(pillH)
            .radius(theme.radius().medium());
      }
      el.child(hoverPill);
    }
    List<El> litParts = new ArrayList<>();
    if (tab.busy()) {
      float iconSide = theme.size().icon();
      float spinnerSide = iconSide - theme.space().s();
      el.child(
          el().size(iconSide, iconSide).shrink(0).center().child(Progress.spinner(spinnerSide)));
    } else if (tab.icon() != null) {
      El icon = Icon.Picture.color(
          Icon.icon(tab.icon(), enabled ? Icon.Variant.NORMAL : Icon.Variant.DISABLED), ink);
      el.child(icon);
      litParts.add(icon);
    }
    El title = label(tab.title()).color(ink);
    el.child(title);
    litParts.add(title);
    float dotSide = theme.size().dot() + theme.space().xs();
    El trailingDot = null;
    if (tab.modified() && !hasClose) {
      trailingDot = el().size(dotSide, dotSide).shrink(0).radius(dotSide / 2).fill(ink);
      el.child(trailingDot);
    }
    if (!enabled) {
      return el;
    }
    El slotDot = null;
    if (hasClose) {
      float side = theme.size().checkbox();
      float glyphSize = theme.size().icon() - theme.space().s();
      int dismissInk = muted ? theme.text().disabled() : theme.text().muted();
      El dismiss = Icon.icon(Symbol.DISMISS, Icon.Variant.NORMAL, glyphSize).color(dismissInk);
      El close = el().key("close-" + index)
          .role("button")
          .label(Texts.CURRENT.get().closeTab(tab.title()))
          .size(side, side)
          .shrink(0)
          .center()
          .radius(theme.radius().small())
          .cursor(Cursor.POINTER)
          .onPress(() -> onClose.accept(index));
      if (tab.modified()) {
        slotDot = el().inert()
            .absolute((side - dotSide) / 2, (side - dotSide) / 2)
            .size(dotSide, dotSide)
            .radius(dotSide / 2)
            .fill(ink);
        dismiss.opacity(0);
        close.opacity(1).child(slotDot).child(dismiss);
      } else {
        close.opacity(active ? 1 : 0).child(dismiss);
      }
      // Spans the tab's right end from the title's end (covering the gap before the close slot) to
      // the tab's right edge, so the cross appears only when the pointer moves toward it.
      El closer = el().hFull().padding(0, gap, 0, gap).center().shrink(0).child(close);
      closer.style().marginLeft = Len.px(-gap);
      if (!quiet) {
        El closeDot = slotDot;
        if (closeDot != null) {
          closer.whenHovered(e -> {
            closeDot.opacity(0);
            dismiss.opacity(1);
          });
        } else if (!active) {
          closer.whenHovered(e -> close.opacity(1));
        }
        close
            .whenHovered(e -> {
              e.fill(s.hover());
              dismiss.color(theme.text().primary());
            })
            .whenPressed(e -> {
              e.opacity(1);
              e.fill(s.pressed());
              dismiss.color(theme.text().primary());
              if (closeDot != null) {
                closeDot.opacity(0);
                dismiss.opacity(1);
              }
            });
      }
      el.child(closer);
    }
    el.cursor(Cursor.POINTER).onPress(() -> onSelect.accept(index));
    if (quiet || active) {
      return el;
    }
    El pill = hoverPill;
    El closeDot = slotDot;
    El trailDot = trailingDot;
    Consumer<El> lit = e -> {
      for (El part : litParts) {
        Icon.Picture.color(part, theme.text().primary());
      }
      if (trailDot != null) {
        trailDot.fill(theme.text().primary());
      }
      if (closeDot != null) {
        closeDot.fill(theme.text().primary());
      }
    };
    return el.whenHovered(lit.andThen(e -> pill.fill(s.hover())))
        .whenPressed(lit.andThen(e -> pill.fill(s.pressed())));
  }
}
