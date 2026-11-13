package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.rethinkj.ui.Script;
import com.rethinkj.ui.examples.Showcase.Look;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ShowcaseTest {

  static Stream<Arguments> looks() {
    return Arrays.stream(Look.values())
        .flatMap(look -> Stream.of(Arguments.of(look, 1), Arguments.of(look, 2)));
  }

  /**
   * Runs {@code showcase.txt} in a look at a scale and leaves the snaps in {@code
   * target/showcase/<look>-<scale>x} to look at, each section before and after the proposal. It
   * compares them with nothing: stored images of every section in four looks would change with
   * every change of the kit's look.
   */
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
  }
}
