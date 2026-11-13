package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
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
 *         s.catalog.loadIfUnknown(node); // children() is null until the data is there
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
 * opens or closes it. While the tree has the focus, Up and Down move the selection, Right opens a
 * closed node or goes to its first child, Left closes an open node or goes to its parent, Home,
 * End, Page Up and Page Down move as in a list, and Enter activates. The rows follow the
 * selection ({@link El#revealRow}): whenever a rebuild puts the selected node in another row, as
 * when the keys move it or nodes above it open or close, the tree scrolls just far enough to show
 * it. The tree is one stop for Tab. Like a list of rows, it needs a height of its own or room to
 * {@link El#grow}.
 *
 * <p>In a tree dump the tree is {@code role=tree}, and its rows are {@code role=treeitem} with the
 * node's label and the key {@code row-<index>}, counted from 0 in the order shown. A node that can
 * have children has a mark with the key {@code mark-<index>}, which shows {@value #CLOSED} while it
 * is closed and {@value #OPEN} while it is open. The row that stands for children not known yet
 * has the key {@code row-<index>} too, but no role, and shows the model's {@link Model#loading}
 * text.
 */
public final class Tree {

  /** The mark of a node that is closed. */
  public static final String CLOSED = "▶";

  /** The mark of a node that is open. */
  public static final String OPEN = "▼";

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

    /** The text of the row that stands for the children of an open node while they are not known. */
    default String loading(N node) {
      return "Loading…";
    }
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
      Theme t, Model<N> model, Predicate<? super N> isOpen, N selected,
      Consumer<? super N> onSelect, Toggle<? super N> onToggle, Consumer<? super N> onActivate) {
    Rows<N> rows = new Rows<>(model, isOpen, selected);
    float height = t.grid().rowHeight();
    float indent = t.space().xl();
    boolean presses = onSelect != null || onToggle != null || onActivate != null;
    // The rows are built after the tree's focus look has run, so they can read what it found.
    boolean[] focused = {false};
    El tree = col().role("tree").revealRow(rows.selected).rows(rows.count, height, i -> {
      N node = rows.node(i);
      El r = row().key("row-" + i)
          .padding(0, t.space().s(), 0, t.space().s() + rows.depth(i) * indent)
          .gap(t.space().s())
          .align(Style.Align.CENTER);
      if (node == null) { // the children of the row's parent are not known yet
        return r.children(el().w(indent).shrink(0),
            Label.label(t, Label.Variant.MUTED, model.loading(rows.parent(i))));
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
        Theme.TextStyle style = t.type().mono();
        mark.key("mark-" + i).child(El.text(open ? OPEN : CLOSED)
            .font(style.font().get()).fontSize(style.size()).color(t.text().muted()));
        if (onToggle != null) {
          mark.onPress(() -> {
            select.run();
            onToggle.toggle(node, !open);
          });
        }
      }
      String label = model.label(node);
      r.role("treeitem").label(label).child(mark);
      El icon = model.icon(node);
      if (icon != null) {
        r.child(icon);
      }
      r.child(Label.label(t, label));
      if (isSelected) {
        r.fill(focused[0] ? t.grid().selected() : t.grid().selectedInactive());
      } else if (presses) {
        r.whenHovered(e -> e.fill(t.surface().hover()));
      }
      return !presses ? r : r.onPress(clicks -> {
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
    if (onSelect == null) {
      return tree;
    }
    // The row the keys last selected: a second key in the same frame goes on from there.
    int[] cursor = {rows.selected};
    return tree.focusable()
        .whenFocused(e -> focused[0] = true)
        .onKey(k -> key(k, model, isOpen, rows, cursor, tree, height, onSelect, onToggle,
            onActivate));
  }

  /** What a key does to the tree with the focus; whether the tree took it. */
  private static <N> boolean key(
      Key k, Model<N> model, Predicate<? super N> isOpen, Rows<N> rows, int[] cursor, El tree,
      float height, Consumer<? super N> onSelect, Toggle<? super N> onToggle,
      Consumer<? super N> onActivate) {
    if (k.modifiers() != 0 || rows.count == 0) {
      return false;
    }
    int from = cursor[0];
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
        boolean child = open && from + 1 < rows.count && rows.depth(from + 1) > rows.depth(from)
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
      cursor[0] = next;
      onSelect.accept(rows.node(next));
    }
    return true;
  }

  /**
   * The rows of the tree as one rebuild of the view sees them: the nodes shown, in order, with
   * their depths, from the roots down through the open nodes. The row that stands for children
   * not known yet holds a {@link Loading}.
   */
  private static final class Rows<N> {
    private Object[] nodes = new Object[64];
    private int[] depths = new int[64];
    int count;
    int selected = -1;

    Rows(Model<N> model, Predicate<? super N> isOpen, N selected) {
      add(model, isOpen, model.roots(), 0, selected);
    }

    private void add(
        Model<N> model, Predicate<? super N> isOpen, List<N> children, int depth, N selected) {
      for (N node : children) {
        if (this.selected < 0 && selected != null && selected.equals(node)) {
          this.selected = count;
        }
        put(node, depth);
        if (!model.isLeaf(node) && isOpen.test(node)) {
          List<N> below = model.children(node);
          if (below == null) {
            put(new Loading(node), depth + 1);
          } else {
            add(model, isOpen, below, depth + 1, selected);
          }
        }
      }
    }

    private void put(Object node, int depth) {
      if (count == nodes.length) {
        nodes = Arrays.copyOf(nodes, count * 2);
        depths = Arrays.copyOf(depths, count * 2);
      }
      nodes[count] = node;
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
