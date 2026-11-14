package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManualTest {

  /** Runs the example's {@code main} on {@code manual.txt}; see {@link Snaps#check}. */
  @Test
  void theScriptShowsTheStoredFrames() throws IOException {
    Snaps.check("manual", Manual::main);
    assertTrue(Snaps.line("manual", "manual-dark-1x-top", "#heading-writing-documents")
        .contains("role=heading \"Writing documents\""));
    assertTrue(Snaps.line("manual", "manual-dark-1x-top", "label=\"tables\"")
        .startsWith("el #link-0 role=link label=\"tables\""));
    // The images, read off the app's thread: a PNG made for scale 2 at half its pixels, a JPEG
    // from the middle of a paragraph.
    assertTrue(Snaps.line("manual", "manual-dark-2x-images", "label=\"The frame of the")
        .matches("image role=img label=\".*\" \\d+,\\d+ 384x224"));
    assertTrue(Snaps.line("manual", "manual-dark-2x-images", "label=\"Three notes as cards\"")
        .endsWith(" 320x150"));
  }

  /** A link to a heading scrolls the document there; a link out of it shows a toast. */
  @Test
  void linksScrollToHeadingsOrShowTheirAddress(@TempDir Path out) throws IOException {
    Snaps.assumeText();
    Manual manual = new Manual(List.of());
    manual.app = new App<>(manual, Manual::view);
    Script.run("size 960 640\nframe\npress role=link label=\"inline\"\nframe", manual.app, out);
    assertEquals("Opens https://commonmark.org", manual.toast);
    Script.run("size 960 640\npress role=link label=\"tables\"\nframe", manual.app, out);
    String heading = manual
        .app
        .tree()
        .dump()
        .lines()
        .filter(l -> l.contains("#heading-tables"))
        .findFirst()
        .orElseThrow();
    int y = Integer.parseInt(heading.replaceAll(".* \\d+,(\\d+)(\\.\\d+)? \\d+x\\d+.*", "$1"));
    assertTrue(y > 33 && y < 640, heading);
  }

  @Test
  void theChangelogGroupsItsEntriesByWhatTheyDo() {
    String log = Manual.changelog(List.of(
        "- Fixed: a crash.\n",
        "- Added: `A.b()` does c.\n",
        "- Something else.\n",
        "- Added: d.\n"));
    assertEquals("""
        # Changelog

        What changed since the last release, for users of the library.

        ## Unreleased

        ### Added

        - `A.b()` does c.
        - D.

        ### Fixed

        - A crash.

        ### Other

        - Something else.
        """, log);
  }
}
