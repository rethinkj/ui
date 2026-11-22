package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.DataType;
import com.rethinkj.ui.kit.DataGrid.Range;
import com.rethinkj.ui.kit.DataGrid.Summary;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Selection summaries and their status bar groups ({@link DataGrid#summary}). */
class GridSummaryTest {

  private static List<Column> sampleColumns() {
    String[] names = {"Ada", "Grace", "Linus", "Ken"};
    String[] qty = {"2", null, "4", "bad"};
    String[] amount = {"100.00", "1075.00", "", "25.50"};
    return List.of(
        new Column("name", 100, DataType.TEXT, r -> names[r]),
        new Column("quantity", 80, DataType.INTEGER, r -> qty[r]),
        new Column("amount", 100, DataType.DECIMAL, r -> amount[r]));
  }

  @Test
  void summaryHandlesMixedOverlappingWholeRowEmptyAndBoundedSelections() {
    List<Column> columns = sampleColumns();

    // Mixed numeric and text columns, skipping NULL, empty and invalid cells; overlapping ranges
    // count each cell once.
    Summary mixed =
        DataGrid.summary(columns, List.of(new Range(0, 0, 2, 2), new Range(0, 2, 1, 2)), 4, 100);
    // Rows 0..2, cols 0..2:
    // qty: 2, null, 4 -> 2, 4
    // amount: 100.00, 1075.00, "" -> 100.00, 1075.00
    assertEquals(4, mixed.count());
    assertEquals(new BigDecimal("1181.00"), mixed.sum());
    assertEquals(
        new BigDecimal("1181.00").divide(BigDecimal.valueOf(4), MathContext.DECIMAL64),
        mixed.average());
    assertEquals(new BigDecimal("2"), mixed.minimum());
    assertEquals(new BigDecimal("1075.00"), mixed.maximum());

    // Two decimal cells alone preserve exact scale in average (587.50).
    Summary pair = DataGrid.summary(columns, List.of(new Range(0, 2, 1, 2)), 4, 100);
    assertEquals(2, pair.count());
    assertEquals(new BigDecimal("1175.00"), pair.sum());
    assertEquals(new BigDecimal("587.50"), pair.average());
    assertEquals(new BigDecimal("100.00"), pair.minimum());
    assertEquals(new BigDecimal("1075.00"), pair.maximum());

    // An average that ends is exact, also beyond a double's digits.
    List<Column> big =
        List.of(new Column("n", 80, DataType.INTEGER, r -> String.valueOf(Long.MAX_VALUE)));
    assertEquals(
        new BigDecimal(Long.MAX_VALUE),
        DataGrid.summary(big, List.of(new Range(0, 0, 0, 0)), 1, 100).average());

    // Whole-row range (Range.ALL) and rows past rowCount (such as an append row) are clamped.
    Summary whole = DataGrid.summary(columns, List.of(new Range(0, 0, 10, Range.ALL)), 4, 100);
    assertEquals(5, whole.count());
    assertEquals(new BigDecimal("1206.50"), whole.sum());
    assertEquals(new BigDecimal("2"), whole.minimum());
    assertEquals(new BigDecimal("1075.00"), whole.maximum());

    // Empty selection and non-numeric-only selection produce count 0 and null stats.
    Summary empty = DataGrid.summary(columns, List.of(), 4, 100);
    assertEquals(new Summary(0, null, null, null, null), empty);

    Summary textOnly = DataGrid.summary(columns, List.of(new Range(0, 0, 3, 0)), 4, 100);
    assertEquals(new Summary(0, null, null, null, null), textOnly);

    Summary appendOnly = DataGrid.summary(columns, List.of(new Range(4, 2, 4, 2)), 4, 100);
    assertEquals(new Summary(0, null, null, null, null), appendOnly);

    // Over limit returns null without reading any cell.
    AtomicInteger reads = new AtomicInteger();
    List<Column> huge = List.of(new Column("n", 80, DataType.INTEGER, r -> {
      reads.incrementAndGet();
      return String.valueOf(r);
    }));
    assertNull(
        DataGrid.summary(huge, List.of(new Range(0, 0, 999_999, Range.ALL)), 1_000_000, 100_000));
    assertEquals(0, reads.get());
  }

  @Test
  void summaryGroupsFormatStatusBarPartsInCurrentTexts() {
    TextLibraries.assumeLoaded();
    assertTrue(DataGrid.summaryGroups(null).isEmpty());
    assertTrue(DataGrid.summaryGroups(new Summary(0, null, null, null, null)).isEmpty());

    Summary pair = new Summary(
        2,
        new BigDecimal("1175.00"),
        new BigDecimal("587.50"),
        new BigDecimal("100.00"),
        new BigDecimal("1075.00"));
    List<El> english = DataGrid.summaryGroups(pair);
    assertEquals(
        List.of("Count 2", "Sum 1175.00", "Avg 587.50", "Min 100.00", "Max 1075.00"),
        english.stream().map(g -> g.children().getFirst().label()).toList());

    List<El> german = Texts.CURRENT.with(new GermanTexts(), () -> DataGrid.summaryGroups(pair));
    assertEquals(
        List.of("Anzahl 2", "Summe 1175.00", "Mittel 587.50", "Min 100.00", "Max 1075.00"),
        german.stream().map(g -> g.children().getFirst().label()).toList());
  }
}
