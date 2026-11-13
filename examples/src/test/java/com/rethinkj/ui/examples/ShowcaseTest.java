package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.Script;
import com.rethinkj.ui.examples.Showcase.Look;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ShowcaseTest {

  static Stream<Arguments> looks() {
    return Arrays.stream(Look.values())
        .filter(look -> look != Look.SYSTEM) // dark or light
        .flatMap(look -> Stream.of(Arguments.of(look, 1), Arguments.of(look, 2)));
  }

  /**
   * Runs {@code showcase.txt} in a look at a scale and leaves the snaps in {@code
   * target/showcase/<look>-<scale>x} to look at, each section before and after the proposal. It
   * compares them with nothing: stored images of every section in four looks would change with
   * every change of the kit's look.
   */
  /** The window can be made as narrow as the step where the path and toolbar give way. */
  @Test
  void theWindowGoesAsNarrowAsThePathAndToolbarGiveWay() throws IOException {
    java.util.regex.Matcher narrow = java.util.regex.Pattern.compile(
            "(?m)^size (\\d+) (\\d+) +# narrow")
        .matcher(Snaps.script("showcase"));
    assertTrue(narrow.find(), "showcase.txt has its narrow step");
    assertTrue(
        Showcase.MIN_WIDTH <= Integer.parseInt(narrow.group(1)),
        "a window of " + Showcase.MIN_WIDTH + " at least never shows what the step shows at "
            + narrow.group(1));
  }

  /** A property's copy button confirms the copy with a check, as the manual's code blocks do. */
  @Test
  void aPropertysCopyButtonConfirmsTheCopy(@org.junit.jupiter.api.io.TempDir Path out)
      throws IOException {
    Snaps.assumeText();
    var app = Showcase.app(Look.DARK);
    Script.run("""
        size 1100 720
        frame
        press role=treeitem label="Table details"
        frame
        move label="Owner" # the row shows its copy button, which fades in
        frame
        advance 0.25
        frame
        press label="Copy Owner"
        frame
        snap copied
        """, app, out);
    String tree = Files.readString(out.resolve("copied.tree.txt"));
    assertTrue(tree.contains("label=\"checkmark\""), "the check: " + tree);
    assertTrue(tree.contains("role=tooltip label=\"Copied\""), tree);
    assertTrue(Files.readString(out.resolve("copied.clipboard.txt")).equals("shop_admin"));
  }

  @ParameterizedTest(name = "{0} at {1}x")
  @MethodSource("looks")
  void theScriptShowsEverySection(Look look, int scale) throws IOException {
    Snaps.assumeText();
    String script = Snaps.script("showcase");
    String scaled = script.replaceFirst("(?m)^size 1100 720$", "size 1100 720 scale " + scale);
    assertNotEquals(script, scaled, "showcase.txt sets the size in its first step");
    String folder = look.slug() + "-" + scale + "x";
    Path out = Path.of("target", "showcase", folder);
    assertFalse(Script.run(scaled, Showcase.app(look), out).isEmpty(), "the script snaps");
    assertTrue(
        Files.readString(out.resolve("find.tree.txt")).contains("role=search"),
        "the code editor's find bar");
  }
}
