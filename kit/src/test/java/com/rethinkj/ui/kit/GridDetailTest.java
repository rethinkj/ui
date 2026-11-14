package com.rethinkj.ui.kit;

import static com.rethinkj.ui.kit.Workbench.dumpLine;
import static com.rethinkj.ui.kit.Workbench.find;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Text;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.Range;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A muted detail beside a cell's value ({@link Column#detail}), driven by scripts on {@link
 * Orders#detailed}: the size after each note and the currency before each amount, where it lies,
 * what gives way, the tooltip, the dump, copies, the editor, a frozen column, typed columns and
 * stored images.
 */
class GridDetailTest {

  private static final Theme T = Theme.DARK;
  private static final String SIZE = Orders.noteSize(1); // "82 B"

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

  /** The texts of a cell's children in their order, the icon's by its label. */
  private static List<String> parts(El cell) {
    List<String> parts = new ArrayList<>();
    for (El child : cell.children()) {
      if (child.text() != null) {
        parts.add(child.label() != null ? child.label() : child.text().string());
      }
    }
    return parts;
  }

  private static El part(El cell, String text) {
    for (El child : cell.children()) {
      if (child.text() != null && child.text().string().equals(text)) {
        return child;
      }
    }
    throw new AssertionError("no " + text + " in " + cell.dump());
  }

  @Test
  void aLeftAlignedCellShowsItsDetailAfterTheValueAndKeepsItWhileTheValueIsCut()
      throws IOException {
    Orders orders = Orders.detailed(T, 8);
    App<Orders> app = start(orders);
    run(app, "wheel #rows 1000 0\nframe");
    El cell = find(app, "cell-1-7");
    assertEquals(List.of(Orders.NOTE, SIZE), parts(cell));
    assertTrue(line(app, "cell-1-7").endsWith(" pos=8/8 value=\"" + SIZE + "\""));
    El value = part(cell, Orders.NOTE);
    El detail = part(cell, SIZE);
    assertTrue(value.text().shortened(), "the note ends in an ellipsis");
    assertFalse(detail.text().shortened(), "the size whole");
    assertEquals(T.text().muted(), detail.text().color());
    assertEquals(value.x() + value.width() + T.space().s(), detail.x(), 0.01f, "s after it");
    float right = cell.width() - T.space().s();
    assertTrue(detail.x() + detail.width() <= right + 0.01f, "inside the padding");

    El empty = find(app, "cell-0-7"); // no note, no detail
    assertEquals(List.of("NULL"), parts(empty));
    assertFalse(line(app, "cell-0-7").contains("value="));
  }

  @Test
  void aRightAlignedCellShowsItsDetailBeforeTheValueWhichStaysAtTheEdge() throws IOException {
    Orders orders = Orders.detailed(T, 8);
    App<Orders> app = start(orders);
    run(app, "wheel #rows 300 0\nframe");
    El cell = find(app, "cell-1-5");
    String amount = cell.children().getLast().text().string();
    assertEquals(List.of("EUR", amount), parts(cell));
    El value = cell.children().getLast();
    El detail = part(cell, "EUR");
    assertEquals(cell.width() - T.space().s(), value.x() + value.width(), 0.5f);
    assertEquals(detail.x() + detail.width() + T.space().s(), value.x(), 0.01f);
    assertEquals(List.of("NULL"), parts(find(app, "cell-5-5")), "no detail beside NULL here");
  }

  @Test
  void inACellTooNarrowForTheDetailAndAFirstCharacterTheDetailGivesWayEntirely()
      throws IOException {
    Orders orders = Orders.detailed(T, 8);
    float least = DataGrid.natural(DataGrid.text(T, false, "C" + Text.ELLIPSIS, 0));
    float size = DataGrid.natural(DataGrid.text(T, false, SIZE, 0));
    float stroke = T.size().stroke();
    float pad = T.space().s();
    orders.widths[7] = (float) Math.ceil(stroke + 3 * pad + size + least); // just room for both
    App<Orders> app = start(orders);
    run(app, "wheel #rows 1000 0\nframe");
    El cell = find(app, "cell-1-7");
    assertEquals(List.of(Orders.NOTE, SIZE), parts(cell), "both, the note cut after its C");
    El note = part(cell, Orders.NOTE);
    assertTrue(note.text().shortened() && note.width() >= least - 0.5f, "" + note.width());

    orders.widths[7] -= 2; // two pixels less: the size gives way, the note has the cell
    run(app, "frame");
    cell = find(app, "cell-1-7");
    assertEquals(List.of(Orders.NOTE), parts(cell));
    assertTrue(line(app, "cell-1-7").contains(" value=\"" + SIZE + "\""), "the dump still says it");

    run(app, "move #cell-1-7\nframe\nadvance 0.5\nframe"); // the tooltip names both
    assertTrue(
        app.tree().dump().contains("role=tooltip label=\"" + Orders.NOTE + " " + SIZE + "\""),
        app.tree().dump());
  }

