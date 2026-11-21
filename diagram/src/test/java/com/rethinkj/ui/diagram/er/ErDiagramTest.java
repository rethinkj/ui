package com.rethinkj.ui.diagram.er;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
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
    Map<String, ErDiagram.Pin> pins = Map.of();
  }

  private App<Board> app() {
    App<Board> app = new App<>(
        new Board(),
        board -> ErDiagram.erDiagram(board.diagram, SCHEMAS, pins -> board.pins = pins)
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
        tree.contains("#shop role=group label=\"shop, 4 tables\""), "the schema's frame: " + tree);
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
    assertEquals(Map.of("shop.customer", pin), board.pins, "only the nudged table is pinned");
    assertEquals(board.diagram.pins(), board.pins);
    // All columns, then keys only, as customer has a column that is no key.
    assertEquals(ErDiagram.Mode.KEYS, board.diagram.mode("shop.customer"));

    Script.run("key Escape\nframe\n", app, out);
    assertNull(board.diagram.selected());
    Script.run("key ArrowDown\nframe\n", app, out);
    assertTrue(board.diagram.selected() != null, "an arrow selects a table where none is");

    board.diagram.arrangeAll();
    assertTrue(board.diagram.pins().isEmpty(), "Arrange all unpins");
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
  void savesTheWholeDiagramAsPng() {
    ErDiagram.State state = new ErDiagram.State();
    state.select("shop.order");
    byte[] png = Theme.CURRENT.with(Theme.LIGHT, () -> ErDiagram.png(state, SCHEMAS));
    assertTrue(Png.decode(png).width() > 2 * 2 * ErStyle.DEFAULT.width(), "two layers wide");
  }

  /** The line of the element with this key in a tree dump, or nothing. */
  private static String line(String tree, String key) {
    return tree.lines().filter(l -> l.contains(key + " ")).findFirst().orElse("");
  }
}
