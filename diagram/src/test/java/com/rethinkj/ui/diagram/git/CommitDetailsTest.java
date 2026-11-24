package com.rethinkj.ui.diagram.git;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.diagram.Golden;
import com.rethinkj.ui.diagram.git.Change.Status;
import com.rethinkj.ui.diagram.git.Ref.Kind;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link CommitDetails}: golden rendering of a single commit (body, parents, refs, all four change
 * statuses, diff slot) and a range comparison, plus jumping to a parent commit on chip press.
 */
class CommitDetailsTest {

  private static final Instant NOW = Instant.parse("2026-11-24T14:30:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @TempDir
  Path out;

  private static void assumeText() {
    try {
      Font.sans();
    } catch (UnsatisfiedLinkError e) {
      assumeTrue(false, "Skipped: FreeType and HarfBuzz cannot be loaded. " + e.getMessage());
    }
  }

  private static Commit commit(
      String id, List<String> parents, String author, long minutesAgo, String subject) {
    return new Commit(id, parents, subject, author, NOW.minus(Duration.ofMinutes(minutesAgo)));
  }

  private static List<Commit> sampleCommits() {
    return List.of(
        commit(
            "a41f9c2",
            List.of("7be3d10"),
            "Mara Lindqvist",
            38,
            "Show query duration in the status bar"),
        commit("9d2e6b1", List.of("c3a8f40"), "Jonas Weber", 124, "Export the diagram as SVG"),
        commit(
            "7be3d10",
            List.of("e5c1a77", "2f9b8d4"),
            "Priya Raman",
            190,
            "Merge branch 'fix/null-timestamps'"),
        commit(
            "6c0d5a8",
            List.of("b81e2f3"),
            "Aiko Tanaka",
            300,
            "Keep the last fifty statements per connection"),
        commit(
            "2f9b8d4",
            List.of("d47a1c9"),
            "Priya Raman",
            1500,
            "Render NULL timestamps as an empty cell"),
        commit(
            "c3a8f40",
            List.of("ff31b72"),
            "Jonas Weber",
            1620,
            "Route edges around frames on export"),
        commit(
            "b81e2f3",
            List.of("5ea9c61", "e5c1a77"),
            "Aiko Tanaka",
            2900,
            "Merge branch 'main' into feature/query-history"),
        commit("e5c1a77", List.of("0b4c7e2"), "Luis Ortega", 3100, "Merge branch 'release/0.9'"),
        commit(
            "d47a1c9",
            List.of("0b4c7e2"),
            "Priya Raman",
            4400,
            "Add a failing test for NULL timestamps"),
        commit("0b4c7e2", List.of(), "Mara Lindqvist", 7500, "Bump wgpu-native to 24.0.3"));
  }

  private static List<Ref> sampleRefs() {
    return List.of(
        new Ref("main", Kind.HEAD, "a41f9c2"),
        new Ref("origin/main", Kind.REMOTE, "7be3d10"),
        new Ref("v0.9.2", Kind.TAG, "7be3d10"),
        new Ref("release/0.9", Kind.BRANCH, "7be3d10"),
        new Ref("v0.9.2-rc1", Kind.TAG, "7be3d10"),
        new Ref("feature/query-history", Kind.BRANCH, "6c0d5a8"));
  }

  private static List<Change> sampleChanges() {
    return List.of(
        new Change(
            Status.MODIFIED, "console/src/main/java/com/example/console/Cells.java", null, 24, 9),
        new Change(
            Status.ADDED, "console/src/test/java/com/example/console/CellsTest.java", null, 48, 0),
        new Change(
            Status.RENAMED,
            "console/src/main/java/com/example/console/TimestampFormat.java",
            "console/src/main/java/com/example/console/TimeFormat.java",
            6,
            4),
        new Change(
            Status.DELETED,
            "console/src/main/java/com/example/console/LegacyCells.java",
            null,
            0,
            37));
  }

  private static final class Harness {
    final GitLog.State log = new GitLog.State(CLOCK);
    final List<Commit> commits = sampleCommits();
    final List<Ref> refs = sampleRefs();
    final List<Change> changes = sampleChanges();
    Change chosen = changes.getFirst();
    boolean withLog;

    El panel() {
      Theme theme = Theme.CURRENT.get();
      if (log.hasRange()) {
        return CommitDetails.details(
            log,
            commits,
            refs,
            CommitDetails.options().changes(changes.subList(0, 2)),
            Button.button(Button.Variant.PRIMARY, Symbol.OPEN, "Open comparison", () -> {}),
            Button.button(Button.Variant.SECONDARY, Symbol.COPY, "Copy range", () -> {}));
      }
      El diffPlaceholder = row()
          .key("diff-slot")
          .h(64)
          .center()
          .radius(theme.radius().medium())
          .fill(theme.surface().raised())
          .border(theme.size().stroke(), theme.border().subtle())
          .child(Label.label(Label.Variant.MUTED, "Diff of " + chosen.path()));
      return CommitDetails.details(
          log,
          commits,
          refs,
          CommitDetails.options()
              .body(
                  "A NULL in a timestamp column threw while formatting. It now renders as an empty,"
                      + " muted cell and shares the formatter across export paths.")
              .changes(changes)
              .chosen(chosen)
              .onChoose(change -> chosen = change)
              .diff(diffPlaceholder));
    }

    El view() {
      Theme theme = Theme.CURRENT.get();
      El content =
          withLog ? GitLog.gitLog(log, commits, refs, GitLog.options().details(panel())) : panel();
      return col()
          .wFull()
          .hFull()
          .fill(theme.surface().window())
          .padding(theme.space().l())
          .child(content.grow(1));
    }
  }

  private static App<Harness> app(Harness harness) {
    App<Harness> app = new App<>(harness, Harness::view);
    app.provide(Theme.CURRENT, ignored -> Theme.DARK);
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  @Test
  void rendersCommitAndRangeAsStored() throws IOException {
    assumeText();
    Harness commitHarness = new Harness();
    commitHarness.log.select(2); // merge commit 7be3d10 with two parents and two refs
    List<Path> commitFiles = Script.run("""
        size 680 500 scale 2
        frame
        snap commit
        """, app(commitHarness), out);
    Golden.assertSnaps("commit-details-dark-", commitFiles, "commit");

    Harness rangeHarness = new Harness();
    rangeHarness.log.selectRange(1, 4); // 4 commits, 3 distinct authors
    List<Path> rangeFiles = Script.run("""
        size 680 310 scale 2
        frame
        snap range
        """, app(rangeHarness), out);
    Golden.assertSnaps("commit-details-dark-", rangeFiles, "range");

    String commitTree = Files.readString(out.resolve("commit.tree.txt"));
    assertTrue(commitTree.contains("#parent-e5c1a77 role=button"), commitTree);
    assertTrue(commitTree.contains("#parent-2f9b8d4 role=button"), commitTree);
    assertTrue(commitTree.contains("#file-0 role=button"), commitTree);
    assertTrue(commitTree.contains("#diff-slot"), commitTree);
  }

  @Test
  void pressingParentChipSelectsParentRowInLog() throws IOException {
    assumeText();
    Harness harness = new Harness();
    harness.withLog = true;
    harness.log.select(2); // 7be3d10 -> parents e5c1a77 (row 7), 2f9b8d4 (row 4)

    Script.run("""
        size 860 620 scale 2
        frame
        press #parent-2f9b8d4
        frame
        """, app(harness), out);

    assertEquals(4, harness.log.cursor());
    assertEquals(4, harness.log.anchor());
  }
}
