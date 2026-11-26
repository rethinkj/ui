package com.rethinkj.ui.kit;

import com.rethinkj.ui.Drag;
import com.rethinkj.ui.Drop;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.Windows;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;

/**
 * Tabs in several windows and split groups, as in browsers and editors: every window has a {@link
 * Frame} holding a binary tree of splits whose leaves are {@link Group}s, the main frame in the
 * app's first window and each other one in a window of its own. The user moves tabs along a bar,
 * splits groups side by side or one above the other when {@link #splits(boolean)} is on, drags a
 * tab out into a new window that follows the pointer, and drops it into a bar of any group. The
 * app keeps one in its state and builds each window's tabs with {@link #tabs}; everything the user
 * does goes through it, so it is the app's state and a script can read it.
 *
 * <pre>{@code
 * final TabGroups<Doc> groups = new TabGroups<>(docs, (frame, options) -> app.windows().open(
 *     frame.active().activeTab().title, 520, 340, options.frameless(),
 *     new App<>(this, state -> state.windowView(frame))));
 *
 * El windowView(TabGroups.Frame<Doc> frame) { // also the main window's, with groups.main()
 *   return col().wFull().hFull().children(titleBar(...),
 *       groups.tabs(frame, doc -> new Tab(doc.title), doc -> editor(doc)).grow(1));
 * }
 * }</pre>
 *
 * <p>The opener decides how a frame's window looks, its title bar and size; the options it gets
 * say where the window opens, which it may add to ({@link Window.Options#frameless}, an icon) and
 * passes to {@link Windows#open} with an {@link com.rethinkj.ui.App} of the same state, so that a
 * change in one window shows in all ({@link Windows}).
 *
 * <ul>
 *   <li>A tab dragged out of its bar ({@link Tabs.Options#onTearOff}) leaves its group for a new
 *       frame, whose window opens under the pointer with the tab where the pointer holds it and
 *       follows the pointer until the release. With {@link #splits(boolean)} on, tear-off starts
 *       only once the pointer leaves every window of the app ({@link Tabs.Options#tearOffOutside});
 *       until then, dragging over any group's panel in the drag's starting window offers drop zones
 *       on top of its content — the outer third toward an edge splits the tab out to that side
 *       where both halves fit {@link Theme.Sizes#minTabGroup}, and the middle docks it at the end
 *       of that group (off on the dragged tab's own group when it holds only that tab), with a
 *       preview overlay ({@code #zone} with {@code value=left|right|top|bottom|center}). Once a
 *       window follows the pointer, only tab bars take the tab. Its options are {@link
 *       Window.Options#underPointer}: no opening animation, and the keyboard focus stays with the
 *       window the drag began in until the release. Where the release lands outside every bar the
 *       window stays there, moved wholly into the work area of its display where the release left
 *       it reaching beyond (not on Wayland, which places windows itself), and takes the focus;
 *       over a bar of a group the tab goes there, before the tab the marker shows, and its window
 *       closes. The window under the pointer that takes it is raised.
 *   <li>The only tab of a torn-off frame with one group drags the whole window, from the first
 *       step, and docks the same way. A bar of the main window's only group lets its last tab go,
 *       which leaves the main group empty.
 *   <li>An emptied source group is pruned only when the drag commits, so Escape or a lost release
 *       gives a drag up and puts a torn-off tab back where it was — including its group, split and
 *       divider position — and moves a window dragged by its only tab back to where it lay.
 *   <li>Each tab has a menu ({@link Menu#contextMenu}): Move to new window, and in a torn-off
 *       window Move back to main window, which do the same without dragging. On Wayland, where no
 *       app places its windows or sees the pointer outside them, they are the way to do it: a
 *       window torn off there opens where the compositor puts it and does not follow. With {@link
 *       #splits(boolean)} on, the menu also has Split right (Control+\) and Split down, disabled
 *       for a group's only tab and where both halves would not get {@link
 *       Theme.Sizes#minTabGroup}; on layouts where {@code \} needs AltGr, such as German, the
 *       menu is the way to split.
 *   <li>A group in a split that empties leaves its frame and its sibling takes the split's place;
 *       when that group had the focus, the focus goes to the group taking its place. A frame's
 *       only group stays when empty in the main frame, or closes its window in another. Closing a
 *       frame's window, by a close request ({@link Windows.Handle#onCloseRequest}) or by the
 *       app's {@link Windows.Handle#close()}, runs {@link #onWindowClose} for a frame still in
 *       {@link #frames()}: {@link #dockAll(Frame)} unless set, or {@link #closeAll(Frame)}.
 * </ul>
 *
 * <p>A frame with one group renders as {@link Tabs#tabs} alone; a split frame renders its tree
 * with {@link Split#cards} (share 0.5 at first, double press resets to half, minimum {@link
 * Theme.Sizes#minTabGroup}) and keys each group's root {@code #group-<id>} from a counter that
 * never repeats. Only the frame's {@link Frame#active() active group} shows the accent line under
 * its active tab; other groups show {@link Theme.Accent#edgeMuted}. A group becomes its frame's
 * active group when the focus moves into it ({@link El#onFocusIn}) or on any press in it,
 * including on its already active tab (which keeps the focus where it is). After a split or a dock
 * with splits, the moved tab is active, its group is the frame's active group, and the focus goes
 * to that group's bar.
 *
 * <p>Content is built only for the active tab of each group, and belongs to the app. It goes into
 * an element of its own for each tab, {@code #content-<n>} in a tree dump, so that the focus is
 * remembered per tab ({@link com.rethinkj.ui.App#focus}): switching tabs and focusing the panel
 * go back to where the focus was in that tab, else to its {@link El#defaultFocus}. What the app
 * keeps of elements by their identity, such as a field's caret and undo history or a scroll
 * offset, belongs to the element's place in its tree: a tab that moves to another window or whose
 * group moves into or out of a split starts there without it, unless the app keeps it in the tab's
 * state ({@link com.rethinkj.ui.FieldState}, {@link com.rethinkj.ui.ScrollState}).
 *
 * @param <T> the app's tabs, its documents or queries; compared by identity
 */
public final class TabGroups<T> {

  /**
   * Opens the window of a new frame, a handler of the app.
   *
   * @param <T> the app's tabs
   */
  @FunctionalInterface
  public interface Opener<T> {
    /**
     * Opens a window for {@code frame}, which holds one group with one tab, and returns it. {@link
     * TabGroups} sets the returned handle's {@link Windows.Handle#onCloseRequest} and {@link
     * Windows.Handle#onClosed(Runnable)} handlers, calling an {@code onClosed} handler the opener
     * set after its own.
     *
     * @param options where the window opens, if anywhere in particular: add the app's own and pass
     *     them to {@link Windows#open}
     */
    Windows.Handle open(Frame<T> frame, Window.Options options);
  }