  @Test
  void theTooltipOfACutValueNamesTheValueAndTheDetail() throws IOException {
    Orders orders = Orders.detailed(T, 8);
    App<Orders> app = start(orders);
    run(app, "wheel #rows 1000 0\nframe\nmove #cell-1-7\nframe\nadvance 0.5\nframe");
    assertTrue(
        app.tree().dump().contains("role=tooltip label=\"" + Orders.NOTE + " " + SIZE + "\""));
    run(app, "move #cell-1-6\nframe\nadvance 1\nframe"); // a time that fits
    assertFalse(app.tree().dump().contains("role=tooltip"));
  }

  @Test
  void aDetailStandsAfterAnIconBeforeTheTextAndAfterOneInItsPlace() throws IOException {
    Orders orders = Orders.detailed(T, 8);
    orders.details.put(1, i -> "#" + i); // after the link before the customer's id
    orders.details.put(3, i -> "paid?"); // after the check alone
    orders.widths[3] = 120;
    App<Orders> app = start(orders);
    El link = find(app, "cell-2-1"); // right-aligned: detail, link, value
    assertEquals(
        List.of("#2", "link", String.valueOf(1 + 2 * 7919 % 5000)), parts(link), link.dump());
    assertEquals(List.of("checkmark", "paid?"), parts(find(app, "cell-0-3")));
    assertTrue(line(app, "cell-0-3").contains("label=\"true\""), "the value an icon stands for");
    assertTrue(line(app, "cell-0-3").contains("value=\"paid?\""));
  }

  @Test
  void aSelectedCellKeepsItsDetailInMutedTextAndACopyTakesTheValueAlone() throws IOException {
    Orders orders = Orders.detailed(T, 8);
    App<Orders> app = start(orders);
    run(
        app,
        "wheel #rows 1000 0\nframe\npress #cell-1-7\npress #cell-1-6 shift\nkey C control\nframe");
    assertTrue(line(app, "cell-1-7").contains(" selected "), line(app, "cell-1-7"));
    assertEquals(T.text().muted(), part(find(app, "cell-1-7"), SIZE).text().color());
    String created = find(app, "cell-1-6").children().getLast().text().string();
    assertEquals(created + "\t" + Orders.NOTE, app.clipboard().read());
    assertEquals(List.of(new Range(1, 6, 1, 7)), orders.selection.ranges());
  }

  @Test
  void theEditorEditsTheValueAloneAndCoversTheDetail() throws IOException {
    Orders orders = Orders.detailed(T, 8);
    orders.editable = true;
    orders.more = orders.more.andThen(o -> o.onCommit((row, column, value) -> null))::apply;
    App<Orders> app = start(orders);
    run(app, "wheel #rows 1000 0\nframe\npress #cell-1-7 clicks 2\nframe");
    String dump = app.tree().dump();
    String afterCell = dump.substring(dump.indexOf("#cell-1-7 "));
    float[] detail = rect(afterCell
        .lines()
        .filter(l -> l.contains("\"" + SIZE + "\""))
        .findFirst()
        .orElseThrow());
    float[] editor = rect(line(app, "editor"));
    assertTrue(
        editor[0] <= detail[0] && editor[0] + editor[2] >= detail[0] + detail[2],
        "the editor over the size");
    assertEquals(0xFF, find(app, "editor").fill() >>> 24, "opaque");
    assertTrue(find(app, "editor").dump().contains("\"" + Orders.NOTE + "\""), "the value alone");
  }

  /** The rectangle of a line of the dump: x, y, width, height in the window. */
  private static float[] rect(String line) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            " (-?[\\d.]+),(-?[\\d.]+) ([\\d.]+)x([\\d.]+)")
        .matcher(line);
    assertTrue(m.find(), line);
    return new float[] {
      Float.parseFloat(m.group(1)),
      Float.parseFloat(m.group(2)),
      Float.parseFloat(m.group(3)),
      Float.parseFloat(m.group(4))
    };
  }

  // Images

  /** details.txt. */
  @ParameterizedTest
  @CsvSource({"dark, 2", "light, 2"})
  void looksAsStored(String name, int scale) throws IOException {
    Orders orders = Orders.detailed(name.equals("dark") ? Theme.DARK : Theme.LIGHT, 8);
    App<Orders> app = orders.app();
    String script = Files.readString(Path.of("src/test/resources/scripts/details.txt"));
    List<Path> files = Script.run("size 900 240 scale " + scale + "\n" + script, app, out);
    Golden.assertSnaps("grid-details-" + name + "-" + scale + "x-", files, "details");
    assertEquals(2, files.size());
  }
}
