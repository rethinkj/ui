package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.diagram.git.Change;
import com.rethinkj.ui.diagram.git.Commit;
import com.rethinkj.ui.diagram.git.GitLog;
import com.rethinkj.ui.diagram.git.Ref;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitHistoryTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-24T12:00:00Z"), ZoneOffset.UTC);

  private static final String NAME_STATUS = """
      M\tsrc/main/java/com/example/app/TemplateCache.java
      A\tsrc/test/java/com/example/app/TemplateCacheTest.java
      R100\tsrc/main/java/com/example/app/RawLoader.java\tsrc/main/java/com/example/app/TemplateLoader.java
      """;

  private static final String NUMSTAT = """
      10\t1\tsrc/main/java/com/example/app/TemplateCache.java
      8\t0\tsrc/test/java/com/example/app/TemplateCacheTest.java
      1\t1\tsrc/main/java/com/example/app/{RawLoader.java => TemplateLoader.java}
      """;

  private static final String OLD_CACHE = """
      final class TemplateCache {
        private final TemplateLoader loader;

        Template load(Path path) throws IOException {
          return Template.parse(loader.read(path));
        }
      }""";

  private static final String NEW_CACHE = """
      final class TemplateCache {
        private final TemplateLoader loader;
        private final Map<Path, Entry> entries = new HashMap<>();

        Template load(Path path) throws IOException {
          byte[] bytes = loader.read(path);
          long checksum = checksum(bytes);
          Entry cached = entries.get(path);
          if (cached != null && cached.checksum() == checksum) {
            return cached.template();
          }
          Template parsed = Template.parse(bytes);
          entries.put(path, new Entry(checksum, parsed));
          return parsed;
        }
      }""";

  /** Runs {@link GitHistory} on stored git output, without git; see {@link Snaps#check}. */
  @Test
  void theFirstScreenShowsTheLogDetailsAndDiff() throws IOException {
    Snaps.check("git-history", args -> GitHistory.run(args, new Stored(), CLOCK));
    assertTrue(Snaps.line("git-history", "git-history", "#row-0")
        .contains("Cache parsed templates by checksum"));
    assertTrue(Snaps.line("git-history", "git-history", "#file-0").contains("TemplateCache.java"));
  }

  @Test
  void gitsOutputIsRead() throws IOException {
    List<Commit> commits = GitHistory.parseLog(stored("log.txt"));
    assertEquals(18, commits.size());
    assertEquals(2, commits.get(2).parents().size(), "a merge");
    assertTrue(commits.getLast().parents().isEmpty(), "the root");

    List<Ref> refs = GitHistory.parseRefs(stored("refs.txt"), null);
    assertEquals(10, refs.size(), "without origin/HEAD");
    assertEquals(new Ref("main", Ref.Kind.HEAD, commits.getFirst().id()), refs.getFirst());
    assertEquals(
        List.of(new Ref("HEAD", Ref.Kind.HEAD, "d4a6c8e")), GitHistory.parseRefs("", "d4a6c8e"));
    assertEquals(
        List.of(commits.getFirst().id()), GitHistory.tips(refs, GitLog.Scope.branch("main")));
    assertEquals(6, GitHistory.tips(refs, GitLog.Scope.ALL).size(), "each commit once");

    List<Change> changes = GitHistory.parseChanges(
        NAME_STATUS + "D\tassets/logo.png\n", NUMSTAT + "-\t-\tassets/logo.png\n");
    assertEquals(
        List.of(
            new Change(
                Change.Status.MODIFIED,
                "src/main/java/com/example/app/TemplateCache.java",
                null,
                10,
                1),
            new Change(
                Change.Status.ADDED,
                "src/test/java/com/example/app/TemplateCacheTest.java",
                null,
                8,
                0),
            new Change(
                Change.Status.RENAMED,
                "src/main/java/com/example/app/TemplateLoader.java",
                "src/main/java/com/example/app/RawLoader.java",
                1,
                1),
            new Change(Change.Status.DELETED, "assets/logo.png", null, 0, 0)),
        changes);
  }

  private static String stored(String name) throws IOException {
    try (InputStream in = GitHistoryTest.class.getResourceAsStream("/git-history/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** The stored output of git for a small repository, in one page. */
  private static final class Stored implements GitHistory.Source {

    @Override
    public String name() {
      return "sample-repo";
    }

    @Override
    public int count(List<String> tips, boolean firstParent) throws IOException {
      return GitHistory.parseLog(stored("log.txt")).size();
    }

    @Override
    public List<Ref> refs() throws IOException {
      return GitHistory.parseRefs(stored("refs.txt"), null);
    }

    @Override
    public List<Commit> commits(List<String> tips, boolean firstParent, int skip, int limit)
        throws IOException {
      return skip == 0 ? GitHistory.parseLog(stored("log.txt")) : List.of();
    }

    @Override
    public GitHistory.Details details(Commit commit) {
      return new GitHistory.Details(
          "Avoid re-parsing unchanged templates on every render pass. Entries expire once the"
              + " checksum changes on disk.",
          GitHistory.parseChanges(NAME_STATUS, NUMSTAT));
    }

    @Override
    public GitHistory.FileDiff diff(Commit commit, Change change) {
      return new GitHistory.FileDiff(OLD_CACHE, NEW_CACHE);
    }
  }
}
