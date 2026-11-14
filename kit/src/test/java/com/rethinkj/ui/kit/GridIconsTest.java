package com.rethinkj.ui.kit;

import static com.rethinkj.ui.kit.Workbench.dumpLine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Measure;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.DataGrid.CellIcon;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.DataType;
import com.rethinkj.ui.text.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Icons in the data grid ({@link Orders#typed}): type icons and names in the headers and what
 * gives way where a column is narrow, icons before a cell's text and alone in its place, on the
 * selection, in tooltips, copies and edits, and stored images.
 * The assertions read the tree dump where it says what they check.
 */
class GridIconsTest {

  private static final Theme T = Theme.DARK;

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private App<Orders> start(Orders orders) throws IOException {
    App<Orders> app = orders.app();
    run(app, "frame");
    return app;
  }

  /** Runs the steps in a window of 720 by 240: the grid is 704 by 224 at 8,8. */
  private void run(App<?> app, String steps) throws IOException {
    Script.run("size 720 240\n" + steps, app, out);
  }

  private static String line(App<?> app, String key) {
    String line = dumpLine(app, key);
    assertNotNull(line, "no #" + key);
    return line;
  }

  /** The lines of the element's children in the dump, without rectangles and what follows. */
  private static List<String> children(App<?> app, String key) {
    return children(find(app, key));
  }

  private static List<String> children(El el) {
    List<String> lines = new ArrayList<>();
    for (El child : el.children()) {
      String line = child.dump().lines().findFirst().orElseThrow().strip();
      lines.add(line.replaceAll(" -?[\\d.]+,-?[\\d.]+ [\\d.]+x[\\d.]+.*", ""));
    }
    return lines;
  }

  /** The start of an icon's line in the dump. */
  private static String icon(Symbol symbol) {
    return "text label=\"" + symbol.name().toLowerCase(Locale.ROOT) + "\" \"\\u{"
        + Integer.toHexString(symbol.codePoint(T.size().icon())).toUpperCase(Locale.ROOT) + "}\"";
  }

  // The header

  @Test
  void anEmptyTypeCanGiveWayToALongTitle() throws IOException {
    String heading = "a title too long for its column";
    Column column = new Column(heading, 40, DataGrid.Align.LEFT, false, i -> "").typeName("");
    App<Column> app = new App<>(
        column,
        c -> DataGrid.dataGrid(T, List.of(c), 1, DataGrid.options()).wFull().hFull());
    run(app, "frame");
    El header = find(app, "column-0");
    assertNull(name(header), "the empty type gives way");
    assertTrue(title(header).text().shortened());
    run(app, "move #column-0\nframe\nadvance 0.5\nframe");
    assertEquals(heading + " ", find(app, "column-0").overlays().getFirst().label());
  }

  @Test
  void aHeaderShowsItsIconTitleTypeAndSortMarkWithTheMarkOnTheOuterSide() throws IOException {
    Orders orders = Orders.typed(T, 8); // sorted by id, which is right-aligned
    orders.widths[0] = 120; // room for its type
    App<Orders> app = start(orders);
    El id = find(app, "column-0");
    assertEquals(
        List.of("el", icon(Symbol.KEY), "el"),
        children(id),
        "the arrow, the key, then the title and the type");
    assertEquals(
        List.of("text \"id\"", "text \"bigint\""), children(id.children().get(2)));
    assertEquals(List.of(icon(Symbol.TEXT_CASE_TITLE), "el"), children(app, "column-2"));
    assertTrue(app.tree().dump().contains("text \"order_status\""), "the type, in the dump");
    assertEquals(T.status().warning(), id.children().get(1).text().color());
    assertEquals(
        T.text().muted(), find(app, "column-2").children().getFirst().text().color());
    assertEquals(T.text().muted(), type(find(app, "column-2")).text().color());

    run(app, "press #column-4\nframe"); // status, left-aligned: the mark after the type
    assertTrue(line(app, "column-4").endsWith(" pos=5/8 sort=ascending"));
    assertEquals(List.of(icon(Symbol.TAG), "el", "el"), children(app, "column-4"));
    assertEquals(1, find(app, "column-4").children().getLast().children().size(), "the arrow");
    assertEquals(List.of("sort [+4]"), orders.log);
  }

  @Test
  void whereAColumnIsNarrowTheTypeGivesWayFirstThenTheTitleWhileIconAndArrowStay()
      throws IOException {
    Orders orders = Orders.typed(T, 8);
    orders.widths[4] = 160;
    App<Orders> app = start(orders);
    El status = find(app, "column-4");
    assertEquals(natural(type(status)), type(status).width(), "160 pixels: all of it shows");
    assertFalse(title(status).text().shortened());

    orders.widths[4] = 90;
    run(app, "frame");
    status = find(app, "column-4");
    assertTrue(
        type(status).width() > 0 && type(status).width() < natural(type(status)),
        "the type is cut short: " + type(status).width());
    assertFalse(title(status).text().shortened(), "the title is whole");

    orders.widths[4] = 70;
    run(app, "frame");
    status = find(app, "column-4");
    assertNull(name(status), "no room for the type: it is left out, not cut to an ellipsis");
    assertFalse(title(status).text().shortened(), "the title still fits");

    orders.widths[4] = 50;
    orders.sort = List.of(new DataGrid.SortKey(4, true));
    run(app, "frame");
    status = find(app, "column-4");
    assertTrue(title(status).text().shortened(), "the title ends in an ellipsis");
    assertEquals(16, status.children().getFirst().width(), "the icon keeps its size");
    assertEquals(16, status.children().getFirst().height());
    El arrow = status.children().getLast().children().getFirst();
    assertEquals(T.grid().mark(), arrow.width(), "and so does the arrow");
    assertTrue(
        x(app, arrow) + arrow.width() <= x(app, status) + status.width(), "inside the header cell");
  }

  @Test
  void theHeadersTooltipShowsTitleAndTypeWhereAnythingIsCutShort() throws IOException {
    App<Orders> app = start(Orders.typed(T, 8));
    run(app, "move #column-2\nframe\nadvance 0.5\nframe"); // customer: all of it shows
    assertFalse(app.tree().dump().contains("role=tooltip"));
    run(app, "move #column-0\nframe\nadvance 0.5\nframe"); // id: no room for bigint
    assertTrue(app.tree().dump().contains("role=tooltip label=\"id bigint\""));
  }

  // Cells

  @Test
  void aCellShowsItsIconBeforeItsTextOrAloneWithTheTextAsItsLabel() throws IOException {
    App<Orders> app = start(Orders.typed(T, 8));
    assertEquals(List.of(icon(Symbol.LINK), "text \"2920\""), children(app, "cell-1-1"));
    assertEquals(List.of(icon(Symbol.RECORD), "text \"refunded\""), children(app, "cell-1-4"));
    assertTrue(line(app, "cell-1-3").startsWith("el #cell-1-3 role=gridcell label=\"false\" "));
    assertEquals(List.of(icon(Symbol.SUBTRACT)), children(app, "cell-1-3"));
    assertTrue(line(app, "cell-0-3").contains(" label=\"true\" "));
    assertEquals(List.of(icon(Symbol.CHECKMARK)), children(app, "cell-0-3"));
    assertTrue(line(app, "cell-6-3").contains(" label=\"NULL\" "), "the NULL marker");
    assertEquals(List.of(icon(Symbol.PROHIBITED)), children(app, "cell-6-3"));
    assertNull(find(app, "cell-1-2").label(), "a cell with text has no label");
    assertEquals(
        List.of("text \"Grace Lovelace\""),
        children(app, "cell-1-2"),
        "and a cell without an icon one element less");

    assertEquals(
        T.status().success(), find(app, "cell-0-3").children().getFirst().text().color());
    assertEquals(
        T.status().danger(), find(app, "cell-1-4").children().getFirst().text().color());
    assertEquals(
        T.status().warning(), find(app, "cell-3-4").children().getFirst().text().color());
    El link = find(app, "cell-1-1"); // right-aligned: icon and number at the right
    El number = link.children().getLast();
    assertEquals(x(app, link) + link.width() - T.space().s(), x(app, number) + number.width());
    assertEquals(x(app, number) - T.space().s() - 16, x(app, link.children().getFirst()));
  }

  @Test
  void theTooltipOfACellIsItsIconsElseTheTextAnIconAloneStandsFor() throws IOException {
    App<Orders> app = start(Orders.typed(T, 8));
    run(app, "move #cell-1-3\nframe\nadvance 0.5\nframe");
    assertTrue(app.tree().dump().contains("role=tooltip label=\"false\""), "paid, as a dash");
    run(app, "move #cell-4-1\nframe\nadvance 0.5\nframe");
    assertTrue(app.tree().dump().contains("role=tooltip label=\"References customers.id\""));
    run(app, "move #cell-4-4\nframe\nadvance 1\nframe"); // a mark and text that fits
    assertFalse(app.tree().dump().contains("role=tooltip"));

    Orders narrow = Orders.typed(T, 8);
    narrow.widths[4] = 60;
    App<Orders> cut = start(narrow);
    run(cut, "move #cell-1-4\nframe\nadvance 0.5\nframe"); // refunded, cut after its mark
    assertTrue(cut.tree().dump().contains("role=tooltip label=\"refunded\""));
  }

  // What keeps working

  @Test
  void aCopyTakesTheTextAlsoOfACellWhoseIconStandsAlone() throws IOException {
    App<Orders> app = start(Orders.typed(T, 8));
    run(app, "press #cell-5-3\npress #cell-6-4 shift\nkey C control\nframe");
    assertEquals("true\trefunded\n\tshipped", app.clipboard().read(), "NULL is nullText, \"\"");
  }

  @Test
  void f2EditsTheCellOfAnIconAlone() throws IOException {
    Orders orders = Orders.typed(T, 8);
    var typed = orders.more;
    orders.more = o -> typed.apply(o).onEdit((r, c) -> orders.log.add("edit " + r + " " + c));
    App<Orders> app = start(orders);
    run(app, "press #cell-1-3\nkey F2\nframe");
    assertEquals("edit 1 3", orders.log.getLast());
  }

  private static long count(El el) {
    long count = 1;
    for (El child : el.children()) {
      count += count(child);
    }
    return count;
  }

  // The kinds of data

  @Test
  void aColumnOfAKindTakesItsIconAlignmentAndFont() {
    Column amount = new Column("amount", 90, DataType.DECIMAL, i -> "1.00");
    assertEquals(Symbol.CALCULATOR, amount.icon());
    assertEquals(DataGrid.Align.RIGHT, amount.align());
    assertTrue(amount.mono());
    assertTrue(amount.sortable());
    Column other = new Column("x", 90, DataType.OTHER, i -> "?").typeName("tsvector");
    assertNull(other.icon());
    assertEquals("tsvector", other.typeName());
    for (DataType type : DataType.values()) {
      assertEquals(type != DataType.OTHER, type.symbol() != null, type.name());
    }
    CellIcon check = CellIcon.instead(Symbol.CHECKMARK, Icon.Variant.SUCCESS).tooltip("Paid");
    assertTrue(check.alone());
    assertEquals("Paid", check.tooltip());
  }

  // Images

  /** icons.txt. */
  @ParameterizedTest
  @CsvSource({"dark, 2", "light, 2"})
  void looksAsStored(String name, int scale) throws IOException {
    Orders orders = Orders.typed(name.equals("dark") ? Theme.DARK : Theme.LIGHT, 8);
    App<Orders> app = orders.app();
    String script = Files.readString(Path.of("src/test/resources/scripts/icons.txt"));
    List<Path> files = Script.run("size 720 240 scale " + scale + "\n" + script, app, out);
    Golden.assertSnaps("grid-icons-" + name + "-" + scale + "x-", files, "top");
    assertEquals(2, files.size());
  }

  // Helpers

  /** The title of a header cell, with its type beside it or without. */
  private static El title(El header) {
    El name = name(header);
    if (name != null) {
      return name.children().getFirst();
    }
    for (El child : header.children()) {
      if (child.text() != null && child.text().font() != Font.icons()) {
        return child;
      }
    }
    throw new AssertionError("no title in " + header.dump());
  }

  private static El type(El header) {
    El name = name(header);
    assertNotNull(name, "no title and type in " + header.dump());
    return name.children().getLast();
  }

  /** The box of the title and the type in a header cell, or null where it shows no type. */
  private static El name(El header) {
    for (El child : header.children()) {
      if (child.text() == null
          && child.children().size() == 2
          && child.children().getFirst().text() != null
          && child.children().getFirst().text().font() != Font.icons()) {
        return child;
      }
    }
    return null;
  }

  private static float natural(El text) {
    return text.text()
        .measure(Float.NaN, Float.NaN, Measure.MAX_CONTENT, Measure.MAX_CONTENT)
        .width();
  }

  private static float x(App<?> app, El el) {
    return at(app.tree(), el, 0, 0)[0];
  }

  private static float y(App<?> app, El el) {
    return at(app.tree(), el, 0, 0)[1];
  }

  /** The window position of {@code target} below {@code el}, which is at this position. */
  private static float[] at(El el, El target, float left, float top) {
    float x = left + el.x();
    float y = top + el.y();
    if (el == target) {
      return new float[] {x, y};
    }
    for (El child : el.children()) {
      float[] found = at(child, target, x, y);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static El find(App<?> app, String key) {
    El found = find(app.tree(), key);
    assertNotNull(found, "no #" + key);
    return found;
  }

  private static El find(El el, String key) {
    if (key.equals(el.key())) {
      return el;
    }
    for (El child : el.children()) {
      El found = find(child, key);
      if (found != null) {
        return found;
      }
    }
    return null;
  }
}
