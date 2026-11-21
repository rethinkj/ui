package com.rethinkj.ui.diagram;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@link Canvas}, driven by {@code canvas.txt}, with stored images: at 100 % and 75 %, panned,
 * with a node dragged and pinned, and fitted, also again after a resize; and a node outside it
 * culled; and the whole diagram saved as PNG.
 */
class CanvasTest {

  @TempDir
  Path out;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  static final class Board {
    final Canvas.Viewport viewport = new Canvas.Viewport();
    final List<Node> nodes = new ArrayList<>(List.of(
        new Node("orders", "orders", 40, 40, 140, 60),
        new Node("customers", "customers", 240, 40, 140, 60),
        new Node("items", "items", 40, 160, 140, 60),
        new Node("archive", "archive", 420, 0, 140, 60)));

    void move(Node node) {
      nodes.replaceAll(n -> n.key().equals(node.key()) ? node : n);
    }

    Node node(String key) {
      return nodes.stream().filter(n -> n.key().equals(key)).findFirst().orElseThrow();
    }
  }

  /** A card with the node's name, and below it a muted line from 100 % on. */
  static El card(Node node, float zoom) {
    Theme theme = Theme.CURRENT.get();
    Theme.TextStyle name = theme.type().heading();
    Theme.TextStyle small = theme.type().small();
    El card = col()
        .wFull()
        .hFull()
        .gap(theme.space().xs() * zoom)
        .padding(theme.space().s() * zoom)
        .fill(theme.surface().panel())
        .radius(theme.radius().medium() * zoom)
        .border(theme.size().stroke(), theme.border().divider())
        .child(text(node.label())
            .font(name.font().get())
            .fontSize(name.size() * zoom)
            .color(theme.text().primary()));
    if (zoom >= 1) {
      card.child(text("3 columns")
          .font(small.font().get())
          .fontSize(small.size() * zoom)
          .color(theme.text().muted()));
    }
    return card;
  }

  @Test
  void looksAsStored() throws IOException {
    App<Board> app = new App<>(
        new Board(),
        board -> col()
            .wFull()
            .hFull()
            .child(Canvas.canvas(board.viewport, board.nodes, CanvasTest::card, board::move)
                .key("board")
                .grow(1)));
    app.provide(Theme.CURRENT, board -> Theme.DARK);
    app.textRendering(TextRendering.LINUX);
    String script = Files.readString(Path.of("src/test/resources/scripts/canvas.txt"));
    List<Path> files = Script.run("size 400 260\n" + script, app, out);
    Golden.assertSnaps("canvas-dark-", files, "actual", "out", "panned", "pinned");

    String actual = Files.readString(out.resolve("actual.tree.txt"));
    String board = line(actual, "#board");
    assertTrue(board.contains("role=graphics-document label=\"Diagram\""), actual);
    assertTrue(board.contains(" value=\"Zoom 100%\""), actual);
    assertTrue(line(actual, "#orders").contains("role=graphics-object label=\"orders\""), actual);
    assertFalse(actual.contains("#archive"), "culled at 100 %: " + actual);
    String zoomedOut = Files.readString(out.resolve("out.tree.txt"));
    assertTrue(line(zoomedOut, "#board").contains(" value=\"Zoom 75%\""), zoomedOut);
    assertTrue(zoomedOut.contains("#archive"), "inside at 75 %: " + zoomedOut);
    assertFalse(zoomedOut.contains("3 columns"), "less detail at 75 %: " + zoomedOut);
    assertTrue(Files.readString(out.resolve("panned.tree.txt")).contains("#archive"));

    Board state = app.state();
    assertEquals(new Node("orders", "orders", 120, 90, 140, 60, true), state.node("orders"));
    assertFalse(state.node("items").pinned(), "only the dragged node is pinned");
    String pinned = Files.readString(out.resolve("pinned.tree.txt"));
    assertTrue(line(pinned, "#orders").contains(" value=\"Pinned\""), pinned);
    assertFalse(line(pinned, "#items").contains(" value="), pinned);

    // From 40,0 to 560,220: 520 by 220, which fits 280 by 260 less the margins at 25 %, centered;
    // 400 by 260, before the canvas became narrower, at 50 %.
    assertEquals(0.25f, state.viewport.zoom());
    assertEquals(300 - 140 / 0.25, state.viewport.left(), 1e-9);
    assertEquals(110 - 130 / 0.25, state.viewport.top(), 1e-9);
    String fitted = app.tree().dump();
    assertTrue(line(fitted, "#board").contains(" value=\"Zoom 25%\""), fitted);
  }

