package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.DataGrid.dataGrid;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.DataGrid.CellIcon;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.DataType;
import com.rethinkj.ui.kit.DataGrid.Mode;
import com.rethinkj.ui.kit.DataGrid.Selection;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tree rows in the first column of a {@link DataGrid} ({@link DataGrid.Options#tree}). */
class GridTreeTest {

  private static final CellIcon DB = CellIcon.before(Symbol.DATABASE, Icon.Variant.MUTED);
  private static final CellIcon FOLDER = CellIcon.before(Symbol.FOLDER, Icon.Variant.MUTED);
  private static final CellIcon TABLE = CellIcon.before(Symbol.TABLE, Icon.Variant.MUTED);

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  record Node(long id, String name, CellIcon icon, long rows, String size, List<Node> children) {
    Node(long id, String name, long rows, String size) {
      this(id, name, TABLE, rows, size, null);
    }

    boolean isLeaf() {
      return children == null;
    }
  }

  private static final class State implements Tree.Model<Node> {
    final Node orders = new Node(11, "orders", 1_000_000, "248 MB");
    final Node customers = new Node(12, "customers", 50_000, "18 MB");
    final Node invoices = new Node(13, "invoices", 320_000, "64 MB");
    final Node publicSchema =
        new Node(10, "public", FOLDER, 1_370_000, "330 MB", List.of(orders, customers, invoices));
    final Node events = new Node(21, "events", 4_200_000, "1.1 GB");
    final Node auditSchema = new Node(20, "audit", FOLDER, 4_200_000, "1.1 GB", List.of(events));
    final Node stagingSchema = new Node(30, "staging", FOLDER, 0, "—", List.of());
    final Node archiveSchema = new Node(40, "archive", FOLDER, 0, "—", List.of());
    final Node shop = new Node(
        1,
        "shop",
        DB,
        5_570_000,
        "1.4 GB",
        List.of(publicSchema, auditSchema, stagingSchema, archiveSchema));

    final Set<Node> open = new HashSet<>(List.of(shop, publicSchema, stagingSchema));
    Selection selection;
    final float[] widths = {220, 100, 90};
    boolean stagingLoading = true;
    boolean archiveFailed;
    final AtomicInteger retries = new AtomicInteger();
    List<DataGrid.Range> deleted = List.of();
    final AtomicInteger pendingCalls = new AtomicInteger();
    App<State> app;

    State(Mode mode) {
      this.selection = new Selection(mode);
    }

    @Override
    public List<Node> roots() {
      return List.of(shop);
    }

    @Override
    public Tree.Children<Node> children(Node node) {
      if (node == stagingSchema && stagingLoading) {
        return Tree.loading();
      }
      if (node == archiveSchema && archiveFailed) {
        return Tree.failed("Permission denied", "Try again", () -> {
          retries.incrementAndGet();
          archiveFailed = false;
        });
      }
      return Tree.ready(node.children());
    }

    @Override
    public boolean isLeaf(Node node) {
      return node.isLeaf();
    }

    @Override
    public String label(Node node) {
      return node.name();
    }

    void toggle(Node node, boolean shouldOpen) {
      if (shouldOpen) {
        open.add(node);
      } else {
        open.remove(node);
      }
    }

    El view() {
      Tree.Rows<Node> rows = Tree.rows(this, open::contains);
      List<Column> columns = List.of(
          new Column("name", widths[0], DataType.TEXT, row -> read(rows, row).name())
              .cellIcon(row -> read(rows, row).icon())
              .detail(row -> {
                Node node = read(rows, row);
                return node == shop ? "database" : node.isLeaf() ? null : "schema";
              }),
          new Column(
              "rows",
              widths[1],
              DataType.INTEGER,
              row -> String.format(java.util.Locale.ROOT, "%,d", read(rows, row).rows())),
          new Column("size", widths[2], DataType.TEXT, row -> read(rows, row).size())
              .cellTooltip(row -> "Size of " + read(rows, row).name()));
      DataGrid.Options options = DataGrid.options()
          .tree(rows, this::toggle)
          .select(selection)
          .rowKey(row -> read(rows, row).id())
          .onResize((column, width) -> widths[column] = width)
          .onDeleteRows(ranges -> deleted = ranges);
      if (app != null) {
        options.copy(app.clipboard());
      }
      return col()
          .wFull()
          .hFull()
          .child(dataGrid(columns, rows.count(), options)
              .key("schema")
              .label("Schema")
              .grow(1));
    }

    private Node read(Tree.Rows<Node> rows, int row) {
      Node node = rows.node(row);
      if (node == null) {
        pendingCalls.incrementAndGet();
        throw new AssertionError("column function called for pending row " + row);
      }
      return node;
    }

    App<State> app() {
      app = new App<>(this, State::view).provide(Theme.CURRENT, s -> Theme.DARK);
      app.textRendering(TextRendering.LINUX);
      return app;
    }
  }

  @Test
  void rejectsRowCountMismatch() {
    State state = new State(Mode.ROWS);
    Tree.Rows<Node> rows = Tree.rows(state, state.open::contains);
    List<Column> columns = List.of(new Column("name", 120, DataType.TEXT, r -> ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> dataGrid(columns, rows.count() + 1, DataGrid.options().tree(rows, state::toggle)));
  }

  @Test
  void togglesByMarkAndKeyboardAndSelectionFollowsWithRowKey() throws IOException {
    State state = new State(Mode.ROWS);
    App<State> app = state.app();
    // Initial rows: 0:shop(open), 1:public(open), 2:orders, 3:customers, 4:invoices,
    // 5:audit(closed), 6:staging(open), 7:Loading…, 8:archive(closed)
    Script.run("size 460 260 scale 2\nframe", app, out);
    String dump = app.tree().dump();
    assertTrue(dump.contains("#row-0 role=row ") && dump.contains("expanded level=1 pos=1/1"));
    assertTrue(dump.contains("#row-1 role=row ") && dump.contains("expanded level=2 pos=1/4"));
    assertTrue(dump.contains("#row-2 role=row ") && dump.contains("level=3 pos=1/3"));
    assertTrue(
        dump.contains("#row-5 role=row ") && dump.contains("expanded=false level=2 pos=2/4"));
    assertTrue(dump.contains("#mark-0") && dump.contains("#mark-5"));
    assertFalse(dump.contains("#mark-2"), "leaves have no mark");

    // Press #mark-5 opens audit and moves the cursor to row 5.
    Script.run("size 460 260 scale 2\npress #mark-5\nframe", app, out);
    assertTrue(state.open.contains(state.auditSchema));
    assertEquals(5, state.selection.row());

    // Keyboard in Mode.ROWS: Right on open audit moves to its first child (events, row 6),
    // Left on leaf moves back to audit (row 5), Left again closes audit.
    Script.run("size 460 260 scale 2\nkey ArrowRight\nframe", app, out);
    assertEquals(6, state.selection.row());
    Script.run("size 460 260 scale 2\nkey ArrowLeft\nframe", app, out);
    assertEquals(5, state.selection.row());
    Script.run("size 460 260 scale 2\nkey ArrowLeft\nframe", app, out);
    assertFalse(state.open.contains(state.auditSchema));
    assertEquals(5, state.selection.row());

    // Right on closed audit opens it again.
    Script.run("size 460 260 scale 2\nkey ArrowRight\nframe", app, out);
    assertTrue(state.open.contains(state.auditSchema));
    assertEquals(5, state.selection.row());

    // Select archive (now row 9), then close public above it: rowKey keeps selection on archive!
    Script.run("size 460 260 scale 2\npress #cell-9-0\nframe", app, out);
    assertEquals(9, state.selection.row());
    state.open.remove(state.publicSchema); // removes 3 rows above archive
    app.invalidate();
    Script.run("size 460 260 scale 2\nframe", app, out);
    assertEquals(6, state.selection.row(), "selection follows archive from row 9 to row 6");
    assertEquals(state.archiveSchema, Tree.rows(state, state.open::contains).node(6));
    assertEquals(0, state.pendingCalls.get());
  }

  @Test
  void cellModeUsesTreeKeysOnlyInFirstColumnAndPendingRowsSkipColumnFunctions() throws IOException {
    State state = new State(Mode.CELLS);
    state.open.add(state.archiveSchema);
    state.archiveFailed = true;
    App<State> app = state.app();
    // 0:shop, 1:public, 2:orders, 3:customers, 4:invoices, 5:audit, 6:staging, 7:Loading…,
    // 8:archive, 9:Permission denied (Try again)
    Script.run("size 460 280 scale 2\nframe", app, out);
    assertEquals(0, state.pendingCalls.get());
    assertNull(Tree.rows(state, state.open::contains).node(7));
    assertNull(Tree.rows(state, state.open::contains).node(9));

    // In Mode.CELLS on a leaf in column 0, Right moves to column 1; Left returns to column 0;
    // Left again moves to parent row (public, row 1).
    Script.run("size 460 280 scale 2\npress #cell-2-0\nframe\nkey ArrowRight\nframe", app, out);
    assertEquals(2, state.selection.row());
    assertEquals(1, state.selection.column());
    Script.run("size 460 280 scale 2\nkey ArrowLeft\nframe", app, out);
    assertEquals(2, state.selection.row());
    assertEquals(0, state.selection.column());
    Script.run("size 460 280 scale 2\nkey ArrowLeft\nframe", app, out);
    assertEquals(1, state.selection.row());
    assertEquals(0, state.selection.column());

    // On open public in column 1, Left moves to column 0 without closing it.
    Script.run("size 460 280 scale 2\npress #cell-1-1\nframe\nkey ArrowLeft\nframe", app, out);
    assertTrue(state.open.contains(state.publicSchema));
    assertEquals(1, state.selection.row());
    assertEquals(0, state.selection.column());

    // Copy across a range including the loading row (rows 6..8): loading row copies empty fields.
    Script.run(
        "size 460 280 scale 2\npress #cell-6-0\npress #cell-8-2 shift\nkey C control\nframe",
        app,
        out);
    assertEquals("staging\t0\t—\n\t\t\narchive\t0\t—", app.clipboard().read());
    // Deleting them reports the nodes' rows around the loading row.
    Script.run("size 460 280 scale 2\nkey Delete control\nframe", app, out);
    assertEquals(
        List.of(
            new DataGrid.Range(6, 0, 6, DataGrid.Range.ALL),
            new DataGrid.Range(8, 0, 8, DataGrid.Range.ALL)),
        state.deleted);

    // Hovering a pending row's cells and fitting column 0 never calls column functions.
    Script.run("""
        size 460 280 scale 2
        move #cell-7-2
        frame
        advance 0.6
        frame
        press #resize-0 clicks 2
        frame""", app, out);
    assertEquals(0, state.pendingCalls.get());

    // Pressing the failed row's action button retries.
    Script.run("size 460 280 scale 2\npress role=button label=\"Try again\"\nframe", app, out);
    assertEquals(1, state.retries.get());
    assertFalse(state.archiveFailed);
  }

  @Test
  void looksAsStored() throws IOException {
    State state = new State(Mode.ROWS);
    App<State> app = state.app();
    List<Path> files = Script.run("""
        size 460 260 scale 2
        frame
        press #cell-2-0
        frame
        snap open""", app, out);
    Golden.assertSnaps("grid-tree-dark-2x-", files, "open");
    assertEquals(2, files.size());
  }
}
