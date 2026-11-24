package com.rethinkj.ui.diagram.git;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.diagram.Golden;
import com.rethinkj.ui.kit.Theme;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link GraphCell} and {@link GraphStyle}: a 15-row commit graph with HEAD, a fork, a merge, an
 * octopus merge, a selected row and a faded lane, verified against its stored image.
 */
class GraphCellTest {

  private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");

  @TempDir
  Path out;

  private static Commit commit(String id, String... parents) {
    return new Commit(id, List.of(parents), id, "alice", TIME);
  }

  private static CommitGraph history() {
    CommitGraph graph = new CommitGraph();
    graph.append(List.of(
        commit("c15", "c14"), // 0: main tip
        commit("c14", "c13"), // 1: HEAD (incoming and outgoing line)
        commit("c13", "c11", "c12"), // 2: 2-parent merge
        commit("c12", "c10"), // 3: merged branch
        commit("c11", "c9"), // 4: main
        commit("c10", "c9"), // 5: curves into c9
        commit("c9", "c8"), // 6: selected row
        commit("c8", "c5", "c7", "c6"), // 7: 3-parent octopus merge
        commit("c7", "c4"), // 8: octopus branch 1
        commit("c6", "c4"), // 9: octopus branch 2 (faded lane)
        commit("tip", "c3"), // 10: fork (independent branch tip)
        commit("c5", "c4"), // 11: main
        commit("c4", "c2"), // 12: converges octopus branches
        commit("c3", "c2"), // 13: fork curves 3 lanes into root
        commit("c2"))); // 14: root commit
    return graph;
  }

  @Test
  void drawsFifteenRowGraphAsStored() throws IOException {
    CommitGraph graph = history();
    assertEquals(15, graph.size());
    assertEquals(4, graph.width());
    int fadedChain = graph.color(9);

    App<CommitGraph> app = new App<>(graph, g -> {
      Theme theme = Theme.CURRENT.get();
      GraphStyle style = GraphStyle.CURRENT.get();
      El rows = col().wFull().hFull();
      for (int row = 0; row < g.size(); row++) {
        boolean head = row == 1;
        boolean selected = row == 6;
        int background = selected ? theme.grid().selected() : theme.surface().panel();
        rows.child(el().wFull()
            .h(style.rowHeight())
            .fill(background)
            .child(GraphCell.cell(g, row, head, selected, background, c -> c == fadedChain)));
      }
      return rows;
    });
    app.provide(Theme.CURRENT, g -> Theme.DARK);

    List<Path> files = Script.run("""
        size 120 480 scale 2
        frame
        snap view
        """, app, out);
    Golden.assertSnaps("graph-cell-dark-", files, "view");
  }
}
