package com.rethinkj.ui.diagram.git;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Colors;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.diagram.Golden;
import com.rethinkj.ui.diagram.git.Ref.Kind;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link RefPills}: every ref kind, branches in sync with their remote and not, detached HEAD, a
 * cut name with its full name in the tooltip, and {@code +n}.
 */
class RefPillsTest {

  private static final int BLUE = Colors.rgb(0x3D6FE6);
  private static final int GREEN = Colors.rgb(0x2E8B57);
  private static final int VIOLET = Colors.rgb(0x7C5CE6);
  private static final int AMBER = Colors.rgb(0xE0A800);
  private static final int TEAL = Colors.rgb(0x0D9488);

  @TempDir
  Path out;

  @BeforeEach
  void needsText() {
    try {
      Font.sans();
    } catch (UnsatisfiedLinkError e) {
      assumeTrue(false, "Skipped: FreeType and HarfBuzz cannot be loaded. " + e.getMessage());
    }
  }

  private static El showcase() {
    Theme theme = Theme.CURRENT.get();
    return col()
        .wFull()
        .hFull()
        .fill(theme.surface().panel())
        .padding(theme.space().l())
        .gap(theme.space().m())
        // Row 1: HEAD branch in sync with origin/main (plus duplicate BRANCH entry) and a tag.
        .child(RefPills.pills(
            List.of(
                new Ref("v1.0", Kind.TAG, "c1"),
                new Ref("origin/main", Kind.REMOTE, "c1"),
                new Ref("main", Kind.BRANCH, "c1"),
                new Ref("main", Kind.HEAD, "c1")),
            BLUE,
            120,
            4))
        // Row 2: local branch in sync with its remote, plus a separate remote branch.
        .child(RefPills.pills(
            List.of(
                new Ref("upstream/next", Kind.REMOTE, "c2"),
                new Ref("origin/release", Kind.REMOTE, "c2"),
                new Ref("release", Kind.BRANCH, "c2")),
            GREEN,
            120,
            4))
        // Row 3 & 4: local branch and its remote on different commits (not in sync).
        .child(RefPills.pills(List.of(new Ref("feat/pills", Kind.BRANCH, "c3")), VIOLET, 120, 4))
        .child(RefPills.pills(
            List.of(new Ref("origin/feat/pills", Kind.REMOTE, "c4")), VIOLET, 120, 4))
        // Row 5: detached HEAD on a bright lane (dark contrasting text) alongside a local branch.
        .child(RefPills.pills(
            List.of(new Ref("hotfix", Kind.BRANCH, "c5"), new Ref("HEAD", Kind.HEAD, "c5")),
            AMBER,
            120,
            4))
        // Row 6: truncated long name and +n overflow collapsing hidden refs.
        .child(RefPills.pills(
            List.of(
                new Ref("v2.0.0-rc.1", Kind.TAG, "c6"),
                new Ref("feature/very-long-branch-name-for-ellipsis", Kind.BRANCH, "c6"),
                new Ref("dev", Kind.BRANCH, "c6"),
                new Ref("origin/staging", Kind.REMOTE, "c6"),
                new Ref("v2.0.0", Kind.TAG, "c6")),
            TEAL,
            110,
            3));
  }

  private static App<Void> app() {
    App<Void> app = new App<>(null, ignored -> showcase());
    app.provide(Theme.CURRENT, ignored -> Theme.DARK);
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  @Test
  void showsEveryKindInSyncAndNotCutNameAndOverflow() throws IOException {
    List<Path> files = Script.run("""
        size 368 172 scale 2
        frame
        snap ref-pills-dark
        move label="Branch feature/very-long-branch-name-for-ellipsis"
        frame
        advance 0.6
        frame
        snap cut-tooltip
        move label="2 more refs: v2.0.0-rc.1, v2.0.0"
        frame
        advance 0.6
        frame
        snap overflow-tooltip
        """, app(), out);

    Golden.assertSnaps("", files, "ref-pills-dark");

    String cutTip = Files.readString(out.resolve("cut-tooltip.tree.txt"));
    assertTrue(
        cutTip.contains("role=tooltip label=\"Branch feature/very-long-branch-name-for-ellipsis\""),
        cutTip);

    String overflowTip = Files.readString(out.resolve("overflow-tooltip.tree.txt"));
    assertTrue(
        overflowTip.contains("role=tooltip label=\"2 more refs: v2.0.0-rc.1, v2.0.0\""),
        overflowTip);
  }
}
