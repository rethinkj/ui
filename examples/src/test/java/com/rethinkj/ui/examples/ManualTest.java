package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManualTest {

  /** Runs the example's {@code main} on {@code manual.txt}; see {@link Snaps#check}. */
  @Test
  void theScriptShowsTheStoredFrames() throws IOException {
    Snaps.check("manual", Manual::main);
    assertTrue(Snaps.line("manual", "manual-dark-1x-top", "#heading-writing-documents")
        .contains("role=heading \"Writing documents\""));
    assertTrue(Snaps.line("manual", "manual-dark-1x-top", "label=\"tables\"")
        .startsWith("el #link-0 role=link label=\"tables\""));
    // The link to a heading scrolled the document: the table's heading is in view.
    String heading = Snaps.line("manual", "manual-dark-1x-linked", "#heading-tables");
    int y = Integer.parseInt(heading.replaceAll(".* \\d+,(\\d+)(\\.\\d+)? \\d+x\\d+.*", "$1"));
    assertTrue(y > 33 && y < 640, heading);
    assertTrue(
        Snaps.line("manual", "manual-dark-1x-lists", "role=checkbox").endsWith("disabled checked"));
    assertTrue(Snaps.line("manual", "manual-toast", "role=status")
        .contains("label=\"Opens https://commonmark.org\""));
    // The images, read off the app's thread: a PNG made for scale 2 at half its pixels, a JPEG
    // from the middle of a paragraph, an image that is not there as its placeholder, a title.
    assertTrue(Snaps.line("manual", "manual-dark-1x-images", "label=\"The frame of the")
        .matches("image role=img label=\".*\" \\d+,\\d+ 384x224"));
    assertTrue(Snaps.line("manual", "manual-dark-1x-images", "label=\"Three notes as cards\"")
        .endsWith(" 320x150"));
    assertTrue(Snaps.line("manual", "manual-dark-2x-images-end", "could not read")
        .startsWith("el role=img label=\"An image the app could not read\""));
    assertTrue(Snaps.line("manual", "manual-dark-2x-images-end", "role=tooltip")
        .contains("label=\"A JPEG\""));
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
