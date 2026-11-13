package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.render.Png;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The structural pieces as {@link Sheets} lays them out, compared with the stored images {@code
 * structure-<view>-<theme>-<scale>x}: cards in their variants, nested, flush and pressable; a
 * section with a group of rows of every kind and dividers; a form with labels beside and above;
 * and the choices settings need with a callout and an empty state.
 */
class StructureTest {

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  @ParameterizedTest(name = "{0} at {1}x")
  @CsvSource({"dark, 1", "dark, 2", "light, 1", "light, 2"})
  void looksAsStored(String theme, int scale) throws IOException {
    check("cards", theme, scale, 640, 400, Sheets::cards);
    check("rows", theme, scale, 520, 640, Sheets::rows);
    check("form", theme, scale, 640, 260, Sheets::form);
    check("choices", theme, scale, 400, 560, Sheets::choices);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"dark", "light"})
  void narrowRowsWrapTheirTextAndKeepTheirControlsWhole(String theme) throws IOException {
    String tree = check("rows-narrow", theme, 1, 300, 700, Sheets::rows);
    // The controls keep their widths, and each row's text ends before its control.
    assertTrue(tree.contains("role=combobox label=\"JetBrains Mono\" "), tree);
    assertEquals("160x24", size(line(tree, "role=combobox")));
    assertEquals("100x24", size(line(tree, "role=spinbutton")));
    String title = line(tree, "text \"Columns per tab stop\"");
    String field = line(tree, "role=spinbutton");
    assertTrue(right(title) <= left(field), title + " / " + field);
    assertTrue(height(title) > 16, "the description wraps: " + title);
  }

  /**
   * Runs the view in a window of this size and compares the snap with {@code
   * structure-<name>-<theme>-<scale>x}.
   *
   * @return the snap's tree
   */
  private String check(
      String name, String theme, int scale, int width, int height, Function<Sheets, El> view)
      throws IOException {
    App<Sheets> app = new Sheets(theme.equals("dark") ? Theme.DARK : Theme.LIGHT).app(view);
    String image = "structure-" + name + "-" + theme + "-" + scale + "x";
    Script.run(
        "size " + width + " " + height + " scale " + scale + "\nframe\nsnap " + image, app, out);
    String tree = Files.readString(out.resolve(image + ".tree.txt"));
    Golden.assertMatches(image, Png.decode(Files.readAllBytes(out.resolve(image + ".png"))), tree);
    return tree;
  }

  static String line(String tree, String part) {
    return tree.lines().filter(l -> l.contains(part)).findFirst().orElseThrow().strip();
  }

  /** The rectangle {@code x,y wxh} of a dump's line, after its quoted parts. */
  static float[] rect(String line) {
    String plain = line.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"");
    var m = java.util.regex.Pattern.compile(" (-?[\\d.]+),(-?[\\d.]+) ([\\d.]+)x([\\d.]+)")
        .matcher(plain);
    assertTrue(m.find(), line);
    return new float[] {
      Float.parseFloat(m.group(1)),
      Float.parseFloat(m.group(2)),
      Float.parseFloat(m.group(3)),
      Float.parseFloat(m.group(4))
    };
  }

  private static String size(String line) {
    float[] r = rect(line);
    return Math.round(r[2]) + "x" + Math.round(r[3]);
  }

  private static float left(String line) {
    return rect(line)[0];
  }

  private static float right(String line) {
    float[] r = rect(line);
    return r[0] + r[2];
  }

  private static float height(String line) {
    return rect(line)[3];
  }
}
