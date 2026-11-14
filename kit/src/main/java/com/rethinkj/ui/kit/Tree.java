package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Scrollbars;
import com.rethinkj.ui.Style;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Nodes that open and close, indented under their parents, such as the schema of a database:
 * servers, databases, schemas, tables, columns. The app owns the nodes, which nodes are open and
 * which one is selected; the tree shows them and reports what the user does:
 *
 * <pre>{@code
 * tree(theme, s.catalog, s.open::contains, s.selected,
 *     node -> s.selected = node,
 *     (node, open) -> {
 *       if (open) {
 *         s.open.add(node);
 *         s.catalog.loadIfUnknown(node); // with App.background; children() is null till then
 *       } else {
 *         s.open.remove(node);
 *       }
 *     },
 *     node -> s.show(node))
 *     .key("schema").label("Schema").grow(1);
 * }</pre>
 *
 * <p>Each rebuild of the view walks the open nodes from the roots down to number the rows, and the
 * rows are a virtual list ({@link El#rows}): only those in view and a few more exist as elements,
 * and the model is asked for the label and icon of those alone. A press on a row selects it; a
 * double press also opens or closes it and activates it. A press on a row's mark selects it and
 * opens or closes it. A secondary press on a row selects it and then runs the tree's own {@link
 * El#onSecondaryPress} handler, such as the one {@link Menu#contextMenu} gives it, so that a context
 * menu acts on the row it opened on. While the tree has the focus, Up and Down move the selection, Right opens a
 * closed node or goes to its first child, Left closes an open node or goes to its parent, Home,
 * End, Page Up and Page Down move as in a list, and Enter activates. The rows follow the
 * selection ({@link El#revealRow}): whenever a rebuild puts the selected node in another row, as
 * when the keys move it or nodes above it open or close, the tree scrolls just far enough to show
 * it. Whenever nodes open or close, it also scrolls the selected node's children into view below
 * it, as far as the tree's height allows with the node still in view: a node opened at the bottom
 * shows what it holds. The tree is one stop for Tab. Like a list of rows, it needs a height of its own or room to
 * {@link El#grow}.
 *
 * <p>In a tree dump the tree is {@code role=tree}, and its rows are {@code role=treeitem} with the
 * node's label and the key {@code row-<index>}, counted from 0 in the order shown, and the
 * model's {@link Model#detail} as {@code value="<detail>"}. A node that can
 * have children has a mark with the key {@code mark-<index>}, which shows the {@link Icon} {@link
 * #CLOSED} while it is closed and {@link #OPEN} while it is open; the marks need the icon font,
 * {@code rethinkj-ui-icons}. The row that stands for children not known yet has the key {@code
 * row-<index>} too, but no role, and shows the model's {@link Model#loading} text. Where the model
 * says why they could not be read ({@link Model#failure}), that row is {@code role=treeitem} and
 * {@code invalid}, with the message as its label and as {@code error="<message>"}, and its action
 * is {@code role=button} with its text as the label. A label, a loading text or a message longer
 * than its row ends in an ellipsis, and a tooltip after the theme's delay shows it whole. A muted
 * row ({@link Model#muted}) says nothing more in a dump: only its label's colour differs.
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
     * The children of a node, in the order shown, or null while they are not known yet: an open
     * node then shows a loading row instead, until the app has them. Called for every open node
     * shown at each rebuild of the view, so it returns what the app holds, without loading or
     * computing anything.
     */
    List<N> children(N node);

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

    /** The text of the row that stands for the children of an open node while they are not known. */
    default String loading(N node) {
      return "Loading…";
    }

    /**
     * Why the children of an open node could not be read, or null, the default, while they are
     * still on their way: asked, when its row is built, for an open node whose {@link #children}
     * are null, before {@link #loading}. A failure shows in the row below the node in place of
     * the loading row, with its action, such as Try again, at the row's end. The tree does not
     * try again by itself.
     */
    default Failure failure(N node) {
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
  }

  /**
   * Why the children of a node could not be read: the message, and an action that the row offers,
   * such as Try again; {@code action} and {@code onAction} are null for none. Made with {@link
   * Tree#failure} and {@link #action}.
   */
  public record Failure(String text, String action, Runnable onAction) {

    /** This failure with an action at the end of its row, which runs {@code onAction}. */
    public Failure action(String action, Runnable onAction) {
      return new Failure(text, action, onAction);
    }
  }

  /** A failure with this message and no action. */
  public static Failure failure(String text) {
    return new Failure(text, null, null);
  }

  /** Told that the user opens or closes a node. */
  @FunctionalInterface
  public interface Toggle<N> {
    void toggle(N node, boolean open);
  }

  private Tree() {}

  /**
   * @param isOpen whether a node is open; asked for the nodes that are not leaves, from the roots
   *     down through the open ones
   * @param selected the selected node, or null for none; one that is not shown, inside a closed
   *     node, counts as none
   * @param onSelect told the node the user selects, only when it changes; null makes the rows
   *     unselectable and the tree no stop for Tab
   * @param onToggle told the node the user opens or closes, and whether it is to be open; null
   *     leaves that to the app
   * @param onActivate told the node the user activates with a double press or Enter; null for none
   */
  public static <N> El tree(
      Theme t,
      Model<N> model,
      Predicate<? super N> isOpen,
      N selected,
      Consumer<? super N> onSelect,
      Toggle<? super N> onToggle,
      Consumer<? super N> onActivate) {
    Rows<N> rows = new Rows<>(model, isOpen, selected);
    float height = t.grid().rowHeight();
    float indent = t.space().xl();
    boolean presses = onSelect != null || onToggle != null || onActivate != null;
    // The rows are built after the tree's focus look has run, so they can read what it found.
    boolean[] focused = {false};
    El tree = col().role("tree").scrollbars(t.scrollbars()).revealRow(rows.selected);
    tree.rows(rows.count, height, i -> {
      N node = rows.node(i);
      El r = row()
          .key("row-" + i)
          .radius(t.radius().medium())
          .padding(0, t.space().s(), 0, t.space().s() + rows.depth(i) * indent)
          .gap(t.space().s())
          .align(Style.Align.CENTER);
      if (node == null) { // the children of the row's parent are not known yet
        N parent = rows.parent(i);
        Failure failure = model.failure(parent);
        if (failure != null) {
          return failed(t, r.child(el().w(indent).shrink(0)), failure, rows.depth(i) + 1, height);
        }
        String text = model.loading(parent);
        El loading = Label.label(t, Label.Variant.MUTED, text).ellipsis().minSize(0, 0);
        return whole(t, r.children(el().w(indent).shrink(0), loading), loading, null, text, height);
      }
      boolean leaf = model.isLeaf(node);
      boolean open = !leaf && isOpen.test(node);
      boolean isSelected = i == rows.selected;
      Runnable select = () -> {
        if (onSelect != null && !isSelected) {
          onSelect.accept(node);
        }
      };
      El mark = el().size(indent, height).shrink(0).center(); // the whole height takes presses
      if (!leaf) {
        mark.key("mark-" + i).child(Icon.icon(t, open ? OPEN : CLOSED, Icon.Variant.MUTED));
        if (onToggle != null) {
          mark.onPress(() -> {
            select.run();
            onToggle.toggle(node, !open);
          });
        }
      }
      String label = model.label(node);
      r.role("treeitem")
          .label(label)
          .child(mark)
          .selected(isSelected)
          .level(rows.depth(i) + 1)
          .position(rows.positions[i], rows.sizes[i]);
      if (!leaf) {
        r.expanded(open);
      }
      El icon = model.icon(node);
      if (icon != null) {
        r.child(icon);
      }
      Label.Variant variant = model.muted(node) ? Label.Variant.MUTED : Label.Variant.NORMAL;
      String detail = model.detail(node);
      if (detail == null) {
        El name = Label.label(t, variant, label).ellipsis().minSize(0, 0);
        whole(t, r.child(name), name, null, label, height);
      } else {
        detail(t, r.value(detail), variant, label, detail, height);
      }
      if (isSelected) {
        r.fill(focused[0] ? t.grid().selected() : t.grid().selectedInactive());
      } else if (presses) {
        r.whenHovered(e -> e.fill(t.surface().hover()));
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
              if (!leaf && onToggle != null) {
                onToggle.toggle(node, !open);
              }
              if (onActivate != null) {
                onActivate.accept(node);
              }
            }
          });
    });
    if (rows.selected >= 0) {
      // When nodes open or close, the number of rows changes: the tree shows the selected node's
      // children below it as far as the view has room, as one just opened by Right or the mark.
      int end = rows.selected + 1;
      while (end < rows.count && rows.depth(end) > rows.depth(rows.selected)) {
        end++;
      }
      tree.reveal(rows.count, 0, rows.selected * height, 0, (end - rows.selected) * height);
    }
    if (onSelect == null) {
      return tree;
    }
    return tree.focusable()
        .focusRing(t.accent().edge())
        .whenFocused(e -> focused[0] = true)
        .onKey(k -> key(k, model, isOpen, rows, tree, height, onSelect, onToggle, onActivate));
  }

  /**
   * The label and the detail after it in a box that wraps and clips at the row's height, as the
   * data grid's header gives way with its type: the label keeps its width up to the box's, the
   * detail shrinks to an ellipsis after its first character and below that wraps to a second line,
   * which the box clips away. A zero-width strut as high as the row makes the first line that high,
   * so that both texts sit in its middle. The tooltip names both where either is cut or gone.
   */
  private static void detail(
      Theme t, El row, Label.Variant variant, String label, String detail, float height) {
    El name = Label.label(t, variant, label).ellipsis().minSize(0, 0).shrink(0);
    name.style().maxWidth = Len.percent(1);
    El more = Label.label(t, Label.Variant.MUTED, detail)
        .ellipsis()
        .minSize(0, 0)
        .grow(1)
        .basis(TextSizing.natural(Label.label(t, Label.Variant.MUTED, TextSizing.least(detail))))
        .margin(0, 0, 0, t.space().s());
    El box = row()
        .wrap()
        .grow(1)
        .minSize(0, 0)
        .h(height)
        .align(Style.Align.CENTER)
        .clip()
        .children(el().size(0, height), name, more);
    box.style().alignContent = Style.Content.START;
    whole(t, row.child(box), name, more, label + " " + detail, height);
  }

  /**
   * Gives a row a tooltip with its whole text, shown after the theme's delay where its name ends
   * in an ellipsis or its detail is cut or wrapped away, which only layout knows; the looks of
   * rows run after it. One look, no element, for every kind of row.
   *
   * @param more the detail after the name, or null for none
   * @return the row
   */
  private static El whole(Theme t, El row, El name, El more, String text, float height) {
    return Tooltip.whenHovered(
        t,
        row,
        () -> name.text().shortened()
                || more != null && (more.text().shortened() || more.y() >= height)
            ? text
            : null);
  }

  /**
   * The row of a failure: an icon in the danger colour, the message, which ends in an ellipsis
   * where the row is narrow, and the action at the end. It is a tree item that is {@code invalid}
   * with the message as its label and error, at the level of the children it stands for and the
   * only one there. The action is a quiet button in the link colour, as high as the row less a
   * gap and clear of the scrollbar, which keeps its width and is a stop for Tab of its own after
   * the tree, since the row is
   * not a node the selection can be on; Enter and Space run it, as a press does, and a press
   * leaves the focus where it is.
   */
  private static El failed(Theme t, El row, Failure failure, int level, float height) {
    El text = Label.label(t, failure.text()).ellipsis().minSize(0, 0).grow(1);
    row.role("treeitem")
        .label(failure.text())
        .invalid(true)
        .errorMessage(failure.text())
        .level(level)
        .position(1, 1)
        .children(Icon.icon(t, Symbol.ERROR_CIRCLE, Icon.Variant.DANGER), text);
    whole(t, row, text, null, failure.text(), height);
    Runnable run = failure.onAction();
    if (failure.action() == null || run == null) {
      return row;
    }
    // Clear of the scrollbar, which lies over the end of the rows: the only row with a part there.
    Scrollbars bars = t.scrollbars();
    float clear = Math.max(0, bars.thickness() + 2 * bars.margin() - t.space().s());
    // No motion: it would fade in as the row turns from loading to failed, untouchable meanwhile.
    return row.child(row()
        .role("button")
        .label(failure.action())
        .h(height - t.space().xs())
        .shrink(0)
        .margin(0, clear, 0, 0)
        .padding(0, t.space().s())
        .align(Style.Align.CENTER)
        .radius(t.radius().small())
        .focusable()
        .keepFocus()
        .focusRing(t.accent().edge())
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
        .whenHovered(e -> e.fill(t.surface().hover())) // as a link: no darker look while pressed
        .child(Label.label(t, failure.action()).color(t.prose().link())));
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
   * nodes. The row that stands for children not known yet holds a {@link Loading}.
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
          List<N> below = model.children(node);
          if (below == null) {
            put(new Loading(node), depth + 1, 0, 0); // the list gives its row a position
          } else {
            add(model, isOpen, below, depth + 1, selected);
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

    /** The node of the row, or null for a loading row. */
    @SuppressWarnings("unchecked")
    N node(int row) {
      return nodes[row] instanceof Loading ? null : (N) nodes[row];
    }

    /** The node whose children a loading row stands for. */
    @SuppressWarnings("unchecked")
    N parent(int row) {
      return (N) ((Loading) nodes[row]).parent();
    }

    int depth(int row) {
      return depths[row];
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
     * loading row; a loading row follows its parent, so going back always finds one.
     */
    int nodeFrom(int row, int step) {
      int i = Math.clamp(row, 0, count - 1);
      while (nodes[i] instanceof Loading) {
        i = i + step < count ? i + step : i - 1;
      }
      return i;
    }
  }

  /** In the rows, in place of the children of {@code parent}, which are not known yet. */
  private record Loading(Object parent) {}
}
