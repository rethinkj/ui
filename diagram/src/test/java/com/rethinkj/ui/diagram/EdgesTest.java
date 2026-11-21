package com.rethinkj.ui.diagram;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.diagram.Canvas.Edge;
import com.rethinkj.ui.diagram.Canvas.Emphasis;
import com.rethinkj.ui.diagram.Canvas.Marker;
import com.rethinkj.ui.diagram.Canvas.Node;
import com.rethinkj.ui.diagram.Canvas.Port;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Edges on a {@link Canvas}, driven by {@code edges.txt}, with stored images: a straight edge, one
 * with a jog, two to one port in lanes of their own, a self-reference, every marker, each emphasis
 * and a tooltip, in the dark theme, and the same without the tooltip in the light one.
 */
class EdgesTest {

  private static final float HEADER = 28;
  private static final float ROW = 20;

  @TempDir
  Path out;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  static final class Board {
    final Canvas.Viewport viewport = new Canvas.Viewport();
    final List<Node> nodes = List.of(
        table("customer", 20, 40, 140, 2),
        table("order", 240, 20, 150, 4),
        table("address", 20, 170, 140, 2),
        table("item", 470, 90, 110, 2),
        table("category", 280, 190, 130, 2));
    final List<Edge> edges = List.of(
        // Straight: both rows at one height.
        new Edge(
            "customer_fk",
            "order_customer_fk",
            row("order", 1),
            row("customer", 0),
            Marker.ZERO_OR_MANY,
            Marker.ONE),
        // Two to one port, each in a lane of its own.
        new Edge(
            "billing_fk",
            "order_billing_fk",
            row("order", 2),
            row("address", 0),
            Marker.ONE_OR_MANY,
            Marker.ONE,
            Emphasis.HIGHLIGHTED,
            "ON DELETE RESTRICT"),
        new Edge(
            "shipping_fk",
            "order_shipping_fk",
            row("order", 3),
            row("address", 0),
            Marker.ZERO_OR_MANY,
            Marker.ONE),
        // A jog between rows at different heights, with an arrow.
        new Edge(
            "item_fk",
            "item_order_fk",
            row("item", 0),
            row("order", 0),
            Marker.NONE,
            Marker.ARROW,
            Emphasis.DIMMED,
            null),
        // A self-reference, on the free side.
        new Edge(
            "parent_fk",
            "category_parent_fk",
            row("category", 1),
            row("category", 0),
            Marker.ZERO_OR_MANY,
            Marker.ZERO_OR_ONE));

    static Node table(String name, double x, double y, float width, int rows) {
      return new Node(name, name, x, y, width, HEADER + rows * ROW);
    }

    static Port row(String table, int index) {
      return new Port(table, HEADER + index * ROW + ROW / 2);
    }
  }

  /** A card with the node's name over two to four rows. */
  static El card(Node node, float zoom) {
    Theme theme = Theme.CURRENT.get();
    El card = col()
        .wFull()
        .hFull()
        .fill(theme.surface().panel())
        .radius(theme.radius().medium() * zoom)
        .border(theme.size().stroke(), theme.border().divider())
        .child(text(node.label())
            .font(theme.type().heading().font().get())
            .fontSize(theme.type().small().size() * zoom)
            .color(theme.text().primary())
            .h(HEADER * zoom)
            .padding(theme.space().s() * zoom));
    int rows = Math.round((node.height() - HEADER) / ROW);
    for (int row = 0; row < rows; row++) {
      card.child(text("column " + (row + 1))
          .fontSize(theme.type().small().size() * zoom)
          .color(theme.text().muted())
          .h(ROW * zoom)
          .padding(0, theme.space().s() * zoom));
    }
    return card;
  }

  private App<Board> app(Theme theme) {
    App<Board> app = new App<>(
        new Board(),
        board -> Canvas.canvas(board.viewport, board.nodes, board.edges, EdgesTest::card, null)
            .key("board")
            .wFull()
            .hFull());
    app.provide(Theme.CURRENT, board -> theme);
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  @Test
  void savesALoopOutsideTheNodesWhole() throws IOException {
    List<Node> nodes = List.of(Board.table("category", 0, 0, 120, 2));
    List<Edge> edges = List.of(new Edge(
        "parent_fk",
        "category_parent_fk",
        Board.row("category", 1),
        Board.row("category", 0),
        Marker.ZERO_OR_MANY,
        Marker.ZERO_OR_ONE));
    Pixels image = Png.decode(Theme.CURRENT.with(
        Theme.LIGHT,
        () -> Canvas.png(
            nodes,
            edges,
            viewport -> Canvas.canvas(viewport, nodes, edges, EdgesTest::card, null))));
    // The loop runs a stub, 30, right of the node, its markers 21 and its line 2 further: to 173,
    // and down to 81; with a margin of 16 on each side, at a scale of 2.
    assertEquals(2 * (173 + 2 * 16), image.width());
    assertEquals(2 * (81 + 2 * 16), image.height());
    Golden.assertImage("edges-png-light", image);
  }

  @Test
  void looksAsStored() throws IOException {
    String script = Files.readString(Path.of("src/test/resources/scripts/edges.txt"));
    List<Path> files = Script.run("size 600 300\n" + script, app(Theme.DARK), out);
    Golden.assertSnaps("edges-dark-", files, "view", "tooltip");
    String tooltip = Files.readString(out.resolve("tooltip.tree.txt"));
    assertTrue(tooltip.contains("role=tooltip label=\"order_billing_fk\""), tooltip);

    Path light = out.resolve("light");
    Files.createDirectories(light);
    files = Script.run("size 600 300\nframe\nsnap view\n", app(Theme.LIGHT), light);
    Golden.assertSnaps("edges-light-", files, "view");
  }
}
