package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.render.Png;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class LightSwitchTest {

  private static final Path SCRIPT = Path.of("src/test/resources/scripts/light-switch.txt");
  private static final Path STORED = Path.of("src/test/resources/golden");
  private static final Path OUT = Path.of("target/light-switch");

  /**
   * Runs the example's {@code main} on a script and compares each snap with the stored one. Run
   * with {@code -Dgolden.update=true} to store the snaps anew; look at them before committing.
   */
  @Test
  void theScriptShowsTheStoredFrames() throws IOException {
    LightSwitch.main(new String[] {"--script", SCRIPT.toString(), "--out", OUT.toString()});

    try (var files = Files.list(OUT)) {
      for (Path written : files.sorted().toList()) {
        Path stored = STORED.resolve(written.getFileName());
        if (Boolean.getBoolean("golden.update")) {
          Files.createDirectories(STORED);
          Files.copy(written, stored, StandardCopyOption.REPLACE_EXISTING);
          continue;
        }
        assertTrue(Files.exists(stored), "No stored " + stored + "; run with -Dgolden.update=true");
        String failure = written.toAbsolutePath() + " differs from " + stored;
        if (written.toString().endsWith(".png")) {
          Pixels expected = Png.decode(Files.readAllBytes(stored));
          Pixels actual = Png.decode(Files.readAllBytes(written));
          assertEquals(expected.width() + "x" + expected.height(),
              actual.width() + "x" + actual.height(), failure);
          assertTrue(Arrays.equals(expected.rgba(), actual.rgba()), failure);
        } else {
          assertEquals(Files.readString(stored), Files.readString(written), failure);
        }
      }
    }
  }
}
