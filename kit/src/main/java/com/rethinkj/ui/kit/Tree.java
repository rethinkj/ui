package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.Drag;
import com.rethinkj.ui.Drop;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Scrollbars;
import com.rethinkj.ui.Shadow;
import com.rethinkj.ui.Style;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * Nodes that open and close, indented under their parents, such as the schema of a database:
 * servers, databases, schemas, tables, columns. The app owns the nodes, which nodes are open and
 * which one is selected; the tree shows them and reports what the user does:
 *
 * <pre>{@code
 * tree(state.catalog, new Tree.Options<Node>()
 *         .open(state.open::contains, (node, open) -> {
 *           if (open) {
 *             state.open.add(node);
 *             state.catalog.loadIfUnknown(node); // with App.background; Tree.loading() till then
 *           } else {
 *             state.open.remove(node);
 *           }
 *         })
 *         .select(state.selected, node -> state.selected = node)
 *         .onActivate(node -> state.show(node))
 *         .toggleOnDoublePress(node -> !node.isTable())) // a table opens, its columns stay shut
 *     .key("schema").label("Schema").grow(1);
 * }</pre>
 *
 * <p>Each rebuild of the view walks the open nodes from the roots down to number the rows, and the
 * rows are a virtual list ({@link El#rows}): only those in view and a few more exist as elements,
 * and the model is asked for the label and icon of those alone; typing asks for the labels of
 * all the nodes shown. A press on a row selects it; a double press also opens or closes it, unless
 * {@link Options#toggleOnDoublePress} says otherwise for the node, and activates it. A press on a
 * row's mark selects it and opens or closes it. A secondary press on a row selects it and then
 * runs the tree's own {@link El#onSecondaryPress} handler, such as the one {@link
 * Menu#contextMenu} gives it, so that a context menu acts on the row it opened on. While the tree
 * has the focus, Up and Down move the selection, Right opens a closed node or goes to its first
 * child, Left closes an open node or goes to its parent, Home, End, Page Up and Page Down move as
 * in a list, and Enter activates. Typing selects the next node shown whose label starts with what
 * was typed, or else the first that holds it ({@link El#onTypeAhead}): characters typed within a
 * second form one text, and the same character again goes on to the next node that starts with
 * it. The rows follow the selection ({@link El#revealRow}): whenever a rebuild puts the selected
 * node in another row, as when the keys move it or nodes above it open or close, the tree scrolls
 * just far enough to show it. Whenever nodes open or close, it also scrolls the selected node's
 * children into view below it, as far as the tree's height allows with the node still in view: a
 * node opened at the bottom shows what it holds. The tree is one stop for Tab. Like a list of
 * rows, it needs a height of its own or room to {@link El#grow}.
 *
 * <p>With {@link Options#drag} the user drags nodes onto others, and the app moves them ({@link
 * Move}): the middle half of a row that can take the node means into its node, the rest of the
 * row before or after it, below the rows after the last root. The rows keep where they were
 * scrolled to and do not follow the selection meanwhile; near the top or the bottom the tree
 * scrolls along while the pointer waits there, and a closed node the pointer rests on, where it
 * could take the node, opens after the theme's {@link Theme.Motion#submenuDelay}. Escape gives
 * the drag up.
 *
 * <p>Each level is indented by {@link Theme.Spacing#l}, and a hairline in {@link
 * Theme.Borders#divider} runs below the mark of every open node through the rows it holds. The
 * selected row has a short bar in the accent's edge at its start, as a {@link NavList}'s item, and
 * a mark that opens and closes its node brightens to the primary text color under the pointer.
 *
 * <p>In a tree dump the tree is {@code role=tree}, and its rows are {@code role=treeitem} with the
 * node's label and the key {@code row-<key>}, where the key is the model's {@link Model#key}, or
 * else the row's index, counted from 0 in the order shown; and the model's {@link Model#detail}
 * as {@code value="<detail>"}. A node that can have children has a mark with the key {@code
 * mark-<key>}, which shows the {@link Icon} {@link #CLOSED} while it is closed and {@link #OPEN}
 * while it is open; the marks need the icon font, {@code rethinkj-ui-icons}. The row that stands
 * for children not known yet has the key {@code row-<index>}, is {@code role=status} with the
 * loading text as its label, and shows a {@link Progress#spinner} before it. Where the children
 * could not be read ({@link #failed}), that row is {@code role=treeitem} and {@code invalid},
 * with the message as its label and as {@code error="<message>"}, and its action is {@code
 * role=button} with its text as the label. A label, a loading text or a message longer than its
 * row ends in an ellipsis, and a tooltip after the theme's delay shows it whole. A muted row
 * ({@link Model#muted}) says nothing more in a dump: only its label's color differs. The row of
 * a node being dragged is {@code grabbed}, with its label muted, and the line in the accent's
 * edge that shows where it goes, or the outline around the row it goes into, is {@code #marker}
 * in a row.
 */
public final class Tree {

  /** The mark of a node that is closed. */
  public static final Symbol CLOSED = Symbol.CHEVRON_RIGHT;

  /** The mark of a node that is open. */
  public static final Symbol OPEN = Symbol.CHEVRON_DOWN;

  /**
   * Where the tree gets its nodes from. The app implements it over the data it holds; nodes are
   * told apart with {@link Object#equals}, so that the selected node is found again in a new list
   * of children.
   *
   * @param <N> the app's type of node
   */
  public interface Model<N> {

    /** The nodes at the top, in the order shown. */
    List<N> roots();

    /**
     * What an open node holds: its children in the order shown ({@link Tree#ready}), or why they
     * are not shown: they are on their way ({@link Tree#loading}), and the row below the node
     * says so, or they could not be read ({@link Tree#failed}), and that row says why, with its
     * action, such as Try again, at its end. The tree does not try again by itself. Called for
     * every open node shown at each rebuild of the view, so it returns what the app holds,
     * without loading or computing anything.
     */
    Children<N> children(N node);

    /** Whether the node can have no children: a leaf has no mark and does not open. */
    boolean isLeaf(N node);

    /** The text of the node's row, also its label in a tree dump. */
    String label(N node);

    /** An element shown before the label, such as an icon; null, the default, for none. */
    default El icon(N node) {
      return null;
    }

    /**
     * A text shown after the label in muted text, such as a column's type or the number of tables
     * in a schema; null, the default, for none. Asked only for the rows that are built. Where the
     * row is narrow it gives way first: it ends in an ellipsis and is left out before the label
     * is cut, and a tooltip then shows both whole. In a tree dump it is the row's {@code value}.
     */
    default String detail(N node) {
      return null;
    }

    /**
     * Whether the node's label is in muted text, as a schema of the system beside the app's own;
     * false, the default. Asked only for the rows that are built. It changes nothing else: the
     * icon is the model's, the detail stays muted, and the row works as any other.
     */
    default boolean muted(N node) {
      return false;
    }

    /**
     * The key of the node's row, which a script finds at {@code #row-<key>} and its mark at
     * {@code #mark-<key>} however the nodes above it open or close; null, the default, keys the
     * row by its index. Asked only for the rows that are built. Unique among the nodes shown,
     * without spaces or double quotes, which a script reads as the key's end, and not a number
     * alone, which a loading row's key could be.
     */
    default String key(N node) {
      return null;
    }
  }

  /**
   * What an open node holds, as {@link Model#children} says: its children, or why the tree shows
   * a row in their place. Made with {@link Tree#ready}, {@link Tree#loading} and {@link
   * Tree#failed}.
   *
   * @param <N> the app's type of node
   */
  public sealed interface Children<N> {

    /** The children, in the order shown. */
    record Ready<N>(List<N> nodes) implements Children<N> {
      public Ready {
        Objects.requireNonNull(nodes, "nodes");
      }
    }

    /** Not known yet; {@code text} is the row's in their place, such as "Connecting…". */
    record Loading<N>(String text) implements Children<N> {
      public Loading {
        Objects.requireNonNull(text, "text");
      }
    }

    /**
     * Not readable: the message, and an action that the row offers, such as Try again; {@code
     * action} and {@code onAction} are null for none.
     */
    record Failed<N>(String text, String action, Runnable onAction) implements Children<N> {
      public Failed {
        Objects.requireNonNull(text, "text");
      }
    }
  }

  /** Children that are known. */
  public static <N> Children<N> ready(List<N> nodes) {
    return new Children.Ready<>(nodes);
  }

  // The kit's own row of children on their way, which says Texts.loading of the view it shows in.
  private static final Children.Loading<?> LOADING = new Children.Loading<>("Loading…");

  /** Children not known yet, whose row says {@link Texts#loading} in the view's texts. */
  @SuppressWarnings("unchecked")
  public static <N> Children<N> loading() {
    return (Children<N>) LOADING;
  }

  /** Children not known yet, whose row says {@code text}. */
  public static <N> Children<N> loading(String text) {
    return new Children.Loading<>(text);
  }

  /** Children that could not be read, for this reason, with no action. */
  public static <N> Children<N> failed(String text) {
    return new Children.Failed<>(text, null, null);
  }

  /** Children that could not be read, with an action at the end of their row. */
  public static <N> Children<N> failed(String text, String action, Runnable onAction) {
    return new Children.Failed<>(text, action, onAction);
  }

  /** Told that the user opens or closes a node. */
  @FunctionalInterface
  public interface Toggle<N> {
    void toggle(N node, boolean open);
  }

  /** Where a dropped node goes, from the node it is dropped on ({@link Move#target}). */
  public enum Place {
    /** Before the target, among its siblings. */
    BEFORE,
    /** After the target, among its siblings: below the target's children, where it is open. */
    AFTER,
    /** Into the target, among its children; where among them is the app's to say. */
    INTO
  }

  /**
   * A node dropped on another, or one that would be: {@code node} goes before, after or into
   * {@code target}, as {@code place} says. Nodes have no parents in the {@link Model}, so the
   * target says where: its parent is the new one of the node, or the target itself for {@link
   * Place#INTO}. The tree never asks about or reports a move of a node into itself or into one
   * of its descendants.
   *
   * @param node the node dragged
   * @param target the node it is dropped on
   * @param place where it goes from there
   * @param <N> the app's type of node
   */
  public record Move<N>(N node, N target, Place place) {
    public Move {
      Objects.requireNonNull(node, "node");
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(place, "place");
    }
  }

  /**
   * What a tree keeps of a drag of its nodes while it lasts: where the node would go and how far
   * the tree has scrolled along. The app makes one for each tree, keeps it in its state, and
   * gives it to {@link Options#drag} at every build of the view; the tree alone changes it.
   */
  public static final class Dragging {
    Object node; // the node dragged; null while none is
    // What the tree kept in view when the drag began, which it keeps meanwhile, so that rows that
    // open do not scroll it away: the selected row, and the number of rows its reveal counts.
    int selectedRow;
    int rowCount;
    int offset; // added to the number of rows it counts, so that the drag's rows count as before
    boolean ended; // a drag ended since the last build
    int scrolls; // the steps it scrolled along near an edge, for its reveal
    float scrollTo; // where the last of them scrolled to, in the content
    int edge; // the edge the pointer waits near: -1 the top, 1 the bottom, 0 neither
    double edgeSince; // since when, on the app's clock, or since it last scrolled
    Object resting; // the closed node the pointer rests on, which opens after a while
    double restingSince;
    int markRow = -1; // the row that shows where the node goes, -1 for none
    boolean into; // into that row's node, else a line at its top or bottom
    boolean top;
    int depth; // the level of the line

    /** A drag state for one tree. */
    public Dragging() {}

    void unmark() {
      markRow = -1;
      edge = 0;
      resting = null;
    }

    void clear() {
      unmark();
      ended |= node != null;
      node = null;
    }
  }

  /** What a row's drag carries: the node, for the tree's drop handler from the first step on. */
  private record Carried(Dragging dragging, Object node) {}

  /**
   * Which nodes are open and selected, and what the user's input does, as {@code new
   * Tree.Options<Node>().open(...).select(...)}. Without any, the tree shows its roots, closed,
   * and takes no input.
   *
   * @param <N> the app's type of node
   */
  public static final class Options<N> {
    private Predicate<? super N> isOpen = node -> false;
    private Toggle<? super N> onToggle;
    private N selected;
    private Consumer<? super N> onSelect;
    private Consumer<? super N> onActivate;
    private Predicate<? super N> toggleOnDoublePress = node -> true;
    private Dragging dragging;
    private Predicate<? super N> canDrag;
    private Predicate<? super Move<N>> canDrop;
    private Consumer<? super Move<N>> onDrop;

    /**
     * @param isOpen whether a node is open; asked for the nodes that are not leaves, from the
     *     roots down through the open ones
     * @param onToggle told the node the user opens or closes, and whether it is to be open; null
     *     leaves that to the app
     */
    public Options<N> open(Predicate<? super N> isOpen, Toggle<? super N> onToggle) {
      this.isOpen = Objects.requireNonNull(isOpen, "isOpen");
      this.onToggle = onToggle;
      return this;
    }

    /**
     * @param selected the selected node, or null for none; one that is not shown, inside a closed
     *     node, counts as none
     * @param onSelect told the node the user selects, only when it changes; null makes the rows
     *     unselectable and the tree no stop for Tab
     */
    public Options<N> select(N selected, Consumer<? super N> onSelect) {
      this.selected = selected;
      this.onSelect = onSelect;
      return this;
    }

    /** Told the node the user activates with a double press or Enter; null for none. */
    public Options<N> onActivate(Consumer<? super N> onActivate) {
      this.onActivate = onActivate;
      return this;
    }

    /**
     * Whether a double press on a node opens or closes it as well as activating it; all of them,
     * the default. A database tool that opens a table on a double press says no for tables, whose
     * columns then open by the mark or the keys alone.
     */
    public Options<N> toggleOnDoublePress(Predicate<? super N> toggles) {
      this.toggleOnDoublePress = Objects.requireNonNull(toggles, "toggles");
      return this;
    }

    /**
     * Lets the user drag nodes and drop them before, after or into others; the app moves them.
     * A press without a drag selects and activates as before.
     *
     * @param dragging what the tree keeps of a drag, which the app holds; one for each tree
     * @param canDrag whether the user may drag a node
     * @param canDrop whether a node may go where the pointer is; asked on the steps of the drag
     *     for what the pointer may mean there, never for a move into the node itself or one of
     *     its descendants
     * @param onDrop told the move when the user drops a node where {@code canDrop} allows it
     */
    public Options<N> drag(
        Dragging dragging,
        Predicate<? super N> canDrag,
        Predicate<? super Move<N>> canDrop,
        Consumer<? super Move<N>> onDrop) {
      this.dragging = Objects.requireNonNull(dragging, "dragging");
      this.canDrag = Objects.requireNonNull(canDrag, "canDrag");
      this.canDrop = Objects.requireNonNull(canDrop, "canDrop");
      this.onDrop = Objects.requireNonNull(onDrop, "onDrop");
      return this;
    }
  }

  private Tree() {}

  /** The nodes of {@code model} as {@code options} say. */
  public static <N> El tree(Model<N> model, Options<N> options) {
    Theme theme = Theme.CURRENT.get();
    Predicate<? super N> isOpen = options.isOpen;
    Toggle<? super N> onToggle = options.onToggle;
    Consumer<? super N> onSelect = options.onSelect;
    Consumer<? super N> onActivate = options.onActivate;
    Predicate<? super N> toggleOnDoublePress = options.toggleOnDoublePress;
    Dragging dragging = options.dragging;
    // While a node is dragged the tree keeps in view what it did when the drag began.
    boolean held = dragging != null && dragging.node != null;
    Rows<N> rows = new Rows<>(model, isOpen, options.selected);
    if (dragging != null && dragging.ended) { // the rows it opened or moved count as they were
      dragging.offset = dragging.rowCount - rows.count;
      dragging.ended = false;
    }
    int counted = rows.count + (dragging == null ? 0 : dragging.offset);
    float height = theme.grid().rowHeight();
    float mark = theme.space().xl(); // the mark's width, which takes presses
    float indent = theme.space().l();
    boolean presses = onSelect != null || onToggle != null || onActivate != null;
    // The rows are built after the tree's focus look has run, so they can read what it found.
    boolean[] focused = {false};
    El tree = col()
        .role("tree")
        .scrollbars(theme.scrollbars())
        .revealRow(held ? dragging.selectedRow : rows.selected);
    IntFunction<El> build = i -> {
      int depth = rows.depth(i);
      El r = row()
          .radius(theme.radius().medium())
          .padding(0, theme.space().s(), 0, theme.space().s() + depth * indent)
          .gap(theme.space().s())
          .align(Style.Align.CENTER);
      if (depth > 0) {
        r.child(guides(theme, depth, mark, indent, height));
      }
      if (rows.nodes[i] instanceof Pending pending) { // the children of the row's parent
        r.key("row-" + i).child(el().w(mark).shrink(0));
        if (pending.children() instanceof Children.Failed<?> failure) {
          return failed(theme, r, failure, depth + 1, height);
        }
        String text = pending.children() == LOADING
            ? Texts.CURRENT.get().loading()
            : ((Children.Loading<?>) pending.children()).text();
        El loading = Label.label(Label.Variant.MUTED, text).ellipsis().minSize(0, 0);
        r.role("status").label(text).children(Progress.spinner(), loading);
        return whole(theme, r, loading, null, text, height);
      }
      N node = rows.node(i);
      boolean grabbed = held && node.equals(dragging.node);
      String key = model.key(node);
      String id = key == null ? Integer.toString(i) : key;
      boolean leaf = model.isLeaf(node);
      boolean open = !leaf && isOpen.test(node);
      boolean isSelected = i == rows.selected;
      Runnable select = () -> {
        if (onSelect != null && !isSelected) {
          onSelect.accept(node);
        }
      };
      El handle = el().size(mark, height).shrink(0).center(); // the whole height takes presses
      if (!leaf) {
        El chevron = Icon.icon(open ? OPEN : CLOSED, Icon.Variant.MUTED);
        handle.key("mark-" + id).child(chevron);
        if (onToggle != null) {
          handle
              .onPress(() -> {
                select.run();
                onToggle.toggle(node, !open);
              })
              .whenHovered(e -> chevron.color(theme.text().primary()));
        }
      }
      String label = model.label(node);
      r.key("row-" + id)
          .role("treeitem")
          .label(label)
          .child(handle)
          .selected(isSelected)
          .level(depth + 1)
          .position(rows.positions[i], rows.sizes[i]);
      if (!leaf) {
        r.expanded(open);
      }
      El icon = model.icon(node);
      if (icon != null) {
        r.child(icon);
      }
      Label.Variant variant =
          grabbed || model.muted(node) ? Label.Variant.MUTED : Label.Variant.NORMAL;
      String detail = model.detail(node);
      if (detail == null) {
        El name = Label.label(variant, label).ellipsis().minSize(0, 0);
        whole(theme, r.child(name), name, null, label, height);
      } else {
        detail(theme, r.value(detail), variant, label, detail, height);
      }
      if (isSelected) {
        // Last, so that selecting a row leaves the places of its other children as they were.
        float bar = theme.size().icon();
        r.child(el().absolute(theme.space().xs(), (height - bar) / 2)
                .size(theme.space().xs(), bar)
                .radius(theme.space().xs() / 2)
                .fill(theme.accent().edge()))
            .fill(focused[0] ? theme.grid().selected() : theme.grid().selectedInactive());
      } else if (presses && !grabbed) { // the dragged row is the one hovered meanwhile
        r.whenHovered(e -> e.fill(theme.surface().hover()));
      }
      if (dragging != null && options.canDrag.test(node)) {
        // The tree's drop handler does the rest, with the rows of the latest build.
        r.grabbed(grabbed).dragPayload(new Carried(dragging, node)).onDrag(d -> {
          if (d.phase() == Drag.Phase.START && dragging.node == null) { // not over the tree
            begin(dragging, node, rows, counted, tree);
          } else if (d.phase() == Drag.Phase.END || d.phase() == Drag.Phase.CANCEL) {
            dragging.clear();
          }
        });
      }
      // The rows are built after the view, so a context menu given to the tree is there: the
      // row a secondary press lands on is selected before the menu opens.
      El.PointHandler secondary = tree.onSecondaryPress();
      if (secondary != null) {
        r.onSecondaryPress((x, y) -> {
          select.run();
          secondary.accept(x, y);
        });
      }
      return !presses
          ? r
          : r.onPress(clicks -> {
            select.run();
            if (clicks == 2) {
              if (!leaf && onToggle != null && toggleOnDoublePress.test(node)) {
                onToggle.toggle(node, !open);
              }
              if (onActivate != null) {
                onActivate.accept(node);
              }
            }
          });
    };
    tree.rows(rows.count, height, i -> marked(theme, dragging, i, height, build.apply(i)));
    int scrolls = dragging == null ? 0 : dragging.scrolls;
    if (held) { // scrolls along near an edge, and else nowhere
      tree.reveal(List.of(dragging.rowCount, scrolls), 0, dragging.scrollTo, 0, 1);
    } else if (rows.selected >= 0) {
      // When nodes open or close, the number of rows changes: the tree shows the selected node's
      // children below it as far as the view has room, as one just opened by Right or the mark.
      int end = rows.end(rows.selected);
      tree.reveal(
          List.of(counted, scrolls), 0, rows.selected * height, 0, (end - rows.selected) * height);
    }
    if (dragging != null) {
      tree.onDrop(d -> drop(theme, model, options, rows, counted, tree, height, d));
    }
    if (onSelect == null) {
      return tree;
    }
    return tree.focusable()
        .focusRing(theme.accent().edge())
        .whenFocused(e -> focused[0] = true)
        .onKey(k -> key(k, model, isOpen, rows, tree, height, onSelect, onToggle, onActivate))
        .onTypeAhead(typed -> {
          int to = TypeAhead.find(rows.count, rows.selected, typed, i -> {
            N node = rows.node(i);
            return node == null ? null : model.label(node);
          });
          if (to >= 0 && to != rows.selected) {
            onSelect.accept(rows.node(to));
          }
        });
  }

  /** How many rows a second the tree scrolls along while a drag waits near its top or bottom. */
  private static final double ALONG = 10;

  /**
   * Adds to a row what shows where a dragged node goes, if it is this row's: a line along its top
   * or bottom from the level the node goes to, or an outline around it for into it.
   */
  private static El marked(Theme theme, Dragging dragging, int row, float height, El r) {
    if (dragging == null || dragging.node == null || row != dragging.markRow) {
      return r;
    }
    float line = theme.size().indicator();
    El marker = el().key("marker");
    if (dragging.into) {
      marker.absolute(0, 0).wFull().h(height).radius(theme.radius().medium());
      marker.border(line, theme.accent().edge());
    } else {
      // Across the boundary with the row above, which this row covers: the first row has none.
      float y = !dragging.top ? height - line : row == 0 ? 0 : -line / 2;
      float left = theme.space().s() + dragging.depth * theme.space().l();
      marker.absolute(left, y).h(line).radius(line / 2).fill(theme.accent().edge());
      marker.style().right = Len.px(theme.space().s());
    }
    return r.child(marker); // last, so that the row's other parts keep their places
  }

  /**
   * Where a node dropped at a point of the tree's content goes, and which row shows it: the
   * middle half of a node's row means into it, where it may go there; the rest of the row's upper
   * half, or all of it, before the node, and of its lower half after it, or before its first
   * child where it shows its children; below the rows, after the last root.
   *
   * @param move what a release would report
   * @param target the row of the move's target
   * @param row the row that shows it
   * @param into whether the outline of that row shows it, else a line
   * @param top whether the line runs along the row's top, else its bottom
   * @param depth the level of the line
   */
  private record Aim<N>(Move<N> move, int target, int row, boolean into, boolean top, int depth) {}

  /** A step of a drag of a node over the tree: the marker, scrolling along, opening, the drop. */
  private static <N> void drop(
      Theme theme,
      Model<N> model,
      Options<N> o,
      Rows<N> rows,
      int counted,
      El tree,
      float height,
      Drop d) {
    Dragging g = o.dragging;
    if (!(d.payload() instanceof Carried c) || c.dragging() != g) {
      return; // something else's drag
    }
    if (d.phase() == Drop.Phase.LEAVE) {
      g.unmark();
      return;
    }
    @SuppressWarnings("unchecked") // the payload of a row of this tree
    N node = (N) c.node();
    if (g.node == null) { // the first step, before the row's drag handler has its own
      begin(g, node, rows, counted, tree);
    }
    if (d.phase() == Drop.Phase.DROP) {
      g.unmark();
      Aim<N> aim = aim(theme, model, o, rows, node, d.x(), d.y(), height);
      if (aim != null) {
        o.onDrop.accept(aim.move());
      }
      return;
    }
    // Near the top or the bottom the tree scrolls along, by the time the pointer waits there.
    double scrollY = tree.scrollY();
    double y = d.y() - scrollY;
    double end = Math.max(0, rows.count * height - tree.height()); // the farthest it scrolls
    float near = Math.min(height, tree.height() / 2);
    int edge = y < near && scrollY > 0 ? -1 : y > tree.height() - near && scrollY < end ? 1 : 0;
    double shift = 0; // how far the rows move under the pointer with this step
    if (edge == 0 || edge != g.edge || !(d.time() > g.edgeSince)) { // NaN: no clock, no scroll
      g.edge = edge;
      g.edgeSince = d.time();
    } else {
      double by = Math.min(tree.height(), (d.time() - g.edgeSince) * ALONG * height);
      g.edgeSince = d.time();
      g.scrolls++;
      g.scrollTo = (float) (edge < 0 ? scrollY - by : scrollY + tree.height() + by - 1);
      shift = edge < 0 ? -Math.min(by, scrollY) : Math.min(by, end - scrollY);
    }
    // Where the node would go once the rows have moved, which the next frame shows.
    Aim<N> aim = aim(theme, model, o, rows, node, d.x(), d.y() + shift, height);
    g.markRow = aim == null ? -1 : aim.row();
    if (aim != null) {
      g.into = aim.into();
      g.top = aim.top();
      g.depth = aim.depth();
    }
    // A closed node that the pointer rests on, where it would drop the node into it, opens.
    N into = aim != null && aim.into() ? aim.move().target() : null;
    if (into == null || o.onToggle == null || o.isOpen.test(into)) {
      g.resting = null;
    } else if (!into.equals(g.resting)) {
      g.resting = into;
      g.restingSince = d.time();
    } else if (d.time() - g.restingSince >= theme.motion().submenuDelay()) {
      g.resting = null;
      o.onToggle.toggle(into, true);
    }
  }

  /** Notes that a drag of {@code node} began, and what the tree keeps in view meanwhile. */
  private static void begin(Dragging g, Object node, Rows<?> rows, int counted, El tree) {
    g.node = node;
    g.selectedRow = rows.selected;
    g.rowCount = counted;
    g.scrollTo = (float) tree.scrollY();
  }

  /** Where a node dropped at {@code x}, {@code y} of the tree's content goes; null for nowhere. */
  private static <N> Aim<N> aim(
      Theme theme,
      Model<N> model,
      Options<N> o,
      Rows<N> rows,
      N node,
      double x,
      double y,
      float height) {
    int count = rows.count;
    if (count == 0) {
      return null;
    }
    // The node's own row and those of its descendants take nothing: it cannot go into itself.
    int from = rows.indexOf(node);
    int to = from < 0 ? -1 : rows.end(from);
    if (y >= count * height) { // below the rows: after the last root
      int root = count - 1;
      while (rows.depth(root) > 0) {
        root--;
      }
      Move<N> move = new Move<>(node, rows.node(root), Place.AFTER);
      return allowed(o, new Aim<>(move, root, count - 1, false, false, 0), from, to);
    }
    int i = (int) Math.max(0, Math.floor(y / height));
    double part = y / height - i;
    N at = rows.node(i);
    if (at != null && !model.isLeaf(at) && part >= 0.25 && part < 0.75) {
      Move<N> move = new Move<>(node, at, Place.INTO);
      Aim<N> into = allowed(o, new Aim<>(move, i, i, true, false, 0), from, to);
      if (into != null) {
        return into;
      }
    }
    Move<N> move;
    int target;
    int row = i + 1; // the row whose top the line runs along
    boolean top = true;
    int depth;
    if (part < 0.5) { // before the row's node
      if (at == null) {
        return null;
      }
      move = new Move<>(node, at, Place.BEFORE);
      target = row = i;
      depth = rows.depth(i);
    } else if (i + 1 < count && rows.depth(i + 1) > rows.depth(i)) { // before its first child
      N first = rows.node(i + 1);
      if (first == null) {
        return null;
      }
      move = new Move<>(node, first, Place.BEFORE);
      target = i + 1;
      depth = rows.depth(i + 1);
    } else {
      // After the row's node, or after one of its ancestors down to the level of the next row:
      // the pointer's place across the levels says which, one indent each, from the left.
      boolean last = i + 1 == count;
      int above = at == null ? rows.parentRow(i) : i;
      double level = Math.floor((x - theme.space().s()) / theme.space().l());
      depth = Math.clamp((long) level, last ? 0 : rows.depth(i + 1), rows.depth(above));
      target = above;
      while (rows.depth(target) > depth) {
        target = rows.parentRow(target);
      }
      move = new Move<>(node, rows.node(target), Place.AFTER);
      if (last) { // along the bottom of the last row
        row = i;
        top = false;
      }
    }
    return allowed(o, new Aim<>(move, target, row, false, top, depth), from, to);
  }

  /**
   * The aim, or null where its target is the dragged node or one of its descendants, in the rows
   * {@code from} to {@code to}, or where the app does not allow the move.
   */
  private static <N> Aim<N> allowed(Options<N> o, Aim<N> aim, int from, int to) {
    boolean inside = aim.target() >= from && aim.target() < to;
    return inside || !o.canDrop.test(aim.move()) ? null : aim;
  }

  /**
   * The hairlines of a row at {@code depth} below the mark of each of its ancestors, through the
   * whole height of the row. One element at the first, whose sharp shadows are the others, so
   * that a deep row has one more element in a dump, not one per level.
   */
  private static El guides(Theme theme, int depth, float mark, float indent, float height) {
    float stroke = theme.size().stroke();
    int color = theme.border().divider();
    List<Shadow> more = new ArrayList<>(depth - 1);
    for (int level = 1; level < depth; level++) {
      more.add(Theme.lineCopy(level * indent, color));
    }
    return el().absolute(theme.space().s() + (mark - stroke) / 2, 0)
        .size(stroke, height)
        .fill(color)
        .shadow(more);
  }

  /**
   * The label and the detail after it in a box that wraps and clips at the row's height, as the
   * data grid's header gives way with its type: the label keeps its width up to the box's, the
   * detail shrinks to an ellipsis after its first character and below that wraps to a second line,
   * which the box clips away. A zero-width strut as high as the row makes the first line that high,
   * so that both texts sit in its middle. The tooltip names both where either is cut or gone.
   */
  private static void detail(
      Theme theme, El row, Label.Variant variant, String label, String detail, float height) {
    El name = Label.label(variant, label).ellipsis().minSize(0, 0).shrink(0);
    name.style().maxWidth = Len.percent(1);
    El more = Label.label(Label.Variant.MUTED, detail)
        .ellipsis()
        .minSize(0, 0)
        .grow(1)
        .basis(TextSizing.natural(Label.label(Label.Variant.MUTED, TextSizing.least(detail))))
        .margin(0, 0, 0, theme.space().s());
    El box = row()
        .wrap()
        .grow(1)
        .minSize(0, 0)
        .h(height)
        .align(Style.Align.CENTER)
        .clip()
        .children(el().size(0, height), name, more);
    box.style().alignContent = Style.Content.START;
    whole(theme, row.child(box), name, more, label + " " + detail, height);
  }

  /**
   * Gives a row a tooltip with its whole text, shown after the theme's delay where its name ends
   * in an ellipsis or its detail is cut or wrapped away, which only layout knows; the looks of
   * rows run after it. One look, no element, for every kind of row.
   *
   * @param more the detail after the name, or null for none
   * @return the row
   */
  private static El whole(Theme theme, El row, El name, El more, String text, float height) {
    return Tooltip.whenHovered(
        theme,
        row,
        () -> name.text().shortened()
                || more != null && (more.text().shortened() || more.y() >= height)
            ? text
            : null);
  }

  /**
   * The row of a failure: an icon in the danger color, the message, which ends in an ellipsis
   * where the row is narrow, and the action at the end. It is a tree item that is {@code invalid}
   * with the message as its label and error, at the level of the children it stands for and the
   * only one there. The action is a quiet button in the link color, as high as the row less a
   * gap and clear of the scrollbar, which keeps its width and is a stop for Tab of its own after
   * the tree, since the row is not a node the selection can be on; Enter and Space run it, as a
   * press does, and a press leaves the focus where it is.
   */
  private static El failed(
      Theme theme, El row, Children.Failed<?> failure, int level, float height) {
    El text = Label.label(failure.text()).ellipsis().minSize(0, 0).grow(1);
    row.role("treeitem")
        .label(failure.text())
        .invalid(true)
        .errorMessage(failure.text())
        .level(level)
        .position(1, 1)
        .children(Icon.icon(Symbol.ERROR_CIRCLE, Icon.Variant.DANGER), text);
    whole(theme, row, text, null, failure.text(), height);
    Runnable run = failure.onAction();
    if (failure.action() == null || run == null) {
      return row;
    }
    // Clear of the scrollbar, which lies over the end of the rows: the only row with a part there.
    Scrollbars bars = theme.scrollbars();
    float clear = Math.max(0, bars.strip() - theme.space().s());
    // No motion: it would fade in as the row turns from loading to failed, untouchable meanwhile.
    return row.child(row()
        .role("button")
        .label(failure.action())
        .h(height - theme.space().xs())
        .shrink(0)
        .margin(0, clear, 0, 0)
        .padding(0, theme.space().s())
        .align(Style.Align.CENTER)
        .radius(theme.radius().small())
        .focusable()
        .keepFocus()
        .focusRing(theme.accent().edge())
        .cursor(Cursor.POINTER)
        .onPress(run)
        .onKey(k -> {
          // Its own, before the tree's keys: the tree would take Enter for the selected node.
          boolean press =
              k.modifiers() == 0 && (k.name().equals("Enter") || k.name().equals("Space"));
          if (press) {
            run.run();
          }
          return press;
        })
        .whenHovered(
            e -> e.fill(theme.surface().hover())) // as a link: no darker look while pressed
        .child(Label.label(failure.action()).color(theme.prose().link())));
  }

  /** What a key does to the tree with the focus; whether the tree took it. */
  private static <N> boolean key(
      Key k,
      Model<N> model,
      Predicate<? super N> isOpen,
      Rows<N> rows,
      El tree,
      float height,
      Consumer<? super N> onSelect,
      Toggle<? super N> onToggle,
      Consumer<? super N> onActivate) {
    if (k.modifiers() != 0 || rows.count == 0) {
      return false;
    }
    int from = rows.selected;
    N node = from < 0 ? null : rows.node(from);
    boolean open = node != null && !model.isLeaf(node) && isOpen.test(node);
    int page = Math.max(1, (int) (tree.height() / height));
    int inView = rows.nodeFrom((int) Math.ceil(tree.scrollY() / height), 1);
    int next = switch (k.name()) {
      case "ArrowUp" -> from < 0 ? inView : rows.nodeFrom(from - 1, -1);
      case "ArrowDown" -> from < 0 ? inView : rows.nodeFrom(from + 1, 1);
      case "PageUp" -> from < 0 ? inView : rows.nodeFrom(from - page, -1);
      case "PageDown" -> from < 0 ? inView : rows.nodeFrom(from + page, 1);
      case "Home" -> 0;
      case "End" -> rows.nodeFrom(rows.count - 1, -1);
      case "ArrowRight" -> {
        if (node == null) {
          yield -2;
        }
        if (!model.isLeaf(node) && !open && onToggle != null) {
          onToggle.toggle(node, true);
        }
        boolean child = open
            && from + 1 < rows.count
            && rows.depth(from + 1) > rows.depth(from)
            && rows.node(from + 1) != null;
        yield child ? from + 1 : from;
      }
      case "ArrowLeft" -> {
        if (node == null) {
          yield -2;
        }
        if (open && onToggle != null) {
          onToggle.toggle(node, false);
          yield from;
        }
        yield rows.parentRow(from);
      }
      case "Enter" -> {
        if (node == null || onActivate == null) {
          yield -2;
        }
        onActivate.accept(node);
        yield from;
      }
      default -> -2;
    };
    if (next == -2) {
      return false;
    }
    if (next != from) {
      onSelect.accept(rows.node(next));
    }
    return true;
  }

  /**
   * The rows of the tree as one rebuild of the view sees them: the nodes shown, in order, with
   * their depths and their positions among their siblings, from the roots down through the open
   * nodes. The row that stands for children not known or not readable holds a {@link Pending}.
   */
  private static final class Rows<N> {
    private Object[] nodes = new Object[64];
    private int[] depths = new int[64];
    int[] positions = new int[64]; // from 1
    int[] sizes = new int[64];
    int count;
    int selected = -1;

    Rows(Model<N> model, Predicate<? super N> isOpen, N selected) {
      add(model, isOpen, model.roots(), 0, selected);
    }

    private void add(
        Model<N> model, Predicate<? super N> isOpen, List<N> children, int depth, N selected) {
      int position = 0;
      for (N node : children) {
        if (this.selected < 0 && selected != null && selected.equals(node)) {
          this.selected = count;
        }
        put(node, depth, ++position, children.size());
        if (!model.isLeaf(node) && isOpen.test(node)) {
          Children<N> below = Objects.requireNonNull(model.children(node), "children");
          if (below instanceof Children.Ready<N> ready) {
            add(model, isOpen, ready.nodes(), depth + 1, selected);
          } else {
            put(new Pending(below), depth + 1, 0, 0); // the list gives its row a position
          }
        }
      }
    }

    private void put(Object node, int depth, int position, int size) {
      if (count == nodes.length) {
        nodes = Arrays.copyOf(nodes, count * 2);
        depths = Arrays.copyOf(depths, count * 2);
        positions = Arrays.copyOf(positions, count * 2);
        sizes = Arrays.copyOf(sizes, count * 2);
      }
      nodes[count] = node;
      positions[count] = position;
      sizes[count] = size;
      depths[count++] = depth;
    }

    /** The node of the row, or null for a pending row. */
    @SuppressWarnings("unchecked")
    N node(int row) {
      return nodes[row] instanceof Pending ? null : (N) nodes[row];
    }

    int depth(int row) {
      return depths[row];
    }

    /** The row after the node's in its row and the rows of its descendants shown. */
    int end(int row) {
      int end = row + 1;
      while (end < count && depths[end] > depths[row]) {
        end++;
      }
      return end;
    }

    /** The row of a node, or -1 where it is not shown. */
    int indexOf(Object node) {
      for (int i = 0; i < count; i++) {
        if (node.equals(nodes[i])) {
          return i;
        }
      }
      return -1;
    }

    /** The row of the node's parent, or the row itself at the top. */
    int parentRow(int row) {
      for (int i = row - 1; i >= 0; i--) {
        if (depths[i] < depths[row]) {
          return i;
        }
      }
      return row;
    }

    /**
     * The row of a node nearest to {@code row}, clamped to the rows, going {@code step} past a
     * pending row; a pending row follows its parent, so going back always finds one.
     */
    int nodeFrom(int row, int step) {
      int i = Math.clamp(row, 0, count - 1);
      while (nodes[i] instanceof Pending) {
        i = i + step < count ? i + step : i - 1;
      }
      return i;
    }
  }

  /** In the rows, in place of children that are loading or failed to load. */
  private record Pending(Children<?> children) {}
}
