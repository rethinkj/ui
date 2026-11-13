package com.rethinkj.ui.kit;

import com.rethinkj.ui.Drag;
import com.rethinkj.ui.El;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.Windows;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Tabs in several windows, as in browsers and editors: groups of tabs, the main one in the app's
 * first window and each other one in a window of its own, whose tabs the user moves along a bar,
 * drags out into a new window that follows the pointer, and drops into a bar of any of them. The
 * app keeps one in its state and builds each window's tabs with {@link #tabs}; everything the user
 * does goes through it, so it is the app's state and a script can read it.
 *
 * <pre>{@code
 * final TabGroups<Doc> groups = new TabGroups<>(docs, (group, options) -> app.windows().open(
 *     group.activeTab().title, 520, 340, options.frameless(),
 *     new App<>(this, s -> s.windowView(group))));
 *
 * El windowView(TabGroups.Group<Doc> group) { // also the main window's, with groups.main()
 *   return col().wFull().hFull().children(titleBar(...),
 *       groups.tabs(theme, group, d -> new Tab(d.title), d -> editor(d)).grow(1));
 * }
 * }</pre>
 *
 * <p>The opener decides how a group's window looks, its title bar and size; the options it gets
 * say where the window opens, which it may add to ({@link Window.Options#frameless}, an icon) and
 * passes to {@link Windows#open} with an {@link com.rethinkj.ui.App} of the same state, so that a
 * change in one window shows in all ({@link Windows}).
 *
 * <ul>
 *   <li>A tab dragged out of its bar ({@link Tabs.Options#onTearOff}) leaves its group for a new
 *       one, whose window opens under the pointer with the tab where the pointer holds it and
 *       follows the pointer until the release. Where the release lands outside every bar the
 *       window stays there; over a bar of a group the tab goes there, before the tab the marker
 *       shows, and its window closes. The window under the pointer that takes it is raised.
 *   <li>The only tab of a window drags the whole window, from the first step, and docks the same
 *       way. A bar of the main window lets its last tab go, which leaves the main group empty.
 *   <li>Escape or a lost release gives a drag up: a tab torn off goes back to where it was, and a
 *       window moved by its only tab goes back to where it lay.
 *   <li>Each tab has a menu ({@link Menu#contextMenu}): Move to new window, and in a group's
 *       window Move back to main window, which do the same without dragging. On Wayland, where no
 *       app places its windows or sees the pointer outside them, they are the way to do it: a
 *       window torn off there opens where the compositor puts it and does not follow.
 *   <li>A group whose last tab leaves or closes closes its window, except the main group. Closing
 *       a group's window ({@link Windows.Handle#onCloseRequest}) runs {@link #onWindowClose}:
 *       {@link #dockAll} unless set, or {@link #closeAll}.
 * </ul>
 *
 * <p>Content is built only for the active tab of each group, and belongs to the app. What the
 * app keeps of elements by their identity, such as a field's caret and undo history or a scroll
 * offset, belongs to the window that showed them: a tab that moves to another window starts
 * there without it, unless the app keeps it in the tab's state.
 *
 * @param <T> the app's tabs, its documents or queries; compared by identity
 */
public final class TabGroups<T> {

  /**
   * Opens the window of a new group, a handler of the app.
   *
   * @param <T> the app's tabs
   */
  @FunctionalInterface
  public interface Opener<T> {
    /**
     * Opens a window for {@code group}, which holds one tab, and returns it.
     *
     * @param options where the window opens, if anywhere in particular: add the app's own and pass
     *     them to {@link Windows#open}
     */
    Windows.Handle open(Group<T> group, Window.Options options);
  }

  /**
   * A group of tabs: the main one, in the app's first window, or one in a window of its own.
   *
   * @param <T> the app's tabs
   */
  public static final class Group<T> {
    final List<T> tabs = new ArrayList<>();
    int active = -1;
    Windows.Handle window; // null for the main group
    final Menu.State menu = new Menu.State();

    private Group() {}

    /** The tabs, from the left. */
    public List<T> tabs() {
      return Collections.unmodifiableList(tabs);
    }

    /** The index of the active tab, -1 while the group is empty. */
    public int active() {
      return active;
    }

    /** The active tab, or null. */
    public T activeTab() {
      return active < 0 ? null : tabs.get(active);
    }

    /** The group's window, or null for the main group, whose window is the app's first. */
    public Windows.Handle window() {
      return window;
    }

    @Override
    public String toString() {
      return (window == null ? "main" : window.title()) + " " + tabs + " active " + active;
    }
  }

  private final Opener<T> opener;
  private final Group<T> main = new Group<>();
  private final List<Group<T>> groups = new ArrayList<>(List.of(main));
  private final Tabs.Dragging dragging = new Tabs.Dragging();
  private Consumer<Group<T>> onWindowClose = this::dockAll;
  private Consumer<T> onClosed = tab -> {};

  // The drag that has left its bar, while it lasts.
  private Group<T> following; // the group whose window follows the pointer
  private boolean placed; // its first tab's place in its window is known from its own tree
  private float firstX; // where its first tab lies in its window
  private float firstY;
  private float tabHeight = Float.NaN; // the height of that tab, once known
  private Group<T> origin; // where a torn tab came from, to go back on a cancel; null if none
  private int originIndex;
  private int startX; // where a window moved by its only tab lay
  private int startY;

  /**
   * @param tabs the main group's tabs, the first active
   * @param opener opens the window of a new group
   */
  public TabGroups(List<T> tabs, Opener<T> opener) {
    this.opener = Objects.requireNonNull(opener, "opener");
    main.tabs.addAll(tabs);
    main.active = tabs.isEmpty() ? -1 : 0;
  }

  /** What closing a group's window does: {@link #dockAll} unless set, or {@link #closeAll}. */
  public TabGroups<T> onWindowClose(Consumer<Group<T>> handler) {
    this.onWindowClose = Objects.requireNonNull(handler, "handler");
    return this;
  }

  /** Told of each tab the user closes, after it left its group: to cancel its work, say. */
  public TabGroups<T> onClosed(Consumer<T> handler) {
    this.onClosed = Objects.requireNonNull(handler, "handler");
    return this;
  }

  /** The group of the app's first window. */
  public Group<T> main() {
    return main;
  }

  /** Every group, the main one first, then in the order their windows opened. */
  public List<Group<T>> groups() {
    return Collections.unmodifiableList(groups);
  }

  /** The group that holds {@code tab}, or null. */
  public Group<T> groupOf(T tab) {
    for (Group<T> g : groups) {
      for (T t : g.tabs) {
        if (t == tab) {
          return g;
        }
      }
    }
    return null;
  }

  /** Whether a tab of these groups is being dragged. */
  public boolean isDragging() {
    return dragging.isActive();
  }

  /**
   * The tabs of {@code group}, for its window's view: the bar with every tab and the content of
   * the active one, which take the moves, drags, drops and menu described above.
   *
   * @param tab how a tab of the app shows in the bar
   * @param content the content of the active tab, and of none (null) in an empty group, whose
   *     bar still takes tabs; it may return null for no content
   */
  public El tabs(Theme t, Group<T> group, Function<T, Tabs.Tab> tab, Function<T, El> content) {
    boolean alone = group.window != null && group.tabs.size() == 1;
    List<Menu.Item> items = new ArrayList<>();
    items.add(Menu.item("Move to new window", alone || group.active < 0 ? null
        : () -> toNewWindow(group, group.active)));
    if (group.window != null) {
      items.add(Menu.item("Move back to main window",
          () -> dock(group, group.active, main, main.tabs.size())));
    }
    T active = group.activeTab();
    return Tabs.tabs(t, group.tabs.stream().map(tab).toList(), group.active,
        i -> select(group, i), i -> close(group, i), content.apply(active),
        Tabs.options().drag(dragging).id(group)
            .onMove((from, to) -> move(group, from, to))
            .onTearOff(this::torn)
            .tearOffAtStart(alone)
            .onDock((from, i, at) -> dock(cast(from), i, group, at))
            .contextMenu(group.menu, items));
  }

  @SuppressWarnings("unchecked") // the bars' ids are the groups of this object
  private Group<T> cast(Object bar) {
    return (Group<T>) bar;
  }

  /** Makes the tab at {@code index} the active one of its group. */
  public void select(Group<T> group, int index) {
    Objects.checkIndex(index, group.tabs.size());
    group.active = index;
  }

  /** Adds a tab to a group, before the tab at {@code at}, and makes it the active one. */
  public void add(Group<T> group, int at, T tab) {
    group.tabs.add(at, Objects.requireNonNull(tab, "tab"));
    group.active = at;
  }

  /**
   * Closes a tab: it leaves its group, whose next tab, else the one before, becomes active if it
   * was, and {@link #onClosed} is told. A group's window closes with its last tab.
   */
  public void close(Group<T> group, int index) {
    T tab = remove(group, index);
    onClosed.accept(tab);
  }

  /** Moves the tab at {@code from} of a group to {@code to}, as {@link Tabs#move}. */
  public void move(Group<T> group, int from, int to) {
    group.active = Tabs.move(group.tabs, group.active, from, to);
  }

  /**
   * Moves a tab to another group, or within its own, before the tab at {@code at} there as it was
   * before the move, and makes it the active one there. A group's window closes when this leaves
   * it empty.
   */
  public void dock(Group<T> from, int index, Group<T> to, int at) {
    if (from == to) {
      move(from, index, at > index ? at - 1 : at);
      return;
    }
    T tab = remove(from, index);
    add(to, Math.min(at, to.tabs.size()), tab);
  }

  /**
   * Moves a tab into a group of its own, in a window that opens where the system puts it, and
   * returns that group.
   */
  public Group<T> toNewWindow(Group<T> group, int index) {
    return split(group, index, Window.options());
  }

  /** Moves every tab of a group to the end of the main group, which closes its window. */
  public void dockAll(Group<T> group) {
    while (group != main && !group.tabs.isEmpty()) {
      dock(group, 0, main, main.tabs.size());
    }
  }

  /** Closes every tab of a group, which closes its window unless it is the main group. */
  public void closeAll(Group<T> group) {
    while (!group.tabs.isEmpty()) {
      close(group, group.tabs.size() - 1);
    }
  }

  /** Takes a tab out of its group; a group's window closes with its last tab. */
  private T remove(Group<T> group, int index) {
    T tab = group.tabs.remove(index);
    if (group.active > index || group.active == group.tabs.size()) {
      group.active--;
    }
    if (group.tabs.isEmpty() && group != main) {
      groups.remove(group);
      group.window.close();
    }
    return tab;
  }

  private Group<T> split(Group<T> from, int index, Window.Options options) {
    T tab = remove(from, index);
    Group<T> group = new Group<>();
    group.tabs.add(tab);
    group.active = 0;
    groups.add(group);
    group.window = opener.open(group, options);
    group.window.onCloseRequest(() -> {
      if (groups.contains(group)) {
        onWindowClose.accept(group);
      }
    });
    return group;
  }

  /** A step of a drag of a tab that has left its bar. */
  private void torn(Tabs.TearOff o) {
    Drag d = o.drag();
    Group<T> group = cast(o.bar());
    boolean moving = d.phase() == Drag.Phase.START || d.phase() == Drag.Phase.MOVE;
    if (moving && following == null) {
      firstX = Float.isNaN(o.firstX()) ? 0 : o.firstX();
      firstY = Float.isNaN(o.firstY()) ? 0 : o.firstY();
      placed = false;
      if (group.window != null && group.tabs.size() == 1) { // its window moves along
        following = group;
        origin = null;
        startX = group.window.x();
        startY = group.window.y();
        float[] at = firstTab(group.window.app().tree());
        tabHeight = at == null ? Float.NaN : at[2];
        placed = true;
      } else { // a window of its own, under the pointer
        origin = group;
        originIndex = o.index();
        float scale = o.window() == null ? 1 : o.window().screenScale();
        following = split(group, o.index(), Window.options().position(
            Math.round(d.screenX() - (firstX + o.grabX()) * scale),
            Math.round(d.screenY() - (firstY + o.grabY()) * scale)));
        dragging.moved(following, 0);
      }
      following.window.dropTarget(false); // so that the windows under it take the drop
    }
    Group<T> f = following;
    if (f == null) {
      return;
    }
    boolean open = groups.contains(f);
    if (moving && open) {
      if (!placed) { // the new window's own tree says where its tab lies, once it has one
        float[] at = firstTab(f.window.app().tree());
        if (at != null) {
          firstX = at[0];
          firstY = at[1];
          tabHeight = at[2];
          placed = true;
        }
      }
      float scale = f.window.screenScale();
      float top = d.screenY() - (firstY + o.grabY()) * scale;
      if (dragging.slot >= 0) {
        // Over a bar, where its tab would cover the marker: the window moves down below the
        // bar's tabs, out of the way of the marker, and back under the pointer once it leaves.
        float h = Float.isNaN(tabHeight) ? 2 * o.grabY() : tabHeight;
        top = d.screenY() + (h - o.grabY()) * scale;
      }
      f.window.moveTo(Math.round(d.screenX() - (firstX + o.grabX()) * scale), Math.round(top));
      return;
    }
    following = null;
    if (d.phase() == Drag.Phase.CANCEL && open) { // as it was
      if (origin != null && groups.contains(origin)) {
        dock(f, 0, origin, originIndex);
      } else if (origin == null) {
        f.window.moveTo(startX, startY);
      }
    }
    if (groups.contains(f)) {
      f.window.dropTarget(true); // it stays where it was let go
    } else if (d.window() != null) {
      d.window().raise(); // it went into the window under the pointer
    }
    origin = null;
  }

  /**
   * Where the first tab of the first bar of a tree lies in its window, and its height; null
   * without one.
   */
  private static float[] firstTab(El root) {
    return root == null ? null : find(root, 0, 0);
  }

  private static float[] find(El el, float x, float y) {
    if ("tablist".equals(el.role())) {
      if (el.children().isEmpty()) {
        return null;
      }
      El first = el.children().getFirst();
      return new float[] {x + first.x(), y + first.y(), first.height()};
    }
    for (El child : el.children()) {
      float[] at = find(child, x + child.x(), y + child.y());
      if (at != null) {
        return at;
      }
    }
    return null;
  }
}