  private sealed interface Node<T> permits Leaf, Branch {
    /** Adds the groups of this node to {@code into}, top left first. */
    void collect(List<Group<T>> into);
  }

  private static final class Leaf<T> implements Node<T> {
    final Group<T> group;

    Leaf(Group<T> group) {
      this.group = group;
    }

    @Override
    public void collect(List<Group<T>> into) {
      into.add(group);
    }
  }

  private static final class Branch<T> implements Node<T> {
    final Split.Direction direction;
    Split.Position position = Split.Position.share(0.5f);
    Node<T> first;
    Node<T> second;

    Branch(Split.Direction direction, Node<T> first, Node<T> second) {
      this.direction = direction;
      this.first = first;
      this.second = second;
    }

    @Override
    public void collect(List<Group<T>> into) {
      first.collect(into);
      second.collect(into);
    }
  }

  /**
   * A window's tree of tab groups: the main one, in the app's first window, or one in a window of
   * its own.
   *
   * @param <T> the app's tabs
   */
  public static final class Frame<T> {
    Node<T> root;
    Group<T> active;
    Windows.Handle window; // null for the main frame
    Group<T> focusGroup; // group whose bar takes the focus on the next build, or null
    Object focusToken;

    private Frame(Group<T> first) {
      this.root = new Leaf<>(first);
      this.active = first;
    }

    /** The frame's window, or null for the main frame, whose window is the app's first. */
    public Windows.Handle window() {
      return window;
    }

    /** Every group in this frame, top left first. */
    public List<Group<T>> groups() {
      List<Group<T>> list = new ArrayList<>();
      root.collect(list);
      return Collections.unmodifiableList(list);
    }

    /**
     * The group of this frame that was last chosen or focused: where the app adds new tabs and
     * where keys handed to the window's root act.
     */
    public Group<T> active() {
      return active;
    }

    boolean isSplit() {
      return root instanceof Branch<T>;
    }

    boolean contains(Group<T> group) {
      return contains(root, group);
    }

    void split(Group<T> beside, Group<T> made, Side side) {
      Split.Direction dir =
          side == Side.LEFT || side == Side.RIGHT ? Split.Direction.ROW : Split.Direction.COLUMN;
      boolean before = side == Side.LEFT || side == Side.TOP;
      root = replace(root, beside, leaf -> {
        Leaf<T> added = new Leaf<>(made);
        return new Branch<>(dir, before ? added : leaf, before ? leaf : added);
      });
    }

    /**
     * Removes {@code group} from a split frame, replacing its split with its sibling, and returns
     * the first group (top left) of the sibling taking its place.
     */
    Group<T> remove(Group<T> group) {
      List<Node<T>> sibling = new ArrayList<>(1);
      root = remove(root, group, sibling);
      return firstGroup(sibling.getFirst());
    }

    private static <T> boolean contains(Node<T> node, Group<T> group) {
      if (node instanceof Leaf<T> leaf) {
        return leaf.group == group;
      }
      Branch<T> branch = (Branch<T>) node;
      return contains(branch.first, group) || contains(branch.second, group);
    }

    private static <T> Node<T> replace(
        Node<T> node, Group<T> target, Function<Leaf<T>, Node<T>> replacement) {
      if (node instanceof Leaf<T> leaf) {
        return leaf.group == target ? replacement.apply(leaf) : leaf;
      }
      Branch<T> branch = (Branch<T>) node;
      branch.first = replace(branch.first, target, replacement);
      branch.second = replace(branch.second, target, replacement);
      return branch;
    }

    private static <T> Node<T> remove(Node<T> node, Group<T> target, List<Node<T>> sibling) {
      if (node instanceof Leaf<T>) {
        return node;
      }
      Branch<T> branch = (Branch<T>) node;
      if (branch.first instanceof Leaf<T> leaf && leaf.group == target) {
        sibling.add(branch.second);
        return branch.second;
      }
      if (branch.second instanceof Leaf<T> leaf && leaf.group == target) {
        sibling.add(branch.first);
        return branch.first;
      }
      branch.first = remove(branch.first, target, sibling);
      if (sibling.isEmpty()) {
        branch.second = remove(branch.second, target, sibling);
      }
      return branch;
    }

    private static <T> Group<T> firstGroup(Node<T> node) {
      Node<T> current = node;
      while (current instanceof Branch<T> branch) {
        current = branch.first;
      }
      return ((Leaf<T>) current).group;
    }

    @Override
    public String toString() {
      return (window == null ? "main" : window.title()) + " " + groups();
    }
  }

  /**
   * A group of tabs inside a {@link Frame}.
   *
   * @param <T> the app's tabs
   */
  public static final class Group<T> {
    final int id;
    Frame<T> frame;
    final List<T> tabs = new ArrayList<>();
    int active = -1;
    final Menu.State menu = new Menu.State();
    final Tabs.Overflow overflow = new Tabs.Overflow();
    T menuTab; // the tab the menu opened for, while it is open
    float width = Float.NaN;
    float height = Float.NaN;
    El tablist; // the bar's #tablist from the last build, for locating its first tab
    boolean focused; // had focus within on the last frame, for focus handoff when it empties
    Group<T> previous; // the frame's active group before a press, see #buildNode

    private Group(int id) {
      this.id = id;
    }

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

    /** The frame that holds this group. */
    public Frame<T> frame() {
      return frame;
    }

    /** The group's window, or null for a group in the main frame, whose window is the app's first. */
    public Windows.Handle window() {
      return frame.window;
    }

    @Override
    public String toString() {
      return (frame.window == null ? "main" : frame.window.title())
          + " "
          + tabs
          + " active "
          + active;
    }
  }

  private final Opener<T> opener;
  private int groupsMade;
  private final Frame<T> main;
  private final List<Frame<T>> frames;
  private final Tabs.Dragging dragging = new Tabs.Dragging();
  private final Map<T, String> contentKeys = new IdentityHashMap<>(); // see #tabs
  private int contentKeysMade;
  private Consumer<Frame<T>> onWindowClose = this::dockAll;
  private Consumer<T> onClosed = tab -> {};
  private ObjIntConsumer<Group<T>> onCloseTab = this::close;
  private BiConsumer<Group<T>, List<T>> onCloseTabs;
  private Consumer<Group<T>> onAdd;
  private BiFunction<Group<T>, T, List<Menu.Item>> tabMenu;
  private boolean splits;

