package com.rethinkj.ui.diagram.er;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.diagram.Canvas;
import com.rethinkj.ui.diagram.DiagramTexts;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@link ErDiagram}'s labels and roles in a tree dump, and its keys, pins, modes and filter.
 * Its images, in both themes, are those of the docs example in the examples module.
 */
class ErDiagramTest {

  @TempDir
  Path out;

  @BeforeEach
  void needsText() {
    try {
      Font.sans();
    } catch (UnsatisfiedLinkError e) {
      assumeTrue(false, "Skipped: FreeType and HarfBuzz cannot be loaded. " + e.getMessage());
    }
  }

  static final List<Schema> SCHEMAS = List.of(new Schema(
      "shop",
      List.of(
          new Table(
              "customer",
              List.of(id(), new Column("email", "text", false, false, true, null)),
              List.of()),
          new Table("address", List.of(id(), new Column("city", "text", true)), List.of()),
          new Table(
              "order",
              List.of(
                  id(),
                  new Column("customer_id", "bigint", false),
                  new Column("billing_id", "bigint", true, false, false, "0"),
                  new Column("note", "text", true)),
              List.of(
                  new ForeignKey("order_customer_fk", "customer_id", "customer", "id"),
                  new ForeignKey(
                      "order_billing_fk",
                      List.of("billing_id"),
                      null,
                      "address",
                      List.of("id"),
                      "SET NULL"))),
          new Table(
              "category",
              List.of(id(), new Column("parent_id", "bigint", true)),
              List.of(new ForeignKey("category_parent_fk", "parent_id", "category", "id"))))));

  private static Column id() {
    return new Column("id", "bigint", false, true, false, null);
  }

  static final class Board {
    final ErDiagram.State diagram = new ErDiagram.State();
    List<Schema> schemas = SCHEMAS;
    Map<String, ErDiagram.Pin> pins = Map.of();
    int pinChanges;

    void onPins(Map<String, ErDiagram.Pin> pins) {
      this.pins = pins;
      pinChanges++;
    }
  }

