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
import org.junit.jupiter.api.Test;
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
    check("form-groups", theme, scale, 720, 470, Sheets::formGroups);
    check("choices", theme, scale, 400, 560, Sheets::choices);
    String tree = check("callouts", theme, scale, 560, 420, Sheets::callouts);
    // In the card of 400, the buttons went below the text, at its right; elsewhere beside it.
    float[] text = rect(line(tree, "text \"Three rows"));
    float[] commit = rect(line(tree, "label=\"Commit\""));
    assertTrue(commit[1] >= text[1] + text[3], "below the text: " + tree);
    float[] note = rect(line(tree, "#narrow role=note"));
    assertTrue(
        Math.abs(commit[0] + commit[2] - (note[0] + note[2] - Theme.DARK.space().m())) <= 1,
        "at the right: " + tree);
    float[] open = rect(line(tree, "label=\"Open\""));
    float[] answered = rect(line(tree, "text \"The server answered"));
    assertTrue(open[0] >= answered[0] + answered[2], "beside the text: " + tree);
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

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"dark", "light"})
  void aNarrowFormWrapsItsRowOfFields(String theme) throws IOException {
    String tree = check("form-groups-narrow", theme, 1, 340, 340, Sheets::formGroupsNarrow);
    assertTrue(left(line(tree, "#beside-port")) < left(line(tree, "#beside-host")) + 72, tree);
  }

  @Test
  void anEmptyStateBreaksItsTextInANarrowPanel() throws IOException {
    App<Object> app = new App<>(
        new Object(),
        s -> El.col()
            .size(258, 400)
            .child(EmptyState.emptyState(
                Theme.DARK,
                Symbol.DATABASE,
                "No connections yet",
                "Connections to your databases will be listed here.",
                null)));
    Script.run("size 258 400\nframe\nsnap narrow", app, out);
    String tree = Files.readString(out.resolve("narrow.tree.txt"));
    float[] text = rect(line(tree, "text \"Connections to"));
    assertTrue(
        text[0] >= 0 && text[0] + text[2] <= 258,
        "inside the panel: " + line(tree, "text \"Connections to"));
    assertTrue(text[3] > 30, "on two lines or more: " + text[3]);
  }

  @ParameterizedTest(name = "at {0}x")
  @ValueSource(ints = {1, 2})
  void aPathWithoutSpacesBreaksInsideACalloutAndAnEmptyState(int scale) throws IOException {
    String text = "Cannot read the connections in"
        + " /a/very/long/path/without/any/space/connections.json: it is not valid JSON.";
    for (El view : new El[] {
      Callout.callout(Theme.DARK, Callout.Kind.DANGER, "Cannot read the connections", text),
      EmptyState.emptyState(Theme.DARK, Symbol.DATABASE, "No connections", text, null)
    }) {
      App<Object> app = new App<>(new Object(), s -> El.col().size(260, 400).child(view));
      Script.run("size 260 400 scale " + scale + "\nframe\nsnap path", app, out);
      String tree = Files.readString(out.resolve("path.tree.txt"));
      float[] path = rect(line(tree, "text \"Cannot read the connections in"));
      assertTrue(path[3] > 40, "on three lines or more: " + path[3]);
      tree.lines().filter(l -> l.strip().startsWith("text ")).forEach(l -> {
        float[] r = rect(l.strip());
        assertTrue(r[0] >= 0 && r[0] + r[2] <= 260, "inside the box of 260: " + l.strip());
      });
    }
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
