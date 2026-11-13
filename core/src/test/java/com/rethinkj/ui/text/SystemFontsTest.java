package com.rethinkj.ui.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Headless;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The system's fonts as fallbacks. What they are depends on the machine, so this checks only what
 * holds on any, prints what this machine chose, and is skipped where there are no fonts.
 */
class SystemFontsTest {

  /** Japanese, Chinese, Korean, Arabic, Thai, an emoji and two symbols, none of them Inter's. */
  private static final List<String> SAMPLES = List.of("日本語のテキスト", "中文文本", "한국어 텍스트",
      "نص عربي", "ข้อความภาษาไทย", "😀", "∀", "☃");

  @BeforeEach
  void needsTheTextLibraries() {
    TextLibraries.assumeLoaded();
  }

  /**
   * In a fresh process this is what starts the scan; after another test looked a character up it
   * finds the scan done.
   */
  @Test
  void turningTheSwitchOnReadsTheFontsAheadOnAnotherThreadWithoutALookup() throws Exception {
    int asked = SystemFonts.lookups();
    App<Object> app = new App<>(new Object(), s -> El.text("abc"));
    long start = System.nanoTime();
    app.systemFonts(true);
    assertTrue(System.nanoTime() - start < 50_000_000L, "it returns at once");
    for (int i = 0; i < 1000 && SystemFonts.scanNanos() == 0; i++) {
      Thread.sleep(10);
    }
    assertTrue(SystemFonts.scanNanos() > 0, "scanned within 10 seconds");
    assertEquals(asked, SystemFonts.lookups(), "without looking anything up");
  }

  @Test
  void textWithoutTheSwitchNeverLooksAtTheSystem() {
    int before = SystemFonts.lookups();
    Headless.render(El.text("中文 😀"), 100, 40, 1);
    App<Object> app = new App<>(new Object(), s -> El.text("中文 😀"));
    app.resize(100, 40, 1);
    app.frame();
    assertFalse(app.systemFonts(), "off unless a window loop or the app turns it on");
    assertEquals(before, SystemFonts.lookups());

    app.systemFonts(true);
    app.frame();
    assertTrue(SystemFonts.lookups() > before, "a missing character asks the system");
    int asked = SystemFonts.lookups();
    Font.sans().withSystemFonts().shape("Kunde Müller", 14, 1);
    assertEquals(asked, SystemFonts.lookups(), "not where the font has every character");
  }

  @Test
  void aCharacterInterLacksIsFoundInAFontThatHasItOrReportedMissing() {
    List<SystemFonts.Face> faces = SystemFonts.faces();
    assumeFalse(faces.isEmpty(), "Skipped: no fonts in " + SystemFonts.folders());
    long ranges = faces.stream().mapToLong(f -> f.ranges().length).sum();
    System.out.printf("System fonts: %d files, %d faces, scanned in %.0f ms; the index holds"
            + " %d ranges of characters, %d KB%n", SystemFonts.files(), faces.size(),
        SystemFonts.scanNanos() / 1e6, ranges / 2, ranges * 4 / 1024);
    for (String sample : SAMPLES) {
      List<Integer> bounds = Runs.clusters(sample);
      Set<String> chosen = new LinkedHashSet<>();
      long start = System.nanoTime();
      for (int k = 0; k + 1 < bounds.size(); k++) {
        String cluster = sample.substring(bounds.get(k), bounds.get(k + 1));
        if (cluster.isBlank()) {
          continue;
        }
        assertFalse(Runs.covers(cluster, 0, (f, c) -> Font.sans().has(c)), cluster);
        Font font = SystemFonts.find(cluster, 0);
        chosen.add(font == null ? "missing" : font.toString());
        if (font != null) {
          assertTrue(cluster.codePoints().allMatch(c -> Runs.hidden(c) || font.has(c)),
              font + " has " + cluster);
        }
      }
      System.out.printf("  %s: %s (%.1f ms)%n", sample, String.join(", ", chosen),
          (System.nanoTime() - start) / 1e6);
    }
  }

  @Test
  void theScanRunsOncePerProcess() {
    List<SystemFonts.Face> faces = SystemFonts.faces();
    long nanos = SystemFonts.scanNanos();
    assertSame(faces, SystemFonts.faces());
    assertEquals(nanos, SystemFonts.scanNanos());
    assertSame(SystemFonts.find("中", 0), SystemFonts.find("中", 0), "and lookups are kept");
  }

  @Test
  void systemFontsFillWhatTheChainLacks() {
    Font chain = Font.sans().withSystemFonts();
    GlyphRun run = chain.shape("a中b", 14, 1);
    Font found = SystemFonts.find("中", 0);
    assertSame(found == null ? Font.sans() : found, run.font(1));
    assertEquals(found == null, run.glyph(1) == 0);
    assertSame(Font.sans(), run.font(0));
  }

  /** Arabic letters join, and come right to left: the last letter's final form first. */
  @Test
  void arabicIsJoinedAndRightToLeftInASystemFont() {
    Font arabic = SystemFonts.find("ك", 0);
    assumeTrue(arabic != null, "Skipped: no system font has Arabic");
    GlyphRun run = Font.sans().withSystemFonts().shape("كتب", 14, 1);
    assertEquals(3, run.count());
    List<Integer> isolated = new ArrayList<>();
    for (String letter : new String[] {"ب", "ت", "ك"}) {
      isolated.add(arabic.shape(letter, 14, 1).glyph(0));
    }
    assertNotEquals(isolated.get(0), run.glyph(0), "the final form of ب, not its isolated one");
    assertNotEquals(isolated.get(1), run.glyph(1), "the medial form of ت");
    assertNotEquals(isolated.get(2), run.glyph(2), "the initial form of ك, last");
    for (int i = 0; i < 3; i++) {
      assertSame(arabic, run.font(i));
    }
  }
}
