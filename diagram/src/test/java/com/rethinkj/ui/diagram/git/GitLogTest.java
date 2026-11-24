package com.rethinkj.ui.diagram.git;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.diagram.DiagramTexts;
import com.rethinkj.ui.diagram.GermanDiagramTexts;
import com.rethinkj.ui.diagram.Golden;
import com.rethinkj.ui.diagram.git.Ref.Kind;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Menu;
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
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link GitLog}: golden rendering with tree dump, selection and range keys, scrolling into view,
 * hover lane emphasis, incremental graph sync, and relative date formatting in English and German.
 */
class GitLogTest {

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
        commit(
            "1d8e5f7",
            List.of("c62b9e0", "4aa71d3"),
            "Mara Lindqvist",
            16100,
            "Merge branch 'feature/dark-mode'"),
        commit(
            "4aa71d3", List.of("92fe0b8"), "Priya Raman", 17400, "Follow the system's dark mode"),
        commit(
            "c62b9e0",
            List.of("5b3c1fa"),
            "Jonas Weber",
            18900,
            "Paginate result sets over 10,000 rows"),
        commit("92fe0b8", List.of("5b3c1fa"), "Priya Raman", 20200, "Add dark surface tokens"),
        commit(
            "5b3c1fa",
            List.of("e04d7a6"),
            "Luis Ortega",
            24600,
            "Reconnect automatically after sleep"),
        commit(
            "e04d7a6", List.of("7c19b2e"), "Mara Lindqvist", 27400, "Add a connection test button"),
        commit(
            "7c19b2e",
            List.of(),
            "Jonas Weber",
            30300,
            "Show column comments in the schema browser"));
  }

  private static List<Ref> sampleRefs() {
    return List.of(
        new Ref("main", Kind.HEAD, "a41f9c2"),
        new Ref("feature/er-export", Kind.BRANCH, "9d2e6b1"),
        new Ref("origin/feature/er-export", Kind.REMOTE, "9d2e6b1"),
        new Ref("origin/main", Kind.REMOTE, "7be3d10"),
        new Ref("feature/query-history", Kind.BRANCH, "6c0d5a8"),
        new Ref("release/0.9", Kind.BRANCH, "88aa1f0"),
        new Ref("origin/release/0.9", Kind.REMOTE, "88aa1f0"),
        new Ref("v0.9.1", Kind.TAG, "88aa1f0"),
        new Ref("v0.9.0", Kind.TAG, "3e7d2b5"),
        new Ref("v0.8.0", Kind.TAG, "5b3c1fa"));
  }

  private static final class Harness {
    final GitLog.State log = new GitLog.State(CLOCK);
    final List<Commit> commits;
    List<Ref> refs = sampleRefs();
    final List<String> opened = new ArrayList<>();
    Runnable onMore;
    int total = -1;
    Runnable onAction = () -> {};

    Harness() {
      this(sampleCommits());
    }

    Harness(List<Commit> commits) {
      this.commits = new ArrayList<>(commits);
    }

    El view() {
      Theme theme = Theme.CURRENT.get();
      return col()
          .wFull()
          .hFull()
          .fill(theme.surface().window())
          .padding(theme.space().l())
          .child(GitLog.gitLog(
                  log,
                  commits,
                  refs,
                  GitLog.options()
                      .onOpen(commit -> opened.add(commit.id()))
                      .onMore(onMore)
                      .total(total)
                      .status("1 ahead of origin/main"),
                  Button.button(
                      Button.Variant.SECONDARY,
                      Symbol.ARROW_DOWNLOAD,
                      "Fetch",
                      () -> onAction.run()))
              .grow(1));
    }
  }

  private static App<Harness> app(Harness harness) {
    App<Harness> app = new App<>(harness, Harness::view);
    app.provide(Theme.CURRENT, ignored -> Theme.DARK);
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  @Test
  void rendersHistoryAsStoredWithTreeDumpAndDateTooltip() throws IOException {
    assumeText();
    Harness harness = new Harness();
    List<Path> files = Script.run("""
        size 960 571 scale 2
        frame
        press #row-3
        frame
        snap view
        move #row-1
        frame
        snap hovered
        move 0,0
        frame
        snap unhovered
        move text="5 h ago"
        frame
        advance 0.6
        frame
        snap date-tip
        """, app(harness), out);

    Golden.assertSnaps("git-log-dark-", files, "view", "hovered");

    String viewTree = Files.readString(out.resolve("view.tree.txt"));
    assertTrue(
        viewTree.contains(
            "label=\"commit a41f9c2, Show query duration in the status bar, Mara Lindqvist, 38 min ago, HEAD → main\""),
        viewTree);
    String row3Line = viewTree
        .lines()
        .filter(line -> line.contains("#row-3 role=row"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(viewTree));
    assertTrue(
        row3Line.contains(
            "label=\"commit 6c0d5a8, Keep the last fifty statements per connection, Aiko Tanaka, 5 h ago, Branch feature/query-history\""),
        row3Line);
    assertTrue(row3Line.contains("selected current"), row3Line);
    assertTrue(viewTree.contains("label=\"" + harness.log.graph().text(3) + "\""), viewTree);

    // The stored hovered image shows the other lanes faded; leaving restores them.
    byte[] viewPng = Files.readAllBytes(out.resolve("view.png"));
    byte[] unhoveredPng = Files.readAllBytes(out.resolve("unhovered.png"));
    assertTrue(java.util.Arrays.equals(viewPng, unhoveredPng), "leaving should restore lanes");

    String tipTree = Files.readString(out.resolve("date-tip.tree.txt"));
    assertTrue(tipTree.contains("role=tooltip label=\"Nov 24, 2026, 09:30\""), tipTree);
  }

  @Test
  void selectionRangeKeysScrollIntoViewAndEnterOpen() throws IOException {
    assumeText();
    Harness harness = new Harness();
    // Viewport tall enough for ~6 rows so End / PageDown / Home exercise scrolling into view.
    Script.run("""
        size 860 320 scale 2
        frame
        press #row-1
        frame
        snap single
        press #row-3 shift
        frame
        snap shift-click
        key ArrowDown shift
        frame
        snap shift-down
        key ArrowUp shift
        key ArrowUp shift
        frame
        snap shift-up
        key ArrowDown
        frame
        snap arrow-down
        key Enter
        frame
        key PageDown
        frame
        snap page-down
        key End
        frame
        snap end
        wheel #rows 0 -400
        frame
        snap scrolled
        key End
        frame
        snap end-again
        key PageUp
        frame
        snap page-up
        key Home
        frame
        snap home
        """, app(harness), out);

    String single = Files.readString(out.resolve("single.tree.txt"));
    assertTrue(single.contains("#row-1 role=row") && single.contains("selected current"), single);

    String shiftClick = Files.readString(out.resolve("shift-click.tree.txt"));
    assertTrue(
        shiftClick.contains("#row-1 ") && shiftClick.contains("selected pos=2/24"), shiftClick);
    assertTrue(
        shiftClick.contains("#row-2 ") && shiftClick.contains("selected pos=3/24"), shiftClick);
    assertTrue(
        shiftClick.contains("#row-3 ") && shiftClick.contains("selected current pos=4/24"),
        shiftClick);

    String shiftDown = Files.readString(out.resolve("shift-down.tree.txt"));
    assertTrue(
        shiftDown.contains("#row-4 ") && shiftDown.contains("selected current pos=5/24"),
        shiftDown);

    String shiftUp = Files.readString(out.resolve("shift-up.tree.txt"));
    assertTrue(
        shiftUp.contains("#row-2 ") && shiftUp.contains("selected current pos=3/24"), shiftUp);

    String arrowDown = Files.readString(out.resolve("arrow-down.tree.txt"));
    assertTrue(
        arrowDown.contains("#row-3 ") && arrowDown.contains("selected current pos=4/24"),
        arrowDown);
    assertFalse(
        arrowDown.contains("#row-2 role=row") && arrowDown.contains("selected pos=3/24"),
        arrowDown);

    assertEquals(List.of("6c0d5a8"), harness.opened);

    String pageDown = Files.readString(out.resolve("page-down.tree.txt"));
    assertTrue(pageDown.contains("#row-8 ") && pageDown.contains("selected current"), pageDown);

    String end = Files.readString(out.resolve("end.tree.txt"));
    assertTrue(end.contains("#row-23 ") && end.contains("selected current pos=24/24"), end);

    String scrolled = Files.readString(out.resolve("scrolled.tree.txt"));
    assertFalse(scrolled.contains("#row-23 "), scrolled);
    String endAgain = Files.readString(out.resolve("end-again.tree.txt"));
    assertTrue(endAgain.contains("#row-23 "), endAgain);

    String pageUp = Files.readString(out.resolve("page-up.tree.txt"));
    assertTrue(pageUp.contains("#row-18 ") && pageUp.contains("selected current"), pageUp);

    String home = Files.readString(out.resolve("home.tree.txt"));
    assertTrue(home.contains("#row-0 ") && home.contains("selected current pos=1/24"), home);
    assertEquals(0, harness.log.cursor());
    assertEquals(0, harness.log.anchor());
    assertFalse(harness.log.hasRange());
  }

  @Test
  void filterJumpsMatchesHashPrefixAndGoToRef() throws IOException {
    assumeText();
    Harness harness = new Harness();
    // A ref at an unloaded commit is left out of Go to ref; a subject naming a later hash is passed
    // over by the first jump for that hash.
    List<Ref> refs = new ArrayList<>(harness.refs);
    refs.add(new Ref("stale/unloaded", Kind.BRANCH, "deadbeef"));
    harness.refs = refs;
    harness.commits.set(
        14, commit("3e7d2b5", List.of("a90c344"), "Luis Ortega", 11800, "Prepare to undo a90c344"));

    List<Path> files = Script.run("""
        size 960 571 scale 2
        frame
        press #row-0
        frame
        key F control
        frame
        type "null"
        frame
        key F3
        frame
        advance 0.5
        frame
        snap filtered
        key F3
        frame
        snap match-2
        key F3
        frame
        snap match-3
        key F3
        frame
        snap match-wrap
        key F3 shift
        frame
        snap match-prev
        key Enter shift
        frame
        snap match-shift-enter
        key Escape
        frame
        type "ortega"
        frame
        key Enter
        frame
        snap author-jump
        key Escape
        frame
        type "a90c3"
        frame
        key Enter
        frame
        snap hash-jump
        key Escape
        frame
        type "zzzzzz"
        frame
        snap no-matches
        key Escape
        frame
        key G control
        frame
        snap ref-popup
        type "0.8"
        frame
        key Enter
        frame
        snap ref-jump
        """, app(harness), out);

    Golden.assertSnaps("git-log-dark-", files, "filtered");

    String filtered = Files.readString(out.resolve("filtered.tree.txt"));
    assertTrue(filtered.contains("label=\"Filter commits\" \"null\""), filtered);
    assertTrue(filtered.contains("\"3 of 24\""), filtered);
    assertTrue(rowLine(filtered, 2).contains("selected current"), filtered);

    String match2 = Files.readString(out.resolve("match-2.tree.txt"));
    assertTrue(rowLine(match2, 4).contains("selected current"), match2);

    String match3 = Files.readString(out.resolve("match-3.tree.txt"));
    assertTrue(rowLine(match3, 8).contains("selected current"), match3);

    String matchWrap = Files.readString(out.resolve("match-wrap.tree.txt"));
    assertTrue(rowLine(matchWrap, 2).contains("selected current"), matchWrap);

    String matchPrev = Files.readString(out.resolve("match-prev.tree.txt"));
    assertTrue(rowLine(matchPrev, 8).contains("selected current"), matchPrev);

    String shiftEnter = Files.readString(out.resolve("match-shift-enter.tree.txt"));
    assertTrue(rowLine(shiftEnter, 4).contains("selected current"), shiftEnter);

    String authorJump = Files.readString(out.resolve("author-jump.tree.txt"));
    assertTrue(authorJump.contains("\"5 of 24\""), authorJump);
    assertTrue(rowLine(authorJump, 7).contains("selected current"), authorJump);

    String hashJump = Files.readString(out.resolve("hash-jump.tree.txt"));
    assertTrue(hashJump.contains("\"2 of 24\""), hashJump);
    assertTrue(rowLine(hashJump, 16).contains("selected current"), hashJump);

    String noMatches = Files.readString(out.resolve("no-matches.tree.txt"));
    assertTrue(noMatches.contains("\"No matches\""), noMatches);

    String refPopup = Files.readString(out.resolve("ref-popup.tree.txt"));
    assertTrue(refPopup.contains("label=\"Go to ref\""), refPopup);
    assertFalse(refPopup.contains("stale/unloaded"), refPopup);

    String refJump = Files.readString(out.resolve("ref-jump.tree.txt"));
    assertFalse(refJump.contains("#palette"), refJump);
    assertTrue(rowLine(refJump, 21).contains("selected current"), refJump);
    assertEquals(21, harness.log.cursor());
    assertTrue(harness.opened.isEmpty(), "Enter in filter/palette should not call onOpen");
  }

  private static String rowLine(String tree, int index) {
    return rowLine(tree, "#row-" + index + " role=row");
  }

  @Test
  void stateAppendsToCachedGraphWhenHistoryGrowsAndRebuildsOtherwise() {
    GitLog.State state = new GitLog.State(CLOCK);
    List<Commit> commits = new ArrayList<>();
    commits.add(commit("c3", List.of("c2"), "Mara", 10, "Third"));
    commits.add(commit("c2", List.of("c1"), "Mara", 20, "Second"));

    CommitGraph first = state.syncGraph(commits);
    assertEquals(2, first.size());

    // Appending a new page reuses the same CommitGraph instance.
    commits.add(commit("c1", List.of(), "Mara", 30, "First"));
    CommitGraph second = state.syncGraph(commits);
    assertSame(first, second);
    assertEquals(3, second.size());

    // Replacing history rebuilds a fresh CommitGraph.
    List<Commit> replaced = List.of(commit("x1", List.of(), "Jonas", 5, "Other"));
    CommitGraph third = state.syncGraph(replaced);
    assertNotSame(second, third);
    assertEquals(1, third.size());
  }

  @Test
  void formatsRelativeAndFullDatesInEnglishAndGerman() {
    DiagramTexts en = DiagramTexts.ENGLISH;
    DiagramTexts de = new GermanDiagramTexts();

    assertEquals("just now", en.relativeDate(NOW.minusSeconds(20), CLOCK));
    assertEquals("gerade eben", de.relativeDate(NOW.minusSeconds(20), CLOCK));

    assertEquals("38 min ago", en.relativeDate(NOW.minus(Duration.ofMinutes(38)), CLOCK));
    assertEquals("vor 38 Min.", de.relativeDate(NOW.minus(Duration.ofMinutes(38)), CLOCK));

    assertEquals("2 h ago", en.relativeDate(NOW.minus(Duration.ofMinutes(124)), CLOCK));
    assertEquals("vor 2 Std.", de.relativeDate(NOW.minus(Duration.ofMinutes(124)), CLOCK));

    assertEquals("yesterday", en.relativeDate(NOW.minus(Duration.ofMinutes(1500)), CLOCK));
    assertEquals("gestern", de.relativeDate(NOW.minus(Duration.ofMinutes(1500)), CLOCK));

    assertEquals("3 days ago", en.relativeDate(NOW.minus(Duration.ofMinutes(4400)), CLOCK));
    assertEquals("vor 3 Tagen", de.relativeDate(NOW.minus(Duration.ofMinutes(4400)), CLOCK));

    assertEquals("Nov 16", en.relativeDate(NOW.minus(Duration.ofMinutes(11800)), CLOCK));
    assertEquals("16. Nov.", de.relativeDate(NOW.minus(Duration.ofMinutes(11800)), CLOCK));

    Instant prevYear = Instant.parse("2025-03-04T12:00:00Z");
    assertEquals("Mar 4, 2025", en.relativeDate(prevYear, CLOCK));
    assertEquals("4. März 2025", de.relativeDate(prevYear, CLOCK));

    ZonedDateTime tipTime = NOW.minus(Duration.ofMinutes(300)).atZone(ZoneOffset.UTC);
    assertEquals("Nov 24, 2026, 09:30", en.fullDate(tipTime));
    assertEquals("24. Nov. 2026, 09:30", de.fullDate(tipTime));

    assertEquals("Filter commits", en.filterCommits());
    assertEquals("Commits filtern", de.filterCommits());
    assertEquals("3 of 24", en.commitMatches(3, 24));
    assertEquals("3 von 24", de.commitMatches(3, 24));
    assertEquals("No matches", en.noMatches());
    assertEquals("Keine Treffer", de.noMatches());
    assertEquals("Go to ref", en.goToRef());
    assertEquals("Gehe zu Referenz", de.goToRef());
    assertEquals("Remote branch", en.refKind(Kind.REMOTE));
    assertEquals("Remote-Branch", de.refKind(Kind.REMOTE));
    assertEquals("12 of 24", en.commitsOf(12, 24));
    assertEquals("12 von 24", de.commitsOf(12, 24));
    assertEquals("Loading older commits…", en.loadingCommits());
    assertEquals("Ältere Commits werden geladen…", de.loadingCommits());
  }

  @Test
  void appendingTwoPagesMatchesOneBuildAndKeepsSelectionAndScroll() throws IOException {
    assumeText();
    List<Commit> all = sampleCommits();

    Harness whole = new Harness(all);
    Path wholeOut = out.resolve("whole");
    Script.run("""
        size 960 360 scale 2
        frame
        press #row-3
        scroll #rows row 4
        frame
        snap view
        """, app(whole), wholeOut);

    Harness paged = new Harness(all.subList(0, 12));
    paged.total = all.size();
    paged.onAction = () -> paged.commits.addAll(all.subList(12, all.size()));
    Path pagedOut = out.resolve("paged");
    Script.run("""
        size 960 360 scale 2
        frame
        press #row-3
        scroll #rows row 4
        frame
        snap before
        press label="Fetch"
        frame
        focus #rows
        advance 1
        frame
        snap after
        """, app(paged), pagedOut);

    assertEquals(all.size(), paged.log.graph().size());
    for (int i = 0; i < all.size(); i++) {
      assertEquals(whole.log.graph().column(i), paged.log.graph().column(i), "column at " + i);
      assertEquals(whole.log.graph().color(i), paged.log.graph().color(i), "color at " + i);
      assertEquals(whole.log.graph().text(i), paged.log.graph().text(i), "graph text at " + i);
    }

    assertEquals(3, paged.log.cursor());
    assertEquals(3, paged.log.anchor());

    String before = Files.readString(pagedOut.resolve("before.tree.txt"));
    String after = Files.readString(pagedOut.resolve("after.tree.txt"));
    String expected = Files.readString(wholeOut.resolve("view.tree.txt"));
    assertTrue(before.contains("label=\"12 of 24\""), before);
    assertTrue(after.contains("label=\"24 commits\""), after);
    assertEquals(
        rowLine(before, "#row-4 ").replace("pos=5/12", "pos=5/24"),
        rowLine(after, "#row-4 "),
        "scroll position should stay put when a page is appended");
    assertEquals(expected, after);
  }

  @Test
  void scrollingToEndAsksForMoreOncePerPageAndKeepsLoadingRowUnselectable() throws IOException {
    assumeText();
    List<Commit> all = sampleCommits();
    Harness harness = new Harness(all.subList(0, 12));
    harness.total = all.size();
    List<Integer> askedAt = new ArrayList<>();
    harness.onMore = () -> askedAt.add(harness.commits.size());

    // While scrolled at the top with rows left below the viewport, onMore is not called yet.
    Script.run("""
        size 960 360 scale 2
        frame
        frame
        """, app(harness), out.resolve("top"));
    assertEquals(List.of(), askedAt);

    int[] page = {0};
    harness.onAction = () -> {
      if (page[0] == 0) {
        assertEquals(List.of(12), askedAt);
        harness.commits.addAll(all.subList(12, 18));
        page[0] = 1;
      } else {
        assertEquals(List.of(12, 18), askedAt);
        harness.commits.addAll(all.subList(18, all.size()));
        harness.onMore = null;
      }
    };

    List<Path> files = Script.run("""
        size 960 360 scale 2
        frame
        scroll #rows row 12
        frame
        frame
        snap loading
        wheel #rows 0 200
        frame
        wheel #rows 0 -64
        frame
        wheel #rows 0 200
        frame
        frame
        press #row-11
        frame
        key ArrowDown
        key PageDown
        key End
        frame
        press #loading
        frame
        snap still-loading
        press label="Fetch"
        frame
        frame
        scroll #rows row 18
        frame
        frame
        wheel #rows 0 200
        frame
        frame
        snap second-page
        press label="Fetch"
        frame
        frame
        snap complete
        """, app(harness), out);

    Golden.assertSnaps("git-log-dark-", files, "loading");

    String loadingTree = Files.readString(out.resolve("loading.tree.txt"));
    assertTrue(loadingTree.contains("label=\"12 of 24\""), loadingTree);
    assertTrue(
        loadingTree.contains("#loading role=row label=\"Loading older commits…\""), loadingTree);

    // Neither the keys nor a press move the selection onto the loading row.
    assertEquals(11, harness.log.cursor());
    assertEquals(11, harness.log.anchor());

    // Asked once for the first page's end and once again after appending the second page.
    assertEquals(List.of(12, 18), askedAt);

    String secondPage = Files.readString(out.resolve("second-page.tree.txt"));
    assertTrue(secondPage.contains("label=\"18 of 24\""), secondPage);
    assertTrue(secondPage.contains("#loading role=row"), secondPage);

    String complete = Files.readString(out.resolve("complete.tree.txt"));
    assertTrue(complete.contains("label=\"24 commits\""), complete);
    assertFalse(complete.contains("#loading"), complete);
  }

  private static String rowLine(String tree, String prefix) {
    return tree.lines()
        .filter(line -> line.contains(prefix))
        .findFirst()
        .orElseThrow(() -> new AssertionError(prefix + " not found in:\n" + tree));
  }

  @Test
  void contextMenusScopeAndFirstParent() throws IOException {
    assumeText();
    final class MenuHarness {
      final GitLog.State log = new GitLog.State(CLOCK);
      List<Commit> commits = sampleCommits();
      final List<Ref> refs = new ArrayList<>(sampleRefs());
      GitLog.Scope scope = GitLog.Scope.ALL;
      boolean firstParent;
      final List<String> events = new ArrayList<>();

      El view() {
        Theme theme = Theme.CURRENT.get();
        return col()
            .wFull()
            .hFull()
            .fill(theme.surface().window())
            .padding(theme.space().l())
            .child(GitLog.gitLog(
                    log,
                    commits,
                    refs,
                    GitLog.options()
                        .commitMenu(commit -> List.of(
                            Menu.item("Cherry-pick", () -> events.add("pick:" + commit.id())),
                            Menu.item("Copy hash", () -> events.add("copy:" + commit.id()))))
                        .rangeMenu((newer, older) -> List.of(Menu.item(
                            "Compare range",
                            () -> events.add("range:" + newer.id() + ".." + older.id()))))
                        .refMenu(ref -> List.of(Menu.item(
                            "Checkout " + ref.name(),
                            () -> events.add("ref:" + ref.kind() + ":" + ref.name()))))
                        .scope(scope, next -> {
                          scope = next;
                          events.add("scope:" + next.kind() + ":" + next.branch());
                        })
                        .firstParent(firstParent, on -> {
                          firstParent = on;
                          events.add("firstParent:" + on);
                        }),
                    Button.button(
                        Button.Variant.SECONDARY, Symbol.ARROW_DOWNLOAD, "Fetch", () -> {}))
                .grow(1));
      }
    }

    MenuHarness harness = new MenuHarness();
    harness.refs.add(new Ref("main", Kind.BRANCH, "a41f9c2")); // the branch HEAD is on
    App<MenuHarness> app = new App<>(harness, MenuHarness::view);
    app.provide(Theme.CURRENT, ignored -> Theme.DARK);
    app.textRendering(TextRendering.LINUX);

    Script.run("""
        size 960 571 scale 2
        frame
        frame
        press #row-3 right
        frame
        advance 0.3
        frame
        snap commit-menu
        press role=menuitem label="Cherry-pick"
        frame
        key F10 shift
        frame
        advance 0.3
        frame
        snap key-menu
        press role=menuitem label="Copy hash"
        frame
        press #row-1
        frame
        press #row-3 shift
        frame
        press #row-2 right
        frame
        advance 0.3
        frame
        snap range-menu
        press role=menuitem label="Compare range"
        frame
        press label="HEAD → main" right
        frame
        advance 0.3
        frame
        snap ref-menu
        press role=menuitem label="Checkout main"
        frame
        press role=button label="All branches"
        frame
        advance 0.3
        frame
        snap scope-menu
        press role=menuitem label="feature/er-export"
        frame
        press role=button label="feature/er-export"
        frame
        advance 0.3
        frame
        press role=menuitem label="Current branch"
        frame
        press role=button label="First parent"
        frame
        snap updated
        """, app, out);

    // Commit menu includes commit items and per-ref submenu.
    String commitMenu = Files.readString(out.resolve("commit-menu.tree.txt"));
    assertTrue(commitMenu.contains("role=menuitem label=\"Cherry-pick\""), commitMenu);
    assertTrue(
        commitMenu.contains("role=menuitem label=\"Branch feature/query-history\""), commitMenu);

    // Shift+F10 opened the commit menu for the focused row.
    String keyMenu = Files.readString(out.resolve("key-menu.tree.txt"));
    assertTrue(keyMenu.contains("role=menuitem label=\"Copy hash\""), keyMenu);

    // Secondary press inside the selected range kept the range and opened the range menu.
    String rangeMenu = Files.readString(out.resolve("range-menu.tree.txt"));
    assertTrue(rangeMenu.contains("role=menuitem label=\"Compare range\""), rangeMenu);
    assertTrue(harness.log.hasRange());
    assertEquals(1, harness.log.rangeStart());
    assertEquals(3, harness.log.rangeEnd());

    // Secondary press on a ref pill opened that ref's menu.
    String refMenu = Files.readString(out.resolve("ref-menu.tree.txt"));
    assertTrue(refMenu.contains("role=menuitem label=\"Checkout main\""), refMenu);

    // Scope dropdown shows All branches (checked), Current branch, and local branches (no remotes).
    String scopeMenu = Files.readString(out.resolve("scope-menu.tree.txt"));
    assertTrue(
        scopeMenu
            .lines()
            .anyMatch(line ->
                line.contains("role=menuitem label=\"All branches\"") && line.contains("checked")),
        scopeMenu);
    assertTrue(scopeMenu.contains("role=menuitem label=\"Current branch\""), scopeMenu);
    assertTrue(scopeMenu.contains("role=menuitem label=\"main\""), scopeMenu);
    assertTrue(scopeMenu.contains("role=menuitem label=\"feature/er-export\""), scopeMenu);
    assertFalse(scopeMenu.contains("role=menuitem label=\"origin/main\""), scopeMenu);

    // Updated header reflects Current branch and checked First parent toggle.
    String updated = Files.readString(out.resolve("updated.tree.txt"));
    assertTrue(updated.contains("role=button label=\"Current branch\""), updated);
    assertTrue(
        updated
            .lines()
            .anyMatch(line ->
                line.contains("role=button label=\"First parent\"") && line.contains("checked")),
        updated);

    assertEquals(
        List.of(
            "pick:6c0d5a8",
            "copy:6c0d5a8",
            "range:9d2e6b1..6c0d5a8",
            "ref:BRANCH:main",
            "scope:BRANCH:feature/er-export",
            "scope:CURRENT:null",
            "firstParent:true"),
        harness.events);

    // A long branch name leaves the header's tools and actions inside a narrow log.
    harness.scope =
        GitLog.Scope.branch("feature/a-branch-name-much-longer-than-the-header-has-room");
    Script.run("size 680 571 scale 2\nframe\nframe\nadvance 1\nframe\nsnap narrow\n", app, out);
    String narrow = Files.readString(out.resolve("narrow.tree.txt"));
    assertTrue(narrow.contains("role=button label=\"More\""), narrow);
    assertTrue(narrow.contains("role=button label=\"Fetch\""), narrow);

    // Menu closes when the commit it opened on disappears from the history.
    Script.run("press #row-0 right\nframe\nadvance 0.3\nframe\n", app, out);
    assertTrue(app.tree().dump().contains("role=menu"));
    harness.commits = harness.commits.subList(1, harness.commits.size());
    app.invalidate();
    Script.run("frame\nadvance 1\nframe\n", app, out);
    String afterRemove = app.tree().dump();
    assertFalse(afterRemove.contains("role=menu"), afterRemove);
  }
}
