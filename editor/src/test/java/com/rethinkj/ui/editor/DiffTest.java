package com.rethinkj.ui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link Diff}: insert, delete, modify, empty texts, newlines and fallback. */
class DiffTest {

  @Test
  void identicalAndEmptyTexts() {
    assertEquals(new Diff(0, 0, List.of()), Diff.lines("", ""));
    assertEquals(new Diff(2, 2, List.of()), Diff.lines("a\nb", "a\nb"));

    Diff addedAll = Diff.lines("", "a\nb");
    assertEquals(0, addedAll.oldLineCount());
    assertEquals(2, addedAll.newLineCount());
    assertEquals(List.of(new Diff.Hunk(Diff.Kind.ADDED, 0, 0, 0, 2, List.of())), addedAll.hunks());

    Diff deletedAll = Diff.lines("a\nb", "");
    assertEquals(2, deletedAll.oldLineCount());
    assertEquals(0, deletedAll.newLineCount());
    assertEquals(
        List.of(new Diff.Hunk(Diff.Kind.DELETED, 0, 2, 0, 0, List.of())), deletedAll.hunks());
  }

  @Test
  void insertDeleteAndModifyWithIntralineWordSpans() {
    String oldText = """
        SELECT id, total
        FROM orders
        -- filters
        WHERE status = 'pending'
        GROUP BY id
        ORDER BY id;""";
    String newText = """
        SELECT id, total, tax
        FROM orders
        JOIN customers c ON c.id = orders.customer_id
        -- filters
        WHERE status = 'paid'
        GROUP BY id""";
    Diff diff = Diff.lines(oldText, newText);
    assertEquals(6, diff.oldLineCount());
    assertEquals(6, diff.newLineCount());
    assertEquals(4, diff.hunks().size());

    // Line 0 modified: ", tax" inserted at end.
    Diff.Hunk h0 = diff.hunks().get(0);
    assertEquals(Diff.Kind.MODIFIED, h0.kind());
    assertEquals(0, h0.oldStart());
    assertEquals(1, h0.oldEnd());
    assertEquals(0, h0.newStart());
    assertEquals(1, h0.newEnd());
    assertEquals(1, h0.lineChanges().size());
    assertTrue(h0.lineChanges().get(0).oldSpans().isEmpty());
    assertEquals(
        List.of(new Diff.Span("SELECT id, total".length(), "SELECT id, total, tax".length())),
        h0.lineChanges().get(0).newSpans());

    // One line added after line 1.
    Diff.Hunk h1 = diff.hunks().get(1);
    assertEquals(Diff.Kind.ADDED, h1.kind());
    assertEquals(2, h1.oldStart());
    assertEquals(2, h1.oldEnd());
    assertEquals(2, h1.newStart());
    assertEquals(3, h1.newEnd());

    // Line 3 -> 4 modified: 'pending' -> 'paid'.
    Diff.Hunk h2 = diff.hunks().get(2);
    assertEquals(Diff.Kind.MODIFIED, h2.kind());
    Diff.LineChange whereChange = h2.lineChanges().get(0);
    int pendingStart = "WHERE status = '".length();
    assertEquals(
        List.of(new Diff.Span(pendingStart, pendingStart + "pending".length())),
        whereChange.oldSpans());
    assertEquals(
        List.of(new Diff.Span(pendingStart, pendingStart + "paid".length())),
        whereChange.newSpans());

    // Line 5 deleted: ORDER BY id;
    Diff.Hunk h3 = diff.hunks().get(3);
    assertEquals(Diff.Kind.DELETED, h3.kind());
    assertEquals(5, h3.oldStart());
    assertEquals(6, h3.oldEnd());
    assertEquals(6, h3.newStart());
    assertEquals(6, h3.newEnd());
  }

  @Test
  void noTrailingNewlineAndCrlfNormalization() {
    Diff trailingAdded = Diff.lines("a\nb", "a\nb\n");
    assertEquals(2, trailingAdded.oldLineCount());
    assertEquals(3, trailingAdded.newLineCount());
    assertEquals(
        List.of(new Diff.Hunk(Diff.Kind.ADDED, 2, 2, 2, 3, List.of())), trailingAdded.hunks());

    assertEquals(new Diff(2, 2, List.of()), Diff.lines("alpha\r\nbeta", "alpha\nbeta"));
  }

  @Test
  void exceedsMaxDistanceFallsBackToSingleModifiedHunk() {
    int n = Diff.MAX_DISTANCE + 20;
    StringBuilder oldText = new StringBuilder("head\n");
    StringBuilder newText = new StringBuilder("head\n");
    for (int i = 0; i < n; i++) {
      oldText.append("old_").append(i).append('\n');
      newText.append("new_").append(i).append('\n');
    }
    oldText.append("tail");
    newText.append("tail");

    Diff diff = Diff.lines(oldText.toString(), newText.toString());
    assertEquals(n + 2, diff.oldLineCount());
    assertEquals(n + 2, diff.newLineCount());
    assertEquals(1, diff.hunks().size());
    Diff.Hunk middle = diff.hunks().get(0);
    assertEquals(Diff.Kind.MODIFIED, middle.kind());
    assertEquals(1, middle.oldStart());
    assertEquals(n + 1, middle.oldEnd());
    assertEquals(1, middle.newStart());
    assertEquals(n + 1, middle.newEnd());
  }

  @Test
  void modifiedHunkPairsBestMatchingLinesAndSkipsUnrelatedOnes() {
    String oldText = "WHERE o.status = 'pending'\n  AND o.legacy = false";
    String newText = "JOIN customers c ON c.id = o.cid\nWHERE o.status = 'paid'";
    Diff diff = Diff.lines(oldText, newText);
    assertEquals(1, diff.hunks().size());
    Diff.Hunk hunk = diff.hunks().get(0);
    assertEquals(1, hunk.lineChanges().size(), "only the two WHERE lines pair");
    Diff.LineChange paired = hunk.lineChanges().get(0);
    assertEquals(0, paired.oldLine());
    assertEquals(1, paired.newLine());
    int start = "WHERE o.status = '".length();
    assertEquals(List.of(new Diff.Span(start, start + "pending".length())), paired.oldSpans());
    assertEquals(List.of(new Diff.Span(start, start + "paid".length())), paired.newSpans());
  }
}