  private enum Zone {
    LEFT("left", Side.LEFT),
    RIGHT("right", Side.RIGHT),
    TOP("top", Side.TOP),
    BOTTOM("bottom", Side.BOTTOM),
    CENTER("center", null);

    final String value;
    final Side side;

    Zone(String value, Side side) {
      this.value = value;
      this.side = side;
    }
  }

  // The drag that has left its bar, while it lasts.
  private Frame<T> following; // the frame whose window follows the pointer
  private boolean placed; // its first tab's place in its window is known from its own tree
  private float firstX; // where its first tab lies in its window
  private float firstY;
  private float tabHeight = Float.NaN; // the height of that tab, once known
  private Group<T> origin; // where a torn tab came from, to go back on a cancel; null if none
  private Frame<T> originFrame;
  private Group<T> originActiveGroup; // the active group in originFrame before the drag began
  private T originActiveTab; // the active tab in origin before the tear-off
  private boolean originHadFocus; // whether origin held the focus when the tab left
  private int originIndex;
  private Object originToken; // its frame's focus request when the tab left, to see a newer one
  private int startX; // where a window moved by its only tab lay
  private int startY;
  private Group<T> zoneGroup; // group whose panel shows a drop-zone preview, or null
  private Zone zone;
  // Tabs being handed to opener.open, also nested ones, to reject re-adding them.
  private final Set<T> opening = Collections.newSetFromMap(new IdentityHashMap<>());

  /**
   * Tabs in one window, without tearing tabs off into new windows: for single-window apps that use
   * {@link #splits(boolean)}, per-tab focus memory, overflow menus, and tab menus.
   *
   * @param tabs the main group's tabs, the first active; each must be non-null and distinct by
   *     identity
   */
  public TabGroups(List<T> tabs) {
    this(tabs, null, false);
  }

  /**
   * @param tabs the main group's tabs, the first active; each must be non-null and distinct by
   *     identity
   * @param opener opens the window of a new frame
   */
  public TabGroups(List<T> tabs, Opener<T> opener) {
    this(tabs, Objects.requireNonNull(opener, "opener"), true);
  }

  private TabGroups(List<T> tabs, Opener<T> opener, boolean withOpener) {
    this.opener = withOpener ? opener : null;
    Objects.requireNonNull(tabs, "tabs");
    Set<T> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (T tab : tabs) {
      Objects.requireNonNull(tab, "tab");
      if (!seen.add(tab)) {
        throw new IllegalArgumentException("Duplicate tab: " + tab);
      }
    }
    Group<T> first = new Group<>(groupsMade++);
    this.main = new Frame<>(first);
    first.frame = this.main;
    first.tabs.addAll(tabs);
    first.active = tabs.isEmpty() ? -1 : 0;
    this.frames = new ArrayList<>(List.of(main));
  }

  /**
   * What closing a frame's window does: {@link #dockAll(Frame)} unless set, or {@link
   * #closeAll(Frame)}. Runs on a close request ({@link Windows.Handle#onCloseRequest}) and also
   * once the window has closed in another way, such as the app's {@link Windows.Handle#close()},
   * for a frame still in {@link #frames()}.
   */
  public TabGroups<T> onWindowClose(Consumer<Frame<T>> handler) {
    this.onWindowClose = Objects.requireNonNull(handler, "handler");
    return this;
  }

  /**
   * What the user's closing of a tab does, by its cross, Control+W, Control+F4, or Close in the
   * tab menu: {@link #close} unless set. A handler that asks first, about changes not yet saved,
   * calls {@link #close} once the user agrees, or nothing. For bulk close menu actions (Close
   * others, Close to the right), see {@link #onCloseTabs}.
   */
  public TabGroups<T> onCloseTab(ObjIntConsumer<Group<T>> handler) {
    this.onCloseTab = Objects.requireNonNull(handler, "handler");
    return this;
  }

  /**
   * What bulk close actions in a tab's menu (Close others, Close to the right) do: receives the
   * group and the closable tabs in bar order so an app can ask once for the whole batch before
   * calling {@link #close}. When null (the default), each tab is passed to {@link #onCloseTab} in
   * order, stopping if a tab remains open (for example, while awaiting confirmation).
   */
  public TabGroups<T> onCloseTabs(BiConsumer<Group<T>, List<T>> handler) {
    this.onCloseTabs = handler;
    return this;
  }

  /** Told of each tab the user closes, after it left its group: to cancel its work, say. */
  public TabGroups<T> onClosed(Consumer<T> handler) {
    this.onClosed = Objects.requireNonNull(handler, "handler");
    return this;
  }

  /**
   * Lets the user add a tab to a group, by its bar's plus button or Control+T: the handler gets
   * that group and typically calls {@link #add}; null, the default, shows no button.
   */
  public TabGroups<T> onAdd(Consumer<Group<T>> handler) {
    this.onAdd = handler;
    return this;
  }

  /**
   * Lets the user split groups within a window, by dropping a dragged tab on a group's edge or
   * center, by each tab's context menu (Split right, Split down) and with Control+\; off by
   * default.
   */
  public TabGroups<T> splits(boolean on) {
    this.splits = on;
    return this;
  }

  /**
   * Appends app-specific items to each tab's context menu, separated from the built-in items. The
   * callback runs while the menu is open with the tab's group and the tab the menu opened for;
   * returning null or an empty list adds nothing. Returned lists must not contain null elements;
   * leading, trailing, and consecutive separators are trimmed. Passing null clears custom items.
   */
  public TabGroups<T> tabMenu(BiFunction<Group<T>, T, List<Menu.Item>> handler) {
    this.tabMenu = handler;
    return this;
  }

  /** The frame of the app's first window. */
  public Frame<T> main() {
    return main;
  }

  /** Every frame, the main one first, then in the order their windows opened. */
  public List<Frame<T>> frames() {
    return Collections.unmodifiableList(frames);
  }

  /**
   * Every group across all frames, the main frame's first, then in the order their windows opened,
   * top left first within each frame.
   */
  public List<Group<T>> groups() {
    List<Group<T>> all = new ArrayList<>();
    for (Frame<T> frame : frames) {
      all.addAll(frame.groups());
    }
    return Collections.unmodifiableList(all);
  }

