package com.rethinkj.ui.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Headless;
import com.rethinkj.ui.render.Scene;
import com.rethinkj.ui.text.ffi.FreeType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The system's fonts as fallbacks. What they are depends on the machine, so this checks only what
 * holds on any, and is skipped where there are no fonts.
 */
class SystemFontsTest {

  /** Japanese, Chinese, Korean, Arabic, Thai, an emoji and two symbols, none of them Inter's. */
  private static final List<String> SAMPLES =
      List.of("日本語のテキスト", "中文文本", "한국어 텍스트", "نص عربي", "ข้อความภาษาไทย", "😀", "∀", "☃");

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
    for (String sample : SAMPLES) {
      int[] bounds = Runs.clusters(sample);
      for (int k = 0; k + 1 < bounds.length; k++) {
        String cluster = sample.substring(bounds[k], bounds[k + 1]);
        if (cluster.isBlank()) {
          continue;
        }
        assertFalse(Runs.covers(cluster, 0, (f, c) -> Font.sans().has(c)), cluster);
        Font font = SystemFonts.find(cluster, 0);
        if (font != null) {
          assertTrue(
              cluster.codePoints().allMatch(c -> Runs.hidden(c) || font.has(c)),
              font + " has " + cluster);
        }
      }
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

  @Test
  void notoColorEmojiFromSystemFontsDrawsEmojiInColor() {
    Path noto = Path.of("/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf");
    assumeTrue(Files.isRegularFile(noto), "Skipped: " + noto + " is not installed");
    App<Object> app = new App<>(new Object(), s -> El.text("Hi 😀🎉").fontSize(16));
    app.systemFonts(true);
    app.resize(200, 40, 1);
    app.frame();
    GlyphRun run = app.tree().text().lines().getFirst().glyphs();
    Font emojiFont = run.font(run.count() - 1);
    assertTrue(
        emojiFont.toString().startsWith("Noto Color Emoji"),
        "expected Noto Color Emoji, got " + emojiFont);
    Scene scene = app.scene();
    int colorGlyphs = 0;
    for (int i = 0; i < scene.count(); i++) {
      if (scene.getInt(i, Scene.KIND) == Scene.COLOR_GLYPH) {
        colorGlyphs++;
      }
    }
    assertEquals(2, colorGlyphs, "both emoji emit COLOR_GLYPH instances");
  }

  @Test
  void installedFamiliesCanBeListedAndLoadedByNameWeightAndStyle() {
    List<Font.Family> families = Font.installedFamilies();
    assumeFalse(families.isEmpty(), "Skipped: no fonts in " + SystemFonts.folders());
    for (Font.Family family : families) {
      assertFalse(family.styles().isEmpty(), family.name() + " has styles");
    }
    Font.Family first = families.getFirst();
    Font.Style style = first.styles().getFirst();
    Font loaded = Font.installed(first.name(), style.weight(), style.italic()).orElseThrow();
    assertTrue(
        loaded.toString().startsWith(first.name()), "expected " + first.name() + ", got " + loaded);
    assertSame(
        loaded,
        Font.installed(first.name().toUpperCase(Locale.ROOT), style.weight(), style.italic())
            .orElseThrow());
    assertThrows(UnsupportedOperationException.class, loaded::close);
    assertTrue(Font.installed("No Such Family 891", 400, false).isEmpty());
  }

  @Test
  void installedFaceSelectionPrefersItalicThenNearestWeightThenPlainStyle() {
    int[] empty = new int[0];
    SystemFonts.Face light =
        new SystemFonts.Face(Path.of("a.ttf"), 0, "Demo", "Light", 0, 300, true, empty, false);
    SystemFonts.Face boldCondensed = new SystemFonts.Face(
        Path.of("b.ttf"),
        0,
        "Demo",
        "Bold Condensed",
        (int) FreeType.STYLE_FLAG_BOLD,
        700,
        true,
        empty,
        false);
    SystemFonts.Face bold = new SystemFonts.Face(
        Path.of("c.ttf"),
        0,
        "Demo",
        "Bold",
        (int) FreeType.STYLE_FLAG_BOLD,
        700,
        true,
        empty,
        false);
    SystemFonts.Face italic = new SystemFonts.Face(
        Path.of("d.ttf"),
        0,
        "Demo",
        "Italic",
        (int) FreeType.STYLE_FLAG_ITALIC,
        400,
        false,
        empty,
        false);
    List<SystemFonts.Face> faces = List.of(light, boldCondensed, bold, italic);

    assertEquals(bold, SystemFonts.candidates(faces, "demo", 600, false, null).getFirst());
    assertEquals(italic, SystemFonts.candidates(faces, "DEMO", 700, true, null).getFirst());
    assertEquals(
        boldCondensed,
        SystemFonts.candidates(faces, "demo", 700, false, "Bold Condensed").getFirst(),
        "a listed style loads the face of its name");
    assertTrue(SystemFonts.candidates(faces, "Other", 400, false, null).isEmpty());

    List<Font.Family> grouped = SystemFonts.families(List.of(bold, light, italic, bold));
    assertEquals(1, grouped.size());
    Font.Family demo = grouped.getFirst();
    assertFalse(demo.monospaced(), "monospaced only when all faces are");
    assertEquals(
        List.of(
            new Font.Style(300, false, "Light"),
            new Font.Style(400, true, "Italic"),
            new Font.Style(700, false, "Bold")),
        demo.styles());
  }
}