  @Test
  void aZoomWhilePanningMovesNothingOnRelease() throws IOException {
    App<Board> app = new App<>(
        new Board(),
        board -> Canvas.canvas(board.viewport, board.nodes, CanvasTest::card, board::move)
            .key("board")
            .wFull()
            .hFull());
    app.textRendering(TextRendering.LINUX);
    String script = """
        size 400 260
        frame
        focus #board
        down 300,200
        move 260,180
        key +
        up 260,180
        frame
        """;
    Script.run(script, app, out);
    Canvas.Viewport viewport = app.state().viewport;
    // Panned to 40,20, then zoomed to 125 % around the center, 200,130: the release adds nothing.
    assertEquals(1.25f, viewport.zoom());
    assertEquals(40 + 200 - 200 / 1.25, viewport.left(), 1e-9);
    assertEquals(20 + 130 - 130 / 1.25, viewport.top(), 1e-9);
  }

  @Test
  void theWheelPansAndZoomsAroundThePointerWithControl() throws IOException {
    App<Board> app = new App<>(
        new Board(),
        board -> Canvas.canvas(board.viewport, board.nodes, CanvasTest::card, board::move)
            .key("board")
            .wFull()
            .hFull());
    app.textRendering(TextRendering.LINUX);
    String script = """
        size 400 260
        frame
        wheel 100,60 30 40
        frame
        wheel 100,60 0 24 control
        frame
        wheel 100,60 0 24 control
        frame
        """;
    Script.run(script, app, out);
    Canvas.Viewport viewport = app.state().viewport;
    // Panned by 30, 40 at 100 %; then two half steps towards the user make one step out, to 75 %,
    // that keeps the point under the pointer, 130,100, in place.
    assertEquals(0.75f, viewport.zoom());
    // zoomBy works in floats for the pointer's place.
    assertEquals(130 - 100 / 0.75, viewport.left(), 1e-4);
    assertEquals(100 - 60 / 0.75, viewport.top(), 1e-4);
  }

  @Test
  void savesTheWholeDiagramAsPng() throws IOException {
    Board board = new Board();
    byte[] png = Theme.CURRENT.with(
        Theme.LIGHT,
        () -> Canvas.png(
            board.nodes, viewport -> Canvas.canvas(viewport, board.nodes, CanvasTest::card, null)));
    Pixels image = Png.decode(png);
    // From 40,0 to 560,220, with a margin of 16 on each side, at a scale of 2.
    assertEquals(2 * (520 + 2 * 16), image.width());
    assertEquals(2 * (220 + 2 * 16), image.height());
    Golden.assertImage("canvas-png-light", image);
  }

  @Test
  void refusesADiagramTooLargeForAnImage() {
    // At most 2^24 physical pixels: 2048 by 2048 at a scale of 2, 4096 by 4096 at 1.
    assertEquals(2, Canvas.pngScale(2048, 2048));
    assertEquals(1, Canvas.pngScale(2049, 2048));
    assertEquals(1, Canvas.pngScale(4096, 4096));
    List<Node> huge = List.of(new Node("huge", null, 0, 0, 5000, 4000));
    assertThrows(
        IllegalArgumentException.class,
        () -> Canvas.png(huge, viewport -> fail("built before the size was checked")));
  }

  @Test
  void routesReuseTheCacheWhenOnlyLabelsPinsOrEmphasisChange() {
    Canvas.Viewport viewport = new Canvas.Viewport();
    Node a = new Node("a", "Orders", 0, 0, 120, 60, false);
    Node b = new Node("b", "Customers", 240, 0, 120, 60, false);
    Edge edge = new Edge(
        "e",
        "orders.customer_id",
        new Port("a", 30),
        new Port("b", 30),
        Marker.ONE_OR_MANY,
        Marker.ONE,
        Emphasis.NORMAL,
        "foreign key");

    List<Routing.Route> first = viewport.routes(List.of(a, b), List.of(edge), EdgeStyle.DEFAULT);
    Node relabeled = new Node("a", "Renamed", 0, 0, 120, 60, true);
    Edge highlighted = new Edge(
        "e",
        "renamed",
        new Port("a", 30),
        new Port("b", 30),
        Marker.ONE_OR_MANY,
        Marker.ONE,
        Emphasis.HIGHLIGHTED,
        "other");
    assertSame(
        first, viewport.routes(List.of(relabeled, b), List.of(highlighted), EdgeStyle.DEFAULT));
    assertNotSame(
        first, viewport.routes(List.of(a.movedTo(20, 0), b), List.of(edge), EdgeStyle.DEFAULT));
  }

  /** The line of the element with this key in a tree dump, or nothing. */
  private static String line(String tree, String key) {
    return tree.lines().filter(l -> l.contains(key + " ")).findFirst().orElse("");
  }
}