  /** The group that holds {@code tab}, or null. */
  public Group<T> groupOf(T tab) {
    for (Group<T> g : groups()) {
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
   * The tabs of {@code frame}, for its window's view: a single group renders as {@link Tabs#tabs}
   * alone; a split frame renders its tree with {@link Split#cards}, each group keyed {@code
   * #group-<id>}, and puts the active group's {@link El#onKey} on the returned element so an app
   * handing {@code tabs(...).onKey()} to its root keeps working when split.
   *
   * @param tab how a tab of the app shows in the bar
   * @param content the content of the active tab of each group, and of none (null) in an empty
   *     group, whose bar still takes tabs; it may return null for no content
   */
  public El tabs(Frame<T> frame, Function<T, Tabs.Tab> tab, Function<T, El> content) {
    Objects.requireNonNull(frame, "frame");
    if (!dragging.isActive()) {
      clearZone();
    }
    Theme theme = Theme.CURRENT.get();
    if (frame.root instanceof Leaf<T> leaf) {
      El built = groupTabs(leaf.group, tab, content, false, theme);
      return built.onKey() != null ? built : built.onKey(k -> false);
    }
    // Keys go to the frame's active group, also while the focus is in another one.
    Map<Group<T>, Predicate<Key>> keys = new IdentityHashMap<>();
    El built = buildNode(frame.root, tab, content, theme, keys);
    return built.onKey(k -> {
      Predicate<Key> handler = keys.get(frame.active);
      return handler != null && handler.test(k);
    });
  }

  private El buildNode(
      Node<T> node,
      Function<T, Tabs.Tab> tab,
      Function<T, El> content,
      Theme theme,
      Map<Group<T>, Predicate<Key>> keys) {
    if (node instanceof Leaf<T> leaf) {
      Group<T> group = leaf.group;
      El built = groupTabs(group, tab, content, true, theme);
      keys.put(group, built.onKey());
      return built
          .onKey(null)
          .key("group-" + group.id)
          .onFocusIn(() -> {
            group.frame.active = group;
            if (group.frame.focusGroup != group) {
              group.frame.focusGroup = null;
              group.frame.focusToken = null;
            }
          })
          // Any press in it makes it active, also one a text field keeps to itself: core tells
          // onAnyPointerDown and then, for a press elsewhere, onPressOutside, which undoes it.
          .onAnyPointerDown(() -> {
            group.previous = group.frame.active;
            group.frame.active = group;
          })
          .onPressOutside(() -> group.frame.active = group.previous);
    }
    Branch<T> branch = (Branch<T>) node;
    El first = buildNode(branch.first, tab, content, theme, keys);
    El second = buildNode(branch.second, tab, content, theme, keys);
    return Split.cards(
        branch.direction,
        branch.position,
        Split.Position.share(0.5f),
        position -> branch.position = position,
        first,
        minSize(branch.first, branch.direction, theme),
        second,
        minSize(branch.second, branch.direction, theme));
  }

  private float minSize(Node<T> node, Split.Direction direction, Theme theme) {
    if (node instanceof Leaf<T>) {
      return theme.size().minTabGroup();
    }
    Branch<T> branch = (Branch<T>) node;
    float a = minSize(branch.first, direction, theme);
    float b = minSize(branch.second, direction, theme);
    return branch.direction == direction ? a + theme.space().m() + b : Math.max(a, b);
  }

  private El groupTabs(
      Group<T> group,
      Function<T, Tabs.Tab> tab,
      Function<T, El> content,
      boolean split,
      Theme theme) {
    group.focused = false;
    boolean alone = alone(group);
    List<Tabs.Tab> uiTabs = group.tabs.stream().map(tab).toList();
    // The menu opens for the active tab, acts on that tab wherever it goes, and ends with it.
    boolean gone = group.menuTab != null && indexOf(group, group.menuTab) < 0;
    if (group.tabs.isEmpty() || (gone && group.menu.isOpen())) {
      group.menu.close();
      group.menuTab = null;
    } else if (!group.menu.isOpen()) {
      group.menuTab = null;
    } else if (group.menuTab == null) {
      group.menuTab = group.activeTab();
    }
    T target = group.menuTab != null ? group.menuTab : group.activeTab();
    List<Menu.Item> items = List.of();
    if (!group.tabs.isEmpty() && group.menu.isOpen()) {
      items = buildTabMenu(group, uiTabs, target, alone, theme);
    }
    T active = group.activeTab();
    El body = content.apply(active);
    if (body != null && active != null) { // each tab's content its own identity, see #tabs
      body.style().minWidth = Len.ZERO;
      body.style().minHeight = Len.ZERO;
      String key = contentKeys.computeIfAbsent(active, x -> "content-" + contentKeysMade++);
      body = El.col().key(key).child(body.grow(1));
    }
    Tabs.Options options = Tabs.options()
        .drag(dragging)
        .id(group)
        .active(!split || group.frame.active == group)
        .onTablist(list -> group.tablist = list)
        .onAdd(
            onAdd == null
                ? null
                : () -> {
                  if (hasGroup(group)) { // not a bar of a view built before its group left
                    onAdd.accept(group);
                  }
                })
        .overflow(group.overflow)
        .onMove((from, to) -> move(group, from, to))
        .onTearOff(opener == null ? null : this::torn)
        .tearOffAtStart(opener != null && alone)
        .tearOffOutside(splits)
        .onDock((from, i, at) -> {
          if (hasGroup(cast(from)) && hasGroup(group)) { // as onAdd
            dock(cast(from), i, group, at);
          }
        });
    if (!group.tabs.isEmpty()) {
      options.contextMenu(group.menu, items);
    }
    if (showZones(group)) {
      El layer = El.el().key("drop").absolute(0, 0).wFull().hFull();
      layer.onDrop(d -> overZone(group, layer, theme, d));
      if (zoneGroup == group && zone != null) {
        layer.child(zonePreview(zone, theme));
      }
      options.panelOverlay(layer);
    }
    if (group.tabs.isEmpty() && group.frame.focusGroup == group) {
      group.frame.focusGroup = null;
      group.frame.focusToken = null;
    }
    if (group.frame.focusGroup == group && group.frame.focusToken != null) {
      options.focusBar(group.frame.focusToken);
    }
    El built = Tabs.tabs(
            uiTabs,
            group.active,
            i -> select(group, i),
            i -> onCloseTab.accept(group, i),
            body,
            options)
        .onResize((w, h) -> {
          group.width = w;
          group.height = h;
        })
        .whenFocusWithin(el -> group.focused = true);
    if (splits && !group.tabs.isEmpty()) {
      Predicate<Key> existing = built.onKey();
      built.onKey(k -> {
        if (k.modifiers() == Key.CONTROL
            && k.name().equals("\\")
            && group.active >= 0
            && canSplit(group, Split.Direction.ROW, theme)) {
          group.menu.close();
          split(group, group.active, group, Side.RIGHT);
          return true;
        }
        return existing != null && existing.test(k);
      });
    }
    return built;
  }

  private List<Menu.Item> buildTabMenu(
      Group<T> group, List<Tabs.Tab> uiTabs, T target, boolean alone, Theme theme) {
    Texts texts = Texts.CURRENT.get();
    List<Menu.Item> items = new ArrayList<>();
    int targetIndex = target == null ? -1 : indexOf(group, target);
    boolean targetClosable = targetIndex >= 0 && uiTabs.get(targetIndex).closable();
    List<T> others = new ArrayList<>();
    List<T> toRight = new ArrayList<>();
    for (int i = 0; i < group.tabs.size(); i++) {
      if (!uiTabs.get(i).closable()) {
        continue;
      }
      if (i != targetIndex && targetIndex >= 0) {
        others.add(group.tabs.get(i));
      }
      if (targetIndex >= 0 && i > targetIndex) {
        toRight.add(group.tabs.get(i));
      }
    }
    addMenuSection(
        items,
        List.of(
            Menu.item(texts.close(), targetClosable ? () -> closeTab(group, target) : null)
                .shortcut(Key.CONTROL, "W"),
            Menu.item(
                texts.closeOthers(), others.isEmpty() ? null : () -> closeTabs(group, others)),
            Menu.item(
                texts.closeToTheRight(),
                toRight.isEmpty() ? null : () -> closeTabs(group, toRight))));
    if (splits) {
      boolean canRight = target != null && canSplit(group, Split.Direction.ROW, theme);
      boolean canDown = target != null && canSplit(group, Split.Direction.COLUMN, theme);
      addMenuSection(
          items,
          List.of(
              Menu.item(
                      texts.splitRight(),
                      canRight ? () -> splitAt(group, target, Side.RIGHT) : null)
                  .shortcut(Key.CONTROL, "\\"),
              Menu.item(
                  texts.splitDown(), canDown ? () -> splitAt(group, target, Side.BOTTOM) : null)));
    }
    if (opener != null) {
      List<Menu.Item> windowItems = new ArrayList<>();
      windowItems.add(Menu.item(
          texts.moveToNewWindow(),
          alone || target == null
              ? null
              : () -> {
                int at = indexOf(group, target);
                if (at >= 0) {
                  toNewWindow(group, at);
                }
              }));
      if (group.frame != main) {
        windowItems.add(Menu.item(
            texts.moveToMainWindow(),
            target == null
                ? null
                : () -> {
                  int at = indexOf(group, target);
                  if (at >= 0) {
                    dock(group, at, main.active, main.active.tabs.size());
                  }
                }));
      }
      addMenuSection(items, windowItems);
    }
    if (tabMenu != null && target != null) {
      List<Menu.Item> extra = tabMenu.apply(group, target);
      if (extra != null && !extra.isEmpty()) {
        addMenuSection(items, trimSeparators(extra));
      }
    }
    return items;
  }

  private static List<Menu.Item> trimSeparators(List<Menu.Item> items) {
    int start = 0;
    int end = items.size();
    while (start < end && Objects.requireNonNull(items.get(start), "menu item").isSeparator()) {
      start++;
    }
    while (end > start
        && Objects.requireNonNull(items.get(end - 1), "menu item").isSeparator()) {
      end--;
    }
    if (start == end) {
      return List.of();
    }
    List<Menu.Item> clean = new ArrayList<>(end - start);
    boolean prevSep = false;
    for (int i = start; i < end; i++) {
      Menu.Item item = Objects.requireNonNull(items.get(i), "menu item");
      if (item.isSeparator()) {
        if (!prevSep) {
          clean.add(item);
          prevSep = true;
        }
      } else {
        clean.add(item);
        prevSep = false;
      }
    }
    return clean;
  }

  private static void addMenuSection(List<Menu.Item> into, List<Menu.Item> section) {
    if (section.isEmpty()) {
      return;
    }
    if (!into.isEmpty()) {
      into.add(Menu.separator());
    }
    into.addAll(section);
  }

  private void closeTab(Group<T> group, T tab) {
    if (!hasGroup(group)) {
      return;
    }
    int at = indexOf(group, tab);
    if (at >= 0) {
      onCloseTab.accept(group, at);
    }
  }

  private void closeTabs(Group<T> group, List<T> targets) {
    if (!hasGroup(group)) {
      return;
    }
    if (onCloseTabs != null) {
      List<T> present = new ArrayList<>(targets.size());
      for (T tab : targets) {
        if (indexOf(group, tab) >= 0) {
          present.add(tab);
        }
      }
      if (!present.isEmpty()) {
        onCloseTabs.accept(group, Collections.unmodifiableList(present));
      }
      return;
    }
    for (T tab : targets) {
      if (!hasGroup(group)) {
        return;
      }
      int at = indexOf(group, tab);
      if (at >= 0) {
        onCloseTab.accept(group, at);
        if (hasGroup(group) && indexOf(group, tab) >= 0) {
          return; // deferred (e.g. awaiting confirmation): avoid overwriting a pending prompt
        }
      }
    }
  }

  private boolean alone(Group<T> group) {
    return group.frame != main && !group.frame.isSplit() && group.tabs.size() == 1;
  }

  private boolean canSplit(Group<T> group, Split.Direction direction, Theme theme) {
    return splits && group.tabs.size() >= 2 && hasSplitRoom(group, direction, theme);
  }

  private boolean hasSplitRoom(Group<T> group, Split.Direction direction, Theme theme) {
    boolean row = direction == Split.Direction.ROW;
    float along = row ? group.width : group.height;
    float across = row ? group.height : group.width;
    float min = theme.size().minTabGroup();
    // NaN until laid out compares false: no split before the group has a size.
    return along >= 2 * min + theme.space().m() && across >= min;
  }

  private boolean showZones(Group<T> group) {
    return splits
        && dragging.isActive()
        && !dragging.isTorn()
        && following == null
        && dragging.bar instanceof Group<?> source
        && source.frame == group.frame
        && (source != group || group.tabs.size() > 1);
  }

  private void clearZone() {
    zoneGroup = null;
    zone = null;
  }

  private void overZone(Group<T> group, El layer, Theme theme, Drop d) {
    if (d.payload() != dragging || !dragging.isActive() || dragging.isTorn() || following != null) {
      return;
    }
    if (d.phase() == Drop.Phase.LEAVE) {
      if (zoneGroup == group) {
        clearZone();
      }
      return;
    }
    Zone picked = zoneAt(group, layer.width(), layer.height(), d.x(), d.y(), theme);
    if (d.phase() == Drop.Phase.OVER) {
      zoneGroup = picked == null ? null : group;
      zone = picked;
      return;
    }
    clearZone();
    if (picked == null || !(dragging.bar instanceof Group<?>)) {
      return;
    }
    Group<T> from = cast(dragging.bar);
    int index = dragging.index;
    if (!hasGroup(from) || !hasGroup(group) || index < 0 || index >= from.tabs.size()) {
      return;
    }
    if (picked.side != null) {
      if (from != group || from.tabs.size() > 1) {
        split(from, index, group, picked.side);
      }
    } else {
      dock(from, index, group, group.tabs.size());
      if (from == group) { // as after a dock into another group: the moved tab is active
        select(group, group.tabs.size() - 1);
        requestFocus(group);
      }
    }
  }

  private Zone zoneAt(Group<T> group, float w, float h, double x, double y, Theme theme) {
    if (w <= 0 || h <= 0) {
      return null;
    }
    double fx = x / w;
    double fy = y / h;
    boolean row = hasSplitRoom(group, Split.Direction.ROW, theme);
    boolean col = hasSplitRoom(group, Split.Direction.COLUMN, theme);
    double best = 1.0 / 3.0;
    Zone picked = Zone.CENTER;
    if (row) {
      if (fx < best) {
        best = fx;
        picked = Zone.LEFT;
      }
      if (1 - fx < best) {
        best = 1 - fx;
        picked = Zone.RIGHT;
      }
    }
    if (col) {
      if (fy < best) {
        best = fy;
        picked = Zone.TOP;
      }
      if (1 - fy < best) {
        best = 1 - fy;
        picked = Zone.BOTTOM;
      }
    }
    return picked;
  }

  private static El zonePreview(Zone zone, Theme theme) {
    float r = theme.radius().large() - theme.size().stroke();
    El preview = El.el()
        .key("zone")
        .value(zone.value)
        .fill(theme.accent().selection())
        .border(theme.size().indicator(), theme.accent().edge());
    preview.style().position = Style.Position.ABSOLUTE;
    switch (zone) {
      case LEFT -> {
        preview.style().left = Len.ZERO;
        preview.style().top = Len.ZERO;
        preview.style().width = Len.percent(0.5f);
        preview.hFull().radius(0, 0, 0, r);
      }
      case RIGHT -> {
        preview.style().right = Len.ZERO;
        preview.style().top = Len.ZERO;
        preview.style().width = Len.percent(0.5f);
        preview.hFull().radius(0, 0, r, 0);
      }
      case TOP -> {
        preview.style().left = Len.ZERO;
        preview.style().top = Len.ZERO;
        preview.wFull();
        preview.style().height = Len.percent(0.5f);
      }
      case BOTTOM -> {
        preview.style().left = Len.ZERO;
        preview.style().bottom = Len.ZERO;
        preview.wFull().radius(0, 0, r, r);
        preview.style().height = Len.percent(0.5f);
      }
      case CENTER -> preview.absolute(0, 0).wFull().hFull().radius(0, 0, r, r);
    }
    return preview;
  }

  private void splitAt(Group<T> group, T target, Side side) {
    int at = indexOf(group, target);
    if (at >= 0) {
      split(group, at, group, side);
    }
  }

  @SuppressWarnings("unchecked") // the bars' ids are the groups of this object
  private Group<T> cast(Object bar) {
    return (Group<T>) bar;
  }

  /** Makes the tab at {@code index} the active one of its group, and the group its frame's active one. */
  public void select(Group<T> group, int index) {
    requireOpen(group);
    Objects.checkIndex(index, group.tabs.size());
    group.active = index;
    group.frame.active = group;
  }

  /**
   * Adds a tab to a group, before the tab at {@code at}, and makes it the active one.
   *
   * @throws IllegalArgumentException if {@code group} is not open or {@code tab} is already in a
   *     group
   */
  public void add(Group<T> group, int at, T tab) {
    requireOpen(group);
    Objects.requireNonNull(tab, "tab");
    Objects.checkIndex(at, group.tabs.size() + 1);
    if (opening.contains(tab) || groupOf(tab) != null) {
      throw new IllegalArgumentException("Tab is already open: " + tab);
    }
    T d = dragged();
    insert(group, at, tab);
    follow(d);
  }

  private void insert(Group<T> group, int at, T tab) {
    group.tabs.add(at, Objects.requireNonNull(tab, "tab"));
    group.active = at;
    group.frame.active = group;
  }

  /**
   * Closes a tab: it leaves its group, whose next tab, else the one before, becomes active if it
   * was, and {@link #onClosed} is told. A group in a split leaves its frame with its last tab; a
   * torn-off frame's only group closes its window.
   */
  public void close(Group<T> group, int index) {
    requireOpen(group);
    Objects.checkIndex(index, group.tabs.size());
    T d = dragged();
    T tab = remove(group, index);
    contentKeys.remove(tab);
    follow(d);
    onClosed.accept(tab);
  }

  /** Moves the tab at {@code from} of a group to {@code to}, as {@link Tabs#move}. */
  public void move(Group<T> group, int from, int to) {
    requireOpen(group);
    Objects.checkIndex(from, group.tabs.size());
    Objects.checkIndex(to, group.tabs.size());
    T d = dragged();
    group.active = Tabs.move(group.tabs, group.active, from, to);
    group.frame.active = group;
    follow(d);
  }

  /**
   * Moves a tab to another group, or within its own, before the tab at {@code at} there as it was
   * before the move, and makes it the active one there. A group in a split leaves its frame when
   * this leaves it empty; a torn-off frame's only group closes its window.
   */
  public void dock(Group<T> from, int index, Group<T> to, int at) {
    requireOpen(from);
    requireOpen(to);
    Objects.checkIndex(index, from.tabs.size());
    Objects.checkIndex(at, to.tabs.size() + 1);
    T d = dragged();
    if (from == to) {
      from.active = Tabs.move(from.tabs, from.active, index, at > index ? at - 1 : at);
      from.frame.active = from;
    } else {
      boolean focus = splits || from.frame.isSplit() || to.frame.isSplit();
      T tab = remove(from, index);
      insert(to, at, tab);
      if (focus) {
        requestFocus(to);
      }
    }
    follow(d);
  }

  /**
   * Moves a tab into a group of its own, in a window that opens where the system puts it, and
   * returns that group. If the tab is already the only tab in an unsplit detached window, returns
   * its group unchanged.
   *
   * @throws IllegalStateException if these groups were created without a window opener
   */
  public Group<T> toNewWindow(Group<T> group, int index) {
    requireOpen(group);
    Objects.checkIndex(index, group.tabs.size());
    if (opener == null) {
      throw new IllegalStateException("No window opener was configured");
    }
    if (alone(group)) {
      return group;
    }
    T d = dragged();
    Group<T> made = openWindow(group, index, Window.options(), true);
    follow(d);
    return made;
  }

  /**
   * Moves the tab at {@code index} of {@code from} into a new group placed on {@code side} of
   * {@code beside}, splitting {@code beside} in its frame, makes the moved tab and the new group
   * active, focuses the new group's bar, and returns the new group.
   */
  public Group<T> split(Group<T> from, int index, Group<T> beside, Side side) {
    requireOpen(from);
    requireOpen(beside);
    Objects.requireNonNull(side, "side");
    Objects.checkIndex(index, from.tabs.size());
    if (from == beside && from.tabs.size() == 1) {
      throw new IllegalArgumentException("cannot split a group's only tab beside itself");
    }
    T d = dragged();
    T tab = remove(from, index);
    Group<T> made = new Group<>(groupsMade++);
    made.frame = beside.frame;
    made.tabs.add(tab);
    made.active = 0;
    beside.frame.split(beside, made, side);
    beside.frame.active = made;
    requestFocus(made);
    follow(d);
    return made;
  }

  /**
   * Moves every tab of {@code group} to the end of the main frame's active group, never into the
   * group itself; an emptied group in a split leaves its frame, and a torn-off frame's only group
   * closes its window.
   */
  public void dockAll(Group<T> group) {
    Objects.requireNonNull(group, "group");
    if (group.tabs.isEmpty()) {
      return;
    }
    dockAll(group, main.active);
  }

  /**
   * Moves every tab of {@code from} to the end of {@code to}, never into {@code from} itself; an
   * emptied group in a split leaves its frame, and a torn-off frame's only group closes its window.
   */
  public void dockAll(Group<T> from, Group<T> to) {
    requireOpen(from);
    requireOpen(to);
    if (from == to) {
      return;
    }
    while (!from.tabs.isEmpty()) {
      dock(from, 0, to, to.tabs.size());
    }
  }

  /**
   * Moves every tab of every group of {@code frame} to the end of the main frame's active group
   * (never into that group itself) and closes {@code frame}'s window once if it is not the main
   * frame.
   */
  public void dockAll(Frame<T> frame) {
    Group<T> target = main.active;
    for (Group<T> group : List.copyOf(frame.groups())) {
      if (group != target) {
        while (!group.tabs.isEmpty()) {
          dock(group, 0, target, target.tabs.size());
        }
      }
    }
    if (frame != main && frames.contains(frame)) {
      frames.remove(frame);
      frame.window.close();
    }
  }

  /** Closes every tab of {@code group}. */
  public void closeAll(Group<T> group) {
    while (!group.tabs.isEmpty()) {
      close(group, group.tabs.size() - 1);
    }
  }

  /** Closes every tab of every group of {@code frame} and its window if it is not the main frame. */
  public void closeAll(Frame<T> frame) {
    for (Group<T> group : List.copyOf(frame.groups())) {
      closeAll(group);
    }
    if (frame != main && frames.contains(frame)) {
      frames.remove(frame);
      frame.window.close();
    }
  }

  private void requestFocus(Group<T> group) {
    group.frame.focusGroup = group;
    group.frame.focusToken = new Object();
  }

  private boolean hasGroup(Group<T> group) {
    return frames.contains(group.frame) && group.frame.contains(group);
  }

  /** A group that left its frame, or whose window closed, takes no tabs. */
  private void requireOpen(Group<T> group) {
    if (!hasGroup(Objects.requireNonNull(group, "group"))) {
      throw new IllegalArgumentException("The group is no longer open: " + group);
    }
  }

  /** The tab being dragged, or null. */
  private T dragged() {
    for (Group<T> g : groups()) {
      if (g == dragging.bar && dragging.index >= 0 && dragging.index < g.tabs.size()) {
        return g.tabs.get(dragging.index);
      }
    }
    return null;
  }

  /**
   * Tells the drag where its tab is after a change of the groups, so that it stays the one
   * dragged; a drag whose tab was closed ends.
   */
  private void follow(T tab) {
    if (tab == null) {
      return;
    }
    for (Group<T> g : groups()) {
      int at = indexOf(g, tab);
      if (at >= 0) {
        dragging.moved(g, at);
        return;
      }
    }
    dragging.clear();
    stopFollowing();
  }

  /**
   * Ends a drag's following of the pointer, however the drag ends: the window that followed
   * takes drops again, and an origin group that emptied during the drag is pruned now.
   */
  private void stopFollowing() {
    if (following != null && frames.contains(following)) {
      following.window.dropTarget(true);
    }
    Group<T> emptied = origin;
    following = null;
    origin = null;
    originFrame = null;
    originActiveGroup = null;
    originActiveTab = null;
    originHadFocus = false;
    clearZone();
    if (emptied != null) { // a dock into its own window asked for the focus: it keeps it
      prune(emptied, emptied.frame.focusToken == originToken);
    }
  }

  /** Where a tab is in a group, by identity; -1 if it is not there. */
  private static <T> int indexOf(Group<T> group, T tab) {
    for (int i = 0; i < group.tabs.size(); i++) {
      if (group.tabs.get(i) == tab) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Takes a tab out of its group; an emptied group in a split leaves its frame, and a torn-off
   * frame's only group closes its window.
   */
  private T remove(Group<T> group, int index) {
    return remove(group, index, true);
  }

  private T remove(Group<T> group, int index, boolean prune) {
    T tab = group.tabs.remove(index);
    if (group.active > index || group.active == group.tabs.size()) {
      group.active--;
    }
    if (prune && group.tabs.isEmpty()) {
      prune(group, true);
    }
    return tab;
  }

  private void prune(Group<T> group, boolean handFocus) {
    if (!group.tabs.isEmpty() || !hasGroup(group)) {
      return;
    }
    Frame<T> frame = group.frame;
    if (frame.isSplit()) {
      boolean hadFocus = group.focused && handFocus;
      Group<T> replacement = frame.remove(group);
      if (frame.active == group) {
        frame.active = replacement;
      }
      if (hadFocus) {
        requestFocus(replacement);
      } else if (frame.focusGroup == group) {
        frame.focusGroup = null;
        frame.focusToken = null;
      }
    } else if (frame != main) {
      frames.remove(frame);
      frame.window.close();
    }
  }

  private Group<T> openWindow(Group<T> from, int index, Window.Options options, boolean prune) {
    Frame<T> fromFrame = from.frame;
    T prevActiveTab = from.activeTab();
    boolean draggedTab = dragging.on(from, index);
    T tab = remove(from, index, false);
    Group<T> group = new Group<>(groupsMade++);
    Frame<T> frame = new Frame<>(group);
    group.frame = frame;
    group.tabs.add(tab);
    group.active = 0;
    if (draggedTab) { // in the staged group, so that what the opener changes leaves the drag alone
      dragging.moved(group, 0);
    }
    Windows.Handle opened;
    opening.add(tab);
    try {
      opened = Objects.requireNonNull(opener.open(frame, options), "opener returned null");
    } catch (RuntimeException | Error e) {
      Group<T> target =
          hasGroup(from) ? from : frames.contains(fromFrame) ? fromFrame.active : main.active;
      int restoreAt = target == from ? Math.min(index, target.tabs.size()) : target.tabs.size();
      target.tabs.add(restoreAt, tab);
      if (target == from) {
        int prev = prevActiveTab == null ? -1 : indexOf(target, prevActiveTab);
        target.active = prev >= 0 ? prev : restoreAt;
      } else if (target.active < 0) {
        target.active = restoreAt;
      }
      if (draggedTab) {
        dragging.moved(target, restoreAt);
      }
      throw e;
    } finally {
      opening.remove(tab);
    }
    if (prune && from.tabs.isEmpty()) {
      prune(from, true);
    }
    frames.add(frame);
    frame.window = opened;
    frame.window.onCloseRequest(() -> {
      if (frames.contains(frame)) {
        onWindowClose.accept(frame);
      }
    });
    Runnable app = frame.window.onClosed();
    frame.window.onClosed(() -> {
      if (dragging.isActive() && dragging.startBar instanceof Group<?> g && g.frame == frame) {
        // The drag began in this window, whose press went with it.
        dragging.clear();
        stopFollowing();
      }
      if (frames.contains(frame)) {
        onWindowClose.accept(frame);
      }
      if (app != null) {
        app.run();
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
      clearZone();
      firstX = Float.isNaN(o.firstX()) ? 0 : o.firstX();
      firstY = Float.isNaN(o.firstY()) ? 0 : o.firstY();
      placed = false;
      if (alone(group)) { // its window moves along
        following = group.frame;
        origin = null;
        originFrame = null;
        originActiveGroup = null;
        originActiveTab = null;
        originHadFocus = false;
        startX = following.window.x();
        startY = following.window.y();
        // Its own tree says where its tab lies, also where the first step is already over
        // another window, whose place the step's own numbers are of.
        float[] at = firstTab(following);
        if (at.length > 0) {
          firstX = at[0];
          firstY = at[1];
        }
        tabHeight = at.length == 0 ? Float.NaN : at[2];
        placed = true;
      } else { // a window of its own, under the pointer
        origin = group;
        originFrame = group.frame;
        // Pointer-down on a tab already activated this group; group.previous holds the group that
        // was active before that press.
        originActiveGroup =
            group.frame.isSplit() && group.previous != null ? group.previous : group.frame.active;
        originActiveTab = group.activeTab();
        originHadFocus = group.focused;
        originToken = group.frame.focusToken;
        originIndex = o.index();
        float scale = o.window() == null ? 1 : o.window().screenScale();
        Group<T> made = openWindow(
            group,
            o.index(),
            Window.options()
                .position(
                    Math.round(d.screenX() - (firstX + o.grabX()) * scale),
                    Math.round(d.screenY() - (firstY + o.grabY()) * scale))
                .underPointer(),
            false);
        following = made.frame;
        dragging.moved(made, 0);
      }
      following.window.dropTarget(false); // so that the windows under it take the drop
    }
    Frame<T> f = following;
    if (f == null) {
      return;
    }
    boolean open = frames.contains(f);
    if (moving && open) {
      if (!placed) { // the new window's own tree says where its tab lies, once it has one
        float[] at = firstTab(f);
        if (at.length > 0) {
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
    if (d.phase() == Drag.Phase.CANCEL && open) { // as it was
      Group<T> from = cast(o.bar());
      if (origin != null
          && hasGroup(origin)
          && hasGroup(from)
          && o.index() >= 0
          && o.index() < from.tabs.size()) {
        T tab = remove(from, o.index());
        int restoreAt = Math.min(originIndex, origin.tabs.size());
        origin.tabs.add(restoreAt, tab);
        int prevActive = originActiveTab == null ? -1 : indexOf(origin, originActiveTab);
        origin.active = prevActive >= 0 ? prevActive : restoreAt;
        if (originActiveGroup != null && originFrame.contains(originActiveGroup)) {
          originFrame.active = originActiveGroup;
        } else {
          originFrame.active = origin;
        }
        if (originHadFocus && originActiveTab == tab) {
          requestFocus(originFrame.active);
        } else if (originFrame.focusGroup == origin) {
          originFrame.focusGroup = null;
          originFrame.focusToken = null;
        }
      } else if (originFrame != null
          && frames.contains(originFrame)
          && hasGroup(from)
          && o.index() >= 0
          && o.index() < from.tabs.size()) {
        dock(from, o.index(), originFrame.active, originFrame.active.tabs.size());
      } else if (origin == null) {
        f.window.moveTo(startX, startY);
      }
    }
    boolean stays = frames.contains(f); // it stays where it was let go
    stopFollowing();
    if (!stays && d.phase() != Drag.Phase.CANCEL && d.window() != null) {
      d.window().raise(); // it went into the window under the pointer
    }
  }

  private static final float[] NO_TAB = {};

  /**
   * Where the first tab of {@code frame}'s first group lies in its window, and its height; empty
   * without one.
   */
  private static <T> float[] firstTab(Frame<T> frame) {
    El root = frame.window == null ? null : frame.window.app().tree();
    if (root == null) {
      return NO_TAB;
    }
    El target = Frame.firstGroup(frame.root).tablist;
    return find(root, target, 0, 0);
  }

  private static float[] find(El el, El target, float x, float y) {
    if (target != null ? el == target : "tablist".equals(el.role())) {
      for (El child : el.children()) {
        if ("tab".equals(child.role())) {
          return new float[] {x + child.x(), y + child.y(), child.height()};
        }
      }
      return NO_TAB;
    }
    for (El child : el.children()) {
      float[] at = find(child, target, x + child.x(), y + child.y());
      if (at.length > 0) {
        return at;
      }
    }
    return NO_TAB;
  }
}
