package com.rethinkj.ui.kit;

import static com.rethinkj.ui.kit.Workbench.find;
import static com.rethinkj.ui.kit.Workbench.labelled;
import static com.rethinkj.ui.kit.Workbench.x;
import static com.rethinkj.ui.kit.Workbench.y;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Split.Position;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Splits and tabs together as the frame of a database tool ({@link Workbench#frameView}) in a
 * window of 720 by 400: the schema, 200 wide, beside the queries, whose active one is an editor
 * over its results, half and half.
 */
class WorkbenchTest {

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private App<Workbench> start() throws IOException {
    App<Workbench> app = Workbench.frame(Theme.DARK).app(Workbench::frameView);
    run(app, "frame");
    return app;
  }

  private void run(App<?> app, String steps) throws IOException {
    Script.run("size 720 400\n" + steps, app, out);
  }

  @Test
  void eachLineOfNestedSplitsMovesItsOwnParts() throws IOException {
    App<Workbench> app = start();
    Workbench w = app.state();
    assertEquals(200, labelled(app, "Schema").width());
    El editor = labelled(app, "Query");
    assertEquals(202, x(app, editor), "inside the edge of the tabs' card");
    assertEquals(34, y(app, editor), "under the card's edge, the bar of 32 and its line");
    assertEquals(182, editor.height(), "half of 365 less the line");

    run(app, "down 200,300\nmove 260,300\nup 260,300\nframe"); // the schema's line
    assertEquals(
        List.of("sidebar " + Position.first(260), "sidebar " + Position.first(260)),
        w.log,
        "moved, then ended");
    assertEquals(262, x(app, labelled(app, "Query")));
    assertEquals(182, labelled(app, "Query").height(), "the editor keeps its height");

    run(app, "down 400,214\nmove 400,114\nup 400,114\nframe"); // the editor's line
    assertEquals(Position.share(82 / 364f), w.editor);
    assertEquals(34 + 82 - 3, y(app, labelled(app, "Resize Query")), "the editor's box is 82");
    assertEquals(82, labelled(app, "Query").height(), "as its box; its taller text is clipped");
    assertEquals(260, labelled(app, "Schema").width(), "the schema keeps its width");
  }

  @Test
  void theEditorKeepsItsShareAndTheSchemaItsWidthWhenTheWindowGrows() throws IOException {
    App<Workbench> app = start();
    Script.run("size 1000 601\nframe", app, out);
    assertEquals(200, labelled(app, "Schema").width());
    assertEquals(283, labelled(app, "Query").height(), "half of 566 less the line");
    Script.run("size 500 400\nframe", app, out);
    assertEquals(179, labelled(app, "Schema").width(), "the queries keep their 320");
  }

  @Test
  void theKeysOfTheTabsGoUpFromTheirContentAndTheRootTakesThemWithoutFocus() throws IOException {
    App<Workbench> app = start();
    run(app, "focus role=separator label=\"Resize Query\"\nkey ArrowUp\nframe");
    assertEquals(166, labelled(app, "Query").height(), "the line's own key");
    run(app, "key Tab control\nframe");
    assertEquals(3, app.state().active, "Control+Tab went up to the tabs");
    assertEquals(find(app, "filter"), app.focused(), "into the new content's field");

    app.focus(null);
    run(app, "frame\nkey Tab control shift\nframe"); // to the root, which has the tabs' handler too
    assertEquals(2, app.state().active);
    assertEquals(166, labelled(app, "Query").height(), "the app kept the share");
    run(app, "focus #tablist\nkey Tab control\nframe");
    assertEquals(3, app.state().active, "once: the tabs took it before the root");
  }

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void looksAsStored(String name) throws IOException {
    Workbench w = Workbench.frame(name.equals("dark") ? Theme.DARK : Theme.LIGHT);
    assertEquals(1, Workbench.assertLooksAsStored("frame", name, w.app(Workbench::frameView)));
  }
}
