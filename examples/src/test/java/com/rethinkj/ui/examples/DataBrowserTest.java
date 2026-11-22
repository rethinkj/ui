package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Theme;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataBrowserTest {

  /** Runs the example's {@code main} on {@code data-browser.txt}; see {@link Snaps#check}. */
  @Test
  void theScriptShowsTheStoredFrames() throws IOException {
    Snaps.check("data-browser", DataBrowser::main);
  }

  /** Commit takes the value of an open editor first; a refused one stays there and stops it. */
  @Test
  void commitTakesTheValueBeingEditedFirst(@TempDir Path out) throws IOException {
    Snaps.assumeText();
    DataBrowser browser = new DataBrowser();
    App<DataBrowser> app =
        new App<>(browser, DataBrowser::view).provide(Theme.CURRENT, DataBrowser::theme);
    browser.app = app;
    Script.run("""
        size 1000 600
        frame
        press #cell-0-1
        key Enter
        frame
        key A control
        type "Zoe"
        frame
        key S control
        frame""", app, out);
    assertFalse(browser.selection.isEditing());
    assertTrue(browser.changes.isEmpty(), "written");
    assertEquals("1 change written", browser.toast);
    assertEquals("Zoe", browser.changes.cell(1).apply(0));

    browser.toast = null;
    Script.run("""
        size 1000 600
        key End
        key ArrowLeft
        key ArrowLeft
        key ArrowLeft
        frame
        type "2026-02-30 10:00"
        frame
        key S control
        frame""", app, out);
    assertTrue(browser.selection.isEditing(), "the editor stays with its message");
    assertEquals("2026-02-30 10:00", browser.selection.draft());
    assertTrue(app.tree().dump().contains("role=alert"), app.tree().dump());
    assertNull(browser.toast, "nothing written");
  }

  @Test
  void aCopyBeyondTheGridsLimitSaysSoInAToast(@TempDir Path out) throws IOException {
    Snaps.assumeText();
    DataBrowser browser = new DataBrowser();
    App<DataBrowser> app =
        new App<>(browser, DataBrowser::view).provide(Theme.CURRENT, DataBrowser::theme);
    browser.app = app;
    Script.run(
        "size 1000 600\nframe\nfocus #orders\nkey A control\nkey C control\nframe", app, out);
    String copied = app.clipboard().read();
    assertTrue(copied == null || copied.isEmpty(), "nothing copied");
    assertTrue(browser.toast.contains(" cells are too many to copy"), browser.toast);
    Script.run("size 1000 600\nframe", app, out);
    String dump = app.tree().dump();
    float toast = bottom(line(dump, "role=status label=\""));
    float status = top(line(dump, "text #status "));
    assertTrue(toast < status, "the toast leaves the card's footer seen: " + dump);
    Script.run("size 1000 600\nadvance 6\nframe\nframe", app, out);
    assertNull(browser.toast, "it closes itself");
  }

  @Test
  void inspectOpensANonModalDrawerForTheSelectedOrder(@TempDir Path out) throws IOException {
    Snaps.assumeText();
    DataBrowser browser = new DataBrowser();
    App<DataBrowser> app =
        new App<>(browser, DataBrowser::view).provide(Theme.CURRENT, DataBrowser::theme);
    browser.app = app;
    Script.run("size 1000 600 scale 2\nframe\n", app, out);
    String initial = app.tree().dump();
    assertTrue(line(initial, "#inspect ").contains(" disabled"), "disabled with no selection");
    assertFalse(browser.inspecting);

    // Select the first row and open the non-modal row inspector.
    Script.run(
        "size 1000 600 scale 2\npress #cell-0-1\nframe\npress #inspect\nframe\nadvance 0.25\nframe",
        app,
        out);
    assertTrue(browser.inspecting);
    String opened = app.tree().dump();
    assertTrue(opened.contains("\noverlay edge right stretch on 0,0 1000x600\n"), opened);
    assertTrue(opened.contains("label=\"Order 1\""), opened);
    assertTrue(
        opened.contains("role=listitem label=\"customer\" ")
            && opened.contains("value=\"Ada Lovelace\""),
        opened);

    // Clicking another row while the non-modal drawer is open updates the drawer in place.
    Script.run("size 1000 600 scale 2\npress #cell-1-1\nframe\n", app, out);
    assertTrue(browser.inspecting, "non-modal drawer stays open");
    String updated = app.tree().dump();
    assertTrue(updated.contains("label=\"Order 2\""), updated);
    assertTrue(updated.contains("value=\"Grace Lovelace\""), updated);

    // Escape inside the drawer dismisses it.
    Script.run("size 1000 600 scale 2\nfocus #close\nkey Escape\nframe\n", app, out);
    assertFalse(browser.inspecting);
  }

  @Test
  void theTextColumnsRepeatAfterAPeriod() {
    for (int row = 0; row < DataBrowser.ROWS; row += 99_991) {
      assertEquals(DataBrowser.texts(row % DataBrowser.PERIOD), DataBrowser.texts(row), "" + row);
    }
  }

  /** In both orders, the filter's arithmetic shows the rows a test of every row would. */
  @Test
  void theFilterShowsExactlyTheOrdersThatMatch() {
    DataBrowser browser = new DataBrowser();
    int rows = 3 * DataBrowser.PERIOD; // the first rows suffice, as the columns repeat
    for (String filter : List.of("ada paid", " OSLO  cable", "")) {
      browser.filter(filter);
      List<Integer> expected = new ArrayList<>();
      for (int row = 0; row < rows; row++) {
        if (matches(row, filter)) {
          expected.add(row);
        }
      }
      assertEquals(expected.size() / 3 * (DataBrowser.ROWS / DataBrowser.PERIOD), browser.count());
      for (boolean descending : new boolean[] {false, true}) {
        browser.descending = descending;
        List<Integer> shown = new ArrayList<>();
        for (int k = 0; k < browser.count(); k++) {
          int row = browser.orderAt(descending ? browser.count() - 1 - k : k);
          if (row >= rows) {
            break;
          }
          shown.add(row);
          assertEquals(descending ? browser.count() - 1 - k : k, browser.placeOf(row));
        }
        assertEquals(expected, shown, filter);
      }
      browser.descending = false;
    }
    browser.filter("ada");
    assertEquals(-1, browser.placeOf(1), "Grace is not shown");
  }

  private static boolean matches(int row, String filter) {
    for (String word : filter.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
      boolean found = false;
      for (String text : DataBrowser.texts(row)) {
        found |= text.toLowerCase(Locale.ROOT).contains(word);
      }
      if (!found) {
        return false;
      }
    }
    return true;
  }

  private static String line(String dump, String part) {
    return dump.lines().filter(l -> l.contains(part)).findFirst().orElseThrow();
  }

  /** The top and the bottom of a dump line's rectangle, after its quoted parts. */
  private static float top(String line) {
    return rect(line)[1];
  }

  private static float bottom(String line) {
    float[] r = rect(line);
    return r[1] + r[3];
  }

  private static float[] rect(String line) {
    var m = java.util.regex.Pattern.compile(" (-?[\\d.]+),(-?[\\d.]+) ([\\d.]+)x([\\d.]+)")
        .matcher(line.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\""));
    assertTrue(m.find(), line);
    return new float[] {
      Float.parseFloat(m.group(1)),
      Float.parseFloat(m.group(2)),
      Float.parseFloat(m.group(3)),
      Float.parseFloat(m.group(4))
    };
  }
}