  private App<Board> app() {
    App<Board> app = new App<>(
        new Board(),
        board -> ErDiagram.erDiagram(board.diagram, board.schemas, board::onPins)
            .wFull()
            .hFull());
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  @Test
  void labelsTablesColumnsFramesAndEdges() throws IOException {
    String script = """
        size 900 500
        frame
        press #shop.order at top+8
        frame
        snap selected
        """;
    Script.run(script, app(), out);
    String tree = Files.readString(out.resolve("selected.tree.txt"));
    assertTrue(tree.contains("role=graphics-document label=\"Entity relationship diagram\""), tree);
    assertTrue(
        tree.contains("#shop/ role=group label=\"shop, 4 tables\""), "the schema's frame: " + tree);
    assertTrue(
        tree.contains("#shop.order role=graphics-object"
            + " label=\"order, 4 columns, references customer, address\""),
        tree);
    assertTrue(
        tree.contains("#shop.customer role=graphics-object"
            + " label=\"customer, 2 columns, referenced by order\""),
        tree);
    assertTrue(tree.contains("role=list "), tree);
    assertTrue(tree.contains("role=listitem label=\"id, bigint, primary key\""), tree);
    assertTrue(
        tree.contains("role=listitem label=\"billing_id, bigint, foreign key, nullable\""), tree);
    assertTrue(tree.contains("role=listitem label=\"email, text, unique\""), tree);
    assertTrue(
        tree.contains(
            "#shop.order/order_billing_fk role=graphics-symbol" + " label=\"order_billing_fk\""),
        tree);
    assertTrue(
        line(tree, "#shop.order/order_customer_fk").contains("value=\"Highlighted\""),
        "an edge of the selected table: " + tree);
    assertTrue(
        line(tree, "#shop.category/category_parent_fk").contains("value=\"Dimmed\""),
        "an edge of another table: " + tree);
    assertTrue(tree.contains(" selected"), "the selected table: " + tree);
  }

  @Test
  void keysMoveTheSelectionNudgeCollapseAndFollowRelations() throws IOException {
    App<Board> app = app();
    Board board = app.state();
    Script.run("""
        size 900 500
        frame
        press #shop.order at top+8
        frame
        type "]"
        frame
        type "]"
        frame
        type "["
        frame
        """, app, out);
    // From order: customer, address, then back to customer.
    assertEquals("shop.customer", board.diagram.selected());

    Script.run("""
        key ArrowRight shift
        frame
        key Enter
        frame
        """, app, out);
    ErDiagram.Pin pin = board.pins.get("shop.customer");
    assertEquals(1, board.pinChanges);
    assertEquals(Map.of("shop.customer", pin), board.pins, "only the nudged table is pinned");
    assertEquals(board.diagram.pins(), board.pins);
    // All columns, then keys only, as customer has a column that is no key.
    assertEquals(ErDiagram.Mode.KEYS, board.diagram.mode("shop.customer"));

    Script.run("key Escape\nframe\n", app, out);
    assertNull(board.diagram.selected());
    Script.run("key ArrowDown\nframe\n", app, out);
    assertNotNull(board.diagram.selected(), "an arrow selects a table where none is");

    board.diagram.arrangeAll();
    assertTrue(board.diagram.pins().isEmpty(), "Arrange all unpins");
  }

  @Test
  void dragTellsOnPinsOnceAtTheEndAndNeverOnEscape() throws IOException {
    App<Board> app = app();
    Board board = app.state();
    Script.run("""
        size 900 500
        frame
        down #shop.order at top+8
        move 100,100
        frame
        move 160,140
        frame
        """, app, out);
    assertEquals(0, board.pinChanges, "pointer moves do not tell onPins before the drag ends");
    assertEquals("shop.order", board.diagram.viewport().dragging());
    ErDiagram.Pin mid = board.diagram.pins().get("shop.order");
    assertNotNull(mid, "the table follows the pointer live");

    Script.run("""
        move 220,180
        up 220,180
        frame
        """, app, out);
    assertEquals(1, board.pinChanges, "ending the drag tells onPins once");
    assertNull(board.diagram.viewport().dragging());
    ErDiagram.Pin dropped = board.pins.get("shop.order");
    assertEquals(Map.of("shop.order", dropped), board.pins);
    assertEquals(board.diagram.pins(), board.pins);
    float zoom = board.diagram.viewport().zoom();
    assertEquals(mid.x() + 60 / zoom, dropped.x(), 1e-6);
    assertEquals(mid.y() + 40 / zoom, dropped.y(), 1e-6);

    Script.run("""
        down #shop.order at top+8
        move 300,220
        frame
        move 360,260
        frame
        key ArrowRight shift
        frame
        key Escape
        frame
        up 360,260
        frame
        """, app, out);
    assertEquals(1, board.pinChanges, "cancelling a drag with Escape tells onPins nothing");
    assertNull(board.diagram.viewport().dragging());
    assertEquals(dropped, board.diagram.pins().get("shop.order"), "the table is back where it was");
    assertEquals(Map.of("shop.order", dropped), board.pins);
  }

  @Test
  void showsKeysOnlyAndFiltersByName() throws IOException {
    App<Board> app = app();
    Board board = app.state();
    board.diagram.mode(ErDiagram.Mode.KEYS);
    board.diagram.filter("ORD");
    Script.run("size 900 500\nframe\nsnap keys\n", app, out);
    String tree = Files.readString(out.resolve("keys.tree.txt"));
    assertTrue(tree.contains("#shop.order "), tree);
    assertFalse(tree.contains("#shop.customer "), "filtered out: " + tree);
    assertFalse(tree.contains("order_customer_fk"), "no edge to a table filtered out: " + tree);
    assertFalse(tree.contains("label=\"note,"), "not a key: " + tree);
    assertTrue(tree.contains("role=button") && tree.contains("\"+1 column\""), tree);
  }

  @Test
  void filterPansOnlyWhenNarrowingToATableOutsideTheView() throws IOException {
    App<Board> app = app();
    Board board = app.state();
    Canvas.Viewport viewport = board.diagram.viewport();
    Script.run("size 900 500\nframe\n", app, out);
    double fittedLeft = viewport.left();
    double fittedTop = viewport.top();

    // Narrowing to a table already in view keeps the viewport where it is.
    board.diagram.filter("ord");
    app.invalidate();
    Script.run("frame\n", app, out);
    assertEquals(fittedLeft, viewport.left());
    assertEquals(fittedTop, viewport.top());

    // Pan away so the match lies outside, then check whitespace, widening and clearing.
    double awayLeft = fittedLeft + 4000;
    double awayTop = fittedTop + 4000;
    viewport.panTo(awayLeft, awayTop);
    app.invalidate();
    Script.run("frame\n", app, out);

    board.diagram.filter("ord ");
    app.invalidate();
    Script.run("frame\n", app, out);
    assertEquals(awayLeft, viewport.left(), "adding a space does not pan");
    assertEquals(awayTop, viewport.top(), "adding a space does not pan");

    board.diagram.filter("orde");
    app.invalidate();
    Script.run("frame\n", app, out);
    assertEquals(awayLeft, viewport.left(), "the same matches do not pan");
    assertEquals(awayTop, viewport.top(), "the same matches do not pan");

    board.diagram.filter("or");
    app.invalidate();
    Script.run("frame\n", app, out);
    assertEquals(awayLeft, viewport.left(), "widening the filter does not pan");
    assertEquals(awayTop, viewport.top(), "widening the filter does not pan");

    board.diagram.filter("");
    app.invalidate();
    Script.run("frame\n", app, out);
    assertEquals(awayLeft, viewport.left(), "clearing the filter does not pan");
    assertEquals(awayTop, viewport.top(), "clearing the filter does not pan");

    // Narrowing to a table outside the view pans to show it.
    board.diagram.filter("ord");
    app.invalidate();
    Script.run("frame\nsnap revealed\n", app, out);
    assertTrue(
        viewport.left() != awayLeft || viewport.top() != awayTop,
        "narrowing to a match outside the view pans");
    assertTrue(Files.readString(out.resolve("revealed.tree.txt")).contains("#shop.order "));
  }

  @Test
  void filterWithoutMatchShowsANoteAndLeavesNoPendingReveal() throws IOException {
    App<Board> app = app();
    Board board = app.state();
    Canvas.Viewport viewport = board.diagram.viewport();
    Script.run("size 900 500\nframe\n", app, out);
    double awayLeft = viewport.left() + 4000;
    double awayTop = viewport.top() + 4000;
    viewport.panTo(awayLeft, awayTop);
    app.invalidate();
    Script.run("frame\n", app, out);

    board.diagram.filter("  missing  ");
    app.invalidate();
    Script.run("frame\nsnap empty\n", app, out);
    assertEquals(awayLeft, viewport.left());
    assertEquals(awayTop, viewport.top());
    String emptyTree = Files.readString(out.resolve("empty.tree.txt"));
    String expected = DiagramTexts.ENGLISH.noTablesMatch("missing");
    assertTrue(
        emptyTree.contains("#note \"" + expected + "\""),
        "muted empty-state note on canvas: " + emptyTree);

    // Reloading schemas with a matching table must not trigger a stale reveal.
    board.schemas =
        List.of(new Schema("shop", List.of(new Table("missing_item", List.of(id()), List.of()))));
    app.invalidate();
    Script.run("frame\nsnap reloaded\n", app, out);
    assertEquals(awayLeft, viewport.left(), "no pending reveal fires after schema reload");
    assertEquals(awayTop, viewport.top(), "no pending reveal fires after schema reload");
    assertFalse(Files.readString(out.resolve("reloaded.tree.txt")).contains("#note "));

    // Widening back from an unmatched filter also does not pan, and removes the note.
    board.schemas = SCHEMAS;
    board.diagram.filter("nope");
    app.invalidate();
    Script.run("frame\n", app, out);
    board.diagram.filter("");
    app.invalidate();
    Script.run("frame\nsnap cleared\n", app, out);
    assertEquals(awayLeft, viewport.left(), "no pending reveal fires when widened back");
    assertEquals(awayTop, viewport.top(), "no pending reveal fires when widened back");
    assertFalse(Files.readString(out.resolve("cleared.tree.txt")).contains("#note "));

    // An empty schema list with no filter shows no note.
    board.schemas = List.of();
    app.invalidate();
    Script.run("frame\nsnap noSchemas\n", app, out);
    assertFalse(Files.readString(out.resolve("noSchemas.tree.txt")).contains("#note "));
  }

  @Test
  void aTableThatGrowsMovesTheOthersAside() throws IOException {
    App<Board> app = app();
    Board board = app.state();
    board.diagram.mode(ErDiagram.Mode.COLLAPSED);
    // Both lie in the first layer, one above the other, and grow by more than the gap between them.
    Script.run("""
        size 900 500
        frame
        press #shop.customer
        key Enter
        frame
        press #shop.address
        key Enter
        frame
        snap grown
        """, app, out);
    assertEquals(ErDiagram.Mode.ALL, board.diagram.mode("shop.address"));
    List<double[]> boxes = Pattern.compile(" role=graphics-object .* (\\d+),(\\d+) (\\d+)x(\\d+)")
        .matcher(Files.readString(out.resolve("grown.tree.txt")))
        .results()
        .map(m -> new double[] {
          Double.parseDouble(m.group(1)),
          Double.parseDouble(m.group(2)),
          Double.parseDouble(m.group(3)),
          Double.parseDouble(m.group(4))
        })
        .toList();
    assertEquals(4, boxes.size());
    for (int i = 0; i < boxes.size(); i++) {
      for (int j = i + 1; j < boxes.size(); j++) {
        double[] a = boxes.get(i);
        double[] b = boxes.get(j);
        boolean apart = a[0] + a[2] <= b[0]
            || b[0] + b[2] <= a[0]
            || a[1] + a[3] <= b[1]
            || b[1] + b[3] <= a[1];
        assertTrue(apart, "tables " + i + " and " + j + " overlap");
      }
    }
  }

  @Test
  void aTableWhoseModeChangesComesIntoView() throws IOException {
    Script.run("""
        size 900 500
        frame
        press #shop.customer
        frame
        wheel role=graphics-document 4000 4000
        frame
        snap away
        key Enter
        frame
        snap back
        """, app(), out);
    assertFalse(Files.readString(out.resolve("away.tree.txt")).contains("#shop.customer "));
    assertTrue(Files.readString(out.resolve("back.tree.txt")).contains("#shop.customer "));
  }

  @Test
  void arrangeAndArrangeAllFitTheTablesInView() throws IOException {
    Script.run("""
        size 900 500
        frame
        down #shop.customer at top+8
        move 2500,2000
        up 2500,2000
        frame
        press role=button label="Arrange"
        frame
        snap arranged
        focus role=graphics-document
        key 0
        frame
        press role=button label="Arrange all"
        frame
        snap arranged-all
        """, app(), out);
    assertAllTablesInView(Files.readString(out.resolve("arranged.tree.txt")), 4);
    assertAllTablesInView(Files.readString(out.resolve("arranged-all.tree.txt")), 4);
  }

  private static void assertAllTablesInView(String tree, int expected) {
    var canvas = Pattern.compile(" role=graphics-document .* (-?\\d+),(-?\\d+) (\\d+)x(\\d+)")
        .matcher(tree);
    assertTrue(canvas.find(), "canvas in tree: " + tree);
    int left = Integer.parseInt(canvas.group(1));
    int top = Integer.parseInt(canvas.group(2));
    int right = left + Integer.parseInt(canvas.group(3));
    int bottom = top + Integer.parseInt(canvas.group(4));
    List<int[]> boxes = Pattern.compile(" role=graphics-object .* (-?\\d+),(-?\\d+) (\\d+)x(\\d+)")
        .matcher(tree)
        .results()
        .map(m -> new int[] {
          Integer.parseInt(m.group(1)),
          Integer.parseInt(m.group(2)),
          Integer.parseInt(m.group(3)),
          Integer.parseInt(m.group(4))
        })
        .toList();
    assertEquals(expected, boxes.size(), "all tables in view: " + tree);
    for (int[] box : boxes) {
      assertTrue(
          box[0] >= left && box[1] >= top && box[0] + box[2] <= right && box[1] + box[3] <= bottom,
          "table inside canvas: " + tree);
    }
  }

  @Test
  void keysAFrameApartFromATableOfItsName() throws IOException {
    List<Schema> schemas = List.of(
        new Schema("shop", List.of(new Table("orders", List.of(id()), List.of()))),
        new Schema("", List.of(new Table("shop/", List.of(id()), List.of()))));
    App<ErDiagram.State> app = new App<>(
        new ErDiagram.State(),
        state -> ErDiagram.erDiagram(state, schemas, null).wFull().hFull());
    app.textRendering(TextRendering.LINUX);
    Script.run("size 600 400\nframe\nsnap view\n", app, out);
    String tree = Files.readString(out.resolve("view.tree.txt"));
    assertTrue(tree.contains("#shop/ role=graphics-object"), tree);
    assertTrue(tree.contains("#shop// role=group label=\"shop, 1 table\""), tree);
  }

  @Test
  void quotesDottedNamesSoCollidingKeysStayDistinct() throws IOException {
    List<Schema> schemas = List.of(
        new Schema(
            "a",
            List.of(new Table(
                "b.c",
                List.of(
                    id(), new Column("c_id", "bigint", false), new Column("bc_col", "text", true)),
                List.of(
                    new ForeignKey("bc_to_c", List.of("c_id"), "a.b", "c", List.of("id"), null))))),
        new Schema(
            "a.b",
            List.of(new Table("c", List.of(id(), new Column("ab_col", "text", true)), List.of()))),
        new Schema(
            "",
            List.of(
                new Table("x.y", List.of(id()), List.of()),
                new Table("\"q\"", List.of(id()), List.of()))),
        new Schema("x", List.of(new Table("y", List.of(id()), List.of()))));
    ErDiagram.State state = new ErDiagram.State();
    String aBc = "a.\"b.c\"";
    String abC = "\"a.b\".c";
    state.select(aBc);
    state.mode(abC, ErDiagram.Mode.COLLAPSED);
    state.pins(Map.of(aBc, new ErDiagram.Pin(20, 40), "\"x.y\"", new ErDiagram.Pin(500, 40)));
    App<ErDiagram.State> app =
        new App<>(state, s -> ErDiagram.erDiagram(s, schemas, null).wFull().hFull());
    app.textRendering(TextRendering.LINUX);
    Script.run("size 900 500\nframe\nsnap dotted\n", app, out);
    String tree = Files.readString(out.resolve("dotted.tree.txt"));
    assertTrue(
        tree.contains("#" + aBc + " role=graphics-object label=\"b.c, 3 columns, references c\""),
        tree);
    assertTrue(
        tree.contains(
            "#" + abC + " role=graphics-object label=\"c, 2 columns, referenced by b.c\""),
        tree);
    assertTrue(tree.contains("#\"x.y\" role=graphics-object label=\"x.y, 1 column\""), tree);
    assertTrue(
        tree.contains("#\"\"\"q\"\"\" role=graphics-object label=\"\\\"q\\\", 1 column\""), tree);
    assertTrue(tree.contains("#x.y role=graphics-object label=\"y, 1 column\""), tree);
    assertTrue(
        line(tree, "#" + aBc + "/bc_to_c").contains("value=\"Highlighted\""),
        "edge to the selected table's target: " + tree);
    assertEquals(1, tree.lines().filter(l -> l.contains(" selected")).count(), tree);
    assertTrue(tree.contains("label=\"bc_col,"), "a.\"b.c\" shows all columns: " + tree);
    assertFalse(tree.contains("label=\"ab_col,"), "\"a.b\".c is collapsed: " + tree);
    assertEquals(aBc, state.selected());
    assertEquals(ErDiagram.Mode.ALL, state.mode(aBc));
    assertEquals(ErDiagram.Mode.COLLAPSED, state.mode(abC));
    assertEquals(
        Map.of(aBc, new ErDiagram.Pin(20, 40), "\"x.y\"", new ErDiagram.Pin(500, 40)),
        state.pins());
    // Both pinned tables sit at y=40 in diagram coordinates, so they share the same top y.
    int aBcY = boxY(line(tree, "#" + aBc));
    int xyY = boxY(line(tree, "#\"x.y\""));
    assertEquals(aBcY, xyY, "pinned tables share their pinned y: " + tree);
  }

  @Test
  void keysDuplicateAndUnnamedForeignKeysApart() throws IOException {
    List<Schema> schemas = List.of(new Schema(
        "shop",
        List.of(
            new Table("customer", List.of(id()), List.of()),
            new Table(
                "order",
                List.of(
                    id(),
                    new Column("customer_id", "bigint", false),
                    new Column("billing_id", "bigint", true),
                    new Column("shipping_id", "bigint", true),
                    new Column("other_id", "bigint", true)),
                List.of(
                    new ForeignKey("dup_fk", "customer_id", "customer", "id"),
                    new ForeignKey("dup_fk", "billing_id", "customer", "id"),
                    new ForeignKey("", "shipping_id", "customer", "id"),
                    new ForeignKey("2", "other_id", "customer", "id"),
                    new ForeignKey("dup_fk/1", "id", "customer", "id"))))));
    App<ErDiagram.State> app = new App<>(
        new ErDiagram.State(),
        state -> ErDiagram.erDiagram(state, schemas, null).wFull().hFull());
    app.textRendering(TextRendering.LINUX);
    Script.run("size 900 500\nframe\nsnap edges\n", app, out);
    String tree = Files.readString(out.resolve("edges.tree.txt"));
    assertEquals(5, tree.lines().filter(l -> l.contains("role=graphics-symbol")).count(), tree);
    assertTrue(tree.contains("#shop.order/dup_fk role=graphics-symbol label=\"dup_fk\""), tree);
    assertTrue(tree.contains("#shop.order/dup_fk/1/1 role=graphics-symbol label=\"dup_fk\""), tree);
    assertTrue(
        tree.contains("#shop.order/2/2 role=graphics-symbol"
            + " label=\"order(shipping_id) → customer(id)\""),
        tree);
    assertTrue(tree.contains("#shop.order/2 role=graphics-symbol label=\"2\""), tree);
    assertTrue(tree.contains("#shop.order/dup_fk/1 role=graphics-symbol label=\"dup_fk/1\""), tree);
  }

  @Test
  void prunesPinsAndSelectionWhenTablesAreRemoved() throws IOException {
    class MutableBoard {
      final ErDiagram.State diagram = new ErDiagram.State();
      List<Schema> schemas = SCHEMAS;
    }
    MutableBoard board = new MutableBoard();
    ErDiagram.Pin customerPin = new ErDiagram.Pin(20, 40);
    ErDiagram.Pin addressPin = new ErDiagram.Pin(300, 40);
    board.diagram.pins(Map.of("shop.customer", customerPin, "shop.address", addressPin));
    board.diagram.select("shop.address");
    App<MutableBoard> app = new App<>(
        board, b -> ErDiagram.erDiagram(b.diagram, b.schemas, null).wFull().hFull());
    app.textRendering(TextRendering.LINUX);
    Script.run("size 900 500\nframe\n", app, out);
    assertEquals(
        Map.of("shop.customer", customerPin, "shop.address", addressPin), board.diagram.pins());
    assertEquals("shop.address", board.diagram.selected());

    board.schemas =
        List.of(new Schema("shop", List.of(SCHEMAS.getFirst().tables().getFirst())));
    app.invalidate();
    Script.run("frame\n", app, out);
    assertEquals(Map.of("shop.customer", customerPin), board.diagram.pins());
    assertNull(board.diagram.selected());

    board.diagram.select("shop.customer");
    List<Schema> withoutCustomer =
        List.of(new Schema("shop", List.of(SCHEMAS.getFirst().tables().getLast())));
    Theme.CURRENT.with(Theme.LIGHT, () -> ErDiagram.png(board.diagram, withoutCustomer));
    assertEquals(Map.of("shop.customer", customerPin), board.diagram.pins());
    assertEquals("shop.customer", board.diagram.selected());
  }

  @Test
  void savesTheWholeDiagramAsPng() {
    ErDiagram.State state = new ErDiagram.State();
    state.select("shop.order");
    byte[] png = Theme.CURRENT.with(Theme.LIGHT, () -> ErDiagram.png(state, SCHEMAS));
    assertTrue(Png.decode(png).width() > 2 * 2 * ErStyle.DEFAULT.width(), "two layers wide");
    assertTrue(state.pins().isEmpty() && state.selected().equals("shop.order"), "left as it was");
  }

  /** The line of the element with this key in a tree dump, or nothing. */
  private static String line(String tree, String key) {
    return tree.lines().filter(l -> l.contains(key + " ")).findFirst().orElse("");
  }

  /** The top y coordinate of a tree dump line such as {@code ... 10,20 100x50}. */
  private static int boxY(String line) {
    var matcher = Pattern.compile(" (\\d+),(\\d+) \\d+x\\d+").matcher(line);
    assertTrue(matcher.find(), "no bounds in: " + line);
    return Integer.parseInt(matcher.group(2));
  }
}
