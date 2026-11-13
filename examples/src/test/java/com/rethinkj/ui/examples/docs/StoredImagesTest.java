package com.rethinkj.ui.examples.docs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Headless;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The tests that the page about stored images shows: a script's snaps against stored ones. */
class StoredImagesTest {

  @BeforeAll
  static void text() {
    // docs:start assume-text
    try {
      Font.sans();
    } catch (UnsatisfiedLinkError e) {
      assumeTrue(false, "Skipped: FreeType and HarfBuzz cannot be loaded. " + e.getMessage());
    }
    // docs:end assume-text
  }

  // docs:start stored-images
  @Test
  void theSwitchLightsTheLamp() throws IOException {
    App<Lights> app = new App<>(new Lights(), Lights::view);
    app.textRendering(TextRendering.LINUX); // the same pixels on every platform
    String script = """
        size 360 240 scale 2
        frame
        press role=switch label="Lamp"
        frame
        advance 1
        frame
        snap on
        """;
    List<Path> written = Script.run(script, app, Path.of("target/lights"));

    Path stored = Path.of("src/test/resources/golden/docs/lights-images");
    for (Path file : written) {
      Path kept = stored.resolve(file.getFileName());
      if (Boolean.getBoolean("golden.update")) { // mvn test -Dgolden.update=true
        Files.createDirectories(stored);
        Files.copy(file, kept, StandardCopyOption.REPLACE_EXISTING);
      } else if (file.toString().endsWith(".png")) {
        assertArrayEquals(Png.decode(Files.readAllBytes(kept)).rgba(),
            Png.decode(Files.readAllBytes(file)).rgba(), file + " differs from " + kept);
      } else {
        assertEquals(Files.readString(kept), Files.readString(file));
      }
    }
  }
  // docs:end stored-images

  // docs:start headless
  @Test
  void aViewAloneRendersWithoutAnApp() {
    Lights state = new Lights();
    state.on = true;
    El root = Lights.view(state);
    Headless.Frame frame = Headless.render(root, 360, 240, 2, TextRendering.LINUX);
    assertEquals(720, frame.pixels().width());
    assertTrue(frame.tree().contains("role=switch label=\"Lamp\""), frame.tree());
  }
  // docs:end headless
}
