package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.diagram.git.GitLog.gitLog;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.El;
import com.rethinkj.ui.diagram.git.Commit;
import com.rethinkj.ui.diagram.git.GitLog;
import com.rethinkj.ui.diagram.git.Ref;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** The example of the GitLog page: a multi-branch commit history with selection and a range. */
final class GitLogs {

  private static final Instant NOW = Instant.parse("2026-11-24T14:30:00Z");

  // docs:start git-log
  final GitLog.State log = new GitLog.State(Clock.fixed(NOW, ZoneOffset.UTC));
  final List<Commit> commits = sampleCommits();
  final List<Ref> refs = sampleRefs();
  Commit opened;

  static El view(GitLogs state) {
    Theme theme = Theme.CURRENT.get();
    return col()
        .wFull()
        .hFull()
        .fill(theme.surface().window())
        .padding(theme.space().l())
        .child(gitLog(
                state.log,
                state.commits,
                state.refs,
                GitLog.options().onOpen(state::open).status("1 ahead of origin/main"),
                button(Button.Variant.SECONDARY, Symbol.ARROW_DOWNLOAD, "Fetch", state::fetch))
            .grow(1));
  }

  void open(Commit commit) {
    opened = commit;
  }

  void fetch() {
    // reload commits and refs from the repository
  }
  // docs:end git-log

  // docs:start git-log-paging
  static final class Paged {
    static final int PAGE = 200;

    final GitLog.State log = new GitLog.State();
    final List<Commit> history; // stands in for the repository, which git reads page by page
    final List<Commit> commits = new ArrayList<>();
    final List<Ref> refs = List.of();

    Paged(List<Commit> history) {
      this.history = history;
    }

    static El view(Paged state) {
      boolean more = state.commits.size() < state.history.size();
      return gitLog(
              state.log,
              state.commits,
              state.refs,
              GitLog.options().onMore(more ? state::loadMore : null).total(state.history.size()))
          .grow(1);
    }

    void loadMore() {
      int end = Math.min(commits.size() + PAGE, history.size());
      commits.addAll(history.subList(commits.size(), end));
    }
  }
  // docs:end git-log-paging

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
        commit(
            "e5c1a77",
            List.of("0b4c7e2", "88aa1f0"),
            "Luis Ortega",
            3100,
            "Merge branch 'release/0.9'"),
        commit(
            "d47a1c9",
            List.of("0b4c7e2"),
            "Priya Raman",
            4400,
            "Add a failing test for NULL timestamps"),
        commit(
            "88aa1f0",
            List.of("3e7d2b5"),
            "Luis Ortega",
            4700,
            "Fix crash when a connection drops mid-query"),
        commit(
            "5ea9c61",
            List.of("41d0e93"),
            "Aiko Tanaka",
            5900,
            "Search the history with fuzzy matching"),
        commit(
            "ff31b72",
            List.of("0b4c7e2"),
            "Jonas Weber",
            7300,
            "Add an export dialog for diagrams"),
        commit("0b4c7e2", List.of("6f2a0d8"), "Mara Lindqvist", 7500, "Bump wgpu-native to 24.0.3"),
        commit(
            "41d0e93",
            List.of("6f2a0d8"),
            "Aiko Tanaka",
            8900,
            "Store executed statements in a history table"),
        commit("3e7d2b5", List.of("a90c344"), "Luis Ortega", 11800, "Prepare release 0.9.0"),
        commit(
            "6f2a0d8",
            List.of("1d8e5f7"),
            "Mara Lindqvist",
            13300,
            "Speed up the first frame by caching glyph atlases"),
        commit("a90c344", List.of("1d8e5f7"), "Luis Ortega", 14600, "Freeze the 0.9 API"),
        commit("1d8e5f7", List.of(), "Mara Lindqvist", 16100, "Initialize the workspace"));
  }

  private static List<Ref> sampleRefs() {
    return List.of(
        new Ref("main", Ref.Kind.HEAD, "a41f9c2"),
        new Ref("feature/er-export", Ref.Kind.BRANCH, "9d2e6b1"),
        new Ref("origin/feature/er-export", Ref.Kind.REMOTE, "9d2e6b1"),
        new Ref("origin/main", Ref.Kind.REMOTE, "7be3d10"),
        new Ref("feature/query-history", Ref.Kind.BRANCH, "6c0d5a8"),
        new Ref("release/0.9", Ref.Kind.BRANCH, "88aa1f0"),
        new Ref("origin/release/0.9", Ref.Kind.REMOTE, "88aa1f0"),
        new Ref("v0.9.1", Ref.Kind.TAG, "88aa1f0"),
        new Ref("v0.9.0", Ref.Kind.TAG, "3e7d2b5"));
  }
}
