package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class DataBrowserTest {

  /** Runs the example's {@code main} on {@code data-browser.txt}; see {@link Snaps#check}. */
  @Test
  void theScriptShowsTheStoredFrames() throws IOException {
    Snaps.check("data-browser", DataBrowser::main);
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
    for (String filter : List.of("ada paid", " OSLO  cable", "shop.example gift", "xyz", "")) {
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
}
