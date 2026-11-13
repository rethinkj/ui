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
import com.rethinkj.ui.Text;
import com.rethinkj.ui.kit.DataGrid.CellIcon;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.DataType;
import com.rethinkj.ui.kit.DataGrid.Range;
import com.rethinkj.ui.render.Png;
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
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Icons in the data grid ({@link Orders#typed}): type icons and names in the headers and what
 * gives way where a column is narrow, icons before a cell's text and alone in its place, on the
 * selection, in frozen columns, in tooltips, copies and fits, what they cost, and stored images.
 * The assertions read the tree dump where it says what they check.
 */
class GridIconsTest {

  private static final Theme T = Theme.DARK;

  @TempDir Path out;

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
  void aHeaderShowsItsIconTitleTypeAndSortMarkWithTheMarkOnTheOuterSide() throws IOException {
    Orders orders = Orders.typed(T, 8); // sorted by id, which is right-aligned
    orders.widths[0] = 120; // room for its type
    App<Orders> app = start(orders);
    El id = find(app, "column-0");
    assertEquals(List.of("el", icon(Symbol.KEY), "el"), children(id),
        "the arrow, the key, then the title and the type");
    assertEquals(List.of("text \"id\"", "text \"bigint\""), children(id.children().get(2)));
    assertEquals(List.of(icon(Symbol.TEXT_CASE_TITLE), "el"), children(app, "column-2"));
    assertTrue(app.tree().dump().contains("text \"order_status\""), "the type, in the dump");
    assertEquals(T.status().warning(), id.children().get(1).text().color());
    assertEquals(T.text().muted(), find(app, "column-2").children().getFirst().text().color());
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
    assertTrue(type(status).width() > 0 && type(status).width() < natural(type(status)),
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
    assertTrue(x(app, arrow) + arrow.width() <= x(app, status) + status.width(),
        "inside the header cell");
  }

  @Test
  void aNarrowedTypeShowsAtLeastItsFirstCharacterOrNothing() throws IOException {
    Orders orders = Orders.typed(T, 8);
    App<Orders> app = start(orders);
    int shown = 0;
    int gone = 0;
    for (int column : new int[] {0, 4, 5}) { // id bigint, status order_status, amount numeric
      String type = orders.columns().get(column).typeName();
      float least = natural(Label.label(T, Label.Variant.MUTED, type.charAt(0) + Text.ELLIPSIS));
      for (int width = 200; width >= 40; width--) {
        orders.widths[column] = width;
        run(app, "frame");
        El header = find(app, "column-" + column);
        if (name(header) == null) {
          gone++;
        } else {
          shown++;
          assertTrue(type(header).width() >= least - 0.01f, type + " at " + width + ": "
              + type(header).width() + " wide, less than its first character and an ellipsis");
        }
      }
      orders.widths[column] = 200;
    }
    assertTrue(shown > 0 && gone > 0, shown + " shown, " + gone + " gone");
  }

  @Test
  void aRightAlignedHeaderEndsAtItsRightEdge() throws IOException {
    Orders orders = Orders.typed(T, 8);
    orders.widths[5] = 220; // amount, wide enough for all of it
    App<Orders> app = start(orders);
    El amount = find(app, "column-5");
    El name = amount.children().getLast();
    assertEquals(x(app, amount) + amount.width() - T.space().s(), x(app, name) + name.width());
    assertEquals(natural(type(amount)), type(amount).width());
    assertTrue(x(app, amount.children().getFirst()) > x(app, amount) + 50, "the icon goes along");
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
    assertEquals(List.of("text \"Grace Lovelace\""), children(app, "cell-1-2"),
        "and a cell without an icon one element less");

    assertEquals(T.status().success(), find(app, "cell-0-3").children().getFirst().text().color());
    assertEquals(T.status().danger(), find(app, "cell-1-4").children().getFirst().text().color());
    assertEquals(T.status().warning(),
        find(app, "cell-3-4").children().getFirst().text().color());
    El link = find(app, "cell-1-1"); // right-aligned: icon and number at the right
    El number = link.children().getLast();
    assertEquals(x(app, link) + link.width() - T.space().s(), x(app, number) + number.width());
    assertEquals(x(app, number) - T.space().s() - 16, x(app, link.children().getFirst()));
  }

  /**
   * Every icon in the header and the cells is as high as the theme says, on whole logical pixels,
   * and centred on its row, as the text beside it is, also with the rows scrolled by 7.
   */
  @ParameterizedTest
  @ValueSource(strings = {"1", "2"})
  void iconsSitOnWholePixelsCentredOnTheirRow(String scale) throws IOException {
    App<Orders> app = Orders.typed(T, 8).app();
    Script.run("size 720 240 scale " + scale + "\nframe\nwheel #rows 0 7\nframe", app, out);
    int icons = 0;
    for (String line : app.tree().dump().lines().toList()) {
      if (line.strip().startsWith("text label=") && !line.contains("\"arrow_up\"")) {
        String[] at = line.replaceAll(
            ".* (-?[\\d.]+),(-?[\\d.]+) ([\\d.]+)x([\\d.]+).*", "$1 $2 $3 $4").split(" ");
        float x = Float.parseFloat(at[0]);
        float y = Float.parseFloat(at[1]);
        assertEquals(16, Float.parseFloat(at[3]), line);
        assertEquals(Math.round(x), x, line);
        assertEquals(Math.round(y), y, line);
        icons++;
      }
    }
    assertTrue(icons > 30, icons + " icons");
    for (String key : List.of("cell-3-1", "cell-3-4", "cell-3-3", "column-4")) {
      El cell = find(app, key);
      float middle = y(app, cell) + cell.height() / 2;
      // A cell is its row less the hairline, 23 high: whole pixels put them half a pixel low.
      El icon = cell.children().getFirst();
      assertEquals(middle, y(app, icon) + icon.height() / 2, 0.5, key);
      El text = cell.children().getLast();
      assertEquals(middle, y(app, text) + text.height() / 2, 0.5, key);
    }
  }

  @Test
  void selectedCellsAndTheCursorKeepTheirIconsAndColours() throws IOException {
    Orders orders = Orders.typed(T, 8);
    App<Orders> app = start(orders);
    run(app, "press #cell-1-3\npress #cell-3-4 shift\nframe");
    assertEquals(List.of(new Range(1, 3, 3, 4)), orders.selection.ranges());
    assertTrue(line(app, "cell-1-3").endsWith(" selected pos=4/8"), line(app, "cell-1-3"));
    assertTrue(line(app, "cell-3-4").endsWith(" selected current pos=5/8"));
    assertEquals(List.of(icon(Symbol.RECORD), "text \"pending\""), children(app, "cell-3-4"));
    assertEquals(T.grid().selected(), find(app, "cell-3-4").fill());
    El icon = find(app, "cell-3-4").children().getFirst();
    assertEquals(T.status().warning(), icon.text().color(), "on the selection too");
    assertEquals(x(app, find(app, "cell-3-4")) + T.space().s(), x(app, icon),
        "where it was without the cursor's outline");
  }

  @Test
  void frozenIconsStayAtTheLeftWhileTheRestScrollsSideways() throws IOException {
    App<Orders> app = start(Orders.typed(T, 8));
    float link = x(app, find(app, "cell-3-1").children().getFirst());
    float key = x(app, find(app, "column-0").children().get(1));
    float mark = x(app, find(app, "cell-3-4").children().getFirst());
    run(app, "wheel #rows 300 0\nframe");
    assertEquals(300, find(app, "orders").scrollX());
    assertEquals(link, x(app, find(app, "cell-3-1").children().getFirst()));
    assertEquals(key, x(app, find(app, "column-0").children().get(1)));
    assertEquals(mark - 300, x(app, find(app, "cell-3-4").children().getFirst()));
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
  void noIconFunctionIsCalledForARowThatIsNotLoaded() throws IOException {
    Orders orders = Orders.typed(T, 8);
    var typed = orders.more;
    orders.more = o -> typed.apply(o).loading(i -> i < 1000, (first, last) -> {});
    App<Orders> app = start(orders);
    assertTrue(orders.askedIcons.contains(3));
    orders.askedIcons.clear();
    run(app, "scroll #rows row 2000\nframe");
    assertTrue(find(app, "rows").firstRow() > 1990);
    assertTrue(orders.askedIcons.stream().allMatch(row -> row < 1000), "" + orders.askedIcons);
    El placeholder = find(app, "cell-2001-3").children().getFirst();
    assertEquals(Text.ELLIPSIS, placeholder.text().string());
    assertEquals(1, find(app, "cell-2001-1").children().size(), "no link either");
  }

  @Test
  void aDoublePressOnAnEdgeFitsHeaderAndCellsWithTheirIcons() throws IOException {
    Orders orders = Orders.typed(T, 8);
    orders.widths[4] = 60;
    App<Orders> app = start(orders);
    run(app, "press #resize-4\npress #resize-4 clicks 2\nframe");
    El status = find(app, "column-4");
    float header = 16 + T.space().s() + natural(title(status)) + T.space().s()
        + natural(type(status));
    assertEquals((float) Math.ceil(header) + 2 * T.space().s() + T.size().stroke(),
        orders.widths[4], "the header is the widest: icon, title and type");
    assertEquals(natural(type(status)), type(status).width(), "all of it shows");
    assertFalse(title(status).text().shortened());

    orders.widths[1] = 40; // the customer's id: its cells, link and number, are the widest
    run(app, "frame\npress #resize-1\npress #resize-1 clicks 2\nframe");
    El widest = find(app, "cell-1-1");
    float cell = 16 + T.space().s() + natural(widest.children().getLast());
    assertTrue(orders.widths[1] >= Math.ceil(cell) + 2 * T.space().s() + T.size().stroke(),
        "" + orders.widths[1]);
    assertFalse(widest.children().getLast().text().shortened());
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

  /**
   * The cells of 300 columns with icons cost one element more each where they have an icon before
   * their text, none where it stands alone; each header three more for its icon and type. Without
   * icons the grid is built as before.
   */
  @Test
  void anIconCostsOneElementWhereItIsAndNoneElsewhere() throws IOException {
    Orders bare = Orders.typed(T, 300);
    bare.bare = true;
    long without = count(start(bare).tree());
    Orders typed = Orders.typed(T, 300);
    App<Orders> app = start(typed);
    long with = count(app.tree());
    long before = 0;
    long alone = 0;
    for (El row : find(app, "rows").children()) {
      for (El cell : row.children()) {
        if ("gridcell".equals(cell.role()) && cell.children().getFirst().label() != null) {
          if (cell.children().size() == 2) {
            before++;
          } else {
            alone++;
          }
        }
      }
    }
    System.out.printf("DataGrid, 300 columns of a million rows: %d elements without icons and"
        + " types, %d with them (%d cells with an icon before their text, %d with one alone)%n",
        without, with, before, alone);
    assertTrue(before > 0 && alone > 0, before + " before, " + alone + " alone");
    // Each header has an icon, and the box of title and type and the type where it has room.
    long types = find(app, "header").children().stream()
        .filter(h -> "columnheader".equals(h.role()) && name(h) != null).count();
    assertTrue(types > 0 && types < 300, types + " headers with their type");
    assertEquals(without + 300 + 2 * types + before, with);
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

  /** icons.txt in both themes at scales 1 and 2. */
  @ParameterizedTest
  @CsvSource({"dark, 1", "dark, 2", "light, 1", "light, 2"})
  void looksAsStored(String name, int scale) throws IOException {
    Orders orders = Orders.typed(name.equals("dark") ? Theme.DARK : Theme.LIGHT, 8);
    App<Orders> app = orders.app();
    String script = Files.readString(Path.of("src/test/resources/scripts/icons.txt"));
    List<Path> files = Script.run("size 720 240 scale " + scale + "\n" + script, app, out);
    for (int i = 0; i < files.size(); i += 2) {
      String snap = files.get(i).getFileName().toString().replace(".png", "");
      Golden.assertMatches("grid-icons-" + name + "-" + scale + "x-" + snap,
          Png.decode(Files.readAllBytes(files.get(i))), Files.readString(files.get(i + 1)));
    }
    assertEquals(12, files.size());
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
      if (child.text() == null && child.children().size() == 2
          && child.children().getFirst().text() != null
          && child.children().getFirst().text().font() != Font.icons()) {
        return child;
      }
    }
    return null;
  }

  private static float natural(El text) {
    return text.text().measure(Float.NaN, Float.NaN, Measure.MAX_CONTENT, Measure.MAX_CONTENT)
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
