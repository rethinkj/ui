package com.rethinkj.ui.diagram.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.rethinkj.ui.diagram.git.CommitGraph.Line;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link CommitGraph}: linear history, fork, merge, octopus merge, branch tip in the middle, and
 * incremental paging matching a single-pass layout.
 */
class CommitGraphTest {

  private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");

  private static Commit commit(String id, String... parents) {
    return new Commit(id, List.of(parents), id, "alice", TIME);
  }

  private static List<String> linesOf(CommitGraph graph) {
    List<String> rows = new ArrayList<>(graph.size());
    for (int i = 0; i < graph.size(); i++) {
      rows.add(graph.text(i));
    }
    return rows;
  }

  @Test
  void linearHistoryUsesOneLaneAndOneColor() {
    CommitGraph graph = new CommitGraph();
    assertEquals(0, graph.size());
    assertEquals(0, graph.width());
    assertThrows(IndexOutOfBoundsException.class, () -> graph.column(0));

    graph.append(List.of());
    assertEquals(0, graph.size());

    graph.append(List.of(commit("c3", "c2"), commit("c2", "c1"), commit("c1")));

    assertEquals(3, graph.size());
    assertEquals(1, graph.width());
    assertEquals(List.of("●", "●", "●"), linesOf(graph));
    for (int row = 0; row < 3; row++) {
      assertEquals(0, graph.column(row));
      assertEquals(0, graph.color(row));
    }
    assertEquals(List.of(), graph.above(0));
    assertEquals(List.of(new Line(0, 0, 0)), graph.below(0));
    assertEquals(List.of(new Line(0, 0, 0)), graph.above(1));
    assertEquals(List.of(new Line(0, 0, 0)), graph.above(2));
    assertEquals(List.of(), graph.below(2));
  }

  @Test
  void forkAndBranchTipInTheMiddle() {
    // Two independent branch tips converge on base, with a third tip appearing in the middle whose
    // parent is not in the history yet.
    CommitGraph graph = new CommitGraph();
    graph.append(List.of(
        commit("m2", "m1"),
        commit("f1", "base"),
        commit("m1", "base"),
        commit("base"),
        commit("orphan", "missing")));

    assertEquals(2, graph.width());
    assertEquals(List.of("●", "│ ●", "● │", "● ╯", "●"), linesOf(graph));

    // f1 is a branch tip in the middle: nothing enters its column from above.
    assertEquals(1, graph.column(1));
    assertEquals(1, graph.color(1));
    assertEquals(List.of(new Line(0, 0, 0)), graph.above(1));

    // Straight lines come before curved lines when f1 converges into base.
    assertEquals(List.of(new Line(0, 0, 0), new Line(1, 0, 1)), graph.above(3));
    // A parent not in the history keeps its lane open below the last row.
    assertEquals(List.of(new Line(0, 0, 2)), graph.below(4));

    // When a branch tip's parent is the very next row, the turn happens on the tip's row.
    CommitGraph shortFork = new CommitGraph();
    shortFork.append(List.of(commit("b", "base"), commit("c", "base"), commit("base")));
    assertEquals(List.of("●", "├ ●", "●"), linesOf(shortFork));
  }

  @Test
  void mergeOpensLaneJoinsExistingAndReusesFreeLeftLane() {
    CommitGraph diamond = new CommitGraph();
    diamond.append(
        List.of(commit("m", "a", "b"), commit("b", "base"), commit("a", "base"), commit("base")));

    assertEquals(2, diamond.width());
    assertEquals(List.of("● ╮", "│ ●", "● │", "● ╯"), linesOf(diamond));
    assertEquals(List.of(new Line(0, 0, 0), new Line(0, 1, 1)), diamond.below(0));
    assertEquals(1, diamond.column(1));
    assertEquals(1, diamond.color(1));

    // Merge at column 1 with a lane passing at column 0: "│ ● ╮".
    CommitGraph offsetMerge = new CommitGraph();
    offsetMerge.append(List.of(
        commit("side", "base"),
        commit("m", "a", "b"),
        commit("b", "base"),
        commit("a", "base"),
        commit("base")));
    assertEquals(3, offsetMerge.width());
    assertEquals(List.of("●", "│ ● ╮", "│ │ ●", "├ ● │", "● ─ ╯"), linesOf(offsetMerge));

    // Second parent joins an already-open lane on the right ("● ┤") and a later merge opens a
    // freed lane on the left ("╭ ●").
    CommitGraph joinAndLeftOpen = new CommitGraph();
    joinAndLeftOpen.append(List.of(
        commit("m1", "m2", "p"),
        commit("m2", "end", "p"),
        commit("end"),
        commit("p", "p1", "p2"),
        commit("p1"),
        commit("p2")));
    assertEquals(List.of("● ╮", "● ┤", "● │", "╭ ●", "│ ●", "●"), linesOf(joinAndLeftOpen));
  }

  @Test
  void octopusMergeAndEmptyColumnBridge() {
    CommitGraph octo = new CommitGraph();
    octo.append(List.of(
        commit("octo", "a", "b", "c"),
        commit("c", "base"),
        commit("b", "base"),
        commit("a", "base"),
        commit("base")));

    assertEquals(3, octo.width());
    assertEquals(List.of("● ╮ ╮", "│ │ ●", "│ ● │", "● │ │", "● ╯ ╯"), linesOf(octo));
    assertEquals(List.of(new Line(0, 0, 0), new Line(0, 1, 1), new Line(0, 2, 2)), octo.below(0));

    // When the middle lane closes first, the outer lane bridges the empty column with "─".
    CommitGraph bridged = new CommitGraph();
    bridged.append(
        List.of(commit("octo", "a", "b", "c"), commit("b"), commit("a", "c"), commit("c")));
    assertEquals(List.of("● ╮ ╮", "│ ● │", "●   │", "● ─ ╯"), linesOf(bridged));
  }

  @Test
  void historyAppendedInTwoPagesEqualsTheWhole() {
    List<Commit> commits = List.of(
        commit("m", "a", "b"),
        commit("b", "c"),
        commit("tip", "d"),
        commit("a", "c"),
        commit("c", "x", "y", "d"),
        commit("y", "base"),
        commit("x", "base"),
        commit("d", "base"),
        commit("base", "unloaded"));

    CommitGraph whole = new CommitGraph();
    whole.append(commits);

    // Split right before "c" converges two lanes and opens an octopus merge.
    CommitGraph paged = new CommitGraph();
    paged.append(commits.subList(0, 4));
    assertEquals(4, paged.size());
    assertEquals(List.of(new Line(0, 0, 0), new Line(1, 1, 1), new Line(2, 2, 2)), paged.below(3));

    paged.append(commits.subList(4, commits.size()));

    assertEquals(whole.size(), paged.size());
    assertEquals(whole.width(), paged.width());
    assertEquals(linesOf(whole), linesOf(paged));
    for (int row = 0; row < whole.size(); row++) {
      assertEquals(whole.column(row), paged.column(row), "column at " + row);
      assertEquals(whole.color(row), paged.color(row), "color at " + row);
      assertEquals(whole.above(row), paged.above(row), "above at " + row);
      assertEquals(whole.below(row), paged.below(row), "below at " + row);
    }
  }
}
